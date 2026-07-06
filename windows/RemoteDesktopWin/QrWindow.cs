using System.IO;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using QRCoder;

namespace RemoteDesktopWin;

/// <summary>
/// Shows the session settings as a QR code. The phone scans it with the
/// "Scan QR" button next to its session-key field, which fills in both the
/// server URL and the session key — no typing or clipboard needed.
/// </summary>
public class QrWindow : Window
{
    public QrWindow(string serverUrl, string sessionKey)
    {
        Title = "Pair by QR code";
        Width = 420;
        SizeToContent = SizeToContent.Height;
        ResizeMode = ResizeMode.NoResize;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;

        // The payload carries the server URL too, so one scan configures the phone.
        var payload = new JsonObject
        {
            ["v"] = 1,
            ["server"] = serverUrl,
            ["key"] = sessionKey,
        }.ToJsonString();

        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data).GetGraphic(12);

        var img = new BitmapImage();
        using (var ms = new MemoryStream(png))
        {
            img.BeginInit();
            img.CacheOption = BitmapCacheOption.OnLoad;
            img.StreamSource = ms;
            img.EndInit();
        }
        img.Freeze();

        var panel = new StackPanel { Margin = new Thickness(16) };
        panel.Children.Add(new Image
        {
            Source = img,
            Width = 340,
            Height = 340,
            SnapsToDevicePixels = true,
        });
        panel.Children.Add(new TextBlock
        {
            Text = "On your phone, open Remote Desktop and tap “Scan QR”. " +
                   "This fills in the server URL and session key automatically.\n\n" +
                   "The QR code contains your secret session key — only show it to devices you trust.",
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(4, 12, 4, 0),
            Foreground = Brushes.DimGray,
        });
        Content = panel;
    }
}
