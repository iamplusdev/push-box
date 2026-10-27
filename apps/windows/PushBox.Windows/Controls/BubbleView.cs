using System;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using PushBox.Windows.Data;
using PushBox.Windows.Media;
using PushBox.Windows.Views;
// WPF 的 FrameworkElement 体系里也有一个 MediaState（MediaPlayer 的状态），两边同名不同物
using MediaState = PushBox.Windows.Media.MediaState;

namespace PushBox.Windows.Controls;

/// <summary>
/// 气泡右键菜单要的四件事，由窗口实现：复制要碰系统剪贴板、取原图要签名、删除要先问一句再上报，
/// 渲染层这三样都不该自己干。菜单的形状（给哪几项）留在这里，动作从哪里来由调用方决定——
/// 所以用例传四个记参数的委托就能断"点了复制走的到底是哪一段文字"。
/// </summary>
public sealed record MessageCommands(Action<string> CopyText, Action<string> CopyImage,
    Action<string> CopyLink, Action<string> Delete);

/// <summary>
/// 一条消息在 PC 上长什么样：类型标签 → 左侧竖条 + 气泡（图在上、文在下）→ 打开链接 → 时间。
/// 每一处的取舍都对齐 Android 的 <c>MessageBubble</c>（MainActivity.kt:1004-1043），
/// 因为 §12 P3 验收③ 判的是"同一条消息两端渲染结果一致"。
/// </summary>
public static class BubbleView
{
    public const double AccentBarWidth = 3;

    /// <summary>§9.2 的气泡内边距是"12×10"——横向 12、纵向 10，不是一个数。</summary>
    public const double BubblePadX = 12;

    public const double BubblePadY = 10;
    public const double BubbleRadius = 8;

    /// <summary>Segoe MDL2 Assets 的 Link 字形（E71B），"打开链接/展开全文"那一行用。</summary>
    public const string LinkGlyph = "\uE71B";

    /// <summary>
    /// <paramref name="media"/> 由窗口那边给（只有窗口有 <see cref="MediaLoader"/> 和设备身份），
    /// 渲染层因此能在没有服务端、没有身份的 STA 用例里被完整断言。
    /// 气泡最大宽 72%（§9.2）是**栏宽**的函数，所以限制放在 XAML 的条目容器上，不在这里写死像素。
    /// </summary>
    /// <param name="openLink">收的是链接本身（不是消息 id）：要不要这一行由 <c>link_url</c> 说了算，
    /// 窗口那边就不必为了一次点击回库裡查这条消息指向哪儿。</param>
    /// <param name="expand">§5.2 的"展开全文"：只有 <see cref="MessageItem.ExpandLabel"/> 非空才出现，
    /// 点的是这一条的 msg_id。取回成功后库里那条不再有截断标记，这一行跟着消失。</param>
    public static FrameworkElement Build(MessageItem item, MarkdownStyle style,
        UIElement? media = null, Action<string>? openLink = null, Action<string>? expand = null)
    {
        // §9.2 对齐 Android MessageBubble：头像 + 气泡横向排列，整体靠左，垂直居中对齐
        var outerRow = new Grid { 
            HorizontalAlignment = HorizontalAlignment.Left,
            VerticalAlignment = VerticalAlignment.Top  // 整个行从顶部开始布局
        };
        outerRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });   // 头像列
        outerRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });   // 间距列
        // 气泡列必须是星号列：Auto 列在 WPF 里先按"内容无限宽"量一次子元素，
        // TextBlock 因此拿到无限宽、一行都不折，排出来是一条一万多像素的长条，
        // 超出 ItemsControl 的那截被 ScrollViewer 直接裁掉——症状就是"消息内容显示不全"
        outerRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        // 头像：40px 圆形（与 Android 的 Avatar sizeDp=40 一致），显示首字占位或加载后的图片
        var avatar = new Controls.AvatarBox
        {
            Width = 40,
            Height = 40,
            Side = 40,
            DisplayName = item.SenderName ?? item.SenderId ?? string.Empty,
            Token = item.SenderAvatarToken,
            VerticalAlignment = VerticalAlignment.Top  // 头像顶部对齐
        };
        Grid.SetColumn(avatar, 0);
        outerRow.Children.Add(avatar);

        // 间距 8px（与 Android 的 Spacer(8.dp) 等价）
        var spacer = new Border { Width = 8 };
        Grid.SetColumn(spacer, 1);
        outerRow.Children.Add(spacer);

        // 原有的气泡内容放进第三列
        var bubbleContent = BuildBubbleContent(item, style, media, openLink, expand);
        Grid.SetColumn(bubbleContent, 2);
        outerRow.Children.Add(bubbleContent);

        return outerRow;
    }

    /// <summary>
    /// 构建气泡本体（不含头像）：类型标签 → 左侧竖条 + 气泡（图在上、文在下）→ 打开链接 → 时间。
    /// 每一处的取舍都对齐 Android 的 <c>MessageBubble</c>（MainActivity.kt:1004-1043），
    /// 因为 §12 P3 验收③ 判的是"同一条消息两端渲染结果一致"。
    /// </summary>
    private static FrameworkElement BuildBubbleContent(MessageItem item, MarkdownStyle style,
        UIElement? media = null, Action<string>? openLink = null, Action<string>? expand = null)
    {
        // 使用 Grid 包裹所有内容，确保气泡主体可以垂直居中，而时间戳在下方独立显示
        var wrapper = new Grid();
        wrapper.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 气泡主体行
        wrapper.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 时间戳行

        var column = new StackPanel { HorizontalAlignment = HorizontalAlignment.Left };

        if (item.MsgTypeLabel is { Length: > 0 } label)
        {
            // 类型标签走 token：原来写死的 #0ABF5E 是已退役的主色，30 透明度的底落在气泡上几乎看不见
            var badge = new Border
            {
                Background = style.BadgeBackground,
                CornerRadius = new CornerRadius(4),
                Padding = new Thickness(6, 2, 6, 2),
                Margin = new Thickness(4, 0, 0, 4),
                Child = new TextBlock
                {
                    Text = label,
                    FontSize = MarkdownStyle.LabelSize,
                    Foreground = style.BadgeText,
                    FontWeight = FontWeights.Medium
                }
            };
            column.Children.Add(badge);
        }

        var row = new Grid();
        // 竖条那一列走 Auto 而不是写死 3px：info 档整行没有条，写死就等于给每一条普通消息
        // 留一条看不见的 3px 空槽——它正是"头像离左边列表太远"的一部分
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        // 同上：气泡那一列也得是星号列，Auto 列会把正文量成一条不折行的长条
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.VerticalAlignment = VerticalAlignment.Top; // 与头像顶部对齐（单行消息时关键）

        // info 没有色条：AccentKey 为 null 时这一列不给内容，气泡左边缘跟着贴齐
        // （与 Android 的 if (accent != null) Box(...) 同形）
        var accent = AccentBrush(item.AccentKey, style);
        if (accent is not null)
        {
            row.Children.Add(new Border
            {
                Background = accent,
                Width = AccentBarWidth,
                VerticalAlignment = VerticalAlignment.Stretch
            });
        }

        // 正文不为色条让位：条画在气泡外面那一列里，气泡内边距两档都是 12。
        // 原来这里再补一个 3px 左外边距，等于把同一道条量了两次——warning/error 的字
        // 比普通消息往里缩 3px，而 Android 那边 padding 恒为 10dp、条画在底边上
        var body = new StackPanel();
        if (media is not null) body.Children.Add(media);
        // body ?: summary ?: title 三选一（在 MainModel 里已经选完），再按块解析：
        // 非 markdown 类型的正文通常没有语法，但两端走同一条解析路径才不会"手机按块排、PC 挤成一行"
        // 正文换成可拖选的 FlowDocument：TextBlock 没有选中 API，而气泡又要"贴着字收窄"，
        // 这两件事的交点见 SelectableBody 的注释（标尺量宽 + PageWidth 钉住）
        if (item.Text.Trim().Length > 0)
            body.Children.Add(new SelectableBody(Markdown.Parse(item.Text), style));

        // 气泡本体：背景 + 1px 边框 + 阴影效果（通过 BorderThickness 模拟）
        // 星号列会把剩余宽度全给这一列，所以气泡自己靠左收在内容宽度上，短消息才不会拉成一整条空白
        row.Children.Add(new Border
        {
            Background = style.BlockBackground,
            BorderBrush = style.BubbleBorder,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(BubbleRadius),
            Padding = new Thickness(BubblePadX, BubblePadY, BubblePadX, BubblePadY),
            HorizontalAlignment = HorizontalAlignment.Left,
            Child = body
        });
        Grid.SetColumn(row.Children[^1], 1);
        column.Children.Add(row);

        if (expand is not null && item.ExpandLabel is { Length: > 0 } expandLabel)
        {
            column.Children.Add(ActionLine(expandLabel, style, () => expand(item.MsgId)));
        }

        if (openLink is not null && item.LinkUrl is { Length: > 0 } target)
        {
            column.Children.Add(ActionLine("打开链接", style, () => openLink(target)));
        }

        // 气泡主体放在第一行
        Grid.SetRow(column, 0);
        wrapper.Children.Add(column);

        // 时间智能显示：只在分组第一条或跨日期时显示，放在第二行不影响气泡对齐
        if (item.ShowTimestamp)
        {
            var timestamp = new TextBlock
            {
                Text = item.StampText,
                FontSize = MarkdownStyle.LabelSize,
                Foreground = style.Muted,
                Margin = new Thickness(48, 2, 0, 0) // 左边距 = 头像40 + 间距8
            };
            Grid.SetRow(timestamp, 1);
            wrapper.Children.Add(timestamp);
        }
        
        return wrapper;
    }

    /// <summary>
    /// 气泡下面那一行可点的小动作（"打开链接" / "展开全文"）：改为带图标的小标签样式。
    /// 两行共用一件东西而不是各写一遍，是因为它们必须长得一样——Android 那边也是同一个 TextButton 样式。
    /// </summary>
    private static FrameworkElement ActionLine(string text, MarkdownStyle style, Action run)
    {
        var accent = style.Link;
        // 使用 StackPanel 横向排列图标和文字
        var panel = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Margin = new Thickness(4, 6, 0, 0),
            Cursor = Cursors.Hand
        };

        // 链接图标：Segoe MDL2 的 E71B。彩符 🔗 染不上 accent（在深色底上还会回退成灰字），
        // 换成字形后颜色跟着那一行走
        var icon = new TextBlock
        {
            Text = LinkGlyph,
            FontFamily = style.Icon,
            FontSize = MarkdownStyle.LabelSize,
            Foreground = accent,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 4, 0)
        };

        var label = new TextBlock
        {
            Text = text,
            FontSize = MarkdownStyle.LabelSize,
            Foreground = accent,
            VerticalAlignment = VerticalAlignment.Center
        };

        panel.Children.Add(icon);
        panel.Children.Add(label);
        panel.MouseLeftButtonUp += (_, _) => run();

        return panel;
    }

    /// <summary>日期分节头：居中、弱化的 12 号灰字（Android 的 DateHeader）。</summary>
    public static FrameworkElement Divider(string label, MarkdownStyle style)
    {
        // 使用 Grid 确保文本真正居中，不受容器宽度限制
        var container = new Grid
        {
            HorizontalAlignment = HorizontalAlignment.Stretch,
            Margin = new Thickness(0, 8, 0, 8)
        };
        
        var textBlock = new TextBlock
        {
            Text = label,
            FontSize = MarkdownStyle.LabelSize,
            Foreground = style.Muted,
            TextAlignment = TextAlignment.Center,
            HorizontalAlignment = HorizontalAlignment.Center
        };
        
        container.Children.Add(textBlock);
        return container;
    }

    /// <summary>菜单项的稳定标识。文案会改，`Tag` 不会——用例认它，不认那几行字。</summary>
    public const string CopyTextTag = "copy-text";

    public const string CopyImageTag = "copy-image";

    public const string CopyLinkTag = "copy-link";

    public const string DeleteTag = "delete";

    /// <summary>没拖选时复制整条正文。</summary>
    public const string CopyTextLabel = "复制正文";

    /// <summary>拖选了一段就复制那一段——菜单在打开那一刻才改这个字，不然点下去复制的东西和写着的不一样。</summary>
    public const string CopyPickedLabel = "复制选中内容";

    /// <summary>
    /// 气泡的右键菜单（§9.3 那三类动作在 PC 上的落点）。每一项只在"这条消息真有那样东西"时出现：
    /// 没有正文就没有"复制正文"，没有图就没有"复制图片"，没有链接就没有"复制链接"——
    /// 摆一排用不上的灰项，比少给一项更像坏了。
    /// 多图消息刻意<b>不</b>给"复制图片"：到底复制哪一张说不清，左键点开原图才是那条路。
    /// <paramref name="picked"/> 交的是"这一条现在被拖选的那段字"：有选中就复制选中，
    /// 否则复制整条正文。菜单项文字跟着改，用户点到的和看到的才是同一件事。
    /// </summary>
    public static ContextMenu ContextMenuFor(MessageItem item, MessageCommands commands,
        Func<string?>? picked = null)
    {
        var menu = new ContextMenu();

        if (item.Text.Trim().Length > 0)
        {
            var copy = new MenuItem { Header = CopyTextLabel, Tag = CopyTextTag };
            copy.Click += (_, _) => commands.CopyText(
                picked?.Invoke() is { Length: > 0 } selection ? selection : item.Text);
            if (picked is not null)
                menu.Opened += (_, _) =>
                    copy.Header = picked() is { Length: > 0 } ? CopyPickedLabel : CopyTextLabel;
            menu.Items.Add(copy);
        }

        if (item.MediaCount == 1)
        {
            var copy = new MenuItem { Header = "复制图片", Tag = CopyImageTag };
            copy.Click += (_, _) => commands.CopyImage(item.MsgId);
            menu.Items.Add(copy);
        }

        if (item.LinkUrl is { Length: > 0 } link)
        {
            var copy = new MenuItem { Header = "复制链接", Tag = CopyLinkTag };
            copy.Click += (_, _) => commands.CopyLink(link);
            menu.Items.Add(copy);
        }

        // 只发图没有字的消息，前面一项都没有：那就别让第一行是一条分隔线
        if (menu.Items.Count > 0) menu.Items.Add(new Separator());

        var delete = new MenuItem { Header = "删除这条消息…", Tag = DeleteTag };
        delete.Click += (_, _) => commands.Delete(item.MsgId);
        menu.Items.Add(delete);
        return menu;
    }

    /// <summary>
    /// 键名到画刷只认 <see cref="MarkdownStyle"/> 上那两支，不去摸 <c>Application.Current</c>：
    /// 渲染因此是纯函数，STA 用例能在没有 Application 的线程上断到那一根竖条的颜色。
    /// 键就是 <see cref="TimeFormat.LevelAccent"/> 交出的那两个字面量，info 不在表里 = 没有色条。
    /// </summary>
    public static Brush? AccentBrush(string? key, MarkdownStyle style) => key switch
    {
        "Level.Error" => style.LevelError,
        "Level.Warning" => style.LevelWarning,
        _ => null
    };

    /// <summary>
    /// 这一棵气泡里的可拖选正文（只发图、只发链接的消息没有）。右键菜单据此把"复制正文"
    /// 换成"复制选中内容"：菜单归气泡根节点，选中的范围只有正文自己知道，所以要找得到它。
    /// 走可视树：气泡是纯 Panel/Border 搭的，子节点加进去就是可视子节点，不必先排过版。
    /// </summary>
    public static SelectableBody? SelectableOf(DependencyObject root)
    {
        if (root is SelectableBody body) return body;
        var count = VisualTreeHelper.GetChildrenCount(root);
        for (var i = 0; i < count; i++)
        {
            if (SelectableOf(VisualTreeHelper.GetChild(root, i)) is { } found) return found;
        }
        return null;
    }
}

/// <summary>
/// 一条消息的图位：<b>竖排九宫格</b>，不横向滚动（与 Android 的 <c>MediaGrid</c> 同一张表：
/// MediaImages.kt:249-289）。横排那条路在手机上已经被推翻，PC 再横排就等于两端渲染不一致——
/// 而 §12 P3 验收③ 判的正是这个。1 张占一个 220 宽的大格按原比例铺，2~9 张是 88 见方的格子
/// 每三个一行，超过 9 张折成"另有 N 张"。
/// §4.5 的抓取是异步的，所以这里必须区分"还在等字节"和"字节不会再来"：
/// 前者转圈、后者给 <see cref="MediaAsset.Placeholder"/> 那句"图片已失效"并留一次点击重试。
/// 图在正文**上方**，与 Android 的 <c>if (mediaCount &gt; 0) MediaGrid(...)</c> 先于文本同序。
/// </summary>
public static class MediaStrip
{
    public const double SingleWidth = 220;

    /// <summary>还没解码出比例时的占位高：4:3，与 Android 的 <c>?: 4f / 3f</c> 同一个兜底。</summary>
    public const double SingleHeight = 165;

    public const double CellSize = 88;
    public const double Gutter = 4;
    public const int GridColumns = 3;
    public const int GridMax = 9;

    /// <summary>
    /// <paramref name="fullLoad"/> 给了就在缩略图上加一次点击：大图走 <c>size=full</c> 那条端点，
    /// 不是把缩略图放大（§4.5 的缩略图是服务端降级的产物，放大只会糊）。
    /// </summary>
    public static FrameworkElement Build(int count, Func<int, Task<MediaAsset>> load, MarkdownStyle style,
        Func<int, Task<MediaAsset>>? fullLoad = null)
    {
        var column = new StackPanel();
        if (count <= 0) return column;

        if (count == 1)
        {
            column.Children.Add(Slot(0, SingleWidth, SingleHeight, false, 0, fullLoad, load, style));
            return column;
        }

        var shown = Math.Min(count, GridMax);
        for (var start = 0; start < shown; start += GridColumns)
        {
            var row = new StackPanel { Orientation = Orientation.Horizontal };
            for (var i = start; i < Math.Min(start + GridColumns, shown); i++)
            {
                // 行内那道缝落在第二格起，行外的落在第二行起——和 Android 的 spacedBy 等价
                row.Children.Add(Slot(i, CellSize, CellSize, true,
                    i == start ? 0 : Gutter, fullLoad, load, style));
            }

            row.Margin = start == 0 ? new Thickness(0) : new Thickness(0, Gutter, 0, 0);
            column.Children.Add(row);
        }

        if (count > GridMax)
        {
            column.Children.Add(new TextBlock
            {
                Text = $"另有 {count - GridMax} 张",
                FontSize = MarkdownStyle.LabelSize,
                Foreground = style.Muted,
                Margin = new Thickness(0, Gutter, 0, 0)
            });
        }
        return column;
    }

    /// <summary>
    /// 一个格子。<paramref name="crop"/> = true 是九宫格里的小方格（裁满），false 是单图大格（按原比例放全）。
    /// </summary>
    private static Border Slot(int idx, double size, double fallbackHeight, bool crop, double leftMargin,
        Func<int, Task<MediaAsset>>? fullLoad, Func<int, Task<MediaAsset>> load, MarkdownStyle style)
    {
        var slot = new Border
        {
            Width = size,
            Height = fallbackHeight,
            CornerRadius = new CornerRadius(BubbleView.BubbleRadius),
            Background = style.BlockBackground,
            Margin = new Thickness(leftMargin, 0, 0, 0),
            ClipToBounds = true
        };
        Fill(slot, idx, load, style, crop, fullLoad);
        if (fullLoad != null)
        {
            slot.Cursor = Cursors.Hand;
            slot.ToolTip = "查看原图";
            slot.MouseLeftButtonUp += (_, _) => MediaViewer.Show(() => fullLoad(idx), style);
        }
        return slot;
    }

    /// <summary>
    /// 异步填一个槽位。<c>async void</c> 在这里是对的：这是"元素自己把自己画出来"，
    /// 没人能 await 它，所以异常必须在本方法里咽干净——两条出口都给得出占位，绝不留下一个空白图框。
    /// </summary>
    private static async void Fill(Border slot, int idx, Func<int, Task<MediaAsset>> load, MarkdownStyle style,
        bool crop, Func<int, Task<MediaAsset>>? fullLoad)
    {
        slot.Child = Label("加载中…", style, null);
        MediaAsset asset;
        try
        {
            asset = await load(idx).ConfigureAwait(true);
        }
        catch (Exception)
        {
            slot.Child = Label("图片暂时取不到", style,
                () => Fill(slot, idx, load, style, crop, fullLoad));
            return;
        }

        if (ImageSourceOf.FromBytes(asset.Bytes) is { } source)
        {
            // 单图大格按真实比例长高（Android 那边是 aspectRatio(bmp 宽高比)），九宫格维持方格裁满
            if (!crop && source.PixelHeight > 0 && source.PixelWidth > 0)
            {
                slot.Height = Math.Max(CellSize,
                    Math.Min(400, Math.Round(SingleWidth * source.PixelHeight / source.PixelWidth)));
            }

            slot.Child = new Image
            {
                Source = source,
                Stretch = crop ? Stretch.UniformToFill : Stretch.Uniform,
                Width = slot.Width,
                Height = slot.Height
            };
            return;
        }

        // Placeholder 只在"字节不会再来"的四个状态上给（W10 的口径）；
        // pending 还是转圈语义，NoSource 压根没图源（重试也变不出图源来），两者都不该写"已失效"
        slot.Child = asset.Placeholder is { Length: > 0 } text
            ? Label(text, style, () => Fill(slot, idx, load, style, crop, fullLoad))
            : Label(asset.State switch
            {
                MediaState.Pending => "图片加载中",
                MediaState.NoSource => string.Empty,
                _ => "图片暂时取不到"
            }, style, asset.State is MediaState.Pending or MediaState.NoSource
                ? null
                : () => Fill(slot, idx, load, style, crop, fullLoad));
    }

    private static TextBlock Label(string text, MarkdownStyle style, Action? retry)
    {
        var label = new TextBlock
        {
            Text = text,
            FontSize = MarkdownStyle.LabelSize,
            Foreground = style.Muted,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center
        };
        if (retry is null) return label;
        label.Cursor = Cursors.Hand;
        label.ToolTip = "点击重试";
        // Handled 那一笔是必须的：占位文字整个铺在格子里，不吃掉这次点击的话冒泡到 Border 就同时开了原图预览
        label.MouseLeftButtonUp += (_, args) =>
        {
            args.Handled = true;
            retry();
        };
        return label;
    }
}

/// <summary>
/// §7.4 的原图预览：Android 那边是一个 AlertDialog，PC 这边是一扇没有边框的深色窗。
/// 它只认一个 <c>Func&lt;Task&lt;MediaAsset&gt;&gt;</c>，所以既不需要设备身份也不需要 MediaLoader，
/// 关闭条件是"点空白处 / Esc"——图本身吃掉点击，不然看大图时手一滑就关了。
/// </summary>
public static class MediaViewer
{
    public static void Show(Func<Task<MediaAsset>> load, MarkdownStyle style)
    {
        // 遮罩刻意不走 token：看图时它盖住两套色板，深浅灰底上都是同一层深色，跟主题无关
        var host = new Grid { Background = new SolidColorBrush(Color.FromArgb(0xF2, 0x14, 0x14, 0x14)) };
        var hint = new TextBlock
        {
            Text = "加载中…",
            FontSize = MarkdownStyle.LabelSize,
            Foreground = style.Muted,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center
        };
        host.Children.Add(hint);

        var work = SystemParameters.WorkArea;
        var owner = Application.Current?.MainWindow;
        var viewer = new Window
        {
            Owner = owner,
            WindowStyle = WindowStyle.None,
            ResizeMode = ResizeMode.NoResize,
            ShowInTaskbar = false,
            // 有主窗就叠在它上面（CenterScreen 在多显示器上会把图开到另一块屏去），没有才回到屏幕中央
            WindowStartupLocation = owner is null
                ? WindowStartupLocation.CenterScreen
                : WindowStartupLocation.CenterOwner,
            // 960×680 在 1366×768@150% 上比工作区还大，同主窗/设置窗一个口径
            Width = MainModel.FitToWorkArea(960, work.Width),
            Height = MainModel.FitToWorkArea(680, work.Height),
            Content = host
        };
        viewer.PreviewMouseLeftButtonDown += (_, args) =>
        {
            if (ReferenceEquals(args.OriginalSource, host)) viewer.Close();
        };
        viewer.KeyDown += (_, args) =>
        {
            if (args.Key == Key.Escape) viewer.Close();
        };

        viewer.Show();
        _ = FillAsync(host, hint, load);
    }

    private static async Task FillAsync(Grid host, TextBlock hint, Func<Task<MediaAsset>> load)
    {
        try
        {
            var asset = await load().ConfigureAwait(true);
            if (ImageSourceOf.FromBytes(asset.Bytes) is not { } source)
            {
                hint.Text = asset.Placeholder is { Length: > 0 } text ? text : "原图暂时取不到";
                return;
            }

            host.Children.Remove(hint);
            var image = new Image
            {
                Source = source,
                Stretch = Stretch.Uniform,
                Margin = new Thickness(24)
            };
            image.MouseLeftButtonDown += (_, args) => args.Handled = true;
            host.Children.Add(image);
        }
        catch (Exception)
        {
            hint.Text = "原图暂时取不到";
        }
    }
}
