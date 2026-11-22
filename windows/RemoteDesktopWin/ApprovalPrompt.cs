using System.Drawing;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media.Imaging;

namespace RemoteDesktopWin;

/// <summary>
/// Asks whether a new device may pair with this PC. Not a MessageBox: the
/// question can stop mattering while it is showing (the device leaves, or is
/// answered in the devices list), and then MainWindow closes it. It is not
/// modal either, so the PC stays usable, including by an approved device
/// viewing it, while it waits. Closing it without choosing leaves the device
/// waiting, with Approve and Deny still in the devices list.
/// </summary>
public class ApprovalPrompt : Window
{
    public event Action? Allowed;
    public event Action? Denied;

    public ApprovalPrompt(PeerAuth.PendingRequest req)
    {
        Title = "New device wants to connect";
        Width = 440;
        SizeToContent = SizeToContent.Height;
        ResizeMode = ResizeMode.NoResize;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;
        ShowInTaskbar = false;

        var icon = new System.Windows.Controls.Image
        {
            Source = Imaging.CreateBitmapSourceFromHIcon(SystemIcons.Warning.Handle, Int32Rect.Empty,
                BitmapSizeOptions.FromEmptyOptions()),
            Width = 32,
            Height = 32,
            VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(0, 0, 16, 0),
        };
        var message = new TextBlock
        {
            Text = $"\"{req.Name}\" ({req.Device}) wants to pair with this computer.\n\n" +
                   $"Device code: {DeviceIdentity.ShortCode(req.Fingerprint)}\n\n" +
                   "Only allow if that device shows the same code. Once approved it can " +
                   "view, control and browse this computer whenever those switches are on.",
            TextWrapping = TextWrapping.Wrap,
        };
        var body = new DockPanel { Margin = new Thickness(20, 20, 20, 16) };
        DockPanel.SetDock(icon, Dock.Left);
        body.Children.Add(icon);
        body.Children.Add(message);

        var allow = new Button { Content = "Allow", MinWidth = 88, Padding = new Thickness(12, 4, 12, 4) };
        // Deny is the default, as No was in the box this replaces: Enter alone never lets a device in.
        var deny = new Button
        {
            Content = "Deny", MinWidth = 88, Padding = new Thickness(12, 4, 12, 4),
            Margin = new Thickness(8, 0, 0, 0), IsDefault = true,
        };
        allow.Click += (_, _) => { Close(); Allowed?.Invoke(); };
        deny.Click += (_, _) => { Close(); Denied?.Invoke(); };
        var buttons = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = System.Windows.HorizontalAlignment.Right,
            Margin = new Thickness(20, 0, 20, 16),
        };
        buttons.Children.Add(allow);
        buttons.Children.Add(deny);

        var root = new StackPanel();
        root.Children.Add(body);
        root.Children.Add(buttons);
        Content = root;
        Loaded += (_, _) => deny.Focus();
    }
}
