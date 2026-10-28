using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;

namespace RemoteDesktopWin;

/// <summary>
/// Lightweight in-app toast: a borderless topmost popup in the bottom-right
/// corner of the work area that fades out after a few seconds. Never steals
/// focus. Multiple toasts stack upwards.
/// </summary>
public static class Toast
{
    // Oldest at the bottom.
    private static readonly List<Window> Open = new();

    public static void Show(string message, int durationMs = 3500)
    {
        Application.Current?.Dispatcher.BeginInvoke(() => ShowCore(message, durationMs));
    }

    private static void ShowCore(string message, int durationMs)
    {
        var text = new TextBlock
        {
            Text = message,
            Foreground = Brushes.White,
            FontSize = 13,
            TextWrapping = TextWrapping.Wrap,
            MaxWidth = 340,
            Margin = new Thickness(14, 10, 14, 10),
        };
        var border = new Border
        {
            Background = new SolidColorBrush(Color.FromArgb(0xE6, 0x20, 0x24, 0x2B)),
            CornerRadius = new CornerRadius(8),
            Child = text,
        };
        var win = new Window
        {
            WindowStyle = WindowStyle.None,
            AllowsTransparency = true,
            Background = Brushes.Transparent,
            ShowInTaskbar = false,
            ShowActivated = false,
            Topmost = true,
            SizeToContent = SizeToContent.WidthAndHeight,
            Content = border,
        };

        Stack(win);
        win.Show();

        var timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(durationMs) };
        timer.Tick += (_, _) =>
        {
            timer.Stop();
            var fade = new DoubleAnimation(1, 0, TimeSpan.FromMilliseconds(350));
            fade.Completed += (_, _) => win.Close();
            win.BeginAnimation(UIElement.OpacityProperty, fade);
        };
        timer.Start();
    }

    /// <summary>Places a window about to be shown above the toasts already
    /// showing, and keeps the stack closed up as windows come and go.</summary>
    internal static void Stack(Window win)
    {
        Open.Add(win);
        win.Loaded += (_, _) => Arrange();
        win.SizeChanged += (_, _) => Arrange();
        win.Closed += (_, _) =>
        {
            Open.Remove(win);
            Arrange();
        };
    }

    // Each sits on the one below it, so a toast that closes lets the ones above
    // drop into its place. Placing each new one by the total height of the rest
    // instead put it on top of a long-lived one once a shorter one below it had gone.
    private static void Arrange()
    {
        var wa = SystemParameters.WorkArea;
        var bottom = wa.Bottom - 16;
        foreach (var w in Open)
        {
            if (!w.IsLoaded) continue;
            w.Left = wa.Right - w.ActualWidth - 16;
            w.Top = bottom - w.ActualHeight;
            bottom = w.Top - 8;
        }
    }
}
