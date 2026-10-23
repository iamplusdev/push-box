using System;
using System.IO;
using System.Linq;
using PushBox.Windows.Logging;

namespace PushBox.Crypto.Tests;

/// <summary>
/// §14 那条底线（"任何日志不得出现消息正文"）的实现层用例：<see cref="PrivacyGate"/> 的五道网各自钉一条，
/// 而不是"看一眼示例日志觉得挺干净"。整条链（含真文件）在 <c>AppLogTest</c>。
///
/// 用例里的碎片都是唯一的、带大写标记的串（ALPHA/BRAVO/…），这样"洗掉了"这件事不依赖上下文就能断言。
/// </summary>
public sealed class PrivacyGateTest
{
    private const string Body = "正文碎片ALPHA 磁盘告警 /mnt 已用 91%";
    private const string Summary = "摘要碎片BRAVO";
    private const string RecoveryCode = "Ab3dEf6hIj9kLm1nOp4qRs";

    // ---- 第 1 道：属性名自己就说明它是内容 ----------------------------------------------

    [Fact]
    public void ContentSizedKeysAreRecognisedWhateverCaseTheyComeIn()
    {
        Assert.True(PrivacyGate.IsSensitiveKey("content"));
        Assert.True(PrivacyGate.IsSensitiveKey("Summary"));
        Assert.True(PrivacyGate.IsSensitiveKey("RECOVERY_CODE"));
        Assert.True(PrivacyGate.IsSensitiveKey("q"));
    }

    [Fact]
    public void TheErrorCodeAndTheCursorAreNotContentAndStayLoggable()
    {
        // 这条是反向的闸：把 code/seq 也列进黑名单的话，日志就变成"出事也看不见"，
        // 而 rate_limited / not_found / 游标数字恰恰是排障唯一能看的东西。
        Assert.False(PrivacyGate.IsSensitiveKey("code"));
        Assert.False(PrivacyGate.IsSensitiveKey("seq"));
        Assert.False(PrivacyGate.IsSensitiveKey("scope"));
        Assert.False(PrivacyGate.IsSensitiveKey("outcome"));
    }

    // ---- 第 2 道：超长值整值替换，不是截断 ----------------------------------------------

    [Fact]
    public void AValueLongerThanAShortFieldIsGoneEntirelyNotChopped()
    {
        var longBody = string.Concat(Enumerable.Repeat("正文", PrivacyGate.MaxValueLength));
        var scrubbed = PrivacyGate.ScrubValue(longBody);

        // 留前 120 字仍然是在留正文，验收写的是"grep 不到任何片段"，所以这里是换掉而不是切一刀
        Assert.Equal(PrivacyGate.RedactedTooLong, scrubbed);
        Assert.DoesNotContain("正文", scrubbed);
    }

    [Fact]
    public void ASingleFieldOfOrdinarySizePassesThroughUnchanged()
    {
        Assert.Equal("rate_limited", PrivacyGate.ScrubValue("rate_limited"));
        Assert.Equal("01JZK9Q8R7S6T5U4V3W2X1Y0ZA", PrivacyGate.ScrubValue("01JZK9Q8R7S6T5U4V3W2X1Y0ZA"));
    }

    // ---- 第 3 道：整行/整段里的 key=value ----------------------------------------------

    [Fact]
    public void ABareValueWithSpacesIsEatenToTheEndOfTheLine()
    {
        var line = PrivacyGate.ScrubLine($"留痕 content={Body} seq=18481");

        Assert.DoesNotContain("ALPHA", line);
        // 裸值一路吃到行尾，所以同一行后面的字段会一起没掉——这一行本来就是转储，宁可少留信息
        Assert.DoesNotContain("18481", line);
    }

    [Fact]
    public void AQuotedValueStopsAtItsQuoteSoTheRestOfTheLineSurvives()
    {
        var line = PrivacyGate.ScrubLine($"summary=\"{Summary}\" code=rate_limited");

        Assert.DoesNotContain("BRAVO", line);
        Assert.Contains("rate_limited", line);
    }

    [Fact]
    public void AJsonDumpIsScrubbedEvenWithTheQuoteBetweenKeyAndColon()
    {
        // 服务端回包/帧原文长这样：键和冒号之间隔着一个收尾引号，早先的写法在这儿压根匹配不上
        var line = PrivacyGate.ScrubLine($"{{\"content\":\"{Body}\",\"seq\":18481}}");

        Assert.DoesNotContain("ALPHA", line);
        Assert.Contains("18481", line);
    }

    [Fact]
    public void AQueryInAUrlIsScrubbedAndTakesTheRestOfThatLineWithIt()
    {
        var line = PrivacyGate.ScrubLine("GET /api/v1/search?q=%E7%A3%81%E7%98%A9&channel_no=83%2027");

        // 路径留下来还能看出是哪个接口出的事；query 之后的字段一起没掉是明说的代价——
        // query 串里 & 本来是分隔符，但"裸值一路吃到行尾"比"猜哪一段才是值"更保险
        Assert.Contains("/api/v1/search", line);
        Assert.DoesNotContain("%E7%A3%81", line);
        Assert.DoesNotContain("83%2027", line);
    }

    [Fact]
    public void AKeyInsideAWordOrABiggerWordIsNotMatched()
    {
        // q 是搜索词，但 seq / quota 里都含着 q 或 qu——边界没写对就会把正常的 seq 洗成洞
        Assert.Equal("seq=18481 quota=60 text 类型", PrivacyGate.ScrubLine("seq=18481 quota=60 text 类型"));
    }

    // ---- 第 4 道：凭据形状 --------------------------------------------------------------

    [Fact]
    public void AKeyOrSignatureShapedRunIsReplacedWhereverItSits()
    {
        var b64 = new string('A', 43);
        var wrapped = Convert.ToBase64String(RandomNumberBytes(61));

        Assert.DoesNotContain(b64, PrivacyGate.ScrubLine($"握手 eph_pub={b64} 完成"));
        Assert.DoesNotContain(wrapped, PrivacyGate.ScrubLine($"对象钥 {wrapped}"));
    }

    [Fact]
    public void AThirtyTwoHexSecretIsShorterThanTheBase64NetButStillCaught()
    {
        // §15 的示例 API Secret 就是 32 个十六进制字符，够不到 CredentialLength，所以十六进制单列一条下限
        var secret = "4f9c1e8a2b7d4c6e9f01a3b5c7d9e1f3";
        Assert.DoesNotContain(secret, PrivacyGate.ScrubLine($"频道 secret {secret} 已重置"));
    }

    [Fact]
    public void ARecoveryCodeIsBelowEveryShapeNetSoTheRemainingWallsArePinned()
    {
        // 恢复码只有 22 位（EnrollmentFlow.RecoveryCodeLength），形状网抓不到它——再往短抓就会连 msg_id 这类
        // 正常标识一起洗掉，所以这里刻意留一道空。空之外还有两面墙：键名网（下面第一条），
        // 以及 AppLog 压根没有能接住它的形参（AppLogTest 那条反射用例钉的就是这件事）。
        Assert.DoesNotContain(RecoveryCode,
            PrivacyGate.ScrubLine($"join body={{\"recovery_code\":\"{RecoveryCode}\"}}"));
        Assert.Contains(RecoveryCode, PrivacyGate.ScrubLine($"用恢复码 {RecoveryCode} 加入"));
    }

    // ---- 第 5 道：异常只留类型名与堆栈 ----------------------------------------------------

    [Fact]
    public void AnExceptionContributesItsTypeAndItsThrowingLineButNeverItsMessage()
    {
        Exception thrown;
        try
        {
            throw new InvalidOperationException($"处理失败：{Body}",
                new IOException($"读流出错 {Summary}"));
        }
        catch (Exception e)
        {
            thrown = e;
        }

        var lines = PrivacyGate.ExceptionShapes(thrown).ToArray();
        var all = string.Join(" / ", lines);

        Assert.Contains("InvalidOperationException", all);
        Assert.Contains("IOException", all);
        Assert.Contains("at ", all);
        // 内层链也在，但整段里没有一句 Message——这是 W13 给 LastFailure 立的口径推广成机制
        Assert.DoesNotContain("ALPHA", all);
        Assert.DoesNotContain("处理失败", all);
        Assert.DoesNotContain("读流出错", all);
    }

    [Fact]
    public void AnExceptionWithNoStackStillLeavesItsType()
    {
        // 构造出来但没抛过的异常 StackTrace 是 null：这一句不留痕的话，测试替身里的异常就成了空行
        Assert.Equal("System.ArgumentException", Assert.Single(PrivacyGate.ExceptionShapes(new ArgumentException("x"))));
    }

    [Fact]
    public void NewlinesBecomeOneMarkerSoOneEventIsOneLine()
    {
        Assert.Equal("第一段⏎第二段", PrivacyGate.OneLine("第一段\r\n第二段"));
        Assert.Equal("单行", PrivacyGate.OneLine("单行"));
    }

    private static byte[] RandomNumberBytes(int count)
    {
        var bytes = new byte[count];
        for (var i = 0; i < count; i++) bytes[i] = (byte)(i * 31 + 7);
        return bytes;
    }
}
