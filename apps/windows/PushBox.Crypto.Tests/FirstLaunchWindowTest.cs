using System;
using System.Threading;
using System.Windows;
using System.Windows.Threading;
using PushBox.Windows.Views;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// A XAML mistake (x:Name not matching a field, a panel that never folds) otherwise only surfaces when
/// someone clicks. This builds the object graph on an STA thread — no dispatcher pumping, no Show — so
/// the markup at least loads and the initial panel state is the one §7.5 describes.
/// </summary>
public class FirstLaunchWindowTest
{
    [Fact]
    public void FirstLaunchWindowBuildsFromXaml()
    {
        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var window = new FirstLaunchWindow(new OnboardingModel());
                Assert.Equal(Visibility.Collapsed, window.RecoveryPanelState);
                Assert.Equal(Visibility.Collapsed, window.BackupPanelState);
                Assert.False(string.IsNullOrWhiteSpace(window.SuggestedNameText));
                Assert.Null(window.ShownError);

                window.ShowRecoveryCodeInput();
                Assert.Equal(Visibility.Visible, window.RecoveryPanelState);
            }
            catch (Exception e)
            {
                failure = e;
            }
            finally
            {
                Dispatcher.CurrentDispatcher.InvokeShutdown();
            }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.IsBackground = true;

        thread.Start();
        Assert.True(thread.Join(TimeSpan.FromSeconds(20)), "构造首启窗口没有按时完成");
        Assert.True(failure == null, failure?.ToString());
    }
}
