using System.Globalization;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;

namespace RemoteDesktopWin;

/// <summary>
/// The pure parts of the clipboard messages (see PROTOCOL.md, "Clipboard"):
/// the size limit both apps share, the Windows "HTML Format" envelope, and
/// which shortcuts copy and paste in a given window. No WPF or Win32 here, so
/// the tests can link this file on its own.
/// </summary>
public static class ClipboardFormat
{
    /// <summary>
    /// Most text plus HTML one clipboard message may carry, in UTF-16 code
    /// units. Android hands a clip to the system through Binder, whose buffer
    /// is about 1 MB, and a large control message delays any input queued
    /// behind it on the reliable lane.
    /// </summary>
    public const int MaxChars = 200_000;

    /// <summary>
    /// Fits a payload to <see cref="MaxChars"/>. The HTML goes first, since the
    /// plain text still pastes everywhere; text that is too long on its own is
    /// refused, with the reason in <c>Error</c>.
    /// </summary>
    public static (string? Text, string? Html, string? Error) Fit(string? text, string? html)
    {
        text ??= "";
        if (text.Length > MaxChars)
            return (null, null, $"That's too much text to copy ({text.Length:N0} characters; the limit is {MaxChars:N0}).");
        if (html != null && text.Length + html.Length > MaxChars) html = null;
        return (text, html, null);
    }

    /// <summary>
    /// The copied fragment inside a CF_HTML payload, or null if its header is
    /// missing or wrong. The header's offsets count UTF-8 bytes, so they are
    /// applied to the re-encoded bytes rather than to the string.
    /// </summary>
    public static string? HtmlFragment(string cfHtml)
    {
        // The header ends where the markup starts; don't read offsets out of the page.
        int markup = cfHtml.IndexOf('<');
        var header = markup < 0 ? cfHtml : cfHtml[..markup];
        int start = HeaderValue(header, "StartFragment:");
        int end = HeaderValue(header, "EndFragment:");
        var bytes = Encoding.UTF8.GetBytes(cfHtml);
        if (start < 0 || end < start || end > bytes.Length) return null;
        return Encoding.UTF8.GetString(bytes, start, end - start);
    }

    private static int HeaderValue(string header, string name)
    {
        int i = header.IndexOf(name, StringComparison.Ordinal);
        if (i < 0) return -1;
        i += name.Length;
        int j = i;
        while (j < header.Length && char.IsAsciiDigit(header[j])) j++;
        return int.TryParse(header.AsSpan(i, j - i), NumberStyles.None, CultureInfo.InvariantCulture, out var value)
            ? value : -1;
    }

    /// <summary>Wraps an HTML fragment in the CF_HTML envelope Windows apps
    /// paste from, with the byte offsets counted over UTF-8.</summary>
    public static string HtmlEnvelope(string fragment)
    {
        const string header = "Version:0.9\r\nStartHTML:{0:D10}\r\nEndHTML:{1:D10}\r\n" +
                              "StartFragment:{2:D10}\r\nEndFragment:{3:D10}\r\n";
        const string prefix = "<html><body>\r\n<!--StartFragment-->";
        const string suffix = "<!--EndFragment-->\r\n</body></html>";
        int startHtml = Encoding.UTF8.GetByteCount(string.Format(CultureInfo.InvariantCulture, header, 0, 0, 0, 0));
        int startFragment = startHtml + Encoding.UTF8.GetByteCount(prefix);
        int endFragment = startFragment + Encoding.UTF8.GetByteCount(fragment);
        int endHtml = endFragment + Encoding.UTF8.GetByteCount(suffix);
        return string.Format(CultureInfo.InvariantCulture, header, startHtml, endHtml, startFragment, endFragment) +
               prefix + fragment + suffix;
    }

    /// <summary>Readable text for a copy that came with HTML but no plain text.</summary>
    public static string PlainText(string html) =>
        WebUtility.HtmlDecode(Regex.Replace(Regex.Replace(html, @"<(br|/p|/div|/li|/tr)\b[^>]*>", "\n",
            RegexOptions.IgnoreCase), "<[^>]*>", "")).Trim();

    private static readonly HashSet<string> Terminals = new(StringComparer.Ordinal)
    {
        "ConsoleWindowClass",            // classic console host
        "CASCADIA_HOSTING_WINDOW_CLASS", // Windows Terminal
        "mintty",                        // Git Bash, Cygwin, MSYS2
        "VirtualConsoleClass",           // ConEmu
    };

    /// <summary>
    /// The key to press with Ctrl to copy in a window of this class, or null
    /// when the window copies on select and any key would reach the program
    /// in it. Terminals get Insert: Ctrl+C with nothing selected there stops
    /// the running program instead of copying, while Ctrl+Insert is copy in
    /// the console host, Windows Terminal and mintty alike.
    /// </summary>
    public static string? CopyKey(string windowClass) =>
        windowClass == "PuTTY" ? null : Terminals.Contains(windowClass) ? "INSERT" : "C";

    /// <summary>The paste shortcut for a window of this class: Shift+Insert in
    /// terminals, for the same reason as <see cref="CopyKey"/>.</summary>
    public static (string Modifier, string Key) PasteKeys(string windowClass) =>
        windowClass == "PuTTY" || Terminals.Contains(windowClass) ? ("SHIFT", "INSERT") : ("CTRL", "V");
}
