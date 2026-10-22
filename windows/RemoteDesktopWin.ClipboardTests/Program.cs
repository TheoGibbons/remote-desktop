using System.Globalization;
using System.Text;
using RemoteDesktopWin;

internal static class Program
{
    private static void Main()
    {
        CheckHtmlFragments();
        CheckSizeLimit();
        CheckShortcuts();
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
        Check(ClipboardFormat.CopyKey("Chrome_WidgetWin_1") == "C", "Ordinary windows copy with Ctrl+C");
        Check(ClipboardFormat.PasteKeys("Notepad") == ("CTRL", "V"), "Ordinary windows paste with Ctrl+V");
        foreach (var terminal in new[] { "ConsoleWindowClass", "CASCADIA_HOSTING_WINDOW_CLASS", "mintty", "VirtualConsoleClass" })
        {
            Check(ClipboardFormat.CopyKey(terminal) == "INSERT", $"{terminal} copies with Ctrl+Insert, never Ctrl+C");
            Check(ClipboardFormat.PasteKeys(terminal) == ("SHIFT", "INSERT"), $"{terminal} pastes with Shift+Insert");
        }
        Check(ClipboardFormat.CopyKey("PuTTY") == null, "PuTTY copies on select, so no key goes to the remote shell");
        Check(ClipboardFormat.PasteKeys("PuTTY") == ("SHIFT", "INSERT"), "PuTTY pastes with Shift+Insert");
        Console.WriteLine("PASS: terminals copy and paste without Ctrl+C or Ctrl+V");
    }

    private static void Check(bool condition, string message)
    {
        if (!condition) throw new Exception(message);
    }
}
