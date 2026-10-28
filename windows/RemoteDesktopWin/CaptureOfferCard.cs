using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace RemoteDesktopWin;

/// <summary>
/// Shows a <see cref="CaptureOffer"/> as a card among the toasts in the corner
/// of the screen. Clicking it downloads the files, then opens what arrived. It
/// closes after a minute unless the pointer rests on it, and never takes focus
/// from whatever is being typed in.
/// </summary>
internal sealed class CaptureOfferCard : Window
{
    private static readonly List<CaptureOfferCard> Cards = new();
    // Older offers make room rather than stack up the screen.
    private const int MaxCards = 4;

    private static readonly TimeSpan OfferedFor = TimeSpan.FromMinutes(1);
    private static readonly TimeSpan SavedFor = TimeSpan.FromSeconds(20);
    private static readonly TimeSpan FailedFor = TimeSpan.FromSeconds(30);
    // After the pointer leaves, at least this long to come back.
    private static readonly TimeSpan GraceAfterHover = TimeSpan.FromSeconds(5);

    private static readonly Brush CardBrush = Frozen(Color.FromArgb(0xF2, 0x20, 0x24, 0x2B));
    private static readonly Brush HoverBrush = Frozen(Color.FromArgb(0xF2, 0x2D, 0x32, 0x3A));
    private static readonly Brush TileBrush = Frozen(Color.FromArgb(0x33, 0xFF, 0xFF, 0xFF));
    private static readonly Brush MutedBrush = Frozen(Color.FromRgb(0xBD, 0xC1, 0xC6));
    private static readonly Brush ActionBrush = Frozen(Color.FromRgb(0x8A, 0xB4, 0xF8));
    private static readonly Brush GoodBrush = Frozen(Color.FromRgb(0x81, 0xC9, 0x95));
    private static readonly Brush BadBrush = Frozen(Color.FromRgb(0xF2, 0x8B, 0x82));
    private static readonly FontFamily Glyphs = new("Segoe MDL2 Assets");

    private readonly CaptureOffer _offer;
    private readonly Border _card;
    private readonly FrameworkElement _close;
    private readonly TextBlock _title = Line(14, Brushes.White, FontWeights.SemiBold);
    private readonly TextBlock _detail = Line(12.5, MutedBrush);
    private readonly TextBlock _names = Line(12.5, MutedBrush);
    private readonly TextBlock _action = Line(12.5, ActionBrush);
    private readonly TextBlock _folderLink;
    private readonly DispatcherTimer _timer = new();
    private CaptureOffer.Stage? _shown;
    private TimeSpan? _left; // until it closes on its own; null while downloading
    private DateTime _since; // when _left was measured
    private bool _pressed;
    private bool _closing;

    public static void Show(CaptureOffer offer)
    {
        var card = new CaptureOfferCard(offer);
        Cards.Add(card);
        if (Cards.Count > MaxCards)
            Cards.FirstOrDefault(c => c != card && c._offer.Status == CaptureOffer.Stage.Offered)?.FadeOut();
        Toast.Stack(card);
        card.Show();
    }

    /// <summary>A phone left, or the relay connection dropped (null for every
    /// phone): what it offered can no longer be downloaded.</summary>
    public static void PeerLeft(string? peerId)
    {
        foreach (var card in Cards.Where(c => peerId == null || c._offer.PeerId == peerId).ToList())
        {
            var downloading = card._offer.Status == CaptureOffer.Stage.Downloading;
            card._offer.PeerGone();
            // A download that was cut off stays to say so, and saved files stay to be opened.
            if (!downloading && card._offer.Status != CaptureOffer.Stage.Saved) card.FadeOut();
        }
    }

    private CaptureOfferCard(CaptureOffer offer)
    {
        _offer = offer;
        Title = offer.Title;
        WindowStyle = WindowStyle.None;
        AllowsTransparency = true;
        Background = Brushes.Transparent;
        ShowInTaskbar = false;
        ShowActivated = false;
        Topmost = true;
        ResizeMode = ResizeMode.NoResize;
        SizeToContent = SizeToContent.WidthAndHeight;

        _folderLink = FolderLink();
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        foreach (var line in new[] { _title, _detail, _names, _action, _folderLink }) text.Children.Add(line);
        _title.Margin = new Thickness(0, 0, 0, 2);
        _action.Margin = new Thickness(0, 4, 0, 0);
        _action.TextWrapping = TextWrapping.Wrap;
        _action.TextTrimming = TextTrimming.None;

        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        _close = CloseButton();
        Grid.SetColumn(text, 1);
        Grid.SetColumn(_close, 2);
        grid.Children.Add(Preview());
        grid.Children.Add(text);
        grid.Children.Add(_close);

        _card = new Border
        {
            Width = 360,
            Background = CardBrush,
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(12, 12, 6, 12),
            Child = grid,
        };
        _card.MouseEnter += (_, _) => Pause();
        _card.MouseLeave += (_, _) => Resume();
        _card.MouseLeftButtonDown += (_, _) => _pressed = true;
        _card.MouseLeftButtonUp += (_, _) =>
        {
            if (_pressed) Clicked();
            _pressed = false;
        };
        Content = _card;

        _timer.Tick += (_, _) => FadeOut();
        _offer.Changed += Update;
        Closed += (_, _) =>
        {
            _timer.Stop();
            _offer.Changed -= Update;
            Cards.Remove(this);
        };
        Update();
    }

    private static TextBlock Line(double size, Brush color, FontWeight? weight = null) => new()
    {
        FontSize = size,
        Foreground = color,
        FontWeight = weight ?? FontWeights.Normal,
        TextTrimming = TextTrimming.CharacterEllipsis,
    };

    private static Brush Frozen(Color color)
    {
        var brush = new SolidColorBrush(color);
        brush.Freeze();
        return brush;
    }

    /// <summary>The phone's preview of the newest file, or an icon for its kind.</summary>
    private FrameworkElement Preview()
    {
        var tile = new Grid { Width = 64, Height = 64, Margin = new Thickness(0, 0, 12, 0), VerticalAlignment = VerticalAlignment.Center };
        var kind = _offer.Files[^1].Kind;
        var image = Decode(_offer.Thumbnail);
        tile.Children.Add(new Border
        {
            CornerRadius = new CornerRadius(6),
            Background = image == null ? TileBrush : new ImageBrush(image) { Stretch = Stretch.UniformToFill },
            Child = image != null ? null : new TextBlock
            {
                Text = kind switch
                {
                    "photo" => "",                     // camera
                    "video" or "screen-recording" => "", // video camera
                    "screenshot" => "",                // phone
                    _ => "",                           // document
                },
                FontFamily = Glyphs,
                FontSize = 26,
                Foreground = Brushes.White,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            },
        });
        if (image != null && kind is "video" or "screen-recording")
            tile.Children.Add(new Border
            {
                Width = 26,
                Height = 26,
                CornerRadius = new CornerRadius(13),
                Background = Frozen(Color.FromArgb(0x99, 0, 0, 0)),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                Child = new TextBlock
                {
                    Text = "", // play
                    FontFamily = Glyphs,
                    FontSize = 11,
                    Foreground = Brushes.White,
                    Margin = new Thickness(2, 0, 0, 0),
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center,
                },
            });
        return tile;
    }

    private static BitmapImage? Decode(byte[]? jpeg)
    {
        if (jpeg == null) return null;
        try
        {
            var image = new BitmapImage();
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.StreamSource = new MemoryStream(jpeg);
            image.EndInit();
            image.Freeze();
            return image;
        }
        catch
        {
            return null; // An unreadable preview just leaves the icon.
        }
    }

    private FrameworkElement CloseButton()
    {
        var glyph = new TextBlock
        {
            Text = "", // ✕
            FontFamily = Glyphs,
            FontSize = 10,
            Foreground = MutedBrush,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
        var button = new Border
        {
            Width = 28,
            Height = 28,
            CornerRadius = new CornerRadius(4),
            Background = Brushes.Transparent,
            VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(4, -6, 0, 0),
            Cursor = Cursors.Hand,
            Child = glyph,
        };
        button.MouseEnter += (_, _) => glyph.Foreground = Brushes.White;
        button.MouseLeave += (_, _) => glyph.Foreground = MutedBrush;
        // Handled here, so the card underneath doesn't take it as a click too.
        button.MouseLeftButtonDown += (_, e) => e.Handled = true;
        button.MouseLeftButtonUp += (_, e) =>
        {
            e.Handled = true;
            _pressed = false;
            if (_offer.Status == CaptureOffer.Stage.Downloading) _offer.Cancel();
            FadeOut();
        };
        return button;
    }

    private void Update()
    {
        _title.Text = _offer.Title;
        _detail.Text = _offer.TotalSize > 0 ? $"{_offer.PeerName} · {FileEntry.FormatSize(_offer.TotalSize)}" : _offer.PeerName;
        _names.Text = _offer.Names;
        _names.ToolTip = string.Join("\n", _offer.Files.Select(f => f.Name));
        var retry = _offer.PeerOnline ? " Click to try again." : "";
        (_action.Text, _action.Foreground) = _offer.Status switch
        {
            CaptureOffer.Stage.Offered => ("Click to download", ActionBrush),
            CaptureOffer.Stage.Downloading => ($"Downloading… {_offer.Percent}%", MutedBrush),
            CaptureOffer.Stage.Saved when _offer.SavedPaths.Count == 1 => ("Saved to Downloads · click to open", GoodBrush),
            CaptureOffer.Stage.Saved => ($"Saved {_offer.SavedPaths.Count} files to Downloads", GoodBrush),
            _ => ($"Couldn't download: {_offer.Error?.TrimEnd('.')}.{retry}", BadBrush),
        };
        _folderLink.Visibility = _offer.Status == CaptureOffer.Stage.Saved ? Visibility.Visible : Visibility.Collapsed;
        _card.Cursor = Clickable ? Cursors.Hand : Cursors.Arrow;
        _close.ToolTip = _offer.Status == CaptureOffer.Stage.Downloading ? "Cancel the download" : "Dismiss";

        // Each stage gets its own time on screen, counted from when it began.
        if (_shown == _offer.Status) return;
        _shown = _offer.Status;
        Countdown(_offer.Status switch
        {
            CaptureOffer.Stage.Offered => OfferedFor,
            CaptureOffer.Stage.Saved => SavedFor,
            CaptureOffer.Stage.Failed => FailedFor,
            _ => null, // a download stays until it ends
        });
    }

    private bool Clickable => _offer.Status switch
    {
        CaptureOffer.Stage.Offered => true,
        CaptureOffer.Stage.Saved => true,
        CaptureOffer.Stage.Failed => _offer.PeerOnline,
        _ => false,
    };

    private void Clicked()
    {
        switch (_offer.Status)
        {
            case CaptureOffer.Stage.Offered:
            case CaptureOffer.Stage.Failed:
                _offer.Download();
                break;
            case CaptureOffer.Stage.Saved:
                Open();
                break;
        }
    }

    /// <summary>Shown under the saved message, which only says what clicking the card does.</summary>
    private TextBlock FolderLink()
    {
        var link = Line(12.5, ActionBrush);
        link.Text = "Show in folder";
        link.Cursor = Cursors.Hand;
        link.HorizontalAlignment = HorizontalAlignment.Left;
        link.Margin = new Thickness(0, 2, 0, 0);
        link.MouseEnter += (_, _) => link.TextDecorations = TextDecorations.Underline;
        link.MouseLeave += (_, _) => link.TextDecorations = null;
        // Handled here, so the card underneath doesn't open the file as well.
        link.MouseLeftButtonDown += (_, e) => e.Handled = true;
        link.MouseLeftButtonUp += (_, e) =>
        {
            e.Handled = true;
            _pressed = false;
            ShowInFolder();
        };
        return link;
    }

    /// <summary>One file opens in its app; several show in their folder.</summary>
    private void Open()
    {
        if (_offer.SavedPaths.Count == 1) Explorer($"\"{_offer.SavedPaths[0]}\"");
        else ShowInFolder();
    }

    /// <summary>Downloads, with the last file saved selected.</summary>
    private void ShowInFolder()
    {
        if (_offer.SavedPaths.Count > 0) Explorer($"/select,\"{_offer.SavedPaths[^1]}\"");
    }

    // Through Explorer, so things open as the signed-in user even while this
    // app runs as administrator.
    private void Explorer(string args)
    {
        try { Process.Start(new ProcessStartInfo("explorer.exe", args) { UseShellExecute = false }); }
        catch (Exception ex) { Toast.Show("Couldn't open it: " + ex.Message); }
        FadeOut();
    }

    private void Countdown(TimeSpan? time)
    {
        _left = time;
        _since = DateTime.UtcNow;
        _timer.Stop();
        if (time is { } left && !_card.IsMouseOver) Start(left);
    }

    private void Start(TimeSpan left)
    {
        _timer.Interval = left;
        _timer.Start();
    }

    private void Pause()
    {
        _card.Background = HoverBrush;
        if (_left is not { } left || !_timer.IsEnabled) return;
        _timer.Stop();
        _left = left - (DateTime.UtcNow - _since);
    }

    private void Resume()
    {
        _card.Background = CardBrush;
        _pressed = false;
        if (_left is not { } left || _closing) return;
        _left = left < GraceAfterHover ? GraceAfterHover : left;
        _since = DateTime.UtcNow;
        Start(_left.Value);
    }

    private void FadeOut()
    {
        if (_closing) return;
        _closing = true;
        _timer.Stop();
        var fade = new DoubleAnimation(1, 0, TimeSpan.FromMilliseconds(250));
        fade.Completed += (_, _) => Close();
        BeginAnimation(OpacityProperty, fade);
    }

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        // Clicking the card must not take focus from what is being typed in.
        var hwnd = new WindowInteropHelper(this).Handle;
        SetWindowLong(hwnd, GWL_EXSTYLE, GetWindowLong(hwnd, GWL_EXSTYLE) | WS_EX_NOACTIVATE);
    }

    private const int GWL_EXSTYLE = -20;
    private const int WS_EX_NOACTIVATE = 0x08000000;

    [DllImport("user32.dll")]
    private static extern int GetWindowLong(IntPtr hwnd, int index);

    [DllImport("user32.dll")]
    private static extern int SetWindowLong(IntPtr hwnd, int index, int value);
}
