using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using PushBox.Windows.Data;
using PushBox.Windows.Media;
using PushBox.Windows.Net;
using PushBox.Windows.Sync;

// 真服务体检（§12 P3）：与 infra/probe/p0-e2e.mjs 同一个定位，只不过这里跑的是 PC 客户端自己的类。
// 单元测试里的服务端是脚本（vectors.json 的字节 + 测试自己封的盒），这里把它换成真的 Java 服务端：
// 真 nonce、真验签、真密封盒、真广播。跑法：
//   bash infra/run-dev.sh --server.port=8081
//   dotnet run --project windows/PushBox.LiveCheck -- http://127.0.0.1:8081
// 每跑一次在开发库里多出一个 Space 和两台设备，与 node 探针同样的代价。
Console.OutputEncoding = Encoding.UTF8;
var baseUrl = args.Length > 0 ? args[0] : "http://127.0.0.1:8081";
const string Channel = "80000001";
const string Sender = "live-check";
var checks = new Checks();
Console.WriteLine($"PushBox PC 客户端真服务体检 → {baseUrl}");

MessageFrame Msg(long seq) => new(seq, "live-" + seq, Channel, Sender, "Live Check", null, "text",
    "体检 " + seq, "体检摘要", "体检正文", "info", null, null, Array.Empty<MediaSlot>(), true,
    DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), false);

int Badge(DeviceStore db) => db.Conversations().Single(c => c.ChannelNo == Channel
    && c.SenderId == Sender).UnreadCount;

var runDir = Path.Combine(Path.GetTempPath(), "pushbox-live-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(runDir);
var storeA = new IdentityStore(Path.Combine(runDir, "a"));
var storeB = new IdentityStore(Path.Combine(runDir, "b"));
using var dbA = new DeviceStore(Path.Combine(runDir, "a", "pushbox.db"));
using var dbB = new DeviceStore(Path.Combine(runDir, "b", "pushbox.db"));

// ① 首启两屏背后的两次开放接口：注册拿到恢复码，另一台设备用它加入同一个 Space
DeviceIdentity pc, phone;
try
{
    pc = await new EnrollmentFlow(new DeviceClient(), storeA).CreateSpaceAsync(baseUrl, "Live PC");
    phone = await new EnrollmentFlow(new DeviceClient(), storeB)
        .JoinSpaceAsync(baseUrl, pc.RecoveryCode!, "Live Phone");
    checks.Result("① register/join 的密封盒解得开",
        pc.DeviceId.Length > 0 && pc.Token.Length > 0 && phone.GroupId == pc.GroupId
            && phone.RecoveryCode == null,
        $"pc={pc.DeviceId} phone={phone.DeviceId} group={pc.GroupId}，加入者拿不到恢复码（§4.2）");
}
catch (Exception e)
{
    checks.Fail("① register/join 的密封盒解得开", e);
    return checks.Summary();
}

// ② 真的 ClientWebSocket 走完整 §5.3：hello → auth（一次一签，吃服务端现发的 nonce）→ kx（验签）
var enginePc = new SyncEngine(storeA, dbA, new DeviceClient(), checks);
var enginePhone = new SyncEngine(storeB, dbB, new DeviceClient(), checks);
_ = enginePc.Start(storeA.Load());
_ = enginePhone.Start(storeB.Load());
var online = await checks.Reaches(
    () => enginePc.State is SyncState.Online && enginePhone.State is SyncState.Online,
    TimeSpan.FromSeconds(15));
var pinned = storeA.Load().PinnedServerFp;
checks.Result("② 真长连接走完 hello/auth/kx 并落 TOFU 指纹",
    online && pinned == pc.PinnedServerFp,
    online ? $"server_pub_fp={pinned}，与 register 回包里的是同一个值"
        : $"没连上：pc={enginePc.State} phone={enginePhone.State}");

// ③ 签名 GET（W8 的补拉路径）：query 逐字节进 canonical，服务端用 getQueryString() 复原
try
{
    var page = await new DeviceClient().HistoryAsync(pc, 0L);
    checks.Result("③ 签名 GET /api/v1/messages 被服务端接受", !page.HasMore,
        $"latest_seq={page.LatestSeq}，正文 {page.Messages.Count} 条、墓碑 {page.Tombstones.Count} 条");
}
catch (Exception e)
{
    checks.Fail("③ 签名 GET /api/v1/messages 被服务端接受", e);
}

// ④ 反向一条：同一套签名代码，换一个假 token。服务端必须认不出来，否则③只是自说自话
var refused = await CatchAsync(() => new DeviceClient().HistoryAsync(
    IdentityStore.CreateNew(baseUrl) with { DeviceId = pc.DeviceId, Token = "pbk_not_a_token" }, 0L));
checks.Result("④ 换掉 token 的同一条请求被 401 拒",
    refused is PushBoxApiException api && api.Code == "unauthorized",
    refused == null ? "服务端居然放过了一个假 token"
        : $"{refused.GetType().Name}: {refused.Message}");

// ⑤ §12 P3 验收②：这台报已读 → 同组那台的红点在 2 秒内消失
for (long seq = 1; seq <= 3; seq++)
{
    dbA.ApplyMessage(Msg(seq));
    dbB.ApplyMessage(Msg(seq));
}

var before = Badge(dbB);
var reporter = new ReadReporter(dbA, storeA, new DeviceClient());
reporter.SetOnline(true);
var clock = Stopwatch.StartNew();
reporter.MarkRead(Channel, Sender);
var cleared = false;
while (clock.Elapsed < TimeSpan.FromSeconds(5))
{
    if (Badge(dbB) == 0)
    {
        cleared = true;
        break;
    }

    await Task.Delay(25);
}

checks.Result("⑤ 本机报已读 → 他端红点在 2 秒内消失",
    cleared && clock.Elapsed < TimeSpan.FromSeconds(2) && reporter.LastError == null,
    cleared
        ? $"他端未读 {before} → 0 用了 {(int)clock.Elapsed.TotalMilliseconds} ms"
        : $"没消失（本机未读仍是 {Badge(dbB)}）：{(reporter.LastError ?? "read 帧没到")}"
          + $"，攒在队列里的上报数={reporter.PendingCount}");

// ⑥ 断线重连：重连仍只认这同一个指纹（§4.3 不降级），且服务端按 since_seq 续得上
enginePc.Stop();
await checks.Reaches(() => enginePc.State is SyncState.Idle, TimeSpan.FromSeconds(10));
_ = enginePc.Start(storeA.Load());
var backOnline = await checks.Reaches(() => enginePc.State is SyncState.Online, TimeSpan.FromSeconds(15));
var reloaded = storeA.Load();
// 游标的权威值是库里那行 sync_state；identity.json 的副本只在故障断线时回写（用户主动 Stop 不回写，
// 因为下次建连照样会读库取 max），所以这里断言的是库游标没被重连冲掉，副本另有单测覆盖
checks.Result("⑥ 断开后带游标重连，仍然只认同一个指纹",
    backOnline && reloaded.PinnedServerFp == pinned && dbA.GroupCursor() == 3L,
    backOnline ? $"重连后游标仍在 {dbA.GroupCursor()}，指纹没变"
        : $"没重连上：{enginePc.State}");

// ⑦ W10 的签名取图：这一发一次验两件事——我们拼的那串 size query 服务端验得过签（404 而不是 401），
// 以及"回答不是密封盒"这条路走得通：这里探的是服务端没有的消息，MediaController 回 404 + hint=槽位状态，
// 客户端要把它变成一个占位，而不是解不开盒子的那句"地址指错了服务"。
try
{
    var mediaClient = new DeviceClient();
    var cacheDir = Path.Combine(runDir, "media");
    var probe = await CatchAsync(() => mediaClient.MediaAsync(pc, "live_media_probe", 0, true));
    var slot = await new MediaLoader(mediaClient, new MediaCache(cacheDir))
        .ImageAsync(pc, "live_media_probe", 0, true);
    var e = probe as PushBoxApiException;
    // 目录压根没被建起来也算"失败的取图没落盘"：缓存只在写成功时才创建目录
    var nothingCached = !Directory.Exists(cacheDir) || !Directory.EnumerateFiles(cacheDir, "*.img").Any();
    checks.Result("⑦ 签名 GET 图片字节：query 进得了验签，槽位状态进得了占位",
        e is { Status: HttpStatusCode.NotFound, Code: "not_found", Hint: "media" }
            && slot.State == MediaState.Missing && slot.Placeholder == "图片已失效" && nothingCached,
        e == null
            ? probe == null ? "服务端居然回了一张真图（这条消息并不存在）" : $"{probe.GetType().Name}: {probe.Message}"
            : $"404 hint={e.Hint} → {slot.State}，占位「{slot.Placeholder}」，失败的取图没往缓存里落");
}
catch (Exception e)
{
    checks.Fail("⑦ 签名 GET 图片字节：query 进得了验签，槽位状态进得了占位", e);
}

// ⑧ W11 的签名搜索：这一发的 query 里有中文、空格、% 和 &，转义后逐字节进 canonical 才验得过签。
// & 是最要紧的那个字符——它同时是 query 的分隔符，服务端 getQueryString() 拿到的是转义形态，
// 我们要是先拼未转义的串再签名，这两发都会变成 401。
try
{
    var search = new SearchService(dbA, storeA, new DeviceClient());
    var odd = await search.SearchAsync("体检 100%&阈值 空间不足", Channel, Sender);
    var hit = await search.SearchAsync("体检", Channel, Sender);
    // 服务端返 0 条不是"搜索坏了"：这个 Space 是本次体检刚建出来的，服务端从来没有过这三条消息，
    // 它们只在本地库里——验收①"未缓存的历史消息能被搜到"的另一半（服务端真有内容）由单测的假服务端覆盖。
    checks.Result("⑧ 带中文与 %& 的签名搜索被服务端原样验签通过，且本地那半没被合并掉",
        odd.Error is null && odd.Server.Count == 0 && hit.Error is null
            && hit.Local.Count == 3 && hit.Server.Count == 0,
        $"怪词那发 本地 {odd.Local.Count} / 服务端 {odd.Server.Count}（{(odd.Error ?? "服务端认了")}），"
        + $"「体检」那发 本地 {hit.Local.Count} / 服务端 {hit.Server.Count}（{(hit.Error ?? "服务端认了")}）");
}
catch (Exception e)
{
    checks.Fail("⑧ 带中文与 %& 的签名搜索被服务端原样验签通过，且本地那半没被合并掉", e);
}

// ⑨⑩ W14 的两发 DELETE：这一层只有真服务端能给——单测里的 canonical 是我们自己算的，
// 签到底验没验过是脚本在迁就我们。两发都故意打在"服务端没有这些行"上，因为本次体检的消息只落在两端本地库里：
// 单条那发的正确回答是 404 hint=message（401 才是签名不对），整会话那发的正确回答是 deleted=0 的 200
// （幂等，不报 404）。服务端有真行的那一支由 infra/probe 的 ⑪/⑱ 覆盖，这里不重复建频道与推送。
try
{
    var gone = await CatchAsync(() => new DeviceClient()
        .DeleteMessageAsync(pc, "live-not-on-the-server"));
    var notFound = gone as PushBoxApiException;
    checks.Result("⑨ 单条 DELETE 被服务端验签接受，并被客户端读成『服务端本来就没有这条』",
        notFound is { Status: HttpStatusCode.NotFound, Code: "not_found", Hint: "message" }
            && DeleteReporter.IsAlreadyGone(notFound),
        gone == null ? "服务端居然说删掉了（这条在它那儿并不存在）"
            : $"{(int?)notFound?.Status} error={notFound?.Code} hint={notFound?.Hint} → 不回滚，本机保持移除");

    // 整会话走的是真的 DeleteReporter：本机的乐观删除、服务端那一发、确认之后的收尾全在这条路上
    var clearer = new DeleteReporter(dbA, storeA, new DeviceClient(), new MediaLoader());
    var clearedAll = await clearer.ClearConversationAsync(Channel, Sender);
    checks.Result("⑩ 带 scope 的 DELETE 被服务端验签接受，deleted=0 读成幂等成功而不回滚",
        clearedAll.Outcome == DeleteOutcome.Deleted
            && !dbA.Conversations().Any(c => c.ChannelNo == Channel && c.SenderId == Sender),
        clearedAll.Outcome == DeleteOutcome.Deleted
            ? "本机那条会话已经没了，回滚票没用上，提示条一句没说"
            : $"回滚了：{clearedAll.Notice}");
}
catch (Exception e)
{
    checks.Fail("⑨⑩ 两发 DELETE", e);
}

// ⑪⑫ §5.2 接入频道（"APP 和 PC 收到同样的消息"判的就是这两条）。
// 单测里的服务端是我们自己写的脚本：字段名 channel_no / secret / subscribed / subscribed_only
// 打错任何一个字母，两边都会照样绿，所以这两条必须打在真 Java 服务端上。
// 这里走 REST 历史而不是 WS 实时帧：过滤逻辑两条路径共用 ChannelService.readScope，
// 历史这一条是确定性读数，不必把判据挂在连接时序上（实时那半由 infra/probe 的 ㉓ 覆盖）。
try
{
    var client = new DeviceClient();
    using var ingest = new HttpClient { BaseAddress = new Uri(baseUrl) };
    var mine = await client.CreateChannelAsync(pc, "Live 接入频道");
    var theirs = await client.CreateChannelAsync(phone, "Live 另一台的频道");
    var flags = await Task.WhenAll(
        SubscribedOn(client, pc, mine.ChannelNo), SubscribedOn(client, pc, theirs.ChannelNo),
        SubscribedOn(client, phone, theirs.ChannelNo), SubscribedOn(client, phone, mine.ChannelNo));
    var badSecret = await CatchAsync(() =>
        client.JoinChannelAsync(pc, theirs.ChannelNo, new string('0', 32)));
    checks.Result("⑪ 真服务端认这套接入请求：建频道的那台默认已接入自己那条，同 Space 他端看得见但未接入，"
        + "错密钥被 401 拒",
        flags[0] && !flags[1] && flags[2] && !flags[3]
            && badSecret is PushBoxApiException denied && denied.Code == "unauthorized",
        $"pc 自己/看对方={flags[0]}/{flags[1]}，phone 自己/看对方={flags[2]}/{flags[3]}，"
        + $"错密钥={(badSecret as PushBoxApiException)?.Code ?? badSecret?.GetType().Name}");

    var cursor = (await client.HistoryAsync(pc, 0L)).LatestSeq;
    var onMine = await PushAsync(ingest, mine);
    var onTheirs = await PushAsync(ingest, theirs);
    var modeOn = await client.SetSubscriptionModeAsync(pc, true);
    var filtered = await client.HistoryAsync(pc, cursor);
    var both = await client.HistoryAsync(phone, cursor);
    await client.JoinChannelAsync(pc, theirs.ChannelNo, theirs.Secret);
    var afterJoin = await client.HistoryAsync(pc, cursor);
    await client.LeaveChannelAsync(pc, theirs.ChannelNo);
    var afterLeave = await client.HistoryAsync(pc, cursor);
    var modeOff = await client.SetSubscriptionModeAsync(pc, false);
    var unfiltered = await client.HistoryAsync(pc, cursor);
    // 接入之后连接入<b>前</b>那一条也拉得到：过滤按频道而不是按接入时刻，这是刻意的口径
    checks.Result("⑫ 「只接收已接入频道」在真服务端真的参与读路径：未接入的频道拉不到，"
        + "接入后同一条频道的消息两台都拉到（含接入前那条），取消接入又收不到，关回全收恢复",
        modeOn && Sees(filtered, onMine) && !Sees(filtered, onTheirs)
            && Sees(both, onMine) && Sees(both, onTheirs)
            && Sees(afterJoin, onTheirs) && !Sees(afterLeave, onTheirs)
            && !modeOff && Sees(unfiltered, onTheirs),
        $"过滤开={modeOn}：pc 自己/对方={Sees(filtered, onMine)}/{Sees(filtered, onTheirs)}，"
        + $"phone（全收）={Sees(both, onMine)}/{Sees(both, onTheirs)} → 接入后={Sees(afterJoin, onTheirs)}"
        + $" → 取消后={Sees(afterLeave, onTheirs)} → 关回全收={modeOff} 后={Sees(unfiltered, onTheirs)}");
}
catch (Exception e)
{
    checks.Fail("⑪⑫ 接入频道", e);
}

enginePc.Stop();
enginePhone.Stop();
try
{
    Directory.Delete(runDir, true);
}
catch (IOException)
{
    // SQLite 句柄还挂在 OS 上：留着临时目录比误删别人的文件好
}

return checks.Summary();

static async Task<Exception?> CatchAsync(Func<Task> body)
{
    try
    {
        await body();
        return null;
    }
    catch (Exception e)
    {
        return e;
    }
}

/// <summary>§5.2 列表里这一台对那条频道的接入标记。缺行直接抛，不静默当成 false。</summary>
static async Task<bool> SubscribedOn(DeviceClient client, DeviceIdentity identity, string channelNo) =>
    (await client.ChannelsPageAsync(identity)).Channels.Single(c => c.ChannelNo == channelNo).Subscribed;

/// <summary>
/// 第三方入站那一发（<c>POST /message</c>，公开入站路径，凭据就是频道号 + 密钥本身）：
/// 它不需要设备签名，所以这里用一个裸 HttpClient——真设备走的是同一套入站规则。
/// 返回消息 id：接入前后的两次历史读都拿它比对，密钥与正文都不进输出。
/// </summary>
static async Task<string> PushAsync(HttpClient http, NewChannel channel)
{
    var payload = $"{{\"channel\":\"{channel.ChannelNo}\",\"secret\":\"{channel.Secret}\","
        + "\"type\":\"text\",\"sender_id\":\"live-check\",\"content\":\"接入体检：这一条要过读路径过滤\"}";
    var response = await http.PostAsync("/message",
        new StringContent(payload, new UTF8Encoding(false), "application/json"));
    response.EnsureSuccessStatusCode();
    using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
    return doc.RootElement.GetProperty("id").GetString()!;
}

static bool Sees(HistoryPage page, string id) => page.Messages.Any(m => m.Id == id);

/// <summary>体检的记账本：只关心"服务端认不认这一发"，正文一条都不打（§14）。</summary>
internal sealed class Checks : INotifier
{
    private int _passed;
    private int _failed;

    public void Notify(MessageFrame message)
    {
        // 通知的格式是 W13 的 WinRT Toast 管的事，这里只把帧咽下去：真服务体检只看协议层
    }

    public void Result(string name, bool ok, string detail)
    {
        if (ok)
        {
            _passed++;
            Console.WriteLine($"  通过  {name}　{detail}");
        }
        else
        {
            _failed++;
            Console.WriteLine($"  失败  {name}　{detail}");
        }
    }

    public void Fail(string name, Exception e) =>
        Result(name, false, $"{e.GetType().Name}: {e.Message}");

    public async Task<bool> Reaches(Func<bool> condition, TimeSpan timeout)
    {
        var deadline = Stopwatch.GetTimestamp() + (long)(timeout.TotalSeconds * Stopwatch.Frequency);
        while (!condition() && Stopwatch.GetTimestamp() < deadline) await Task.Delay(25);
        return condition();
    }

    public int Summary()
    {
        Console.WriteLine($"合计 {_passed}/{_passed + _failed} 通过");
        return _failed == 0 ? 0 : 1;
    }
}
