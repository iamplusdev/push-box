using System;
using System.IO;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using PushBox.Windows.Media;
using PushBox.Windows.Views;

namespace PushBox.Windows.Controls;

/// <summary>
/// 会话行左边那一个头像（§7.3/§7.4）。三种形态按优先级来：本机"设头像"落的那个文件、
/// 服务端转存的发送者头像（<c>/api/v1/avatars/{token}</c>）、最后才是首字 + 名字定色的占位——
/// 与 Android 的 <c>Avatar()</c>（MainActivity.kt:2899-2944）同一张顺序表。
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

    public static readonly DependencyProperty DisplayNameProperty = DependencyProperty.Register(
        nameof(DisplayName), typeof(string), typeof(AvatarBox), new PropertyMetadata(string.Empty, OnShapeChanged));

    public static readonly DependencyProperty TokenProperty = DependencyProperty.Register(
        nameof(Token), typeof(string), typeof(AvatarBox), new PropertyMetadata(null, OnShapeChanged));

    public static readonly DependencyProperty LocalPathProperty = DependencyProperty.Register(
        nameof(LocalPath), typeof(string), typeof(AvatarBox), new PropertyMetadata(null, OnShapeChanged));

    /// <summary>边长。§9.2 的会话行是 44 见方、圆角 6（微信式方圆形，不是正圆）。</summary>
    public static readonly DependencyProperty SideProperty = DependencyProperty.Register(
        nameof(Side), typeof(double), typeof(AvatarBox), new PropertyMetadata(44d, OnShapeChanged));

    /// <summary>显示名：首字取它、占位底色也由它定（与 <see cref="AvatarVisual"/> 同一张表）。
    /// 不叫 <c>Name</c> 是因为 <c>FrameworkElement.Name</c> 已经占了那个名字。</summary>
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

    private static void OnShapeChanged(DependencyObject d, DependencyPropertyChangedEventArgs e) =>
        ((AvatarBox)d).Render();

    private void Render()
    {
        Children.Clear();
        // 重画就是"这一格换了人"：还在飞的那一次回来时 _waiting 已经不是它了，直接扔
        _waiting = null;

        var shell = new Border
        {
            Width = Side,
            Height = Side,
            CornerRadius = new CornerRadius(Side * 6 / 44),
            Background = new SolidColorBrush(AvatarVisual.ColorOf(DisplayName)),
            ClipToBounds = true
        };
        shell.Child = new TextBlock
        {
            Text = AvatarVisual.Initial(DisplayName),
            Foreground = Brushes.White,
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

        var token = (Token ?? string.Empty).Trim();
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
