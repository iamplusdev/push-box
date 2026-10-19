using System;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using PushBox.Windows.Data;

namespace PushBox.Windows.Views;

/// <summary>
/// §7.5 first launch, two screens: enroll (new Space or recovery code) and, only for the device that
/// created the Space, the one-time recovery code display.
/// All decisions live in <see cref="OnboardingModel"/>; this file only moves text.
/// </summary>
public partial class FirstLaunchWindow : Window
{
    private readonly OnboardingModel _model;
    private CancellationTokenSource? _running;
    private DeviceIdentity? _awaitingBackup;

    public FirstLaunchWindow() : this(null) {}

    public FirstLaunchWindow(OnboardingModel? model)
    {
        InitializeComponent();
        _model = model ?? new OnboardingModel();
        NameBox.Text = OnboardingModel.SuggestedDeviceName();
    }

    private void OnJoinClick(object sender, RoutedEventArgs e) => ShowRecoveryCodeInput();

    private void OnCreateClick(object sender, RoutedEventArgs e) => EnrollAsync(false);

    private void OnJoinConfirmClick(object sender, RoutedEventArgs e) => EnrollAsync(true);

    /// <summary>Revealed only after "我有恢复码", so the first screen stays one decision wide.</summary>
    public void ShowRecoveryCodeInput()
    {
        RecoveryPanel.Visibility = Visibility.Visible;
        CreateButton.Visibility = Visibility.Collapsed;
        JoinButton.Visibility = Visibility.Collapsed;
        CodeBox.Focus();
    }

    // 给测试用的只读视图：XAML 改坏（x:Name 对不上、面板没折叠）要能在 dotnet test 里被抓住
    public Visibility RecoveryPanelState => RecoveryPanel.Visibility;
    public Visibility BackupPanelState => BackupPanel.Visibility;
    public string SuggestedNameText => NameBox.Text;
    public string? ShownError => ErrorBox.Visibility == Visibility.Visible ? ErrorBox.Text : null;

    private async void EnrollAsync(bool join)
    {
        _running?.Cancel();
        var cts = new CancellationTokenSource();
        _running = cts;
        SetBusy(true, join ? "正在用恢复码加入…" : "正在注册这台设备…");
        HideError();

        try
        {
            var result = join
                ? await _model.JoinAsync(ServerBox.Text, CodeBox.Text, NameBox.Text, cts.Token)
                : await _model.CreateSpaceAsync(ServerBox.Text, NameBox.Text, cts.Token);

            if (result.Cancelled) return;
            if (!result.Succeeded)
            {
                ShowError(result.Error);
                return;
            }
            if (result.NeedsBackup)
            {
                ShowBackup(result.Identity!);
                return;
            }
            EnterApp();
        }
        finally
        {
            if (ReferenceEquals(_running, cts)) _running = null;
            SetBusy(false, "");
            cts.Dispose();
        }
    }

    private void ShowBackup(DeviceIdentity identity)
    {
        _awaitingBackup = identity;
        EnrollPanel.Visibility = Visibility.Collapsed;
        BackupPanel.Visibility = Visibility.Visible;
        CodeShowBox.Text = identity.RecoveryCode;
    }

    private void OnCopyCodeClick(object sender, RoutedEventArgs e)
    {
        if (!string.IsNullOrEmpty(CodeShowBox.Text)) Clipboard.SetText(CodeShowBox.Text);
    }

    private void OnEnterClick(object sender, RoutedEventArgs e) => EnterApp();

    private void EnterApp()
    {
        // 身份已经落盘（EnrollmentFlow 在返回前存过），主窗口自己读就行
        _awaitingBackup = null;
        ((App)Application.Current).OpenMainWindow();
        Close();
    }

    private void SetBusy(bool busy, string status)
    {
        BusyBar.Visibility = busy ? Visibility.Visible : Visibility.Collapsed;
        BusyBar.IsIndeterminate = busy;
        StatusBox.Text = status;
        CreateButton.IsEnabled = !busy;
        JoinButton.IsEnabled = !busy;
        JoinConfirmButton.IsEnabled = !busy;
        EnrollPanel.IsEnabled = !busy;
    }

    private void HideError()
    {
        ErrorBox.Visibility = Visibility.Collapsed;
        ErrorBox.Text = "";
    }

    private void ShowError(string? message)
    {
        ErrorBox.Text = message ?? "不知道哪里出了问题，请重试。";
        ErrorBox.Visibility = Visibility.Visible;
    }
}
