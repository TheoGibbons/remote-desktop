using System.Globalization;
using System.Text;
using System.Text.Json.Nodes;
using RemoteDesktopWin;

internal static class Program
{
    private static void Main()
    {
        CheckHtmlFragments();
        CheckSizeLimit();
        CheckShortcuts();
        CheckReplies();
        CheckLineEndings();
    }

    // CF_HTML offsets count UTF-8 bytes, so anything past ASCII is where a
    // reader counting chars would cut the fragment in the wrong place.
    private static void CheckHtmlFragments()
    {
        var samples = new[]
        {
            "<b>plain</b>",
            "<span style=\"color: rgb(255, 0, 0);\">Grüße</span>",
            "😀 <i>emoji</i>",
            "中文 <u>text</u>",
            "",
        };
        foreach (var fragment in samples)
        {
            Check(ClipboardFormat.HtmlFragment(ClipboardFormat.HtmlEnvelope(fragment)) == fragment,
                $"\"{fragment}\" survives the envelope");
            // Chrome lays the page out differently from our envelope; the
            // offsets, not the layout, are what the reader must follow.
            Check(ClipboardFormat.HtmlFragment(ChromeStyle(fragment)) == fragment,
                $"\"{fragment}\" is read back out of a browser's copy");
        }

        Check(ClipboardFormat.HtmlFragment("<b>no header</b>") == null, "A payload without a header is rejected");
        Check(ClipboardFormat.HtmlFragment("Version:0.9\r\nStartFragment:0000000999\r\nEndFragment:0000001000\r\n<b>x</b>") == null,
            "Offsets past the end are rejected");
        Check(ClipboardFormat.HtmlFragment("Version:0.9\r\nStartFragment:0000000060\r\nEndFragment:0000000059\r\n<b>x</b>") == null,
            "An end before the start is rejected");
        // A page that mentions the header names must not be mistaken for the header.
        Check(ClipboardFormat.HtmlFragment("Version:0.9\r\n<p>StartFragment:0000000001 EndFragment:0000000005</p>") == null,
            "Header names inside the markup are ignored");

        Check(ClipboardFormat.PlainText("<p>One</p><p>Two &amp; three</p>") == "One\nTwo & three",
            "HTML without plain text still yields readable text");
        Console.WriteLine("PASS: CF_HTML fragments round-trip, including non-ASCII, and bad headers are rejected");
    }

    private static string ChromeStyle(string fragment)
    {
        const string header = "Version:0.9\r\nStartHTML:{0:D10}\r\nEndHTML:{1:D10}\r\n" +
                              "StartFragment:{2:D10}\r\nEndFragment:{3:D10}\r\nSourceURL:https://example.com/\r\n";
        const string before = "<html>\r\n<body>\r\n<!--StartFragment-->";
        const string after = "<!--EndFragment-->\r\n</body>\r\n</html>";
        int startHtml = Encoding.UTF8.GetByteCount(string.Format(CultureInfo.InvariantCulture, header, 0, 0, 0, 0));
        int start = startHtml + Encoding.UTF8.GetByteCount(before);
        int end = start + Encoding.UTF8.GetByteCount(fragment);
        return string.Format(CultureInfo.InvariantCulture, header, startHtml, end + Encoding.UTF8.GetByteCount(after), start, end) +
               before + fragment + after;
    }

    private static void CheckSizeLimit()
    {
        int max = ClipboardFormat.MaxChars;
        var (text, html, error) = ClipboardFormat.Fit("text", "<b>text</b>");
        Check(text == "text" && html == "<b>text</b>" && error == null, "Small copies keep their HTML");

        (text, html, error) = ClipboardFormat.Fit(new string('a', max - 5), "<b>too much</b>");
        Check(text!.Length == max - 5 && html == null && error == null, "The HTML goes first when a copy is too big");

        (text, html, error) = ClipboardFormat.Fit(new string('a', max), null);
        Check(text!.Length == max && error == null, "Text right at the limit is kept");

        (text, html, error) = ClipboardFormat.Fit(new string('a', max + 1), "<b>x</b>");
        Check(text == null && html == null && error != null && error.Contains(max.ToString("N0")),
            "Text over the limit is refused, saying what the limit is");

        Check(ClipboardFormat.Fit(null, null).Text == "", "Missing text counts as empty");
        Console.WriteLine("PASS: clipboard payloads drop HTML first and refuse oversized text");
    }

    private static void CheckShortcuts()
    {
        var ctrlInsert = ClipboardKeys.Shortcut("CTRL", "INSERT");
        // VS Code and the JetBrains IDEs share one window class between the
        // editor and its terminal, where Ctrl+C with nothing selected stops
        // the running program. Nothing but File Explorer gets Ctrl+C.
        foreach (var window in new[] { "Chrome_WidgetWin_1", "SunAwtFrame", "Notepad", "OpusApp", "CASCADIA_HOSTING_WINDOW_CLASS", "mintty", "" })
            Check(ClipboardFormat.CopyKeys(window) == ctrlInsert, $"{window} copies with Ctrl+Insert");
        foreach (var shell in new[] { "CabinetWClass", "Progman", "WorkerW" })
            Check(ClipboardFormat.CopyKeys(shell) == ClipboardKeys.Shortcut("CTRL", "C"), $"{shell} copies files with Ctrl+C");
        Check(ClipboardFormat.CopyKeys("ConsoleWindowClass") == ClipboardKeys.Console(ClipboardFormat.ConsoleCopy),
            "A console window copies through its own menu, so no key reaches the program");
        Check(ClipboardFormat.CopyKeys("PuTTY") == ClipboardKeys.None, "PuTTY copies on select, so no key goes to the remote shell");

        // VS Code's terminal pastes with Ctrl+V but not Shift+Insert.
        Check(ClipboardFormat.PasteKeys("Chrome_WidgetWin_1") == ClipboardKeys.Shortcut("CTRL", "V"), "Ordinary windows paste with Ctrl+V");
        foreach (var terminal in new[] { "CASCADIA_HOSTING_WINDOW_CLASS", "mintty", "VirtualConsoleClass", "PuTTY", "KiTTY" })
            Check(ClipboardFormat.PasteKeys(terminal) == ClipboardKeys.Shortcut("SHIFT", "INSERT"), $"{terminal} pastes with Shift+Insert");
        Check(ClipboardFormat.PasteKeys("ConsoleWindowClass") == ClipboardKeys.Console(ClipboardFormat.ConsolePaste),
            "A console window pastes through its own menu");
        Console.WriteLine("PASS: copying never presses Ctrl+C outside File Explorer, and terminals paste their own way");
    }

    private static ClipboardSnapshot Clip(string? text = null, string? html = null, string[]? files = null,
        int folders = 0, int paths = -1, bool image = false, bool sensitive = false, int textLength = -1) =>
        new(text, textLength >= 0 ? textLength : text?.Length ?? 0, html, files ?? [], folders,
            paths >= 0 ? paths : (files?.Length ?? 0) + folders, image, sensitive);

    private static JsonObject Describe(ClipboardSnapshot clip, bool fresh = true, bool pressed = true, bool filesAllowed = true)
    {
        var reply = new JsonObject();
        ClipboardFormat.Describe(reply, clip, fresh, pressed, filesAllowed);
        return reply;
    }

    private static string? Field(JsonObject reply, string name) => reply[name]?.ToString();

    private static void CheckReplies()
    {
        var copied = Describe(Clip("hello", "<b>hello</b>"));
        Check(Field(copied, "text") == "hello" && Field(copied, "html") == "<b>hello</b>" && Field(copied, "error") == null,
            "A copy carries its text and HTML");
        Check(Field(Describe(Clip(html: "<p>One</p><p>Two</p>")), "text") == "One\nTwo", "HTML without plain text still gives text");

        // Secrets: sent when just copied or asked for by name, flagged either way,
        // and never as the leftover of a copy that didn't happen.
        var secret = Clip("hunter2", sensitive: true);
        Check(Field(Describe(secret), "sensitive") == "true" && Field(Describe(secret), "text") == "hunter2",
            "A fresh secret is sent, marked sensitive");
        Check(Field(Describe(secret, fresh: true, pressed: false), "text") == "hunter2", "A secret asked for by name is sent");
        var stale = Describe(secret, fresh: false);
        Check(Field(stale, "text") == null && Field(stale, "error") != null, "An old secret is not sent when a copy changed nothing");
        var leftover = Describe(Clip("old text"), fresh: false);
        Check(Field(leftover, "text") == "old text" && Field(leftover, "fresh") == "false",
            "Ordinary leftovers are sent, marked not fresh, for the viewer to confirm");

        var huge = Describe(Clip(null, textLength: ClipboardFormat.MaxChars + 1, html: "<b>x</b>"));
        Check(Field(huge, "text") == null && Field(huge, "error")!.Contains(ClipboardFormat.MaxChars.ToString("N0")),
            "Text too long to read is refused on its length");

        var files = Describe(Clip(files: [@"C:\a.txt", @"C:\b.txt"], folders: 1));
        Check(files["files"]!.AsArray().Count == 2 && Field(files, "folders") == "1", "Copied files are offered, folders counted");
        Check(Field(Describe(Clip(files: [@"C:\a.txt"]), filesAllowed: false), "error")!.Contains("Browse and transfer files"),
            "Files aren't offered while file access is off");
        Check(Field(Describe(Clip(paths: ClipboardFormat.MaxFiles + 1)), "error")!.Contains("at most"), "Too many paths are refused");
        Check(Field(Describe(Clip(folders: 2)), "error")!.Contains("Folders"), "Folders alone are refused");
        Check(Field(Describe(Clip(image: true)), "error")!.Contains("picture"), "A picture says so");
        Check(Field(Describe(Clip(), fresh: false), "error")!.Contains("didn't change"), "An unchanged, empty clipboard says so");
        Check(Field(Describe(Clip(), pressed: false), "error") == "The PC's clipboard is empty.", "An empty clipboard asked for by name says so");
        Console.WriteLine("PASS: replies send text, files or a reason, and never an old secret");
    }

    private static void CheckLineEndings()
    {
        Check(ClipboardFormat.ToCrLf("a\nb\r\nc\rd") == "a\r\nb\r\nc\r\nd", "Every kind of line break becomes CRLF");
        Check(ClipboardFormat.ToCrLf("one line") == "one line", "Text without line breaks is unchanged");
        Console.WriteLine("PASS: text from a phone gets Windows line breaks");
    }

    private static void Check(bool condition, string message)
    {
        if (!condition) throw new Exception(message);
    }
}
