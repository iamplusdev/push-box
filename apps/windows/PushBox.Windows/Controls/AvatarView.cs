using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using PushBox.Windows.Media;
using PushBox.Windows.Themes;
using PushBox.Windows.Views;

namespace PushBox.Windows.Controls;

/// <summary>
/// 会话行左边那一个头像（§7.3/§7.4）。三种形态按优先级来：本机"设头像"落的那个文件、
/// 服务端转存的发送者头像（<c>/api/v1/avatars/{token}</c>）、最后才是首字占位——
/// 取图的顺序表与 Android 的 <c>Avatar()</c>（MainActivity.kt:2899-2944）一致，
/// 只有占位那张脸按档分开：浅色是白底配名字色首字，深色沿用 Android 那种定色底配白字。
///
/// 它是一个控件而不是 XAML 里的一段模板，因为头像字节是<b>异步</b>取回来的：
/// 绑定的值没有"稍后再填一次"这一说，而这里要的就是"先画占位、字节到了再盖上去"。
/// 取字节的委托挂在 <see cref="Loader"/> 上（只有装配层有 <c>MediaLoader</c> 和设备身份），
/// 所以 STA 用例能在没有身份、没有服务端的情况下断到那一格首字占位。
/// </summary>
public sealed class AvatarBox : Grid
{
    /// <summary>窗口装配时挂上来的取字节委托；为 null 时永远只有占位（用例、以及还没注册好的那台机器）。</summary>
    public static Func<string, Task<MediaAsset>>? Loader;

    /// <summary>
    /// 某个 token 的头像作废了（服务端覆盖写了它）：画着它的那一格要重新取一次字节。
    /// 做成事件而不是让装配层去遍历可视树，是因为头像既在会话行里也在气泡里，还随虚拟化进出，
    /// 没有一份"现在有谁画着这个 token"的现成名册。与 Android 的 <c>AvatarCache.revision</c> 同一手法。
    /// </summary>
    public static event Action<string>? Invalidated;

    /// <summary>装配层收到"帧里带着 sender_avatar"时调这里（另一端是 <see cref="MediaLoader.AvatarInvalidated"/>）。</summary>
    public static void Invalidate(string token)
    {
        // 记一笔代数再广播：退订中的那些格子中不了这一枪，只能在回到屏上时靠这个数发现自己画旧了
        lock (RevisionGate)
            Revisions[token] = Revisions.TryGetValue(token, out var revision) ? revision + 1 : 1;
        Invalidated?.Invoke(token);
    }

    /// <summary>
    /// 每个 token 作废过几次。<b>缓存里那棵气泡树离开可视树时就退订了</b>（订阅跟着进出走，
    /// 见构造函数那段），而 <c>BubbleCache</c> 把它接回屏上时 <c>Token</c> 一个字都没变，
    /// 依赖属性回调也不会来——没有这份代数的话，换掉之前的那张脸会一直挂在那一格上。
    /// 只记"被作废过"的 token，所以这张表的大小就是"本机见过换头像的发送者数"。
    /// </summary>
    private static readonly Dictionary<string, int> Revisions = new(StringComparer.Ordinal);
    private static readonly object RevisionGate = new();

    private static int RevisionOf(string token)
    {
        lock (RevisionGate) return Revisions.TryGetValue(token, out var revision) ? revision : 0;
    }

    public static readonly DependencyProperty DisplayNameProperty = DependencyProperty.Register(
        nameof(DisplayName), typeof(string), typeof(AvatarBox), new PropertyMetadata(string.Empty, OnShapeChanged));

    public static readonly DependencyProperty TokenProperty = DependencyProperty.Register(
        nameof(Token), typeof(string), typeof(AvatarBox), new PropertyMetadata(null, OnShapeChanged));

    public static readonly DependencyProperty LocalPathProperty = DependencyProperty.Register(
        nameof(LocalPath), typeof(string), typeof(AvatarBox), new PropertyMetadata(null, OnShapeChanged));

    /// <summary>边长。§9.2 的会话行是 44 见方、圆角 6（微信式方圆形，不是正圆）。</summary>
    public static readonly DependencyProperty SideProperty = DependencyProperty.Register(
        nameof(Side), typeof(double), typeof(AvatarBox), new PropertyMetadata(44d, OnShapeChanged));

    /// <summary>显示名：首字取它，占位那一格的颜色也由它定（浅色主题落在首字上、深色主题落在底上，
    /// 与 <see cref="AvatarVisual"/> 同一张表）。不叫 <c>Name</c> 是因为 <c>FrameworkElement.Name</c> 已经占了那个名字。</summary>
    public string? DisplayName
    {
        get => (string?)GetValue(DisplayNameProperty);
        set => SetValue(DisplayNameProperty, value);
    }

    /// <summary>头像对象 token（sender_id 的 sha256 十六进制）。空与 null 同义：这一格没有远端图源。</summary>
    public string? Token
    {
        get => (string?)GetValue(TokenProperty);
        set => SetValue(TokenProperty, value);
    }

    /// <summary>本机"设头像"落下的那个文件，优先级高于远端头像。</summary>
    public string? LocalPath
    {
        get => (string?)GetValue(LocalPathProperty);
        set => SetValue(LocalPathProperty, value);
    }

    public double Side
    {
        get => (double)GetValue(SideProperty);
        set => SetValue(SideProperty, value);
    }

    /// <summary>这一次请求是为哪个 token 发的：续延回来时换人了就把那份字节丢掉。</summary>
    private string? _waiting;

    /// <summary>这一格上一次开画时那个 token 的代数。与当前代数对不上就是"画着换掉之前那张脸"。</summary>
    private int _paintedRevision;

    public AvatarBox()
    {
        // 订阅跟着可视树的进出走：会话列表每次重画都换一批格子，构造函数里订阅而不在这里退订，
        // 会让每一个被淘汰的格子都被 <see cref="Invalidated"/> 永久钉在内存里
        Loaded += (_, _) =>
        {
            Invalidated -= OnInvalidated;
            Invalidated += OnInvalidated;
            ThemeManager.Changed -= OnThemeChanged;
            ThemeManager.Changed += OnThemeChanged;
            // 出去那一趟错过的广播在这儿补：气泡树在 <c>BubbleCache</c> 里躺着的时候是退订状态，
            // 回屏时 Token 没变、依赖属性回调不来，只有这一个时机还能救那一格
            if (RevisionOf((Token ?? string.Empty).Trim()) != _paintedRevision) Render();
        };
        Unloaded += (_, _) =>
        {
            Invalidated -= OnInvalidated;
            ThemeManager.Changed -= OnThemeChanged;
        };
    }

    /// <summary>
    /// 换档时把自己重画一遍。窗口那次 <c>Refresh()</c> 救不了这里：虚拟化的会话行是<b>复用</b>的，
    /// 复用出来的格子 <see cref="DisplayName"/> 没变，依赖属性回调不会来，于是一屏旧脸配一屏新色。
    /// </summary>
    private void OnThemeChanged()
    {
        if (!Dispatcher.CheckAccess())
        {
            Dispatcher.BeginInvoke(new Action(OnThemeChanged));
            return;
        }

        Render();
    }

    private static void OnShapeChanged(DependencyObject d, DependencyPropertyChangedEventArgs e) =>
        ((AvatarBox)d).Render();

    private void OnInvalidated(string token)
    {
        if (!Dispatcher.CheckAccess())
        {
            Dispatcher.BeginInvoke(new Action(() => OnInvalidated(token)));
            return;
        }

        // 画的不是它（或者已经是另一个人了）：重画一次只是白跑一趟
        if (!string.Equals((Token ?? string.Empty).Trim(), token, StringComparison.Ordinal)) return;
        Render();
    }

    private void Render()
    {
        Children.Clear();
        // 重画就是"这一格换了人"：还在飞的那一次回来时 _waiting 已经不是它了，直接扔
        _waiting = null;
        var token = (Token ?? string.Empty).Trim();
        // 开画这一刻的代数要记下：下一次进可视树时靠它判"这一格要不要重来"
        _paintedRevision = RevisionOf(token);

        var tint = new SolidColorBrush(AvatarVisual.ColorOf(DisplayName));
        // 两档各一张脸：浅色主题这一格是白的（首字改用名字那支色，否则白字压在白底上什么都看不见），
        // 深色主题沿用"定色底 + 白首字"——一块 #FFFFFF 落在 #1A1A1A 的页面上是闪光灯，不是头像。
        // 描边只在浅色出现：会话列表那一栏本身接近白，白方块不勾一圈就糊成背景的一部分。
        var light = !ThemeManager.IsDarkTheme;
        var divider = light ? BrushOf("Divider") : null;
        var shell = new Border
        {
            Width = Side,
            Height = Side,
            CornerRadius = new CornerRadius(Side * 6 / 44),
            Background = light ? BrushOf("Bg.Elevated") ?? Brushes.White : tint,
            BorderBrush = divider,
            BorderThickness = divider == null ? new Thickness() : new Thickness(1),
            ClipToBounds = true
        };
        shell.Child = new TextBlock
        {
            Text = AvatarVisual.Initial(DisplayName),
            Foreground = light ? tint : Brushes.White,
            FontSize = Side * 16 / 44,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center
        };
        Children.Add(shell);

        if (FromPath(LocalPath) is { } local)
        {
            Cover(shell, local);
            return;
        }

        if (token.Length == 0 || Loader == null) return;
        _waiting = token;
        FetchAsync(shell, token);
    }

    /// <summary>
    /// <c>async void</c> 的理由与 <see cref="MediaStrip"/> 那条一样：这是"元素把自己画出来"，没人能 await 它，
    /// 所以异常必须在这儿咽干净——取不到就留着首字占位，头像不该让会话行塌一格。
    /// </summary>
    private async void FetchAsync(Border shell, string token)
    {
        MediaAsset asset;
        try
        {
            asset = await Loader!(token).ConfigureAwait(true);
        }
        catch (Exception)
        {
            return;
        }

        if (!string.Equals(_waiting, token, StringComparison.Ordinal)) return;
        if (ImageSourceOf.FromBytes(asset.Bytes) is not { } source) return;
        Cover(shell, source);
    }

    private void Cover(Border shell, ImageSource source) => shell.Child = new Image
    {
        Source = source,
        Stretch = Stretch.UniformToFill
    };

    /// <summary>
    /// 从 token 字典取画刷。取不到就返回 null：用例里常常没有 App 实例，而"描边有没有"这件事
    /// 不该由一个凭空编出来的灰底冒充。
    /// </summary>
    private static Brush? BrushOf(string key) => Application.Current?.TryFindResource(key) as Brush;

    /// <summary>本机头像文件的解码：坏文件回 null，落回首字占位而不是把列表带崩。</summary>
    private static BitmapImage? FromPath(string? path)
    {
        if (string.IsNullOrWhiteSpace(path)) return null;
        try
        {
            var full = Path.GetFullPath(path!);
            if (!File.Exists(full)) return null;
            var image = new BitmapImage();
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.UriSource = new Uri(full);
            image.EndInit();
            image.Freeze();
            return image;
        }
        catch (Exception)
        {
            return null;
        }
    }
}
