using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using PushBox.Windows.Crypto;
using PushBox.Windows.Data;

namespace PushBox.Windows.Net;

/// <summary>The five fields the server answers inside the sealed register/join box (§5.2).</summary>
public sealed record Enrollment(string DeviceId, string GroupId, string Token,
    string? RecoveryCode, string ServerPubFp);

/// <summary>
/// One page of §5.2 <c>GET /api/v1/messages</c>. A history item uses the same field names as a
/// downlink message frame (core/api/HistoryPage.HistoryItem), so items are bound by
/// <see cref="FrameParser.ReadMessage"/>; rows the server already tombstoned come back as a parallel
/// list, which is why the two halves travel together.
/// </summary>
public sealed record HistoryPage(IReadOnlyList<MessageFrame> Messages,
    IReadOnlyList<TombstoneFrame> Tombstones, long LatestSeq, bool HasMore);

/// <summary>一个会话的已读游标（§5.2 的 read_state 就是按 (channel_no, sender_id) 定位的）。</summary>
public sealed record ReadEntry(string ChannelNo, string SenderId, long LastReadSeq);

/// <summary>
/// §4.5 取回的图片字节。这里<b>没有</b>密封盒：服务端代理 GetObject 后直接以 200 回字节
/// （<c>Cache-Control: no-store</c>），所以 ContentType 是桶里那个对象的实测 MIME。
/// no-store 只约束 HTTP 缓存，不约束我们自己落盘的 media_cache（§4.5 明确要求写进去）。
/// </summary>
public sealed record MediaBytes(byte[] Bytes, string? ContentType);

/// <summary>
/// §5.2 <c>GET /api/v1/search</c> 的一条命中（core/api/SearchPage.SearchHit）。服务端<b>只回片段</b>：
/// 一次搜索 200 条命中如果都带正文，跨网的响应体就是几百 KB，而列表只需要一两行（D19）。
/// </summary>
public sealed record SearchHit(string Id, long Seq, string ChannelNo, string SenderId,
    string? SenderName, string MsgType, string? Title, string Snippet, string Level,
    string? MediaStatus, long CreatedAt);

/// <summary>一页服务端搜索命中：has_more 只说明"服务端还有"，本地那半是另一次查询的结果。</summary>
public sealed record SearchPage(IReadOnlyList<SearchHit> Hits, bool HasMore);

/// <summary>
/// §5.2/§14 频道治理视图：服务端 <c>ChannelService.view</c> 的 snake_case 全量，
/// <b>永远不含明文 secret</b>（库里只存 sha256，密钥只在创建/轮换那一次回显）。
/// 设置页一次列表就把治理位全读回来，不必为每个频道再单独发一次请求。
/// </summary>
public sealed record ChannelInfo(string ChannelNo, string Name, string MaxLevel, int MaxImages,
    int RateLimit, int MaxBodyBytes, bool StrictAuth, bool Muted, int DailyMsgLimit,
    int DailyImageLimit, long DailyBytesLimit, bool SenderWhitelistMode, bool Subscribed)
{
    /// <summary>字段读法借用 <see cref="DeviceClient"/> 的那三个助手：缺列一律按服务端的建表默认值兜，
    /// 与 Android 的 path(...).asInt(默认) 同一条口径。</summary>
    public static ChannelInfo From(JsonElement n) => new(
        DeviceClient.Str(n, "channel_no") ?? "", DeviceClient.Str(n, "name") ?? "",
        DeviceClient.Str(n, "max_level") ?? "info",
        (int)DeviceClient.Long(n, "max_images", 4), (int)DeviceClient.Long(n, "rate_limit", 60),
        (int)DeviceClient.Long(n, "max_body_bytes", 32768), DeviceClient.Long(n, "strict_auth") != 0,
        DeviceClient.Long(n, "muted") != 0, (int)DeviceClient.Long(n, "daily_msg_limit"),
        (int)DeviceClient.Long(n, "daily_image_limit"), DeviceClient.Long(n, "daily_bytes_limit"),
        DeviceClient.Long(n, "sender_whitelist_mode") != 0, DeviceClient.Flag(n, "subscribed"));
}

/// <summary>
/// §5.2 GET /channels 的一页：频道列表 + 本机的「只接收已接入频道」开关当前值。
/// 那个开关是设备级的、存在服务端，界面必须以这里的回显为准——本机自记一份迟早和服务器说不一样的话。
/// </summary>
public sealed record ChannelListPage(IReadOnlyList<ChannelInfo> Channels, bool SubscribedOnly);

/// <summary>创建或轮换密钥的那一次回显：这是唯一能看到明文密钥的地方。</summary>
public sealed record NewChannel(string ChannelNo, string Secret, string MaxLevel);

/// <summary>§14 容量报表：这个数字是"该不该清一清"的依据，全部来自服务端聚合。</summary>
public sealed record Capacity(long TotalMessages, long TotalTextBytes, long EarliestMsgAt,
    long TotalImages, long TotalImageBytes);

/// <summary>§14 白名单的一行：enabled=false 是"停用"而不是删除，保留它是为了审计。</summary>
public sealed record SenderEntry(string SenderId, bool Enabled)
{
    /// <summary>Android 的 channel_senders_enabled / _disabled：停用那一行仍然列着，界面上要说清它是哪一种"没有"。</summary>
    public string StatusText => Enabled ? "已允许" : "已停用";
}

/// <summary>§5.2 设备行：<see cref="DeviceInfo.Revoked"/> 为真就是已吊销，<see cref="Current"/> 是本机。</summary>
public sealed record DeviceInfo(string DeviceId, string Name, string Platform, long CreatedAt,
    long LastSeenAt, long RevokedAt, bool Current)
{
    public bool Revoked => RevokedAt > 0;
}

/// <summary>
/// PUT 补丁：没填的字段一律不进正文——服务端把"没提交"与"提交 null"当成两件事，
/// 多发一个 null 就把那一列当成清空处理。与 Android 的 ChannelPatch 同字段、同省略口径。
/// </summary>
public sealed record ChannelPatch(string? Name = null, string? MaxLevel = null,
    int? MaxImages = null, int? RateLimit = null, int? MaxBodyBytes = null, bool? StrictAuth = null,
    bool? Muted = null, int? DailyMsgLimit = null, int? DailyImageLimit = null,
    long? DailyBytesLimit = null, bool? SenderWhitelistMode = null)
{
    /// <summary>一个字段都没填时返回 null：空正文的 PUT 在服务端是"什么都不改"，不值得发这一趟。</summary>
    public byte[]? BuildBody()
    {
        if (Name == null && MaxLevel == null && MaxImages == null && RateLimit == null
            && MaxBodyBytes == null && StrictAuth == null && Muted == null && DailyMsgLimit == null
            && DailyImageLimit == null && DailyBytesLimit == null && SenderWhitelistMode == null)
            return null;

        using var ms = new MemoryStream();
        using (var w = new Utf8JsonWriter(ms))
        {
            w.WriteStartObject();
            if (Name != null) w.WriteString("name", Name);
            if (MaxLevel != null) w.WriteString("max_level", MaxLevel);
            if (MaxImages != null) w.WriteNumber("max_images", MaxImages.Value);
            if (RateLimit != null) w.WriteNumber("rate_limit", RateLimit.Value);
            if (MaxBodyBytes != null) w.WriteNumber("max_body_bytes", MaxBodyBytes.Value);
            // 三个开关服务端存的是 0/1 整列，正文因此也写 0/1 而不是 true/false
            if (StrictAuth != null) w.WriteNumber("strict_auth", StrictAuth.Value ? 1 : 0);
            if (Muted != null) w.WriteNumber("muted", Muted.Value ? 1 : 0);
            if (DailyMsgLimit != null) w.WriteNumber("daily_msg_limit", DailyMsgLimit.Value);
            if (DailyImageLimit != null) w.WriteNumber("daily_image_limit", DailyImageLimit.Value);
            if (DailyBytesLimit != null) w.WriteNumber("daily_bytes_limit", DailyBytesLimit.Value);
            if (SenderWhitelistMode != null)
                w.WriteNumber("sender_whitelist_mode", SenderWhitelistMode.Value ? 1 : 0);
            w.WriteEndObject();
        }

        return ms.ToArray();
    }
}

/// <summary>
/// §5.2's two <em>open</em> device endpoints, plus the signed reads every later step needs.
/// register/join must not send Authorization or any X-PB-* header: at that moment the server has no
/// device_id, no token and no public key on file, so a signature header would only earn a 401.
/// Rate limiting is by IP, nothing else.
///
/// The response is a one-round sealed box opened with the device's <em>static</em> enc private key
/// (see <see cref="RestSeal"/>) — the WS session key doesn't exist yet, and it only ever exists
/// while a socket is open, so REST backfill must keep using this box (§5.2/§4.3).
/// </summary>
public sealed class DeviceClient
{
    /// <summary>Free-text field the server stores as-is; Android sends "android".</summary>
    public const string Platform = "windows";

    public const string RegisterPath = "/api/v1/devices/register";
    public const string JoinPath = "/api/v1/devices/join";
    public const string HistoryPath = "/api/v1/messages";
    public const string ReadPath = "/api/v1/state/read";
    public const string SearchPath = "/api/v1/search";

    /// <summary>§5.2/§14 治理面：频道与设备的 CRUD 都只能从"App 是唯一控制台"那两处进，PC 这边对齐。</summary>
    public const string ChannelsPath = "/api/v1/channels";
    public const string DevicesPath = "/api/v1/devices";

    /// <summary>§4.5 两个字节接口的前缀：媒体按 (msg_id, idx) 定位，头像按 token 定位。</summary>
    public const string MediaPathPrefix = "/api/v1/media/";
    public const string AvatarPathPrefix = "/api/v1/avatars/";

    /// <summary>size 只有这两个取值，别的都吃 400 invalid_param（探针 ⑮ 专门验过 medium）。</summary>
    public const string SizeThumb = "thumb";
    public const string SizeFull = "full";

    /// <summary>avatar_token 是 sha256hex(sender_id)：只认 64 位小写十六进制，其余一律当"没有头像"。</summary>
    private static readonly System.Text.RegularExpressions.Regex AvatarTokenPattern =
        new("^[0-9a-f]{64}$", System.Text.RegularExpressions.RegexOptions.Compiled);

    /// <summary>Server clamps limit into [1,500] (§5.2); 200 is what both clients ask for.</summary>
    public const int HistoryPageSize = 200;

    /// <summary>§5.2 服务端搜索一次取多少条：50 是服务端默认值，片段每条只有一两行，
    /// 本地那半另算，所以这里不需要按上限 200 要。</summary>
    public const int SearchPageSize = 50;

    private static readonly MediaTypeHeaderValue JsonMediaType =
        new("application/json") { CharSet = "utf-8" };

    private readonly HttpClient _http;
    private readonly TimeSpan _timeout;

    public DeviceClient(HttpClient? http = null, TimeSpan? timeout = null)
    {
        _timeout = timeout ?? TimeSpan.FromSeconds(15);
        _http = http ?? new HttpClient();
        // An injected client gets the same timeout when the caller asked for one explicitly
        if (http == null || timeout != null) _http.Timeout = _timeout;
    }

    /// <summary>New Space (no recovery code) — the answer contains the recovery code, once.</summary>
    public Task<Enrollment> RegisterAsync(DeviceIdentity pending, string deviceName,
        CancellationToken ct = default)
        => EnrollAsync(pending, deviceName, null, ct);

    /// <summary>§4.2 joining an existing Space with the recovery code read from the phone.</summary>
    public Task<Enrollment> JoinAsync(DeviceIdentity pending, string recoveryCode,
        string deviceName, CancellationToken ct = default)
        => EnrollAsync(pending, deviceName, recoveryCode, ct);

    /// <summary>
    /// §5.2 增量补拉：after_seq=0 就是重装后的全量恢复（D3 验收③），落后太多时服务端把已删的行
    /// 放进 tombstones[]，所以一页要同时带正文和墓碑，否则补拉会把本地已经消失的消息又拉回来。
    /// </summary>
    public async Task<HistoryPage> HistoryAsync(DeviceIdentity identity, long afterSeq,
        int limit = HistoryPageSize, CancellationToken ct = default)
    {
        // 查询串原样进签名（服务端是拿 request.getQueryString() 复原的），拼一次两处共用
        var query = "after_seq=" + afterSeq.ToString(CultureInfo.InvariantCulture)
            + "&limit=" + limit.ToString(CultureInfo.InvariantCulture);
        var url = BuildUrl(identity.ServerBaseUrl, HistoryPath) + "?" + query;

        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        Sign(request, identity, HistoryPath, query, null);

        var text = await SendAsync(request, url, ct).ConfigureAwait(false);
        using var plain = OpenSealed(text, identity.EncPrivSeed, url);
        return ReadPage(plain.RootElement);
    }

    /// <summary>
    /// §5.2/D19 服务端全文搜索：本机没缓存的历史消息只有这条路能搜到（服务端明文库 + ngram 索引）。
    /// 参数顺序固定为 <c>q, channel_no, sender_id, type, since, limit</c>，与服务端
    /// <c>SearchController</c> 的声明同序——签名覆盖的是<b>原始</b> query 串，而服务端用
    /// <c>request.getQueryString()</c> 复原它，两端换序或差一个转义字节就整片 401。
    /// 没设的过滤条件整个参数省略（服务端按 null 处理），不留 <c>key=</c>：向量
    /// <c>rest_auth.get_search</c> 那种"空值参数也进 canonical"的写法同样签得开，
    /// 但这里的口径是"省略即不查"，少一串字节也少一处两端要对齐的地方。
    /// </summary>
    public async Task<SearchPage> SearchAsync(DeviceIdentity identity, string query,
        string? channelNo = null, string? senderId = null, string? type = null, long since = 0L,
        int limit = SearchPageSize, CancellationToken ct = default)
    {
        var term = (query ?? "").Trim();
        if (term.Length == 0) return new SearchPage(Array.Empty<SearchHit>(), false);

        var wire = SearchQuery(term, channelNo, senderId, type, since, limit);
        var url = BuildUrl(identity.ServerBaseUrl, SearchPath) + "?" + wire;

        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        Sign(request, identity, SearchPath, wire, null);

        var body = await SendAsync(request, url, ct).ConfigureAwait(false);
        using var plain = OpenSealed(body, identity.EncPrivSeed, url);
        return ReadSearchPage(plain.RootElement);
    }

    /// <summary>
    /// 建 §5.2 搜索的原始 query 串：值一律先百分号编码再拼，不把原文塞进 URL 留给 HTTP 层去规范化——
    /// 签名覆盖的是这一串，服务端拿 <c>getQueryString()</c> 复原的是上路那串，两边必须是同一个值。
    /// <c>SearchClientTest</c> 拿一批最难看的搜索词（空格、中文、emoji、<c># ? & = % ! ' ( ) *</c>）
    /// 核对过 <c>Uri</c> 在这一条上是原样透传的，转义不会被还原成原文。
    /// </summary>
    public static string SearchQuery(string q, string? channelNo, string? senderId, string? type,
        long since, int limit)
    {
        var parts = new List<string> { "q=" + Uri.EscapeDataString(q) };
        if (!string.IsNullOrWhiteSpace(channelNo)) parts.Add("channel_no=" + Uri.EscapeDataString(channelNo.Trim()));
        if (!string.IsNullOrWhiteSpace(senderId)) parts.Add("sender_id=" + Uri.EscapeDataString(senderId.Trim()));
        if (!string.IsNullOrWhiteSpace(type)) parts.Add("type=" + Uri.EscapeDataString(type.Trim()));
        parts.Add("since=" + since.ToString(CultureInfo.InvariantCulture));
        parts.Add("limit=" + limit.ToString(CultureInfo.InvariantCulture));
        return string.Join("&", parts);
    }

    /// <summary>
    /// §5.2/§4.4 已读游标上报：一次 PUT 把若干会话整段置已读，服务端写 read_state 后向同组他端广播
    /// read 帧，红点就此消失（D9）。服务端那一行是<b>后写覆盖</b>而不是取最大值，所以调用方必须保证
    /// 发出去的游标只增不减——把旧游标再发一次会把别端刚清掉的红点点回来。
    /// </summary>
    public async Task<int> ReportReadAsync(DeviceIdentity identity, IReadOnlyCollection<ReadEntry> entries,
        CancellationToken ct = default)
    {
        if (entries.Count == 0) return 0;

        // 先按要发出去的这串字节建正文，再拿它进签名：canonical 里那行 sha256 算的就是这两个数组
        var body = ReadBody(entries);
        var url = BuildUrl(identity.ServerBaseUrl, ReadPath);
        using var request = new HttpRequestMessage(HttpMethod.Put, url)
        {
            Content = new ByteArrayContent(body)
        };
        request.Content.Headers.ContentType = JsonMediaType;
        Sign(request, identity, ReadPath, null, body);

        var text = await SendAsync(request, url, ct).ConfigureAwait(false);
        using var plain = OpenSealed(text, identity.EncPrivSeed, url);
        // 回答只有 {ok, updated}，报出去几条由服务端确认；缺这一项按 0 算，不影响游标已经生效
        return plain.RootElement.TryGetProperty("updated", out var el)
               && el.ValueKind == JsonValueKind.Number
            ? el.GetInt32()
            : 0;
    }

    /// <summary>正文是数组不是对象：字段名必须与 core/api 的 ReadItem 逐字一致（下划线小写）。</summary>
    public static byte[] ReadBody(IReadOnlyCollection<ReadEntry> entries)
    {
        using var ms = new MemoryStream();
        using (var w = new Utf8JsonWriter(ms))
        {
            w.WriteStartArray();
            foreach (var entry in entries)
            {
                w.WriteStartObject();
                w.WriteString("channel_no", entry.ChannelNo);
                w.WriteString("sender_id", entry.SenderId);
                w.WriteNumber("last_read_seq", entry.LastReadSeq);
                w.WriteEndObject();
            }

            w.WriteEndArray();
        }
        return ms.ToArray();
    }

    /// <summary>
    /// §5.2/D13 单条删除：<c>DELETE /api/v1/messages/{msg_id}</c>。空 query、空 body——canonical 的
    /// 第四、六两行因此都是空/占位（<c>sha256("")</c>），与服务端读到的那个空正文同一条字节：探针 ⑪/⑱
    /// 发的就是不带 body 的 DELETE，这一条形态是它验过的。
    /// 服务端那一发做三件事：写 tombstone、把它的图片标成待删、向同组广播一条 <c>tombstone</c> 帧。
    /// 库里没有这一行（多半是别端刚删过）时回 <c>404 hint=message</c>——这不是"请求失败"而是"目的已达到"，
    /// 判在哪一层见 <see cref="Sync.DeleteReporter.IsAlreadyGone"/>。
    /// </summary>
    public async Task DeleteMessageAsync(DeviceIdentity identity, string msgId,
        CancellationToken ct = default)
    {
        var path = MessagePath(msgId);
        var url = BuildUrl(identity.ServerBaseUrl, path);

        using var request = new HttpRequestMessage(HttpMethod.Delete, url);
        Sign(request, identity, path, null, null);

        // 回答是密封盒 {ok,id,deleted_at}：打开它这件事就是断言"回来的确实是本服务端正文"，
        // deleted_at 没有消费者（tombstone 帧自己带时间），所以不取
        var text = await SendAsync(request, url, ct).ConfigureAwait(false);
        using var _ = OpenSealed(text, identity.EncPrivSeed, url);
    }

    /// <summary>
    /// §5.2/D13 清空：一次 <c>DELETE /api/v1/messages?channel_no=&amp;sender_id=</c> 标完整个会话，
    /// 服务端只广播<b>一条</b>带 scope 的 tombstone 帧，并回确认的条数 <c>deleted</c>。
    /// 逐条删在这里是错的：一个会话可能上千条，既打爆服务端的 SQL 往返，也让其他端收到上千帧。
    /// 省略 <c>sender_id</c> 就是清整个频道（scope 帧的 sender_id 为空），这条接口两种语义共用。
    /// 重复调用幂等回 <c>deleted=0</c>，不报 404，所以清空这条路没有"已经没了"那个分支。
    /// </summary>
    public async Task<int> ClearMessagesAsync(DeviceIdentity identity, string channelNo,
        string? senderId = null, CancellationToken ct = default)
    {
        var query = ClearQuery(channelNo, senderId);
        var url = BuildUrl(identity.ServerBaseUrl, HistoryPath) + "?" + query;

        using var request = new HttpRequestMessage(HttpMethod.Delete, url);
        Sign(request, identity, HistoryPath, query, null);

        var text = await SendAsync(request, url, ct).ConfigureAwait(false);
        using var plain = OpenSealed(text, identity.EncPrivSeed, url);
        // 报出去多少条由服务端确认；缺这一项按 0 算，本机那半早就没显示了
        return plain.RootElement.TryGetProperty("deleted", out var el)
               && el.ValueKind == JsonValueKind.Number
            ? el.GetInt32()
            : 0;
    }

    /// <summary>§5.2 单条删除的定位：msg_id 原样进 path，保留字符靠转义保证 URL 与签名里那串 path
    /// 仍是同一串字节（与 <see cref="MediaPath"/> 同一条理由）。服务端解 path 变量时认的是转义后的形态。</summary>
    public static string MessagePath(string msgId) =>
        HistoryPath + "/" + Uri.EscapeDataString(msgId);

    /// <summary>
    /// 清空的原始 query 串：<b>先百分号编码再拼</b>，和 <see cref="SearchQuery"/> 同一条口径——
    /// 签名覆盖的就是这一串，服务端拿 <c>request.getQueryString()</c> 复原的是上路那串，
    /// 两边差一个转义字节就整片 401。参数顺序 <c>channel_no, sender_id</c> 与服务端方法声明同序。
    /// <c>sender_id</c> 省略即"整频道"，不留 <c>sender_id=</c>：服务端把空白也当省略（wholeChannel 那条分支）。
    /// </summary>
    public static string ClearQuery(string channelNo, string? senderId)
    {
        var channel = (channelNo ?? "").Trim();
        if (channel.Length == 0)
            throw new ArgumentException("清空必须指明频道（服务端对空 channel_no 回 400）", nameof(channelNo));

        var parts = new List<string> { "channel_no=" + Uri.EscapeDataString(channel) };
        if (!string.IsNullOrWhiteSpace(senderId)) parts.Add("sender_id=" + Uri.EscapeDataString(senderId.Trim()));
        return string.Join("&", parts);
    }

    /// <summary>
    /// §4.5/§5.2 取图：<c>GET /api/v1/media/{msg_id}/{idx}?size=thumb|full</c>。
    /// 请求侧照旧要 token + 签名，回答侧<b>不是</b>密封盒（服务端代理取回对象后以 200 直接回字节），
    /// 所以这里不走 <see cref="OpenSealed"/>。
    /// size 那一串原始 query 同时进签名和 URL，共用同一个变量——服务端是拿
    /// <c>request.getQueryString()</c> 复原的，两边差一个字节就是整片 401。
    /// 缩略图缺失时服务端自己降级发原图，客户端不需要"thumb 拿不到就再要一次 full"的分支。
    /// </summary>
    public async Task<MediaBytes> MediaAsync(DeviceIdentity identity, string msgId, int idx,
        bool thumb, CancellationToken ct = default)
    {
        var path = MediaPath(msgId, idx);
        var query = "size=" + (thumb ? SizeThumb : SizeFull);
        var url = BuildUrl(identity.ServerBaseUrl, path) + "?" + query;

        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        Sign(request, identity, path, query, null);
        return await SendBytesAsync(request, url, ct).ConfigureAwait(false);
    }

    /// <summary>
    /// §4.5 头像：<c>GET /api/v1/avatars/{avatar_token}</c>。传进来的是下发列 <c>sender_avatar</c>
    /// 的原文（服务端现算的 <c>/api/v1/avatars/{token}</c>，没转存成功时是 NULL）。
    /// 这个接口没有查询参数，canonical 的第四行因此是空行。
    /// </summary>
    public async Task<MediaBytes> AvatarAsync(DeviceIdentity identity, string senderAvatar,
        CancellationToken ct = default)
    {
        var token = AvatarTokenOf(senderAvatar)
                    ?? throw new ArgumentException("头像地址里没有合法的 avatar_token", nameof(senderAvatar));
        var path = AvatarPathPrefix + token;
        var url = BuildUrl(identity.ServerBaseUrl, path);

        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        Sign(request, identity, path, null, null);
        return await SendBytesAsync(request, url, ct).ConfigureAwait(false);
    }

    /// <summary>§4.5：对象键是 <c>media/{msg_id}/{idx}</c>，读接口按同一条定位。id 里出现保留字符时
    /// 由转义保证 URL 与签名里的那串 path 仍然是同一串字节。</summary>
    public static string MediaPath(string msgId, int idx) =>
        MediaPathPrefix + Uri.EscapeDataString(msgId) + "/" + idx.ToString(CultureInfo.InvariantCulture);

    /// <summary>接受整条路径或裸 token 两种写法，不成形就返回 null（调用方据此走首字占位头像，
    /// 而不是发一个服务端必定 404 的请求）。服务端对不成形的 token 也是直接 404。</summary>
    public static string? AvatarTokenOf(string? senderAvatar)
    {
        var value = (senderAvatar ?? "").Trim();
        if (value.Length == 0) return null;
        if (value.StartsWith(AvatarPathPrefix, StringComparison.Ordinal))
            value = value[AvatarPathPrefix.Length..];
        value = value.TrimEnd('/');
        return AvatarTokenPattern.IsMatch(value) ? value : null;
    }

    /// <summary>
    /// §4.5：头像对象 token 就是 <c>sha256(sender_id)</c> 的小写十六进制——服务端
    /// <c>MediaObjects.avatarToken</c> 与 Android 的 <c>Hashes.sha256Hex(senderId)</c> 算的是同一个串，
    /// 所以本机不必等 <c>sender_avatar</c> 那一列带下来就能认出自己的头像在哪。
    /// </summary>
    public static string AvatarTokenFor(string senderId) =>
        Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(
            System.Text.Encoding.UTF8.GetBytes(senderId ?? ""))).ToLowerInvariant();

    /// <summary>
    /// §5.2/§14 一次签名请求的公共走法：正文先建好再进签名（canonical 那行 sha256 算的就是发出去的字节），
    /// 成功响应统一是 §4.3 的密封盒。GET/DELETE 传 null 正文，与 <see cref="Sign"/> 的
    /// <c>sha256("")</c> 占位同一条口径。
    /// </summary>
    private async Task<JsonDocument> SignedAsync(DeviceIdentity identity, HttpMethod method,
        string path, string? query, byte[]? body, CancellationToken ct)
    {
        var url = BuildUrl(identity.ServerBaseUrl, path)
                  + (string.IsNullOrEmpty(query) ? string.Empty : "?" + query);
        using var request = new HttpRequestMessage(method, url);
        if (body != null)
        {
            request.Content = new ByteArrayContent(body);
            request.Content.Headers.ContentType = JsonMediaType;
        }

        Sign(request, identity, path, query, body);
        var text = await SendAsync(request, url, ct).ConfigureAwait(false);
        return OpenSealed(text, identity.EncPrivSeed, url);
    }

    /// <summary>频道号原样进 path：与 <see cref="MessagePath"/> 同一个理由，转义一次让 URL 与签名的
    /// 那串 path 是同一串字节。</summary>
    public static string ChannelPath(string channelNo) =>
        ChannelsPath + "/" + Uri.EscapeDataString(channelNo);

    /// <summary>§5.2 频道列表（含全部治理位与本机的接入/过滤状态，永不含明文密钥）。</summary>
    public async Task<ChannelListPage> ChannelsPageAsync(DeviceIdentity identity,
        CancellationToken ct = default)
    {
        using var plain = await SignedAsync(identity, HttpMethod.Get, ChannelsPath, null, null, ct)
            .ConfigureAwait(false);
        var list = new List<ChannelInfo>();
        foreach (var item in Objects(plain.RootElement, "channels")) list.Add(ChannelInfo.From(item));
        return new ChannelListPage(list, Flag(plain.RootElement, "subscribed_only"));
    }

    public async Task<IReadOnlyList<ChannelInfo>> ChannelsAsync(DeviceIdentity identity,
        CancellationToken ct = default) =>
        (await ChannelsPageAsync(identity, ct).ConfigureAwait(false)).Channels;

    /// <summary>
    /// §5.2 接入频道：频道号 + 密钥把这台设备登记进该频道的接收集。
    /// 密钥在这里只作二次确认，不是读凭据（§4.1）——接入改变的是这台设备收不收内容帧，
    /// 而不是它有没有权限读；跨 Space、未知号码、已归档一律 404，密钥不对 401。
    /// </summary>
    public async Task JoinChannelAsync(DeviceIdentity identity, string channelNo, string secret,
        CancellationToken ct = default)
    {
        using var ms = new MemoryStream();
        using (var w = new Utf8JsonWriter(ms))
        {
            w.WriteStartObject();
            w.WriteString("channel_no", channelNo.Trim());
            w.WriteString("secret", secret.Trim());
            w.WriteEndObject();
        }

        using var plain = await SignedAsync(identity, HttpMethod.Post, ChannelsPath + "/join",
            null, ms.ToArray(), ct).ConfigureAwait(false);
    }

    /// <summary>§5.2 取消接入：服务端幂等，没接入过也回成功；过滤模式开关不受影响。</summary>
    public async Task LeaveChannelAsync(DeviceIdentity identity, string channelNo,
        CancellationToken ct = default)
    {
        using var ms = new MemoryStream();
        using (var w = new Utf8JsonWriter(ms))
        {
            w.WriteStartObject();
            w.WriteString("channel_no", channelNo.Trim());
            w.WriteEndObject();
        }

        using var plain = await SignedAsync(identity, HttpMethod.Post, ChannelsPath + "/leave",
            null, ms.ToArray(), ct).ConfigureAwait(false);
    }

    /// <summary>§5.2 切换"只接收已接入频道"：返回服务端真正认下的值，界面以回显为准而不是以自己的意图为准。</summary>
    public async Task<bool> SetSubscriptionModeAsync(DeviceIdentity identity, bool subscribedOnly,
        CancellationToken ct = default)
    {
        using var ms = new MemoryStream();
        using (var w = new Utf8JsonWriter(ms))
        {
            w.WriteStartObject();
            w.WriteNumber("subscribed_only", subscribedOnly ? 1 : 0);
            w.WriteEndObject();
        }

        using var plain = await SignedAsync(identity, HttpMethod.Post,
            ChannelsPath + "/subscription-mode", null, ms.ToArray(), ct).ConfigureAwait(false);
        return Flag(plain.RootElement, "subscribed_only");
    }

    /// <summary>§5.2 建频道：明文密钥只在这次回答里出现，界面上要当场给出复制入口。</summary>
    public async Task<NewChannel> CreateChannelAsync(DeviceIdentity identity, string name,
        CancellationToken ct = default)
    {
        using var ms = new MemoryStream();
        using (var w = new Utf8JsonWriter(ms))
        {
            w.WriteStartObject();
            w.WriteString("name", name ?? "");
            w.WriteEndObject();
        }

        using var plain = await SignedAsync(identity, HttpMethod.Post, ChannelsPath, null,
            ms.ToArray(), ct).ConfigureAwait(false);
        return new NewChannel(Str(plain.RootElement, "channel_no") ?? "",
            Str(plain.RootElement, "secret") ?? "", Str(plain.RootElement, "max_level") ?? "info");
    }

    /// <summary>§5.2 轮换密钥：同样只回显这一次。</summary>
    public async Task<NewChannel> RotateSecretAsync(DeviceIdentity identity, string channelNo,
        CancellationToken ct = default)
    {
        using var plain = await SignedAsync(identity, HttpMethod.Post,
            ChannelPath(channelNo) + "/rotate-secret", null, null, ct).ConfigureAwait(false);
        return new NewChannel(Str(plain.RootElement, "channel_no") ?? channelNo,
            Str(plain.RootElement, "secret") ?? "", Str(plain.RootElement, "max_level") ?? "");
    }

    /// <summary>§14 改治理位。补丁为空就一个字节都不发（服务端会把它当成"整列清空"来读）。</summary>
    public async Task<ChannelInfo> UpdateChannelAsync(DeviceIdentity identity, string channelNo,
        ChannelPatch patch, CancellationToken ct = default)
    {
        var body = patch.BuildBody()
                   ?? throw new ArgumentException("治理补丁一个字段都没填，不必发这一趟", nameof(patch));
        using var plain = await SignedAsync(identity, HttpMethod.Put, ChannelPath(channelNo),
            null, body, ct).ConfigureAwait(false);
        return ChannelInfo.From(plain.RootElement);
    }

    /// <summary>§5.2 归档频道：归档后服务端对该频道的新投递回 403 channel_locked。</summary>
    public async Task ArchiveChannelAsync(DeviceIdentity identity, string channelNo,
        CancellationToken ct = default)
    {
        using var plain = await SignedAsync(identity, HttpMethod.Delete, ChannelPath(channelNo),
            null, null, ct).ConfigureAwait(false);
    }

    /// <summary>§14 容量报表：该频道在服务器上的消息数/字节数/图片数。</summary>
    public async Task<Capacity> CapacityAsync(DeviceIdentity identity, string channelNo,
        CancellationToken ct = default)
    {
        using var plain = await SignedAsync(identity, HttpMethod.Get,
            ChannelPath(channelNo) + "/capacity", null, null, ct).ConfigureAwait(false);
        var r = plain.RootElement;
        return new Capacity(Long(r, "total_messages"), Long(r, "total_text_bytes"),
            Long(r, "earliest_msg_at"), Long(r, "total_images"), Long(r, "total_image_bytes"));
    }

    /// <summary>
    /// §14 换发新的对外频道号码：旧号即刻作废，历史随迁、密钥不变。返回新号码；
    /// 本机要以旧号为键的会话设置丢掉后重拉（服务端为此广播 config 帧）。
    /// </summary>
    public async Task<string> ResetChannelNoAsync(DeviceIdentity identity, string channelNo,
        CancellationToken ct = default)
    {
        using var plain = await SignedAsync(identity, HttpMethod.Post,
            ChannelPath(channelNo) + "/reset-channel-no", null, null, ct).ConfigureAwait(false);
        return Str(plain.RootElement, "channel_no") ?? "";
    }

    /// <summary>§14 白名单：列出该频道允许发送的 sender_id（含已停用的）。</summary>
    public async Task<IReadOnlyList<SenderEntry>> SendersAsync(DeviceIdentity identity,
        string channelNo, CancellationToken ct = default)
    {
        using var plain = await SignedAsync(identity, HttpMethod.Get,
            ChannelPath(channelNo) + "/senders", null, null, ct).ConfigureAwait(false);
        var list = new List<SenderEntry>();
        foreach (var item in Objects(plain.RootElement, "senders"))
        {
            var id = Str(item, "sender_id");
            if (!string.IsNullOrEmpty(id)) list.Add(new SenderEntry(id, Long(item, "enabled") != 0));
        }

        return list;
    }

    /// <summary>§14 白名单：加一个或改一个；enabled=false 是停用而不是删除。</summary>
    public async Task PutSenderAsync(DeviceIdentity identity, string channelNo, string senderId,
        bool enabled, CancellationToken ct = default)
    {
        using var ms = new MemoryStream();
        using (var w = new Utf8JsonWriter(ms))
        {
            w.WriteStartObject();
            w.WriteString("sender_id", senderId);
            w.WriteBoolean("enabled", enabled);
            w.WriteEndObject();
        }

        using var plain = await SignedAsync(identity, HttpMethod.Post,
            ChannelPath(channelNo) + "/senders", null, ms.ToArray(), ct).ConfigureAwait(false);
    }

    /// <summary>§5.2 设备列表：本空间全部设备，含已吊销的那几行（界面上要看得见"谁被吊销过"）。</summary>
    public async Task<IReadOnlyList<DeviceInfo>> DevicesAsync(DeviceIdentity identity,
        CancellationToken ct = default)
    {
        using var plain = await SignedAsync(identity, HttpMethod.Get, DevicesPath, null, null, ct)
            .ConfigureAwait(false);
        // 这一页的回答正文本身就是数组（不是 {devices:[...]}），与 Android 那边同一种形态
        var list = new List<DeviceInfo>();
        if (plain.RootElement.ValueKind != JsonValueKind.Array) return list;
        foreach (var item in plain.RootElement.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object) continue;
            var id = Str(item, "device_id");
            if (string.IsNullOrEmpty(id)) continue;
            list.Add(new DeviceInfo(id, Str(item, "name") ?? "", Str(item, "platform") ?? "",
                Long(item, "created_at"), Long(item, "last_seen_at"), Long(item, "revoked_at"),
                item.ValueKind == JsonValueKind.Object && Long(item, "current") != 0));
        }

        return list;
    }

    /// <summary>§5.2 远程吊销：作废 token 并即时踢掉那条在线连接（验收④）。</summary>
    public async Task RevokeDeviceAsync(DeviceIdentity identity, string deviceId,
        CancellationToken ct = default)
    {
        var path = DevicesPath + "/" + Uri.EscapeDataString(deviceId);
        using var plain = await SignedAsync(identity, HttpMethod.Delete, path, null, null, ct)
            .ConfigureAwait(false);
    }

    /// <summary>§14 轮换恢复码：旧码即刻作废，新码只在这次回答里出现一次。</summary>
    public async Task<string> RotateRecoveryCodeAsync(DeviceIdentity identity,
        CancellationToken ct = default)
    {
        using var plain = await SignedAsync(identity, HttpMethod.Post,
            DevicesPath + "/rotate-recovery-code", null, null, ct).ConfigureAwait(false);
        return Str(plain.RootElement, "recovery_code") ?? "";
    }

    /// <summary>
    /// §5.2/§7.4 全文回捞：正文被服务端截断下发时（truncated），按 msg_id 单独取那一条的完整正文。
    /// 404（本机没有 / 已 tombstone）在这里不是错误而是"没有更多可看"，所以吞成 null。
    /// </summary>
    public async Task<MessageFrame?> FulltextAsync(DeviceIdentity identity, string msgId,
        CancellationToken ct = default)
    {
        try
        {
            using var plain = await SignedAsync(identity, HttpMethod.Get, MessagePath(msgId),
                null, null, ct).ConfigureAwait(false);
            return FrameParser.ReadMessage(plain.RootElement);
        }
        catch (PushBoxApiException e) when (e.Status == HttpStatusCode.NotFound)
        {
            return null;
        }
    }

    private static HistoryPage ReadPage(JsonElement root)

    {
        var messages = new List<MessageFrame>();
        foreach (var item in Objects(root, "messages")) messages.Add(FrameParser.ReadMessage(item));

        // 历史页的墓碑只有 {id, deleted_at}（core/api/HistoryPage.TombstoneItem）：整会话的清空
        // 走 §5.3 的 scope 帧，不会出现在这一页里
        var tombstones = new List<TombstoneFrame>();
        foreach (var item in Objects(root, "tombstones"))
        {
            var id = Str(item, "id");
            tombstones.Add(new TombstoneFrame(string.IsNullOrEmpty(id) ? null : id, null, null,
                Long(item, "deleted_at")));
        }

        return new HistoryPage(messages, tombstones, Long(root, "latest_seq"),
            root.TryGetProperty("has_more", out var more) && more.ValueKind == JsonValueKind.True);
    }

    /// <summary>搜索页只认 results[] 里的对象，字段名与 core/api/SearchPage.SearchHit 逐字一致；
    /// 缺 id 的那条进不了结果集——去重与"点进去跳转会话"都以 id 为锚，留着它等于埋一条会重复的命中。</summary>
    private static SearchPage ReadSearchPage(JsonElement root)
    {
        var hits = new List<SearchHit>();
        foreach (var item in Objects(root, "results"))
        {
            var id = Str(item, "id");
            if (string.IsNullOrEmpty(id)) continue;
            hits.Add(new SearchHit(id, Long(item, "seq"), Str(item, "channel_no") ?? "",
                Str(item, "sender_id") ?? "", Str(item, "sender_name"), Str(item, "type") ?? "text",
                Str(item, "title"), Str(item, "snippet") ?? "", Str(item, "level") ?? "info",
                Str(item, "media_status"), Long(item, "created_at")));
        }

        return new SearchPage(hits,
            root.TryGetProperty("has_more", out var more) && more.ValueKind == JsonValueKind.True);
    }

    private async Task<Enrollment> EnrollAsync(DeviceIdentity pending, string deviceName,
        string? recoveryCode, CancellationToken ct)
    {
        var path = recoveryCode == null ? RegisterPath : JoinPath;
        var url = BuildUrl(pending.ServerBaseUrl, path);
        var body = BuildBody(pending.EncPubB64, pending.SignPubB64, deviceName, recoveryCode);

        using var request = new HttpRequestMessage(HttpMethod.Post, url)
        {
            Content = new ByteArrayContent(body)
        };
        request.Content.Headers.ContentType = JsonMediaType;

        // 开放接口：这里一个 X-PB-* 头都不能加，服务端此刻还不认识这台设备
        return OpenEnvelope(await SendAsync(request, url, ct).ConfigureAwait(false),
            pending.EncPrivSeed, url);
    }

    /// <summary>
    /// §5.2 restAuth: 五个头一起加，签名覆盖的 query 必须和真正发出去的那串字节一致，
    /// 空 body 走 sha256("") 占位——服务端对 GET 传的是 requestBody=null，两侧算法同一条。
    /// </summary>
    private static void Sign(HttpRequestMessage request, DeviceIdentity identity, string path,
        string? query, byte[]? body)
    {
        var ts = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        // nonce 每请求现取：服务端要求 ≥16 字节，且同设备重用同一个 nonce 会 409 replay
        var nonce = RandomNumberGenerator.GetBytes(16);
        var sig = B64Url.Encode(Ed25519Sign.Sign(identity.SignPrivSeed,
            RestCanonical.Build(identity.DeviceId, request.Method.Method, path, query, body, ts, nonce)));

        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", identity.Token);
        request.Headers.Add("X-PB-Device", identity.DeviceId);
        request.Headers.Add("X-PB-Ts", ts.ToString(CultureInfo.InvariantCulture));
        request.Headers.Add("X-PB-Nonce", B64Url.Encode(nonce));
        request.Headers.Add("X-PB-Sig", sig);
    }

    /// <summary>Send, turn a bad status into one readable sentence, and hand back the body text.</summary>
    private async Task<string> SendAsync(HttpRequestMessage request, string url, CancellationToken ct)
    {
        using var response = await OpenAsync(request, url, ct).ConfigureAwait(false);
        var text = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        FailIfNeeded(response, text);
        return text;
    }

    /// <summary>字节接口（媒体/头像）走这条路：正文是图片，绝不能按文本读一遍再交给密封盒。</summary>
    private async Task<MediaBytes> SendBytesAsync(HttpRequestMessage request, string url, CancellationToken ct)
    {
        using var response = await OpenAsync(request, url, ct).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            // 非 2xx 的正文是 §5.1 那个几十十字节的错误体，槽位状态就写在 hint 里，值得读成文本
            FailIfNeeded(response, await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false));
        }

        return new MediaBytes(
            await response.Content.ReadAsByteArrayAsync(ct).ConfigureAwait(false),
            response.Content.Headers.ContentType?.MediaType);
    }

    private async Task<HttpResponseMessage> OpenAsync(HttpRequestMessage request, string url,
        CancellationToken ct)
    {
        try
        {
            return await _http.SendAsync(request, ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw; // 用户自己取消的，不该被说成超时
        }
        catch (TaskCanceledException e)
        {
            throw new PushBoxTransportException(url, $"{HumanDuration(_timeout)}内没有响应", e);
        }
        catch (HttpRequestException e)
        {
            throw new PushBoxTransportException(url, DescribeTransport(e), e);
        }
    }

    private static void FailIfNeeded(HttpResponseMessage response, string text)
    {
        if (response.IsSuccessStatusCode) return;

        var retryAfter = response.Headers.RetryAfter?.Delta?.TotalSeconds;
        throw PushBoxApiException.FromResponse(response.StatusCode, text,
            retryAfter.HasValue ? ((int)retryAfter.Value).ToString(CultureInfo.InvariantCulture) : null);
    }

    /// <summary>Tests use sub-second timeouts; "0 秒" would read like a bug, so switch to milliseconds.</summary>
    private static string HumanDuration(TimeSpan value) =>
        value.TotalSeconds >= 1 ? $"{(int)value.TotalSeconds} 秒" : $"{(int)value.TotalMilliseconds} 毫秒";

    /// <summary>TLS failures arrive as an inner AuthenticationException; "证书不受信任" is the one a
    /// self-signed CA produces (§11.1), so it must be named rather than left as a socket error.</summary>
    private static string DescribeTransport(HttpRequestException e)
    {
        var inner = e.InnerException;
        if (inner is System.Security.Authentication.AuthenticationException
            || inner is System.Security.Cryptography.CryptographicException)
        {
            return "服务器 TLS 证书未被信任。自签 CA 需要把根证书装进本机受信任根存储（§11.1）。";
        }
        return string.IsNullOrWhiteSpace(e.Message) ? "网络不可达" : e.Message;
    }

    /// <summary>
    /// Hand-written JSON: field names must match DeviceApi.RegisterRequest exactly
    /// (enc_pubkey / sign_pubkey / name / platform / group_secret), and group_secret is omitted
    /// rather than sent as null so the server takes its "first device ⇒ mint a code" branch.
    /// </summary>
    public static byte[] BuildBody(string encPubB64, string signPubB64, string deviceName,
        string? recoveryCode)
    {
        using var ms = new MemoryStream();
        using (var w = new Utf8JsonWriter(ms))
        {
            w.WriteStartObject();
            w.WriteString("enc_pubkey", encPubB64);
            w.WriteString("sign_pubkey", signPubB64);
            w.WriteString("name", deviceName);
            w.WriteString("platform", Platform);
            if (!string.IsNullOrEmpty(recoveryCode)) w.WriteString("group_secret", recoveryCode);
            w.WriteEndObject();
        }
        return ms.ToArray();
    }

    /// <summary>
    /// Base URL alone, with the same guardrails as <see cref="BuildUrl"/>: this is what gets written
    /// into the identity file, so later signed calls and the WS reconnect use one and the same root.
    /// </summary>
    public static string NormalizeBaseUrl(string baseUrl)
    {
        var trimmed = (baseUrl ?? "").Trim();
        if (trimmed.Length == 0)
            throw new ArgumentException("服务器地址不能为空，例如 https://push.iamplus.cn");
        if (!trimmed.Contains("://", StringComparison.Ordinal)) trimmed = "https://" + trimmed;
        if (!Uri.TryCreate(trimmed, UriKind.Absolute, out var uri)
            || (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
        {
            throw new ArgumentException($"服务器地址「{trimmed}」不是一个合法的 http/https 地址");
        }
        if (uri.Query.Length > 1 || uri.Fragment.Length > 0)
            throw new ArgumentException("服务器地址不要带查询参数或 #锚点，只填站点根地址");
        if (uri.UserInfo.Length > 0)
            throw new ArgumentException("服务器地址里不要写账号密码（凭据一律走请求头和签名）");

        return uri.ToString().TrimEnd('/');
    }

    /// <summary>Base URL + path. §5.3: device_id/token never go into a URL, so the path is fixed.</summary>
    public static string BuildUrl(string baseUrl, string path) => NormalizeBaseUrl(baseUrl) + path;

    private static Enrollment OpenEnvelope(string text, byte[] encPrivSeed, string url)
    {
        using var plain = OpenSealed(text, encPrivSeed, url);
        try
        {
            var root = plain.RootElement;
            return new Enrollment(
                Required(root, "device_id"),
                Required(root, "group_id"),
                Required(root, "token"),
                // join 时服务端把 recovery_code 置 null：旧码只在创建那一次下发
                NullableString(root, "recovery_code"),
                Required(root, "server_pub_fp"));
        }
        catch (JsonException e)
        {
            throw new PushBoxApiException(HttpStatusCode.OK, "invalid_response", e.Message);
        }
    }

    /// <summary>§4.3 的一轮密封盒：每个签名接口的成功响应都是它，用设备静态 enc 私钥打开。
    /// 打不开就是三种情况之一：不是密封盒（走了别的服务/被代理拦下）、密钥对不上、盒里没有正文。</summary>
    private static JsonDocument OpenSealed(string text, byte[] encPrivSeed, string url)
    {
        try
        {
            return RestSeal.Open(text, encPrivSeed);
        }
        catch (Exception e) when (e is JsonException or InvalidOperationException or CryptographicException)
        {
            // 一把密钥只开得开用它封的盒子：解不开多半是地址指错了服务，或身份文件与服务端记录不同步
            throw new PushBoxTransportException(url,
                "响应不是本机的 PushBox 密封盒——这个地址可能指向了别的服务、被反向代理拦下了，"
                + "或本机身份文件与服务端记录已不同步（可尝试用恢复码重新加入）", e);
        }
    }

    /// <summary>缺字段就按缺省走，与 Android 的 path(...).asLong() 口径一致：一页里少一个 latest_seq
    /// 不该让整次补拉失败，分页是否继续由 has_more 决定。</summary>
    internal static string? Str(JsonElement root, string name) =>
        root.TryGetProperty(name, out var el) && el.ValueKind == JsonValueKind.String ? el.GetString() : null;

    internal static long Long(JsonElement root, string name) => Long(root, name, 0L);

    /// <summary>缺列时的兜底取的是服务端的建表默认值，不是随手写的数：治理页要能原样回显这些默认。</summary>
    internal static long Long(JsonElement root, string name, long fallback) =>
        root.TryGetProperty(name, out var el) && el.ValueKind == JsonValueKind.Number
            ? el.GetInt64()
            : fallback;

    /// <summary>布尔位在服务端有两种写法：治理位是整数 0/1，而 {@code subscribed} 直接放的是
    /// Java 的 Boolean（序列化出来是 true/false，不是数字）。两种都认，缺字段一律按 false——
    /// "没接入"与"这个字段还没有"都渲染成未接入，客户端不会因此做出比服务端更激进的决定。</summary>
    internal static bool Flag(JsonElement root, string name) =>
        root.TryGetProperty(name, out var el)
        && (el.ValueKind == JsonValueKind.True
            || (el.ValueKind == JsonValueKind.Number && el.GetInt64() != 0));

    internal static IEnumerable<JsonElement> Objects(JsonElement root, string name)
    {
        if (!root.TryGetProperty(name, out var arr) || arr.ValueKind != JsonValueKind.Array)
            return Array.Empty<JsonElement>();
        var items = new List<JsonElement>();
        foreach (var item in arr.EnumerateArray())
            if (item.ValueKind == JsonValueKind.Object) items.Add(item);
        return items;
    }

    private static string Required(JsonElement root, string name)
    {
        var value = NullableString(root, name);
        if (value != null) return value;
        throw new JsonException($"注册响应里缺少 '{name}'");
    }

    private static string? NullableString(JsonElement root, string name)
    {
        return root.TryGetProperty(name, out var el) && el.ValueKind == JsonValueKind.String
            ? el.GetString()
            : null;
    }
}
