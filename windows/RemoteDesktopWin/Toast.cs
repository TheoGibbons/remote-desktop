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

        win.Loaded += (_, _) =>
        {
            var wa = SystemParameters.WorkArea;
            win.Left = wa.Right - win.ActualWidth - 16;
            // Stack above any toasts that are already showing.
            win.Top = wa.Bottom - win.ActualHeight - 16 - Open.Where(w => w != win).Sum(w => w.ActualHeight + 8);
        };

        Open.Add(win);
        win.Closed += (_, _) => Open.Remove(win);
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
}
