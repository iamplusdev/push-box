using System;
using System.Linq;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using PushBox.Windows.Logging;

namespace PushBox.Windows.Controls;

/// <summary>
/// 全仓唯一碰 <c>Emoji.Wpf</c> 的一页。WPF 的文本栈不认 COLR/CPAL、CBDT/CBLC、SVG 任何一类
/// 彩色字形表，<c>Font.UI</c> 里补不补 <c>Segoe UI Emoji</c> 都一样——字体回退早就命中它了，
/// 画出来的是真字形轮廓，只是单色（2026-10-26 离屏逐像素实测 chromatic=0）。
/// Emoji.Wpf 走的是另一条路：自己把系统 Segoe UI Emoji 的字形解成 Geometry，
/// 以 <c>EmojiInline : InlineUIContainer</c> 的形式插到 <see cref="Run"/> 旁边。
///
/// 两条实测出来的口径，改这里之前先看：
/// - <b>顺序不能反</b>：必须先 <c>Inlines.Add(run)</c> 再替换。反着来不抛异常，只是什么都不出
///   （替换要在 Run 有父集合时才能把兄弟节点插进去）。
/// - <b>只有这一个入口</b>：<c>SubstituteGlyphs(Run)</c> 认的是普通 <see cref="TextBlock"/>，
///   所以 <see cref="MarkdownView"/> 的返回类型没变，渲染层的断言照旧能拿 TextBlock。
///   包里那个 <c>Emoji.Wpf.TextBlock</c> 的 <c>Text</c> 是 <c>new</c> 出来的普通 CLR 属性、
///   不是依赖属性，XAML 里 <c>Text="{Binding …}"</c> 根本挂不上去（Binding 不能指向非 DP），
///   所以列表那半用的是下面自己写的 <see cref="EmojiTextBlock"/>。
/// </summary>
public static class EmojiText
{
    private static int _prewarmed;

    /// <summary>
    /// 把一个 TextBlock 里所有 Run 的 emoji 换成彩色字形。没有 emoji 的文本原样不动
    /// （不会多出 Inline），所以调用方不必自己先判。
    /// </summary>
    public static void Substitute(TextBlock block)
    {
        // 先 ToList：替换会往同一个集合里插 EmojiInline，边遍历边改会炸
        foreach (var run in block.Inlines.OfType<Run>().ToList()) Substitute(run);
    }

    /// <summary>
    /// 单个 Run 的替换。<see cref="MarkdownView"/> 要按内联样式挑着换（行内码与围栏代码块保持单色），
    /// 所以这一层也开着。替换失败只降级成单色，不让第三方解析器把渲染线程带崩。
    /// </summary>
    public static void Substitute(Run run)
    {
        try
        {
            Emoji.Wpf.FlowDocumentExtensions.SubstituteGlyphs(run);
        }
        catch (Exception e)
        {
            AppLog.EmojiSubstituteFailed(e.GetType().Name);
        }
    }

    /// <summary>
    /// 首次替换要付一次性的字形表初始化（实测约 500 ms，落在哪个线程哪个线程就卡）。
    /// 这里在后台 STA 线程上先把它付掉：整轮 1.1–1.6 s 全在 UI 线程之外，
    /// 之后 UI 线程首次替换降到 7–12 ms。幂等，重复调用直接返回。
    /// </summary>
    public static void Prewarm()
    {
        if (Interlocked.Exchange(ref _prewarmed, 1) == 1) return;
        var thread = new Thread(() =>
        {
            try
            {
                Emoji.Wpf.EmojiData.Load();
                // 光 Load() 还不够：真正贵的是第一次排版字形，所以在这儿做一次丢掉就好的替换
                var probe = new TextBlock();
                var run = new Run("\U0001F600");
                probe.Inlines.Add(run);
                Emoji.Wpf.FlowDocumentExtensions.SubstituteGlyphs(run);
            }
            catch (Exception e)
            {
                AppLog.EmojiPrewarmFailed(e.GetType().Name);
            }
        });
        // FrameworkElement 只能在 STA 线程上构造，否则 InvalidOperationException
        thread.SetApartmentState(ApartmentState.STA);
        thread.IsBackground = true;
        thread.Start();
    }
}

/// <summary>
/// 会话列表摘要那种"文本来自 Binding"的地方用的薄控件：
/// <see cref="Source"/> 是真依赖属性，变了就重建一个 Run 再替换。
/// 只有一层内联样式（整段用同一个 Style），所以不需要 Markdown 那套。
/// </summary>
public sealed class EmojiTextBlock : TextBlock
{
    public static readonly DependencyProperty SourceProperty =
        DependencyProperty.Register(nameof(Source), typeof(string), typeof(EmojiTextBlock),
            new PropertyMetadata(null, (d, e) => ((EmojiTextBlock)d).Render(e.NewValue as string)));

    public string? Source
    {
        get => (string?)GetValue(SourceProperty);
        set => SetValue(SourceProperty, value);
    }

    private void Render(string? value)
    {
        Inlines.Clear();
        if (string.IsNullOrEmpty(value)) return;
        var run = new Run(value);
        Inlines.Add(run);
        EmojiText.Substitute(run);
    }
}
