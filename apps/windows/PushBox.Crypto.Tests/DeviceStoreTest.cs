using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using Microsoft.Data.Sqlite;
using PushBox.Windows.Data;
using PushBox.Windows.Net;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// W7: §3.3 的本地库要能在进程重启后把会话、消息、游标原样端回来，分页不乱序，
/// 未读数由游标算出来，且这台机器上的明文库里除了消息正文不该有别的敏感东西。
/// </summary>
public sealed class DeviceStoreTest : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(),
        "pushbox-store-test-" + Guid.NewGuid().ToString("N"));

    private string DbPath => Path.Combine(_dir, "pushbox.db");

    [Fact]
    public void TheDefaultDatabaseSitsInThePrivateLocalAppDataFolder()
    {
        // §3.3：Windows 存 %LOCALAPPDATA%\PushBox\pushbox.db，不进漫游配置
        var local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        Assert.Equal(Path.Combine(local, "PushBox", "pushbox.db"), DeviceStore.DefaultDatabasePath);
        Assert.Equal(Path.Combine(local, "PushBox", "identity.json"), new IdentityStore().FilePath);
    }

    [Fact]
    public void ColdStartRestoresConversationsMessagesAndCursor()
    {
        using (var store = new DeviceStore(DbPath))
        {
            Assert.Equal(0L, store.GroupCursor());
            Assert.True(store.ApplyMessage(Msg(18481, "m1", body: "构建 #1287 完成")));
            Assert.True(store.ApplyMessage(Msg(18482, "m2", body: "磁盘告警 /mnt 已用 91%")));
        }

        // 重开 = 进程重启：建表语句再跑一遍必须是幂等的，数据一条不能少
        using (var reopened = new DeviceStore(DbPath))
        {
            Assert.Equal(18482L, reopened.GroupCursor());
            Assert.Equal(18482L, Assert.Single(reopened.Conversations()).LastSeq);
            var rows = reopened.MessagesPage("83271504", "grafana");
            Assert.Equal(new[] { 18481L, 18482L }, rows.Select(r => r.Seq));
            Assert.Equal("磁盘告警 /mnt 已用 91%", rows[1].Body);
        }
    }

    [Fact]
    public void AMissingDirectoryIsCreatedBeforeTheFileIsOpened()
    {
        var nested = Path.Combine(_dir, "deep", "nested", "pushbox.db");
        Assert.False(Directory.Exists(Path.GetDirectoryName(nested)!));

        using var store = new DeviceStore(nested);
        Assert.True(File.Exists(nested));
    }

    [Fact]
    public void MessageConversationAndCursorMoveInOneStep()
    {
        using var store = new DeviceStore(DbPath);
        Assert.True(store.ApplyMessage(Msg(10, "m10", summary: "部署完成")));

        Assert.Equal(10L, store.GroupCursor());
        var conversation = Assert.Single(store.Conversations());
        Assert.Equal("Grafana", conversation.DisplayName);
        Assert.Equal(10L, conversation.LastSeq);
        Assert.Equal("部署完成", conversation.LastSummary);
        Assert.Equal(1, conversation.UnreadCount);

        // 补拉与实时下发是同一条消息的两个副本：第二次必须报告"见过"，且什么都不能变
        Assert.False(store.ApplyMessage(Msg(10, "m10", summary: "部署完成")));
        Assert.Equal(10L, store.GroupCursor());
        conversation = Assert.Single(store.Conversations());
        Assert.Equal(1, conversation.UnreadCount);
        Assert.Single(store.MessagesPage("83271504", "grafana"));
    }

    [Fact]
    public void PagesComeBackInSeqOrderAndTheNextPageNeitherRepeatsNorSkips()
    {
        using var store = new DeviceStore(DbPath);
        // 落库顺序故意打乱：补拉按 seq 正序、实时按到达序，两条路径混在一起
        foreach (var seq in new[] { 3L, 1L, 5L, 2L, 4L })
            store.ApplyMessage(Msg(seq, $"m{seq}"));

        var first = store.MessagesPage("83271504", "grafana", limit: 2);
        Assert.Equal(new[] { 4L, 5L }, first.Select(r => r.Seq));

        var second = store.MessagesPage("83271504", "grafana", first[0].Seq - 1, 2);
        Assert.Equal(new[] { 2L, 3L }, second.Select(r => r.Seq));

        var third = store.MessagesPage("83271504", "grafana", second[0].Seq - 1, 2);
        Assert.Equal(new[] { 1L }, third.Select(r => r.Seq));

        var all = first.Concat(second).Concat(third).ToList();
        Assert.Equal(5, all.Count);
        Assert.Equal(5, all.Select(r => r.MsgId).Distinct().Count());
    }

    [Fact]
    public void UnreadIsDerivedFromTheReadCursor()
    {
        using var store = new DeviceStore(DbPath);
        for (long seq = 1; seq <= 3; seq++) store.ApplyMessage(Msg(seq, $"m{seq}"));
        Assert.Equal(3, Assert.Single(store.Conversations()).UnreadCount);

        store.MarkConversationRead("83271504", "grafana");
        Assert.Equal(0, Assert.Single(store.Conversations()).UnreadCount);

        // 服务端游标回退不可能，但更小的 last_read_seq 必须被 MAX 挡住
        store.ApplyRead("83271504", "grafana", 2L);
        Assert.Equal(0, Assert.Single(store.Conversations()).UnreadCount);

        store.ApplyMessage(Msg(4, "m4"));
        var conversation = Assert.Single(store.Conversations());
        Assert.Equal(1, conversation.UnreadCount);
        Assert.Equal(3L, conversation.LastReadSeq);
    }

    [Fact]
    public void TheMuteFlagIsReadPerConversationLine()
    {
        // §7.3 免打扰。这一列在 PC 上目前只有读的一侧（§8.1 的形态里还没有那个写入口），
        // 而读它的那一道门现在就立在通知路径上，所以这里按 §3.3 的形状把列造出来，测的是"读得对不对"
        using var store = new DeviceStore(DbPath);
        // 会话行还不存在时的口径是 false：第一条消息、已读游标、静音状态谁先到没有保证，
        // "没有这一行"的意思是"没人说过要静音"，而不是"未知所以别弹"（Android 的 `!= true` 同义）
        Assert.False(store.IsMuted("83271504", "grafana"));

        store.ApplyMessage(Msg(1, "m1"));
        store.ApplyMessage(Msg(2, "m2"));
        store.ApplyMessage(Msg(3, "m3", sender: "deploy"));
        Assert.False(store.IsMuted("83271504", "grafana"));

        WriteMuted(store.DatabasePath, "83271504", "grafana", true);
        Assert.True(store.IsMuted("83271504", "grafana"));
        // 同频道的另一条会话线不受影响：免打扰是 (channel_no, sender_id) 这一对，不是整个频道
        Assert.False(store.IsMuted("83271504", "deploy"));

        // 这一列只管"要不要弹"：静音之后消息照常落库、红点照常算、会话行照常带着 Muted 出来给 UI 画标记
        Assert.Equal(2, store.MessagesPage("83271504", "grafana").Count);
        var muted = store.Conversations().Single(c => c.SenderId == "grafana");
        Assert.True(muted.Muted);
        Assert.Equal(2, muted.UnreadCount);
    }

    [Fact]
    public void MarkConversationReadAnswersWithTheCursorThatActuallyGotStored()
    {
        // W9 要把这个值 PUT 回服务端，所以它必须是"库里现在真的是多少"，而不是"我以为最后一条是多少"
        using var store = new DeviceStore(DbPath);
        for (long seq = 1; seq <= 3; seq++) store.ApplyMessage(Msg(seq, $"m{seq}"));
        Assert.Equal(3L, store.MarkConversationRead("83271504", "grafana"));

        // 会话不在库里：没有已读可言，也不该发一发 PUT
        Assert.Equal(0L, store.MarkConversationRead("00000000", "nobody"));

        // 他端读得更远时，回传的仍是那个更大的值——发小的一位会把别端刚清掉的红点点回来
        store.ApplyRead("83271504", "grafana", 10L);
        Assert.Equal(10L, store.MarkConversationRead("83271504", "grafana"));
    }

    [Fact]
    public void AReadCursorThatArrivesBeforeTheMessagesStillSuppressesUnread()
    {
        using var store = new DeviceStore(DbPath);
        // 常见时序：本端离线时他端把会话读到了 10，重连后 5..12 一起补拉进来
        store.ApplyRead("83271504", "grafana", 10L);
        for (long seq = 5; seq <= 12; seq++) store.ApplyMessage(Msg(seq, $"m{seq}"));

        var conversation = Assert.Single(store.Conversations());
        Assert.Equal(2, conversation.UnreadCount);
        // 壳行的名字在他端的第一条真实帧到达时被覆盖，不是永远停在 sender_id
        Assert.Equal("Grafana", conversation.Name);
    }

    [Fact]
    public void MediaReadyFillsTheRowWithoutTouchingTheCursorOrTheBadge()
    {
        using var store = new DeviceStore(DbPath);
        // 历史项自带 media_status（§5.2），实时帧没有：两种形状落进同一列
        store.ApplyMessage(Msg(7, "m7", body: "带图告警", mediaStatus: "pending"));
        var before = Assert.Single(store.Conversations());
        Assert.Equal("pending", Assert.Single(store.MessagesPage("83271504", "grafana")).MediaStatus);

        store.ApplyMediaStatus("m7", "ready", 2);

        var row = Assert.Single(store.MessagesPage("83271504", "grafana"));
        Assert.Equal("ready", row.MediaStatus);
        Assert.Equal(2, row.MediaCount);
        Assert.Equal(before.LastSeq, Assert.Single(store.Conversations()).LastSeq);
        Assert.Equal(7L, store.GroupCursor());
    }

    [Fact]
    public void TombstoneOfOneMessageRecomputesTheConversationAggregate()
    {
        using var store = new DeviceStore(DbPath);
        store.ApplyMessage(Msg(5, "m5", body: "保留", summary: "保留的摘要", createdAt: 1000L));
        store.ApplyMessage(Msg(6, "m6", body: "删我", summary: "要被删的摘要", createdAt: 2000L));

        store.ApplyTombstone(Tombstone("{\"t\":\"tombstone\",\"id\":\"m6\",\"deleted_at\":3000}"));

        var conversation = Assert.Single(store.Conversations());
        Assert.Equal(5L, conversation.LastSeq);
        Assert.Equal(1000L, conversation.LastMsgAt);
        Assert.Equal("保留的摘要", conversation.LastSummary);
        // 删掉的是一条未读，红点跟着少一个——这正是未读数不落列的原因
        Assert.Equal(1, conversation.UnreadCount);
        Assert.Single(store.MessagesPage("83271504", "grafana"));
    }

    [Fact]
    public void TombstoneOfTheLastMessageRemovesTheConversationRow()
    {
        using var store = new DeviceStore(DbPath);
        store.ApplyMessage(Msg(5, "m5"));
        store.ApplyTombstone(Tombstone("{\"t\":\"tombstone\",\"id\":\"m5\",\"deleted_at\":6}"));

        Assert.Empty(store.Conversations());
        Assert.Empty(store.MessagesPage("83271504", "grafana"));
    }

    [Fact]
    public void ConversationScopeClearsOneSenderAndChannelScopeClearsTheWholeChannel()
    {
        using var store = new DeviceStore(DbPath);
        store.ApplyMessage(Msg(1, "a1", channel: "83271504", sender: "grafana"));
        store.ApplyMessage(Msg(2, "a2", channel: "83271504", sender: "jenkins"));
        store.ApplyMessage(Msg(3, "a3", channel: "10010010", sender: "grafana"));

        store.ApplyTombstone(Tombstone(
            """{"t":"tombstone","scope":{"channel_no":"83271504","sender_id":"grafana"},"deleted_at":4}"""));
        // 排序口径是"最新在前"（pinned 之后按 last_msg_at），不是插入顺序
        Assert.Equal(new[] { "grafana@10010010", "jenkins@83271504" },
            store.Conversations().Select(c => $"{c.SenderId}@{c.ChannelNo}"));

        store.ApplyTombstone(Tombstone(
            """{"t":"tombstone","scope":{"channel_no":"83271504"},"deleted_at":5}"""));
        var left = Assert.Single(store.Conversations());
        Assert.Equal("10010010", left.ChannelNo);
        Assert.Equal("a3", Assert.Single(store.MessagesPage("10010010", "grafana")).MsgId);
    }

    [Fact]
    public void AFrameWithNeitherIdNorScopeClearsNothing()
    {
        using var store = new DeviceStore(DbPath);
        store.ApplyMessage(Msg(1, "a1"));
        store.ApplyTombstone(Tombstone("{\"t\":\"tombstone\",\"deleted_at\":2}"));
        Assert.Single(store.MessagesPage("83271504", "grafana"));
    }

    [Fact]
    public void SearchHitsTitleSummaryAndBody()
    {
        using var store = new DeviceStore(DbPath);
        store.ApplyMessage(Msg(1, "in-title", title: "夜间备份任务", sender: "restic"));
        store.ApplyMessage(Msg(2, "in-body", body: "POST /mnt 空间不足，请及时清理数据"));
        store.ApplyMessage(Msg(3, "in-summary", summary: "摘要里提到回滚流程", sender: "cd"));
        store.ApplyMessage(Msg(4, "ascii", body: "Disk FULL on /var", sender: "node"));

        Assert.Equal("in-title", Assert.Single(store.Search("夜间备份")).MsgId);
        Assert.Equal("in-body", Assert.Single(store.Search("空间不足")).MsgId);
        Assert.Equal("in-summary", Assert.Single(store.Search("回滚流程")).MsgId);
        // trigram 默认大小写不敏感，和 LIKE 分支的 lower() 口径一致
        Assert.Equal("ascii", Assert.Single(store.Search("disk full")).MsgId);
        Assert.Empty(store.Search("没有这个词的内容"));
    }

    [Fact]
    public void ShortTermsFallBackToLikeBecauseTrigramSlicesThreeChars()
    {
        using var store = new DeviceStore(DbPath);
        store.ApplyMessage(Msg(1, "hit", body: "备份失败：目标盘只读"));
        store.ApplyMessage(Msg(2, "miss", body: "一切正常"));

        // 与 §3.2 服务端 ngram(2) + LIKE 兜底同一个道理：短词进不了分词索引
        Assert.Equal("hit", Assert.Single(store.Search("备份")).MsgId);
        Assert.Equal("hit", Assert.Single(store.Search("只读")).MsgId);
        Assert.Equal("hit", Assert.Single(store.Search("只读")).MsgId);
        Assert.Empty(store.Search("不存在"));
    }

    [Fact]
    public void AScopedSearchStaysInsideOneConversationLine()
    {
        using var store = new DeviceStore(DbPath);
        store.ApplyMessage(Msg(1, "here", sender: "node", body: "本会话的 磁盘阈值 提示"));
        store.ApplyMessage(Msg(2, "other-channel", channel: "91002203", sender: "node",
            body: "别的频道的 磁盘阈值 提示"));
        store.ApplyMessage(Msg(3, "other-sender", sender: "deploy", body: "同频道别的发送者的 磁盘阈值 提示"));

        // §7.4 对话页那个"仅本会话"：两条 SQL 分支都得吃这个条件。LIKE 那条的三个 OR 不整体括号的话，
        // AND 只管得住最后一个 OR 分支（SQL 里 AND 结合得比 OR 紧），短词查询就会把别的会话也捞出来
        Assert.Equal("here", Assert.Single(store.Search("磁盘阈值", "83271504", "node")).MsgId);
        Assert.Equal("here", Assert.Single(store.Search("阈值", "83271504", "node")).MsgId);
        // 只给频道不给发送者 = 这个频道下所有会话线
        Assert.Equal(2, store.Search("磁盘阈值", "83271504").Count);
        Assert.Equal(3, store.Search("磁盘阈值").Count);
    }

    [Fact]
    public void TombstonedRowsDisappearFromTheIndex()
    {
        using var store = new DeviceStore(DbPath);
        store.ApplyMessage(Msg(1, "keep", body: "保留的正文内容"));
        store.ApplyMessage(Msg(2, "drop", body: "待删的正文内容"));
        Assert.Equal(2, store.Search("正文内容").Count);

        store.ApplyTombstone(Tombstone("{\"t\":\"tombstone\",\"id\":\"drop\",\"deleted_at\":3}"));
        Assert.Equal("keep", Assert.Single(store.Search("正文内容")).MsgId);
    }

    [Fact]
    public void WildcardsInTheQueryAreMatchedLiterally()
    {
        using var store = new DeviceStore(DbPath);
        store.ApplyMessage(Msg(1, "literal", body: "字面量 100% 覆盖与 a_b 变量"));
        store.ApplyMessage(Msg(2, "other", body: "百分之 90 覆盖"));

        // 单字符走 LIKE 分支：这两个查询要是没转义，% 和 _ 会当成通配符把两行都捞出来
        Assert.Equal("literal", Assert.Single(store.Search("%")).MsgId);
        Assert.Equal("literal", Assert.Single(store.Search("_")).MsgId);
        // 三个字符以上走 trigram，那里本来就没有通配符语义
        Assert.Equal("literal", Assert.Single(store.Search("100% ")).MsgId);
        Assert.Equal("literal", Assert.Single(store.Search("a_b")).MsgId);
    }

    [Fact]
    public void BodyIsPlaintextLocallyButNoCredentialEverLandsInThisFile()
    {
        using var store = new DeviceStore(DbPath);
        store.ApplyMessage(Msg(1, "m1", body: "只有本机看得到正文"));
        // SQLite 以 RW 共享方式持有文件句柄，所以这里必须按 ReadWrite 共享去读，
        // File.ReadAllBytes（默认只共享读）会被自己的连接挡下来
        Assert.Contains("只有本机看得到正文", Encoding.UTF8.GetString(ReadWholeFile(DbPath)));

        // §3.3 的保密口径：明文库只装消息，设备凭据一律归 identity.json（W4 的 DPAPI）
        foreach (var table in new[] { "messages", "conversations", "sync_state" })
        {
            foreach (var column in ColumnsOf(store, table))
            {
                foreach (var forbidden in new[] { "token", "priv", "seed", "secret", "fp", "key" })
                {
                    Assert.False(column.Contains(forbidden, StringComparison.OrdinalIgnoreCase),
                        $"{table}.{column} 看着像在存凭据，本地明文库不该有这一列");
                }
            }
        }
    }

    // ---- W14：回滚票与放回 ----------------------------------------------------------------------------

    [Fact]
    public void TheUndoTicketCarriesTheColumnsOnlyThisMachineKnows()
    {
        using var store = new DeviceStore(DbPath);
        store.ApplyMessage(Msg(1, "m1"));
        store.ApplyMessage(Msg(2, "m2"));
        WriteMuted(DbPath, "83271504", "grafana", true);
        store.MarkConversationRead("83271504", "grafana");

        var undo = store.DeleteMessageLocal("m1");

        // 消息一回来，last_seq / last_msg_at / 摘要都能从 messages 现算；会话那一行的这几列删掉之后就没处再取，
        // 所以票上必须带着它们——这正是 §9.3 那条"回滚兜底"在库这一侧的代价
        Assert.Equal("m1", undo!.Message.MsgId);
        Assert.Equal("Grafana", undo.Conversation.Name);
        Assert.True(undo.Conversation.Muted);
        Assert.Equal(2L, undo.Conversation.LastReadSeq);

        store.RestoreMessage(undo);
        Assert.Equal(new[] { "m1", "m2" }, Ids(store));
        var restored = Assert.Single(store.Conversations());
        Assert.True(restored.Muted);
        // 红点没有因为这一趟来回凭空复现
        Assert.Equal(0, restored.UnreadCount);
    }

    [Fact]
    public void TheLocalDeleteAndTheTombstoneFrameLandOnTheSameDatabaseState()
    {
        // §8.4 那句"本地即时移除 + 等 tombstone 收敛"靠的不是两套代码各自小心，而是这个同一性：
        // 乐观删除走的就是帧到了之后那段行变更（删行 → 重算聚合 → 空了就删会话行）
        using var optimistic = new DeviceStore(Path.Combine(_dir, "optimistic.db"));
        using var converged = new DeviceStore(Path.Combine(_dir, "converged.db"));
        foreach (var store in new[] { optimistic, converged })
        {
            store.ApplyMessage(Msg(1, "m1"));
            store.ApplyMessage(Msg(2, "m2"));
            store.ApplyMessage(Msg(3, "m3"));
        }

        optimistic.DeleteMessageLocal("m2");
        converged.ApplyTombstone(Tombstone("{\"t\":\"tombstone\",\"id\":\"m2\",\"deleted_at\":1765500009000}"));

        Assert.Equal(Ids(converged), Ids(optimistic));
        Assert.Equal(Shape(converged), Shape(optimistic));
        Assert.Equal(converged.GroupCursor(), optimistic.GroupCursor());
    }

    [Fact]
    public void ARestoreNeverDragsTheReadCursorBackwards()
    {
        using var store = new DeviceStore(DbPath);
        store.ApplyMessage(Msg(1, "m1"));
        store.ApplyMessage(Msg(2, "m2"));
        var undo = store.DeleteMessageLocal("m2")!;

        // 删出去那段时间里：这条会话又来了新消息，并且被别的端读到 3（§5.3 的 read 帧先落库）
        store.ApplyMessage(Msg(3, "m3"));
        store.ApplyRead("83271504", "grafana", 3L);

        store.RestoreMessage(undo);

        // 本机一次<b>失败</b>的删除不许把手机上刚灭的红点又点亮，所以这一列只增不减
        Assert.Equal(3L, Assert.Single(store.Conversations()).LastReadSeq);
        Assert.Equal(new[] { "m1", "m2", "m3" }, Ids(store));
    }

    [Fact]
    public void ARestoreDoesNotClobberWhatAnotherFrameChangedInTheMeanTime()
    {
        using var store = new DeviceStore(DbPath);
        store.ApplyMessage(Msg(1, "m1", name: "监控告警"));
        var undo = store.DeleteMessageLocal("m1")!;
        // 会话行因为这条消息被删空而消失，紧接着新消息把行重建了，名字是服务端现在给的那个
        Assert.Empty(store.Conversations());
        store.ApplyMessage(Msg(2, "m2", name: "Grafana 新版"));

        store.RestoreMessage(undo);

        // 放回只补消息和缺失的那一行，不覆盖行现在有的 name / pinned / muted
        Assert.Equal("Grafana 新版", Assert.Single(store.Conversations()).Name);
    }

    [Fact]
    public void AMessageThatAlreadyCameBackWinsOverTheTicket()
    {
        using var store = new DeviceStore(DbPath);
        store.ApplyMessage(Msg(1, "m1", mediaStatus: "pending"));
        var undo = store.DeleteMessageLocal("m1")!;
        // 竞态：补拉在同一条上又落了一次，还回填了服务端的媒体终态
        store.ApplyMessage(Msg(1, "m1"));
        store.ApplyMediaStatus("m1", "ready", 2);

        store.RestoreMessage(undo);

        // ON CONFLICT DO NOTHING：票上那份是删之前的旧值，不该盖掉库里刚到的新值
        var row = Assert.Single(store.MessagesPage("83271504", "grafana"));
        Assert.Equal("ready", row.MediaStatus);
        Assert.Equal(2, row.MediaCount);
    }

    [Fact]
    public void ClearingAndRestoringAConversationIsALosslessRoundTrip()
    {
        using var store = new DeviceStore(DbPath);
        for (long seq = 1; seq <= 5; seq++) store.ApplyMessage(Msg(seq, $"m{seq}", body: $"第 {seq} 条正文"));
        store.MarkConversationRead("83271504", "grafana");

        var undo = store.ClearConversationLocal("83271504", "grafana")!;
        Assert.Equal(5, undo.Messages.Count);
        Assert.Empty(store.Conversations());
        Assert.Empty(Ids(store));
        // 正文走了，索引跟着走（外部内容表的 delete 触发器），否则搜得到却点不开
        Assert.Empty(store.Search("条正文"));

        store.RestoreConversation(undo);

        Assert.Equal(5, store.Search("条正文").Count);
        var conversation = Assert.Single(store.Conversations());
        // 聚合是从消息现算的，票上带的那份一概不信
        Assert.Equal(5L, conversation.LastSeq);
        Assert.Equal(1_765_500_000_005L, conversation.LastMsgAt);
        Assert.Equal(0, conversation.UnreadCount);
    }

    [Fact]
    public void ClearingAConversationThatIsNotHereHandsBackNoTicket()
    {
        using var store = new DeviceStore(DbPath);
        store.ApplyMessage(Msg(1, "m1", sender: "deploy"));

        Assert.Null(store.ClearConversationLocal("83271504", "grafana"));
        // 别的发送者那条不受牵连
        Assert.Single(store.Conversations());
    }

    private static string[] Ids(DeviceStore store) =>
        store.MessagesPage("83271504", "grafana").Select(m => m.MsgId).ToArray();

    /// <summary>会话行拼成一个串来比：分叉的时候要一眼看出是哪一列，而不是只看懂"两个集合不相等"。</summary>
    private static string[] Shape(DeviceStore store) => store.Conversations()
        .Select(c => $"{c.ChannelNo}|{c.SenderId}|{c.Name}|{c.LastSeq}|{c.LastMsgAt}|{c.LastSummary}|{c.UnreadCount}|{c.LastLevel}")
        .ToArray();

    private static byte[] ReadWholeFile(string path)
    {
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        using var buffer = new MemoryStream();
        stream.CopyTo(buffer);
        return buffer.ToArray();
    }

    private static IEnumerable<string> ColumnsOf(DeviceStore store, string table)
    {
        // 另开一条只读连接：表名来自上面写死的数组，不是输入
        using var conn = new SqliteConnection(
            new SqliteConnectionStringBuilder { DataSource = store.DatabasePath }.ToString());
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = $"SELECT name FROM pragma_table_info('{table}');";
        using var reader = cmd.ExecuteReader();
        var names = new List<string>();
        while (reader.Read()) names.Add(reader.GetString(0));
        return names;
    }

    private static TombstoneFrame Tombstone(string json) =>
        (TombstoneFrame)FrameParser.Parse(json)!;

    /// <summary>
    /// 从第二条连接写 muted 这一列。这不是"测试绕过 API"的偷懒：PC 侧还没有改会话旗标的入口，
    /// 而 §7.3 那道读它的门已经在线上，所以造数据这条路要显式留在这里，等会话菜单落地就该换成公开方法。
    /// </summary>
    private static void WriteMuted(string dbPath, string channelNo, string senderId, bool muted)
    {
        using var conn = new SqliteConnection($"Data Source={dbPath}");
        conn.Open();
        using var busy = conn.CreateCommand();
        busy.CommandText = "PRAGMA busy_timeout = 5000;";
        busy.ExecuteNonQuery();

        using var cmd = conn.CreateCommand();
        cmd.CommandText =
            "UPDATE conversations SET muted = :muted WHERE channel_no = :channel AND sender_id = :sender;";
        cmd.Parameters.AddWithValue(":muted", muted ? 1L : 0L);
        cmd.Parameters.AddWithValue(":channel", channelNo);
        cmd.Parameters.AddWithValue(":sender", senderId);
        Assert.Equal(1, cmd.ExecuteNonQuery());
    }

    /// <summary>
    /// 帧按 §5.3 的字段名建，再走 FrameParser——入库路径消费的正是解析后的记录，
    /// 直接 new 一个 record 会把字段名绑定的那层保证让出去。
    /// </summary>
    private static MessageFrame Msg(long seq, string id, string channel = "83271504",
        string sender = "grafana", string? body = null, string? title = null, string? summary = null,
        string name = "Grafana", long? createdAt = null, string? mediaStatus = null)
    {
        var fields = new Dictionary<string, object>
        {
            ["t"] = "message",
            ["seq"] = seq,
            ["id"] = id,
            ["channel_no"] = channel,
            ["sender_id"] = sender,
            ["sender_name"] = name,
            ["type"] = "text",
            ["level"] = "info",
            ["content"] = body ?? $"正文 seq={seq}",
            ["created_at"] = createdAt ?? (1_765_500_000_000L + seq),
        };
        if (title != null) fields["title"] = title;
        if (summary != null) fields["summary"] = summary;
        // media_status 只在 §5.2 历史项里出现，实时帧靠 media_ready 回填
        if (mediaStatus != null) fields["media_status"] = mediaStatus;
        return (MessageFrame)FrameParser.Parse(JsonSerializer.Serialize(fields))!;
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_dir)) Directory.Delete(_dir, true);
        }
        catch (IOException)
        {
            // 临时目录删不掉不影响结论
        }
    }
}
