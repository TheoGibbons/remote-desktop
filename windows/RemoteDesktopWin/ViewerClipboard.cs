using System.Text.Json.Nodes;
using System.Windows;

namespace RemoteDesktopWin;

/// <summary>
/// The viewers' Copy to this PC: what another device sends back in
/// <c>clip-get-result</c> goes onto this PC's clipboard (see PROTOCOL.md,
/// "Clipboard"). Shared by the window on another PC and the one on a phone.
/// </summary>
internal static class ViewerClipboard
{
    /// <summary>Puts the copy on this PC's clipboard, asking first when it is
    /// only what was already on the other device's, and says how it went.</summary>
    /// <param name="owner">The viewer, which owns the question.</param>
    /// <param name="name">The other device's name.</param>
    /// <param name="staleHint">Added to that question: what to do instead.</param>
    public static async Task ApplyCopyAsync(Window owner, string name, JsonNode msg, string? staleHint = null)
    {
        // This arrives inside the socket thread's blocking Dispatcher.Invoke;
        // the question below must not hold up the relay while it waits.
        await Task.Yield();
        try
        {
            if (ClipboardService.Str(msg["error"]) is { } error)
            {
                Toast.Show(error);
                return;
            }
            if (msg["files"] is JsonArray { Count: > 0 } files)
            {
                Toast.Show($"{name} copied {files.Count} file{(files.Count == 1 ? "" : "s")}. Use Files to transfer them.");
                return;
            }
            var (text, html, fitError) = ClipboardFormat.Fit(ClipboardService.Str(msg["text"]), ClipboardService.Str(msg["html"]));
            if (fitError != null || string.IsNullOrEmpty(text))
            {
                Toast.Show(fitError ?? $"{name} sent nothing to copy.");
                return;
            }
            bool sensitive = ClipboardService.Flag(msg["sensitive"], false);
            // Nothing new was copied there, so ask before replacing what's here.
            if (!ClipboardService.Flag(msg["fresh"], true) && MessageBox.Show(owner,
                    StaleQuestion(name, text, sensitive, staleHint),
                    "Copy to this PC", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes)
                return;
            await ClipboardService.WriteAsync(text, html, sensitive);
            Toast.Show($"Copied from {name}");
        }
        catch (Exception ex)
        {
            Toast.Show("Couldn't copy to this PC's clipboard: " + ex.Message);
        }
    }

    /// <summary>What is asked before a copy that changed nothing replaces this PC's clipboard.</summary>
    internal static string StaleQuestion(string name, string text, bool sensitive, string? hint)
    {
        var question = $"Nothing new was copied on {name}. Use what's already on its clipboard?\n\n" +
                       (sensitive ? "(Hidden: it's marked private.)" : text.Length <= 300 ? text : text[..300] + "…");
        return hint == null ? question : question + "\n\n" + hint;
    }
}
