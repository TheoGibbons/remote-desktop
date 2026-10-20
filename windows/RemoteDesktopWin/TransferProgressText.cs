using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;

namespace RemoteDesktopWin;

public static class TransferProgressText
{
    /// <summary>Shows "↑ 15% name.ext": a green up arrow while sending, a red down arrow while receiving.</summary>
    public static void ShowTransfer(this TextBlock block, bool sending, string text)
    {
        block.Inlines.Clear();
        block.Inlines.Add(new Run(sending ? "↑ " : "↓ ")
        {
            Foreground = (Brush)Application.Current.FindResource(sending ? "RdGoodBrush" : "RdDangerBrush"),
            FontWeight = FontWeights.SemiBold,
        });
        block.Inlines.Add(new Run(text));
    }
}
