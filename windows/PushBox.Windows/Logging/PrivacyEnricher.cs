using System;
using System.Collections.Generic;
using System.Linq;
using Serilog.Core;
using Serilog.Events;

namespace PushBox.Windows.Logging;

/// <summary>
/// 富化器这一层管的是"值已经成了属性之后"的两扇门：<see cref="PrivacyGate"/> 管文本怎么写，
/// 这里管<b>什么东西压根不该有被写的机会</b>。
///
/// 两条规则各有明确的事故形状：
/// - <b>非基元对象只留类型名</b>：<c>Log.Information("帧 {Frame}", message)</c> 是最自然的写法，
///   而 record 的 <c>ToString()</c> 会把全部字段吐成 <c>MessageFrame { Seq=…, Content=磁盘告警… }</c> ——
///   正文就这样一个字不差地进了日志。类型名（<c>MessageFrame</c>）照样能说明"这里过了一帧"，字段一个都不给。
///   这条比 <see cref="PrivacyGate"/> 的键值网更靠前，因为它不依赖"值长得像 key=value"。
/// - <b>黑名单属性名整值替换</b>：<c>{Content}</c>、<c>{Summary}</c>、<c>{RecoveryCode}</c> 这些名字
///   自己就说明了它是内容，不需要看内容长什么样。
///
/// 它挂在 <see cref="LoggerConfiguration"/> 的 <c>Enrich</c> 上，所以对所有 sink 生效；
/// 落盘前 <see cref="PrivacyFormatter"/> 还会把整行再过一遍，两层用的是同一套 <see cref="PrivacyGate"/> 规则。
/// </summary>
public sealed class PrivacyEnricher : ILogEventEnricher
{
    public void Enrich(LogEvent logEvent, ILogEventPropertyFactory propertyFactory)
    {
        // 先快照再改：AddOrUpdateProperty 动的是同一个字典，边枚举边写会当场炸（.NET 的枚举器只管自己那一份版本）
        foreach (var pair in logEvent.Properties.ToList())
        {
            // 名字自己就说明了它是内容：{Summary} 里那句 20 字的摘要过不了长度闸，但在这儿整值没了
            var cleaned = PrivacyGate.IsSensitiveKey(pair.Key)
                ? new ScalarValue(PrivacyGate.Redacted)
                : Clean(pair.Value);
            if (ReferenceEquals(cleaned, pair.Value)) continue;
            logEvent.AddOrUpdateProperty(new LogEventProperty(pair.Key, cleaned));
        }
    }

    /// <summary>递归洗一层属性值。返回同一个实例表示"这一支没什么要改的"，省掉一次 AddOrUpdate。</summary>
    private static LogEventPropertyValue Clean(LogEventPropertyValue value)
    {
        switch (value)
        {
            // Serilog 只在有人显式 Destructure 时才产出 StructureValue；默认的未知对象走 ScalarValue + ToString()，
            // 所以下面那一支才是正文真正会溜走的地方。两支都堵。
            case StructureValue structure:
                return new ScalarValue(PrivacyGate.ScrubLine(structure.TypeTag ?? "<object>"));

            case SequenceValue sequence:
                var items = sequence.Elements.Select(Clean).ToList();
                return items.SequenceEqual(sequence.Elements) ? value : new SequenceValue(items);

            case DictionaryValue dictionary:
                var entries = dictionary.Elements
                    .Select(e => new KeyValuePair<ScalarValue, LogEventPropertyValue>(e.Key, Clean(e.Value)))
                    .ToList();
                return new DictionaryValue(entries);

            case ScalarValue { Value: string text }:
                return new ScalarValue(PrivacyGate.ScrubValue(text));

            // 不是基元/枚举/常见值类型 ⇒ 它的 ToString() 是别人写的，我们不知道里面有什么，所以只留类型名。
            case ScalarValue scalar when !IsKeepable(scalar.Value):
                return new ScalarValue(PrivacyGate.ScrubLine(
                    scalar.Value?.GetType().Name ?? "<null>"));

            default:
                return value;
        }
    }

    /// <summary>
    /// 允许原样留下的值。<b>白名单而不是黑名单</b>：新加一种类型默认就是"只留类型名"，
    /// 加了日志字段的人不会因为没想到这一点而把内容带出去。
    /// </summary>
    private static bool IsKeepable(object? raw) => raw is null
        || raw is string
        || raw is bool or byte or sbyte or short or ushort or int or uint or long or ulong
        || raw is float or double or decimal or char
        || raw is DateTime or DateTimeOffset or TimeSpan or Guid or Enum;
}
