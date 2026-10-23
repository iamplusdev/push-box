using System;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using Serilog.Events;
using Serilog.Formatting;
using Serilog.Parsing;

namespace PushBox.Windows.Logging;

/// <summary>
/// 落盘那一行的最后一道网，也是"这一行长什么样"的唯一答案。
///
/// <c>2026-10-15 08:31:02.417 INF 补拉被服务端拒 rate_limited (429)</c>
/// <c>2026-10-15 08:31:09.004 ERR 同步循环崩了 System.InvalidOperationException</c>
///
/// 两层怎么分工：<see cref="PrivacyEnricher"/> 洗<b>属性值</b>（它拿到的是值本身），这一页洗<b>模板字面量</b>
/// ——模板是写代码的人手打的字符串，把正文拼进去（<c>Log.Information($"处理 {body} 失败")</c>）走的就是这条路。
/// 分开的理由写在 <see cref="MessageOf"/>：同一句转储被两道网各扫一遍，第二遍会把本来还能看的字段一起吃掉。
/// 两层的规则都出自 <see cref="PrivacyGate"/>，所以不存在"这层放过、那层放过"的空隙。
///
/// 异常那一段只写类型名与堆栈（<see cref="PrivacyGate.ExceptionShapes"/>），<b>不写 Message</b>：
/// 这是 W13 给 <c>ToastNotifier.LastFailure</c> 立的口径（§8.2 第 46 条），到这里推广成机制。
/// </summary>
public sealed class PrivacyFormatter : ITextFormatter
{
    public void Format(LogEvent logEvent, TextWriter output)
    {
        output.Write(logEvent.Timestamp.ToString("yyyy-MM-dd HH:mm:ss.fff", CultureInfo.InvariantCulture));
        output.Write(' ');
        output.Write(LevelOf(logEvent.Level));
        output.Write(' ');

        var line = MessageOf(logEvent);
        var extras = ExtraProperties(logEvent);
        if (extras.Length > 0) line = line.Length == 0 ? extras : line + " | " + extras;
        output.Write(PrivacyGate.OneLine(line));
        output.Write(Environment.NewLine);

        if (logEvent.Exception == null) return;
        foreach (var detail in PrivacyGate.ExceptionShapes(logEvent.Exception))
        {
            output.Write("    ");
            output.Write(PrivacyGate.OneLine(detail));
            output.Write(Environment.NewLine);
        }
    }

    /// <summary>
    /// 模板里没写到的属性也落盘（写了却看不见比多印一段更难查）。值这里<b>不再洗第二遍</b>：
    /// 装配 <see cref="AppLog.CreateLogger"/> 时富化器一定在链条上，它已经按名字和形状洗过一次了。
    /// </summary>
    private static string ExtraProperties(LogEvent logEvent)
    {
        var used = logEvent.MessageTemplate.Tokens.OfType<PropertyToken>()
            .Select(t => t.PropertyName)
            .ToHashSet(StringComparer.Ordinal);
        return string.Join(" ", logEvent.Properties
            .Where(p => !used.Contains(p.Key))
            .Select(p => p.Key + "=" + Plain(p.Value)));
    }

    /// <summary>
    /// 自己渲染模板，不用 <c>logEvent.RenderMessage()</c>：Serilog 给字符串值一律加引号，
    /// 于是 <c>补拉被服务端拒 rate_limited (429)</c> 会写成 <c>"rate_limited"</c> —— 排障时那对引号是噪音，
    /// 而 grep 的时候它是断言里最容易被漏掉的一笔。代价是不支持 <c>{X:格式}</c> 与对齐符，
    /// 本项目的模板没有用过它们（要用的时候在 <see cref="AppLog"/> 那一层先格式化好再传）。
    ///
    /// <b>逐 token 走还有第二个理由，而且是更实质的那个</b>：字面量部分要过 <see cref="PrivacyGate.ScrubLine"/>
    /// （模板是写代码的人手打的字符串，把正文 <c>$"处理 {Body} 失败"</c> 拼进模板就走的是这条路），
    /// 属性值部分<b>原样</b>留下（富化器已经洗过一次）。整行再洗一遍看着更保险，实际会把
    /// <c>{"summary":"&lt;已脱敏&gt;","seq":18481}</c> 这种"已经洗完的转储"当成一个新值再吃一遍，
    /// 把同一行里本来还能看的 seq 一起带走——两道网扫同一段文本是不划算的。
    /// </summary>
    private static string MessageOf(LogEvent logEvent)
    {
        var line = new StringBuilder();
        foreach (var token in logEvent.MessageTemplate.Tokens)
        {
            switch (token)
            {
                case TextToken text:
                    // 字面量走 ScrubValue：长度闸 + 键值/形状网都在。一段 120 字以上的"模板文本"不可能是源码里的
                    // 固定措辞，它只可能是运行时拼出来的（Log.Information($"处理 {body} 失败")），而那正是正文唯一的进门路
                    line.Append(PrivacyGate.ScrubValue(text.Text));
                    break;
                case PropertyToken property when logEvent.Properties.TryGetValue(property.PropertyName, out var value):
                    line.Append(Plain(value));
                    break;
                case PropertyToken property:
                    line.Append('{').Append(property.PropertyName).Append('}');
                    break;
            }
        }

        return line.ToString();
    }

    /// <summary>属性渲染：标量取原值（字符串不带引号），复合值交给 Serilog 自己那一份（已经被富化器洗过）。</summary>
    private static string Plain(LogEventPropertyValue value) => value switch
    {
        ScalarValue { Value: null } => "null",
        ScalarValue { Value: string text } => text,
        ScalarValue scalar => scalar.Value?.ToString() ?? "null",
        _ => value.ToString()
    };

    private static string LevelOf(LogEventLevel level) => level switch
    {
        LogEventLevel.Verbose => "TRC",
        LogEventLevel.Debug => "DBG",
        LogEventLevel.Information => "INF",
        LogEventLevel.Warning => "WRN",
        LogEventLevel.Error => "ERR",
        _ => "FTL"
    };
}
