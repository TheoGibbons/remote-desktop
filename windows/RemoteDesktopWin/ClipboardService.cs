using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json.Nodes;
using System.Windows;

namespace RemoteDesktopWin;

/// <summary>
/// The clipboard messages (see PROTOCOL.md, "Clipboard"). As host, for
/// <c>clip-get</c> it presses copy in the window in front, waits for the
/// clipboard to change and sends back what is on it; for <c>clip-set</c> it
/// puts a peer's text on the clipboard and, when asked, pastes it. The PC
/// viewer uses its read and write for the same job in the other direction.
///
/// Requests take turns on the UI thread, but the clipboard itself is only
/// touched on short-lived STA threads with a time limit. Apps render some
/// formats only when they are read (Excel, an RDP window fetching over the
/// network), and a hung one would otherwise freeze the UI thread and, through
/// OnJson's blocking Dispatcher.Invoke, every message from the relay.
/// </summary>
internal sealed class ClipboardService
{
    #region Win32

    private const uint ProcessQueryLimitedInformation = 0x1000;
    private const uint TokenQuery = 0x0008;
    private const int TokenElevation = 20;
    private const uint DesktopReadObjects = 0x0001;
    private const int UoiName = 2;
    private const uint CfBitmap = 2, CfDib = 8, CfUnicodeText = 13, CfHdrop = 15, CfDibV5 = 17;
    private const int WmSysCommand = 0x0112;

    [DllImport("user32.dll")]
    private static extern uint GetClipboardSequenceNumber();

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetClassName(IntPtr window, StringBuilder name, int maxCount);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);

    [DllImport("user32.dll")]
    private static extern bool PostMessage(IntPtr window, int message, IntPtr wParam, IntPtr lParam);

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

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern uint DragQueryFile(IntPtr drop, uint index, StringBuilder? file, uint length);

    [DllImport("kernel32.dll")]
    private static extern IntPtr GlobalLock(IntPtr memory);

    [DllImport("kernel32.dll")]
    private static extern bool GlobalUnlock(IntPtr memory);

    [DllImport("kernel32.dll")]
    private static extern UIntPtr GlobalSize(IntPtr memory);

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
    private const string LockedError = "The PC is locked or showing a secure screen.";
    private static readonly TimeSpan ClipboardTimeout = TimeSpan.FromSeconds(5);
    // How long a copy gets to land before the clipboard counts as unchanged.
    private static readonly TimeSpan CopyWait = TimeSpan.FromSeconds(1.5);
    // Clipboard watchers, Windows' own history among them, open the clipboard
    // just after it changes, and an app pasting in that moment finds it locked
    // and pastes nothing.
    private static readonly TimeSpan PasteSettle = TimeSpan.FromMilliseconds(150);

    private static readonly uint HtmlFormat = RegisterClipboardFormat("HTML Format");
    private static readonly uint ExcludeFormat = RegisterClipboardFormat("ExcludeClipboardContentFromMonitorProcessing");
    private static readonly uint HistoryFormat = RegisterClipboardFormat("CanIncludeInClipboardHistory");

    private readonly Action<JsonObject> _send;
    private readonly Func<string, bool> _controlAllowed;
    private readonly Func<bool> _filesAllowed;
    // One request at a time: a paste landing during a copy's wait would be
    // read back as that copy.
    private readonly SemaphoreSlim _busy = new(1, 1);

    /// <param name="controlAllowed">Whether a peer may control this PC right now. Asked
    /// again when a request's turn comes, since the switch may change while it waits.</param>
    /// <param name="filesAllowed">Whether this PC's files may be fetched.</param>
    public ClipboardService(Action<JsonObject> send, Func<string, bool> controlAllowed, Func<bool> filesAllowed)
    {
        _send = send;
        _controlAllowed = controlAllowed;
        _filesAllowed = filesAllowed;
    }

    /// <summary>A string field, or null when it is missing, JSON null, not a
    /// string, or not valid UTF-16 (a lone surrogate escape).</summary>
    internal static string? Str(JsonNode? node)
    {
        try { return node is JsonValue value && value.TryGetValue<string>(out var s) ? s : null; }
        catch (InvalidOperationException) { return null; }
    }

    internal static bool Flag(JsonNode? node, bool fallback) =>
        node is JsonValue value && value.TryGetValue<bool>(out var b) ? b : fallback;

    private static JsonObject Reply(string type, string peerId, string? reqId) =>
        new() { ["type"] = type, ["to"] = peerId, ["reqId"] = reqId };

    private static string Explain(Exception ex) => ex switch
    {
        TimeoutException => "Reading the PC's clipboard took too long. The app that copied may not be responding.",
        ExternalException or ClipboardBusyException => "Another app is holding the PC's clipboard. Try again.",
        _ => "The PC's clipboard couldn't be used: " + ex.Message,
    };

    /// <summary>Answers <c>clip-get</c> from an approved peer: presses copy in
    /// the window in front, unless <paramref name="press"/> asks for the
    /// clipboard as it is, and replies with what is on it.</summary>
    public async Task HandleGetAsync(string peerId, string? reqId, bool press)
    {
        JsonObject reply;
        await _busy.WaitAsync();
        try
        {
            reply = await CopyAsync(peerId, reqId, press);
        }
        catch (Exception ex)
        {
            reply = Reply("clip-get-result", peerId, reqId);
            reply["error"] = Explain(ex);
        }
        finally
        {
            _busy.Release();
        }
        _send(reply);
    }

    private async Task<JsonObject> CopyAsync(string peerId, string? reqId, bool press)
    {
        var reply = Reply("clip-get-result", peerId, reqId);
        if (!_controlAllowed(peerId))
        {
            reply["error"] = ControlOffError;
            return reply;
        }
        if (!InputDesktopIsDefault())
        {
            reply["error"] = LockedError;
            return reply;
        }
        bool fresh = true;
        if (press)
        {
            if (Foreground(out var window, out var windowClass) is { } problem)
            {
                reply["error"] = problem;
                return reply;
            }
            var keys = ClipboardFormat.CopyKeys(windowClass);
            // A window that copies on select gives no sign whether this is new.
            if (keys == ClipboardKeys.None) fresh = false;
            else
            {
                uint before = GetClipboardSequenceNumber();
                Press(window, keys);
                fresh = await WaitForChangeAsync(before);
            }
        }
        var clip = await ReadAsync();
        ClipboardFormat.Describe(reply, clip, fresh, press, _filesAllowed());
        return reply;
    }

    /// <summary>Waits for the copy to land. False means the clipboard never
    /// changed: nothing was selected, or the app ignored the shortcut.</summary>
    private static async Task<bool> WaitForChangeAsync(uint before)
    {
        var waited = Stopwatch.StartNew();
        while (waited.Elapsed < CopyWait)
        {
            await Task.Delay(25);
            if (GetClipboardSequenceNumber() == before) continue;
            // Apps write their formats one after another; let the rest arrive.
            await Task.Delay(50);
            return true;
        }
        return false;
    }

    /// <summary>Answers <c>clip-set</c> from an approved peer: puts its text on
    /// this PC's clipboard and, for <c>paste</c>, pastes it into the window in front.</summary>
    public async Task HandleSetAsync(JsonNode msg, string peerId, string peerName)
    {
        var reply = Reply("clip-set-result", peerId, Str(msg["reqId"]));
        await _busy.WaitAsync();
        try
        {
            reply["error"] = await SetAsync(msg, peerId, peerName);
        }
        catch (Exception ex)
        {
            reply["error"] = Explain(ex);
        }
        finally
        {
            _busy.Release();
        }
        _send(reply);
    }

    private async Task<string?> SetAsync(JsonNode msg, string peerId, string peerName)
    {
        // Even without pasting, this changes what the PC pastes next, so it
        // takes the same switch as input, as that switch's caption says.
        if (!_controlAllowed(peerId)) return ControlOffError;
        // Nobody at a locked PC would see it arrive.
        if (!InputDesktopIsDefault()) return LockedError;
        var (text, html, error) = ClipboardFormat.Fit(Str(msg["text"]), Str(msg["html"]));
        if (error != null) return error;
        if (string.IsNullOrEmpty(text)) return "There was no text to copy.";
        await WriteAsync(text, html, Flag(msg["sensitive"], false));
        if (!Flag(msg["paste"], false))
        {
            Toast.Show($"Copied from {peerName} ({text.Length:N0} characters)");
            return null;
        }
        await Task.Delay(PasteSettle);
        // The text stays on the clipboard either way, so it can still be pasted by hand.
        if (Foreground(out var window, out var windowClass) is { } problem)
            return "It's on the PC's clipboard, but couldn't be pasted. " + problem;
        Press(window, ClipboardFormat.PasteKeys(windowClass));
        return null;
    }

    private static void Press(IntPtr window, ClipboardKeys keys)
    {
        if (keys.ConsoleCommand != 0) PostMessage(window, WmSysCommand, keys.ConsoleCommand, IntPtr.Zero);
        else if (keys.Key != null) InputInjector.Shortcut(keys.Modifier!, keys.Key);
    }

    /// <summary>What is on this PC's clipboard.</summary>
    public static Task<ClipboardSnapshot> ReadAsync() => OnClipboardThread(Read);

    /// <summary>Puts text, and its HTML version when there is one, on this PC's
    /// clipboard. A sensitive copy is kept out of clipboard history and cloud sync.</summary>
    public static Task WriteAsync(string text, string? html, bool sensitive) =>
        OnClipboardThread(() => Write(text, html, sensitive));

    private static Task<T> OnClipboardThread<T>(Func<T> work)
    {
        var done = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() =>
        {
            try { done.SetResult(work()); }
            catch (Exception ex) { done.SetException(ex); }
        }) { IsBackground = true, Name = "Clipboard" };
        // WPF's clipboard, used for writing, needs OLE, and OLE needs an STA thread.
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        // A thread stuck on an app that never renders is abandoned, not waited for.
        return done.Task.WaitAsync(ClipboardTimeout);
    }

    private static bool Write(string text, string? html, bool sensitive)
    {
        var data = new DataObject();
        data.SetData(DataFormats.UnicodeText, ClipboardFormat.ToCrLf(text));
        if (!string.IsNullOrEmpty(html)) data.SetData(DataFormats.Html, ClipboardFormat.HtmlEnvelope(html));
        if (sensitive)
        {
            // What password managers set, so Windows keeps the copy out of
            // clipboard history and cloud sync, and clipboard tools leave it alone.
            data.SetData("ExcludeClipboardContentFromMonitorProcessing", new MemoryStream(new byte[4]));
            data.SetData("CanIncludeInClipboardHistory", new MemoryStream(new byte[4]));
            data.SetData("CanUploadToCloudClipboard", new MemoryStream(new byte[4]));
        }
        // copy: true renders every format now, so the copy outlives this thread.
        Clipboard.SetDataObject(data, copy: true);
        return true;
    }

    private sealed class ClipboardBusyException : Exception;

    /// <summary>
    /// Reads every format in one sitting, and again if the clipboard changed
    /// meanwhile, so the text, HTML and files describe the same copy. Sizes are
    /// checked before anything is read, so a huge copy is refused rather than
    /// rendered.
    /// </summary>
    private static ClipboardSnapshot Read()
    {
        for (int attempt = 0; ; attempt++)
        {
            uint before = GetClipboardSequenceNumber();
            Open();
            string? text, html = null;
            int textLength;
            string[] paths = [];
            int pathCount = 0;
            bool image, sensitive;
            try
            {
                text = ReadText(out textLength);
                // The HTML version is no use when the text itself is too long.
                if (textLength <= ClipboardFormat.MaxChars) html = ReadHtml();
                if (GetClipboardData(CfHdrop) is var drop && drop != IntPtr.Zero)
                {
                    pathCount = (int)DragQueryFile(drop, uint.MaxValue, null, 0);
                    if (pathCount <= ClipboardFormat.MaxFiles) paths = ReadPaths(drop, pathCount);
                }
                image = IsClipboardFormatAvailable(CfBitmap) || IsClipboardFormatAvailable(CfDib) ||
                        IsClipboardFormatAvailable(CfDibV5);
                sensitive = IsSensitive();
            }
            finally
            {
                CloseClipboard();
            }
            if (GetClipboardSequenceNumber() != before && attempt < 2) continue;
            // Disk checks come after closing: a slow network path mustn't hold the clipboard.
            return new ClipboardSnapshot(text, textLength, html,
                paths.Where(File.Exists).ToArray(), paths.Count(Directory.Exists), pathCount, image, sensitive);
        }
    }

    private static void Open()
    {
        // Clipboard watchers, Windows' own history among them, hold it for a
        // moment after every change.
        for (int attempt = 0; attempt < 20; attempt++)
        {
            if (OpenClipboard(IntPtr.Zero)) return;
            Thread.Sleep(25);
        }
        throw new ClipboardBusyException();
    }

    private static string? ReadText(out int length)
    {
        length = 0;
        var handle = GetClipboardData(CfUnicodeText);
        if (handle == IntPtr.Zero) return null;
        long chars = (long)GlobalSize(handle) / 2;
        // Apps size the block to the text, give or take; anything well past the
        // limit is refused on its size without being read.
        if (chars > 4L * ClipboardFormat.MaxChars)
        {
            length = (int)Math.Min(int.MaxValue, chars - 1);
            return null;
        }
        var memory = GlobalLock(handle);
        if (memory == IntPtr.Zero) return null;
        try
        {
            var text = Marshal.PtrToStringUni(memory, (int)chars);
            int end = text.IndexOf('\0');
            if (end >= 0) text = text[..end];
            length = text.Length;
            return text;
        }
        finally { GlobalUnlock(handle); }
    }

    private static string? ReadHtml()
    {
        var handle = GetClipboardData(HtmlFormat);
        if (handle == IntPtr.Zero) return null;
        long size = (long)GlobalSize(handle);
        // UTF-8 with a header; well past the limit is no use either way.
        if (size > 4L * ClipboardFormat.MaxChars) return null;
        var memory = GlobalLock(handle);
        if (memory == IntPtr.Zero) return null;
        try
        {
            var bytes = new byte[size];
            Marshal.Copy(memory, bytes, 0, bytes.Length);
            int end = Array.IndexOf(bytes, (byte)0);
            return ClipboardFormat.HtmlFragment(Encoding.UTF8.GetString(bytes, 0, end >= 0 ? end : bytes.Length));
        }
        finally { GlobalUnlock(handle); }
    }

    private static string[] ReadPaths(IntPtr drop, int count)
    {
        var paths = new string[count];
        for (uint i = 0; i < count; i++)
        {
            var path = new StringBuilder((int)DragQueryFile(drop, i, null, 0) + 1);
            DragQueryFile(drop, i, path, (uint)path.Capacity);
            paths[i] = path.ToString();
        }
        return paths;
    }

    /// <summary>
    /// Whether the copy is marked secret. Password managers set these formats
    /// so clipboard history and cloud sync skip it; the phone uses the hint to
    /// keep the text out of its copy preview. Read with the clipboard open.
    /// </summary>
    private static bool IsSensitive()
    {
        if (IsClipboardFormatAvailable(ExcludeFormat)) return true;
        var handle = GetClipboardData(HistoryFormat);
        if (handle == IntPtr.Zero) return false;
        // A DWORD, 0 to keep the copy out of history. Assume that if it can't be read.
        var memory = GlobalLock(handle);
        if (memory == IntPtr.Zero) return true;
        try { return Marshal.ReadInt32(memory) == 0; }
        finally { GlobalUnlock(handle); }
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

    /// <summary>
    /// The window that keys pressed now would reach, or why they wouldn't.
    /// Windows drops input injected into a higher-integrity window without any
    /// error (UIPI), so that has to be found out up front.
    /// </summary>
    private static string? Foreground(out IntPtr window, out string windowClass)
    {
        windowClass = "";
        window = GetForegroundWindow();
        if (window == IntPtr.Zero) return "No window is in front on the PC. Click the one you want first.";
        var name = new StringBuilder(256);
        if (GetClassName(window, name, name.Capacity) > 0) windowClass = name.ToString();
        if (!MainWindow.IsElevated() && RunsElevated(window))
            return "The window in front runs as administrator, so Remote Desktop can't press keys in it. " +
                   "Run Remote Desktop as administrator on the PC; someone there has to approve that.";
        return null;
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
