using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;

namespace RemoteDesktopWin;

public static class TransferProgressText
{
    /// <summary>Shows "↑ 15% name.ext": an up arrow while sending, a down arrow while receiving.</summary>
    public static void ShowTransfer(this TextBlock block, bool sending, string text)
    {
        block.Inlines.Clear();
        block.Inlines.Add(new Run(sending ? "↑ " : "↓ ") { FontWeight = FontWeights.SemiBold });
        block.Inlines.Add(new Run(text));
    }
}
