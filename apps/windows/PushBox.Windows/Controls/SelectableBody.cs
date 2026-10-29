using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using PushBox.Windows.Data;

namespace PushBox.Windows.Controls;

/// <summary>
/// 气泡正文的可拖选外壳。换成 <see cref="FlowDocument"/> 是为了"选中一段字"这件事本身——
/// <see cref="TextBlock"/> 没有选中 API，而 <see cref="RichTextBox"/> 每次测量都会把文档的
/// PagePadding 改回 (5,0,5,0)（实测），正文因此少 10px、换行比原来早一行，做不到"贴着字收窄"。
/// <see cref="FlowDocumentScrollViewer"/> 没有这层干预：PageWidth 钉成多少，测量和换行就按多少。
///
/// 收窄靠一棵<b>只量不画</b>的标尺：<see cref="MarkdownView.BuildAll"/> 出来的那棵 TextBlock 树
/// 就是正文今天的样子，用它拿到自然宽 N，再把文档的 PageWidth 钉成"放得下就用 N，放不下就用可用宽"。
/// 于是"树 A 量出的宽"与"树 B 画出的宽"是同一个数，短消息照样贴着字收，长消息照样用满气泡。
/// 两棵树用同一份 <see cref="MarkdownView.BuildDocument"/> 规则，高度差由 SelectableBodyTest 逐档钉住。
/// </summary>
public sealed class SelectableBody : Decorator
{
    /// <summary>
    /// 贴字收时多给的一点。标尺的宽和文档排版用的行宽**不是同一次计算**：TextBlock 报出来的是
    /// 取整过的 DesiredSize，文档用的是逐字排出来的行宽，两边能差零点几个像素——正好够把最宽
    /// 那一行的最后一个词挤到下一行（2026-10-29 的天气卡片："📍 临颍 · 当前 15°C 多云" 整行
    /// 一个像素都不多，文档于是把"云"折了下去）。一个像素在屏幕上看不出来，但它是这一整类
    /// "下一行只剩一两个字"的来源。
    /// </summary>
    private const double HugSlack = 1;

    private readonly FrameworkElement _ruler;
    private readonly FlowDocument _document;
    private readonly FlowDocumentScrollViewer _viewer;

    public SelectableBody(IReadOnlyList<MarkdownBlock> blocks, MarkdownStyle style)
    {
        _ruler = MarkdownView.BuildAll(blocks, style);
        _document = MarkdownView.BuildDocument(blocks, style);
        _viewer = new FlowDocumentScrollViewer
        {
            Document = _document,
            IsToolBarVisible = false,
            // 正文不再套一层滚动：气泡自己已经在一个滚动的时间线里，两个都在滚会让滚轮卡住
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            VerticalScrollBarVisibility = ScrollBarVisibility.Disabled,
            IsSelectionEnabled = true,
            IsInactiveSelectionHighlightEnabled = true,
            // 选中底色走独立的拖选 token，且不透明：默认那层半透明叠在气泡底上几乎看不出选到哪儿了
            SelectionBrush = style.TextSelection,
            SelectionOpacity = 1,
            IsTabStop = false,
            Focusable = true,
            Padding = new Thickness(0),
            BorderThickness = new Thickness(0),
            Background = Brushes.Transparent
        };
        Child = _viewer;
        HorizontalAlignment = HorizontalAlignment.Left;

        // 键盘复制与右键菜单走同一条路：把选中的那段交给 style.Copy（窗口那边落到剪贴板），
        // 渲染层自己仍然不碰系统剪贴板
        CommandBindings.Add(new CommandBinding(ApplicationCommands.Copy, (_, args) =>
        {
            if (style.Copy is not { } copy || SelectedText is not { Length: > 0 } picked) return;
            args.Handled = true;
            copy(picked);
        }));
    }

    /// <summary>文档本身。用例据此断"块级映射对不对"，窗口不需要它。</summary>
    public FlowDocument Document => _document;

    /// <summary>文档里的选中范围。拖选落在这上面，用例也用它把"选了一段"如实摆出来。</summary>
    public TextSelection Selection => _viewer.Selection;

    /// <summary>这一棵树实际用到的选中底色。用例据此断"配色从 token 走到了控件"，而不是只停在字典里。</summary>
    public Brush SelectionBrush => _viewer.SelectionBrush;

    /// <summary>选中底的不透明度。默认那支是半透明的，叠在气泡底上会褪成看不出边界，所以这里必须是 1。</summary>
    public double SelectionOpacity => _viewer.SelectionOpacity;

    /// <summary>当前拖选到的文字（空选 = 空串）。尾部的段落换行去掉——用户复制走的不是那两个字符。</summary>
    public string SelectedText
    {
        get
        {
            var text = _viewer.Selection?.Text;
            return string.IsNullOrEmpty(text) ? string.Empty : text.TrimEnd('\r', '\n');
        }
    }

    protected override Size MeasureOverride(Size availableSize)
    {
        // 标尺是游离在可视树外的，继承不到窗口上的 TextFormattingMode / UseLayoutRounding：
        // 文档那边是 Display（每个字形取整到整像素），标尺这边却还是默认的 Ideal，同一行能差出一个字。
        // 这个差不是"差不多"，而是文档按窄一点的那棵树钉宽，最宽那一行于是多折出一个字——
        // 也就是"下一行只剩一两个字"。每次测量前把窗口上那两个排版开关原样转给标尺。
        var mode = TextOptions.GetTextFormattingMode(this);
        if (TextOptions.GetTextFormattingMode(_ruler) != mode) TextOptions.SetTextFormattingMode(_ruler, mode);
        _ruler.UseLayoutRounding = UseLayoutRounding;

        // 标尺量的是"完全不折行时这棵树有多宽"：给无限宽。以前按可用宽量，拿到的是**已经折过行**
        // 的那一行；文档再按这个数折行就是同一段文字量两遍，第二遍只要差一点就多折出一行。
        _ruler.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
        var natural = _ruler.DesiredSize.Width;
        if (!double.IsFinite(natural) || natural < 0) natural = 0;

        double width;
        if (double.IsInfinity(availableSize.Width)) width = natural + HugSlack;
        // 一行放不下：钉满可用宽，折行全交给文档。钉成标尺折出来的那个数只会更窄，
        // 文档按更窄的宽再折一次，尾巴就更短
        else if (natural > availableSize.Width) width = availableSize.Width;
        // 放得下：贴着字收，多给的那半个像素见 HugSlack
        else width = Math.Min(natural + HugSlack, availableSize.Width);
        width = Math.Max(width, 1);

        // 只在真的变了才写：PageWidth 一写就让文档失效，见面就写会在测量里兜圈
        if (!width.Equals(_document.PageWidth)) _document.PageWidth = width;

        _viewer.Measure(new Size(width, double.PositiveInfinity));
        return new Size(width, _viewer.DesiredSize.Height);
    }
}
