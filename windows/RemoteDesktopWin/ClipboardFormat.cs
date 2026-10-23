using System.Globalization;
using System.Net;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace RemoteDesktopWin;

/// <summary>What was on the clipboard, read in one sitting by ClipboardService.</summary>
/// <param name="Text">The plain text, or null if there was none or it was too long to read.</param>
/// <param name="TextLength">The text's length, known even when it was too long to read.</param>
/// <param name="Html">The fragment of the HTML version, when the copying app offered one.</param>
/// <param name="Files">Copied files that exist, when no more than MaxFiles paths were copied.</param>
/// <param name="Folders">How many of the copied paths are folders.</param>
/// <param name="Paths">How many paths were copied, files and folders alike.</param>
/// <param name="Image">The clipboard holds a picture.</param>
/// <param name="Sensitive">The copying app asked to keep this out of clipboard history.</param>
public sealed record ClipboardSnapshot(
    string? Text, int TextLength, string? Html,
    IReadOnlyList<string> Files, int Folders, int Paths,
    bool Image, bool Sensitive);

/// <summary>How to press copy or paste in a window: a shortcut, or a console
/// window's own menu command, which the program running in it never sees.</summary>
public readonly record struct ClipboardKeys(string? Modifier, string? Key, int ConsoleCommand)
{
    public static ClipboardKeys Shortcut(string modifier, string key) => new(modifier, key, 0);
    public static ClipboardKeys Console(int command) => new(null, null, command);
    /// <summary>Nothing to press: the window copies its selection as it is made.</summary>
    public static readonly ClipboardKeys None = new(null, null, 0);
}

/// <summary>
/// The pure parts of the clipboard messages (see PROTOCOL.md, "Clipboard"):
/// the limits both apps share, what a reply says about a clipboard, the
/// Windows "HTML Format" envelope, and which keys copy and paste where. No
/// WPF or Win32 here, so the tests can link this file on its own.
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

    /// <summary>Most copied paths offered for download at once. Every path
    /// goes in one control message, and each file is a separate transfer.</summary>
    public const int MaxFiles = 100;

    /// <summary>
    /// Fits a payload to <see cref="MaxChars"/>. The HTML goes first, since the
    /// plain text still pastes everywhere; text that is too long on its own is
    /// refused, with the reason in <c>Error</c>.
    /// </summary>
    public static (string? Text, string? Html, string? Error) Fit(string? text, string? html)
    {
        text ??= "";
        if (text.Length > MaxChars) return (null, null, TooLong(text.Length));
        if (html != null && text.Length + html.Length > MaxChars) html = null;
        return (text, html, null);
    }

    public static string TooLong(int length) =>
        $"That's too much text to copy ({length:N0} characters; the limit is {MaxChars:N0}). Save it to a file and use Files instead.";

    /// <summary>
    /// Fills a <c>clip-get-result</c> from what was read: the text and its HTML
    /// version, or the copied files, or an error saying why not.
    /// </summary>
    /// <param name="fresh">The copy changed the clipboard; always true when nothing was pressed.</param>
    /// <param name="pressed">A copy was pressed for this request, rather than the clipboard asked for as it is.</param>
    /// <param name="filesAllowed">Whether this PC's files may be fetched, which is what a copy of files is for.</param>
    public static void Describe(JsonObject reply, ClipboardSnapshot clip, bool fresh, bool pressed, bool filesAllowed)
    {
        reply["fresh"] = fresh;
        if (clip.Sensitive)
        {
            // A secret leaves only as what was just copied or what was asked
            // for by name, never as the leftover of a copy that didn't happen.
            if (pressed && !fresh)
            {
                reply["error"] = "The PC's clipboard didn't change, and what's on it is marked private, as a password manager does. Copy it again on the PC to send it.";
                return;
            }
            reply["sensitive"] = true;
        }
        if (clip.TextLength > MaxChars)
        {
            reply["error"] = TooLong(clip.TextLength);
            return;
        }
        var text = string.IsNullOrEmpty(clip.Text) && !string.IsNullOrEmpty(clip.Html) ? PlainText(clip.Html) : clip.Text;
        if (!string.IsNullOrEmpty(text))
        {
            var (fitText, fitHtml, error) = Fit(text, clip.Html);
            if (error != null) reply["error"] = error;
            else
            {
                reply["text"] = fitText;
                if (fitHtml != null) reply["html"] = fitHtml;
            }
            return;
        }
        if (clip.Paths > 0)
        {
            if (!filesAllowed)
                reply["error"] = "Those are copied files, and \"Browse and transfer files\" is turned off on the PC.";
            else if (clip.Paths > MaxFiles)
                reply["error"] = $"That's {clip.Paths:N0} files and folders. Copy at most {MaxFiles} at a time, or use Files.";
            else if (clip.Files.Count == 0)
                reply["error"] = "Folders can't be copied this way. Use Files to transfer them.";
            else
            {
                reply["files"] = new JsonArray(clip.Files.Select(path => (JsonNode?)path).ToArray());
                reply["folders"] = clip.Folders;
            }
            return;
        }
        reply["error"] = clip.Image ? "That's a picture. Only text and files can be copied for now."
            : !pressed ? "The PC's clipboard is empty."
            : fresh ? "The copy didn't put any text on the PC's clipboard."
            : "The PC's clipboard didn't change, and it's empty.";
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

    /// <summary>Windows line breaks. Phones send bare LF, which plain edit
    /// controls show as one long line.</summary>
    public static string ToCrLf(string text) => Regex.Replace(text, "\r\n|\r|\n", "\r\n");

    // The console host's system menu: Edit > Copy copies the selection and
    // does nothing without one; Edit > Paste handles VT input and bracketed paste.
    public const int ConsoleCopy = 0xFFF0, ConsolePaste = 0xFFF1;

    private static readonly HashSet<string> Terminals = new(StringComparer.Ordinal)
    {
        "CASCADIA_HOSTING_WINDOW_CLASS", // Windows Terminal
        "mintty",                        // Git Bash, Cygwin, MSYS2
        "VirtualConsoleClass",           // ConEmu
        "PuTTY",
        "KiTTY",
    };

    private static readonly HashSet<string> ShellViews = new(StringComparer.Ordinal)
    {
        "CabinetWClass", "ExploreWClass", // File Explorer
        "Progman", "WorkerW",             // the desktop
    };

    /// <summary>
    /// How to copy in a window of this class. Ctrl+Insert by default: it is
    /// copy in Office, browsers and Electron apps, VS Code, the JetBrains IDEs
    /// and terminals, and unlike Ctrl+C it never stops a running program when
    /// nothing is selected. That matters because an editor's integrated
    /// terminal has the same window class as the editor. File Explorer keeps
    /// Ctrl+C, which is harmless there; PuTTY copies on select, so nothing is
    /// pressed; a console window gets its own Edit > Copy.
    /// </summary>
    public static ClipboardKeys CopyKeys(string windowClass) => windowClass switch
    {
        "ConsoleWindowClass" => ClipboardKeys.Console(ConsoleCopy),
        "PuTTY" or "KiTTY" => ClipboardKeys.None,
        _ when ShellViews.Contains(windowClass) => ClipboardKeys.Shortcut("CTRL", "C"),
        _ => ClipboardKeys.Shortcut("CTRL", "INSERT"),
    };

    /// <summary>
    /// How to paste in a window of this class: Shift+Insert in terminals, where
    /// Ctrl+V reaches the program; Ctrl+V everywhere else, because VS Code's
    /// terminal pastes with Ctrl+V and not with Shift+Insert.
    /// </summary>
    public static ClipboardKeys PasteKeys(string windowClass) => windowClass switch
    {
        "ConsoleWindowClass" => ClipboardKeys.Console(ConsolePaste),
        _ when Terminals.Contains(windowClass) => ClipboardKeys.Shortcut("SHIFT", "INSERT"),
        _ => ClipboardKeys.Shortcut("CTRL", "V"),
    };
}
