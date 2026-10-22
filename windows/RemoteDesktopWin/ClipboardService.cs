using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json.Nodes;
using System.Windows;

namespace RemoteDesktopWin;

/// <summary>
/// Host side of the clipboard messages (see PROTOCOL.md, "Clipboard"). For
/// <c>clip-get</c> it presses copy in the window in front, waits for the
/// clipboard to change and sends back what is on it; for <c>clip-set</c> it
/// puts a peer's text on this PC's clipboard and, when asked, pastes it.
/// Runs on the UI thread, which WPF's clipboard needs. The waits are awaited,
/// so OnJson's blocking Dispatcher.Invoke still returns at once.
/// </summary>
internal sealed class ClipboardService
{
    #region Win32

    private const uint ProcessQueryLimitedInformation = 0x1000;
    private const uint TokenQuery = 0x0008;
    private const int TokenElevation = 20;
    private const uint DesktopReadObjects = 0x0001;
    private const int UoiName = 2;

    [DllImport("user32.dll")]
    private static extern uint GetClipboardSequenceNumber();

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetClassName(IntPtr window, StringBuilder name, int maxCount);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr OpenInputDesktop(uint flags, bool inherit, uint desiredAccess);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool GetUserObjectInformation(IntPtr obj, int index, StringBuilder info, int length, out int needed);

    [DllImport("user32.dll")]
    private static extern bool CloseDesktop(IntPtr desktop);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern uint RegisterClipboardFormat(string format);

    [DllImport("user32.dll")]
    private static extern bool IsClipboardFormatAvailable(uint format);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool OpenClipboard(IntPtr owner);

    [DllImport("user32.dll")]
    private static extern bool CloseClipboard();

    [DllImport("user32.dll")]
    private static extern IntPtr GetClipboardData(uint format);

    [DllImport("kernel32.dll")]
    private static extern IntPtr GlobalLock(IntPtr memory);

    [DllImport("kernel32.dll")]
    private static extern bool GlobalUnlock(IntPtr memory);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(uint access, bool inherit, uint processId);

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool GetTokenInformation(IntPtr token, int infoClass, out int info, int length, out int returned);

    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);

    #endregion

    internal const string ControlOffError = "\"View and control this computer\" is turned off on the PC.";

    private readonly Action<JsonObject> _send;
    // One copy at a time: overlapping requests would read each other's result.
    private readonly SemaphoreSlim _busy = new(1, 1);

    public ClipboardService(Action<JsonObject> send) => _send = send;

    /// <summary>A string field, or null when it is missing, JSON null or not a string.</summary>
    internal static string? Str(JsonNode? node) =>
        node is JsonValue value && value.TryGetValue<string>(out var s) ? s : null;

    private static JsonObject Reply(string type, string peerId, string? reqId) =>
        new() { ["type"] = type, ["to"] = peerId, ["reqId"] = reqId };

    /// <summary>Answers a <c>clip-get</c> from an approved peer while remote control is off.</summary>
    public void RefuseGet(string peerId, string? reqId)
    {
        var reply = Reply("clip-get-result", peerId, reqId);
        reply["error"] = ControlOffError;
        _send(reply);
    }

    /// <summary>Copies the selection in the window in front and replies with
    /// the clipboard. The caller has checked trust and control consent.</summary>
    public async Task HandleGetAsync(string peerId, string reqId)
    {
        JsonObject reply;
        await _busy.WaitAsync();
        try
        {
            reply = await CopyAsync(peerId, reqId);
        }
        catch (Exception ex)
        {
            reply = Reply("clip-get-result", peerId, reqId);
            reply["error"] = "Couldn't read the PC's clipboard: " + ex.Message;
        }
        finally
        {
            _busy.Release();
        }
        _send(reply);
    }

    private static async Task<JsonObject> CopyAsync(string peerId, string reqId)
    {
        var reply = Reply("clip-get-result", peerId, reqId);
        if (InputProblem(out var windowClass) is { } problem)
        {
            reply["error"] = problem;
            return reply;
        }
        // A window that copies on select already has the selection on the clipboard.
        bool fresh = true;
        if (ClipboardFormat.CopyKey(windowClass) is { } key)
        {
            uint before = GetClipboardSequenceNumber();
            InputInjector.Shortcut("CTRL", key);
            fresh = await WaitForChangeAsync(before);
        }
        Read(reply, fresh);
        return reply;
    }

    /// <summary>Waits for the copy to land. False means the clipboard never
    /// changed: nothing was selected, or the app ignored the shortcut.</summary>
    private static async Task<bool> WaitForChangeAsync(uint before)
    {
        for (int waited = 0; waited < 1500; waited += 25)
        {
            await Task.Delay(25);
            if (GetClipboardSequenceNumber() == before) continue;
            // Apps write their formats one after another; let the rest arrive.
            await Task.Delay(50);
            return true;
        }
        return false;
    }

    /// <summary>Fills a <c>clip-get-result</c> from the clipboard: text and its
    /// HTML version, or the paths of copied files, or an error saying why not.</summary>
    private static void Read(JsonObject reply, bool fresh)
    {
        var data = Clipboard.GetDataObject();
        var text = Get(data, DataFormats.UnicodeText) as string;
        var html = Get(data, DataFormats.Html) is string cfHtml ? ClipboardFormat.HtmlFragment(cfHtml) : null;
        reply["fresh"] = fresh;
        if (IsSensitive()) reply["sensitive"] = true;

        if (string.IsNullOrEmpty(text) && Get(data, DataFormats.FileDrop) is string[] { Length: > 0 } paths)
        {
            // Only files can be fetched with fs-get; folders are counted so the viewer can say so.
            reply["files"] = new JsonArray(paths.Where(File.Exists).Select(p => (JsonNode?)p).ToArray());
            reply["folders"] = paths.Count(Directory.Exists);
            return;
        }
        if (string.IsNullOrEmpty(text) && !string.IsNullOrEmpty(html)) text = ClipboardFormat.PlainText(html);
        if (string.IsNullOrEmpty(text))
        {
            // Ask only whether there is one: rendering a screenshot just to say no is waste.
            reply["error"] = Has(data, DataFormats.Bitmap)
                ? "That's an image. Only text and files can be copied for now."
                : fresh ? "The copy didn't put any text on the PC's clipboard."
                : "Nothing is selected on the PC, and its clipboard is empty.";
            return;
        }
        var (fitText, fitHtml, error) = ClipboardFormat.Fit(text, html);
        if (error != null)
        {
            reply["error"] = error;
            return;
        }
        reply["text"] = fitText;
        if (fitHtml != null) reply["html"] = fitHtml;
    }

    private static object? Get(IDataObject? data, string format)
    {
        try
        {
            return Has(data, format) ? data!.GetData(format) : null;
        }
        catch (ExternalException)
        {
            // The source app renders some formats only when asked, and can fail to.
            return null;
        }
    }

    private static bool Has(IDataObject? data, string format)
    {
        try { return data?.GetDataPresent(format) == true; }
        catch (ExternalException) { return false; }
    }

    /// <summary>
    /// Whether the copy is marked secret. Password managers set these formats
    /// so clipboard history and cloud sync skip it; the phone uses the hint to
    /// keep the text out of its copy preview.
    /// </summary>
    private static bool IsSensitive()
    {
        if (IsClipboardFormatAvailable(RegisterClipboardFormat("ExcludeClipboardContentFromMonitorProcessing"))) return true;
        uint history = RegisterClipboardFormat("CanIncludeInClipboardHistory");
        if (!IsClipboardFormatAvailable(history)) return false;
        // A DWORD, 0 to keep the copy out of history. Assume that if it can't be
        // read. Clipboard watchers such as Windows' own history hold the
        // clipboard open for a moment after every change, so retry briefly.
        bool opened = false;
        for (int attempt = 0; attempt < 10 && !(opened = OpenClipboard(IntPtr.Zero)); attempt++) Thread.Sleep(20);
        if (!opened) return true;
        try
        {
            var handle = GetClipboardData(history);
            var value = handle == IntPtr.Zero ? IntPtr.Zero : GlobalLock(handle);
            if (value == IntPtr.Zero) return true;
            try { return Marshal.ReadInt32(value) == 0; }
            finally { GlobalUnlock(handle); }
        }
        finally { CloseClipboard(); }
    }

    /// <summary>Puts a peer's text on this PC's clipboard and, for <c>paste</c>,
    /// pastes it into the window in front. The caller has checked trust.</summary>
    public void HandleSet(JsonNode msg, string peerId, string peerName, bool controlAllowed)
    {
        var reply = Reply("clip-set-result", peerId, Str(msg["reqId"]));
        reply["error"] = Set(msg, peerName, controlAllowed);
        _send(reply);
    }

    private static string? Set(JsonNode msg, string peerName, bool controlAllowed)
    {
        bool paste = msg["paste"] is JsonValue flag && flag.TryGetValue<bool>(out var value) && value;
        // Pasting presses keys, so it needs the same consent as any other input.
        if (paste && !controlAllowed) return ControlOffError;
        var (text, html, error) = ClipboardFormat.Fit(Str(msg["text"]), Str(msg["html"]));
        if (error != null) return error;
        if (string.IsNullOrEmpty(text)) return "There was no text to copy.";
        if (!Write(text, html, out error)) return error;
        if (!paste)
        {
            Toast.Show($"Copied from {peerName} ({text.Length:N0} characters)");
            return null;
        }
        // The text stays on the clipboard either way, so it can still be pasted by hand.
        if (InputProblem(out var windowClass) is { } problem)
            return "It's on the PC's clipboard, but couldn't be pasted. " + problem;
        var (modifier, key) = ClipboardFormat.PasteKeys(windowClass);
        InputInjector.Shortcut(modifier, key);
        return null;
    }

    /// <summary>Puts text, and its HTML version when there is one, on this
    /// PC's clipboard. Used for copies arriving here, as host and as viewer.</summary>
    public static bool Write(string text, string? html, out string? error)
    {
        try
        {
            var data = new DataObject();
            data.SetData(DataFormats.UnicodeText, text);
            if (!string.IsNullOrEmpty(html)) data.SetData(DataFormats.Html, ClipboardFormat.HtmlEnvelope(html));
            // copy: true renders every format now, so the text outlives this app.
            Clipboard.SetDataObject(data, copy: true);
            error = null;
            return true;
        }
        catch (ExternalException ex)
        {
            error = "Couldn't write to the PC's clipboard. Another app may be using it: " + ex.Message;
            return false;
        }
    }

    /// <summary>
    /// Why keys pressed now would not reach the window in front, or null if
    /// they would. Windows drops input injected into a higher-integrity window
    /// without any error (UIPI), so that has to be found out up front.
    /// </summary>
    private static string? InputProblem(out string windowClass)
    {
        windowClass = "";
        if (!InputDesktopIsDefault()) return "The PC is locked or showing a secure screen.";
        var window = GetForegroundWindow();
        if (window == IntPtr.Zero) return "No window is in front on the PC. Click the one you want first.";
        var name = new StringBuilder(256);
        if (GetClassName(window, name, name.Capacity) > 0) windowClass = name.ToString();
        if (!MainWindow.IsElevated() && RunsElevated(window))
            return "The window in front runs as administrator. Use Switch to administrator in Remote Desktop on the PC, then try again.";
        return null;
    }

    private static bool InputDesktopIsDefault()
    {
        // The lock screen and UAC prompts use the secure desktop, which a user process can't open.
        var desktop = OpenInputDesktop(0, false, DesktopReadObjects);
        if (desktop == IntPtr.Zero) return false;
        try
        {
            var name = new StringBuilder(64);
            return GetUserObjectInformation(desktop, UoiName, name, name.Capacity * 2, out _) &&
                   name.ToString().Equals("Default", StringComparison.OrdinalIgnoreCase);
        }
        finally { CloseDesktop(desktop); }
    }

    private static bool RunsElevated(IntPtr window)
    {
        GetWindowThreadProcessId(window, out uint pid);
        var process = OpenProcess(ProcessQueryLimitedInformation, false, pid);
        // A process this app can't even query is above it, which is what matters here.
        if (process == IntPtr.Zero) return true;
        try
        {
            if (!OpenProcessToken(process, TokenQuery, out var token)) return true;
            try { return GetTokenInformation(token, TokenElevation, out int elevated, sizeof(int), out _) && elevated != 0; }
            finally { CloseHandle(token); }
        }
        finally { CloseHandle(process); }
    }
}
