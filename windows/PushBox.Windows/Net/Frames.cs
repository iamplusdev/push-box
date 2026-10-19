using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;

namespace PushBox.Windows.Net;

/// <summary>
/// §5.3 frame field binding, hand-read from the decrypted JSON tree.
///
/// Why not System.Text.Json straight into records: a naming-policy or case mismatch drops values
/// silently instead of failing (the same trap Android hit with Jackson's isRecord(), §10.1).
/// Unknown frame types must be ignored for forward compatibility, not thrown.
/// Field names are aligned character-by-character with core/protocol/Frames.java.
/// </summary>
public static class FrameTypes
{
    public const string Hello = "hello";
    public const string Kx = "kx";
    public const string Auth = "auth";
    public const string AuthOk = "auth_ok";
    public const string AuthFail = "auth_fail";
    public const string Enc = "enc";
    public const string Message = "message";
    public const string MediaReady = "media_ready";
    public const string MediaFailed = "media_failed";
    public const string Read = "read";
    public const string Tombstone = "tombstone";
    public const string Ping = "ping";
    public const string Pong = "pong";
    public const string ResumeOk = "resume_ok";
    public const string ResumeGap = "resume_gap";

    /// <summary>§5.3 config：凭证或配置在服务端变了，客户端要自己决定重拉什么（不是错误帧）。</summary>
    public const string Config = "config";
}

public abstract record Frame(string Kind);

public sealed record HelloFrame(long ServerTime, int HeartbeatSec, string ServerPubFp, string NonceB64)
    : Frame(FrameTypes.Hello);

/// <summary>§4.3 kx carries the server's static identity key, because a fingerprint alone cannot be verified.</summary>
public sealed record KxFrame(string ServerPubB64, string EphPubB64, string SigB64, int Epoch)
    : Frame(FrameTypes.Kx);

public sealed record AuthOkFrame(int HeartbeatSec) : Frame(FrameTypes.AuthOk);

public sealed record AuthFailFrame(string Error) : Frame(FrameTypes.AuthFail);

public sealed record MediaSlot(int Idx, string Status, string? Key, string? Mime, long Size,
    int? W, int? H, string? Thumb)
{
    public bool Ready => Status == "ready";
}

/// <summary>Downlink frame (§5.3) and history item (§5.2) share these names.</summary>
public sealed record MessageFrame(long Seq, string Id, string ChannelNo, string SenderId,
    string? SenderName, string? SenderAvatar, string Type, string? Title, string? Summary,
    string? Content, string Level, string? Url, string? MediaStatus, IReadOnlyList<MediaSlot> Media,
    bool Notify, long CreatedAt, bool Truncated) : Frame(FrameTypes.Message)
{
    /// <summary>Conversation label: sender_name wins, blank falls back to sender_id.</summary>
    public string DisplayName => string.IsNullOrWhiteSpace(SenderName) ? SenderId : SenderName!;
}

/// <summary>§4.5 media_ready / media_failed: merges into the existing row by Id, never advances a cursor.</summary>
public sealed record MediaReadyFrame(string Id, long Seq, string Status, IReadOnlyList<MediaSlot> Media,
    bool Failed) : Frame(Failed ? FrameTypes.MediaFailed : FrameTypes.MediaReady)
{
    /// <summary>这条一共有几张图。帧里的 <see cref="Media"/> 只带<b>本次到终态的那几槽</b>
    /// （服务端合并窗口到期后按增量补发），取 <c>Media.Count</c> 会把三张图的消息缩成一格，
    /// 所以按见过的最大槽号算跨度。</summary>
    public int SlotSpan => Math.Max(Media.Count, (Media.Count == 0 ? 0 : Media.Max(slot => slot.Idx) + 1));
}

/// <summary>§5.3 read: another device moved the cursor, so the badge must drop.</summary>
public sealed record ReadFrame(string ChannelNo, string SenderId, long LastReadSeq, string? DeviceId)
    : Frame(FrameTypes.Read);

/// <summary>§5.3 tombstone: Id targets one message, Scope a whole conversation — mutually exclusive.</summary>
public sealed record TombstoneFrame(string? Id, string? ChannelNo, string? SenderId, long DeletedAt)
    : Frame(FrameTypes.Tombstone)
{
    public bool ClearsConversation => string.IsNullOrEmpty(Id) && ChannelNo != null && SenderId != null;
}

public sealed record ResumeGapFrame(long MissingFrom, long LatestSeq) : Frame(FrameTypes.ResumeGap);

/// <summary>
/// §5.3 config：服务端把"凭据或配置变了"这件事即时推下来。reason 的词表见
/// <see cref="ConfigReason"/>；channel_no 只对频道类变更有意义，设备类带 device_id，两者都可能为空。
/// </summary>
public sealed record ConfigFrame(string Reason, string? ChannelNo, string? DeviceId)
    : Frame(FrameTypes.Config);

/// <summary>core/protocol/Frames.java 的 R_* 常量，逐字对齐（服务端加新值时这里认不出来就是"整面刷新"）。</summary>
public static class ConfigReason
{
    public const string SecretRotated = "secret_rotated";
    public const string ChannelUpdated = "channel_updated";
    public const string ChannelArchived = "channel_archived";
    public const string ChannelNoReset = "channel_no_reset";
    public const string SendersUpdated = "senders_updated";
    public const string RecoveryCodeRotated = "recovery_code_rotated";
    public const string DeviceRevoked = "device_revoked";
}

/// <summary>ping / pong / resume_ok: the frame type is the whole payload, so Kind from the base is enough.</summary>
public sealed record SimpleFrame : Frame
{
    public SimpleFrame(string kind) : base(kind) {}
}

public static class FrameParser
{
    public static Frame? Parse(string plaintextJson)
    {
        using var doc = JsonDocument.Parse(plaintextJson);
        return Parse(doc.RootElement);
    }

    /// <summary>Null means "a frame type this build doesn't know" — the caller must skip it, not fail.</summary>
    public static Frame? Parse(JsonElement root)
    {
        var type = Str(root, "t") ?? "";
        return type switch
        {
            FrameTypes.Hello => new HelloFrame(
                Long(root, "server_time"),
                (int)Long(root, "heartbeat", 25),
                Str(root, "server_pub_fp") ?? "",
                Str(root, "nonce") ?? ""),
            FrameTypes.Kx => new KxFrame(
                Str(root, "server_pub") ?? "",
                Str(root, "eph_pub") ?? "",
                Str(root, "sig") ?? "",
                (int)Long(root, "epoch")),
            FrameTypes.AuthOk => new AuthOkFrame((int)Long(root, "heartbeat", 25)),
            FrameTypes.AuthFail => new AuthFailFrame(Str(root, "error") ?? ""),
            FrameTypes.Message => ReadMessage(root),
            FrameTypes.MediaReady => ReadMediaReady(root, false),
            FrameTypes.MediaFailed => ReadMediaReady(root, true),
            FrameTypes.Read => new ReadFrame(
                Str(root, "channel_no") ?? "",
                Str(root, "sender_id") ?? "",
                Long(root, "last_read_seq"),
                Str(root, "device_id")),
            FrameTypes.Tombstone => ReadTombstone(root),
            FrameTypes.ResumeGap => new ResumeGapFrame(
                Long(root, "missing_from"),
                Long(root, "latest_seq")),
            FrameTypes.Ping => new SimpleFrame(FrameTypes.Ping),
            FrameTypes.Pong => new SimpleFrame(FrameTypes.Pong),
            FrameTypes.ResumeOk => new SimpleFrame(FrameTypes.ResumeOk),
            FrameTypes.Config => new ConfigFrame(Str(root, "reason") ?? "",
                Str(root, "channel_no"), Str(root, "device_id")),
            _ => null
        };
    }

    public static MessageFrame ReadMessage(JsonElement root) => new(
        Long(root, "seq"),
        Str(root, "id") ?? "",
        Str(root, "channel_no") ?? "",
        Str(root, "sender_id") ?? "",
        Str(root, "sender_name"),
        Str(root, "sender_avatar"),
        Str(root, "type") ?? "text",
        Str(root, "title"),
        Str(root, "summary"),
        Str(root, "content"),
        Str(root, "level") ?? "info",
        Str(root, "url"),
        // media_status 只在 §5.2 的历史项里出现（core/api/HistoryPage.HistoryItem）；实时帧靠
        // media[] 每个槽各自的 status 判断，所以这里缺省为 null 交给 media_ready 回填。
        Str(root, "media_status"),
        ReadMediaSlots(root.TryGetProperty("media", out var media) ? media : default),
        Bool(root, "notify", true),
        Long(root, "created_at"),
        Bool(root, "truncated", false));

    private static MediaReadyFrame ReadMediaReady(JsonElement root, bool failed) => new(
        Str(root, "id") ?? "",
        Long(root, "seq"),
        Str(root, "status") ?? (failed ? "failed" : "ready"),
        ReadMediaSlots(root.TryGetProperty("media", out var media) ? media : default),
        failed);

    /// <summary>Id wins; only without an Id do channel_no/sender_id (inline or under scope) clear a conversation.</summary>
    private static TombstoneFrame ReadTombstone(JsonElement root)
    {
        var id = Str(root, "id");
        var hasScope = root.TryGetProperty("scope", out var scope)
                       && scope.ValueKind == JsonValueKind.Object;
        return new TombstoneFrame(
            string.IsNullOrEmpty(id) ? null : id,
            Str(root, "channel_no") ?? (hasScope ? Str(scope, "channel_no") : null),
            Str(root, "sender_id") ?? (hasScope ? Str(scope, "sender_id") : null),
            Long(root, "deleted_at"));
    }

    private static IReadOnlyList<MediaSlot> ReadMediaSlots(JsonElement node)
    {
        var slots = new List<MediaSlot>();
        if (node.ValueKind != JsonValueKind.Array) return slots;
        foreach (var item in node.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object) continue;
            slots.Add(new MediaSlot(
                (int)Long(item, "idx"),
                Str(item, "status") ?? "pending",
                Str(item, "key"),
                Str(item, "mime"),
                Long(item, "size"),
                item.TryGetProperty("w", out var w) && w.ValueKind == JsonValueKind.Number ? (int)w.GetInt64() : null,
                item.TryGetProperty("h", out var h) && h.ValueKind == JsonValueKind.Number ? (int)h.GetInt64() : null,
                Str(item, "thumb")));
        }
        return slots;
    }

    private static string? Str(JsonElement root, string name)
    {
        if (!root.TryGetProperty(name, out var el)) return null;
        return el.ValueKind switch
        {
            JsonValueKind.String => el.GetString(),
            JsonValueKind.Number => el.GetRawText(),
            JsonValueKind.True => "true",
            JsonValueKind.False => "false",
            _ => null
        };
    }

    private static long Long(JsonElement root, string name, long fallback = 0L)
    {
        return root.TryGetProperty(name, out var el) && el.ValueKind == JsonValueKind.Number
            ? el.GetInt64()
            : fallback;
    }

    private static bool Bool(JsonElement root, string name, bool fallback)
    {
        return root.TryGetProperty(name, out var el) &&
               (el.ValueKind == JsonValueKind.True || el.ValueKind == JsonValueKind.False)
            ? el.GetBoolean()
            : fallback;
    }
}
