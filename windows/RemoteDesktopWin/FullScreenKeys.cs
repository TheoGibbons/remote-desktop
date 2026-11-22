using System.ComponentModel;
using System.Runtime.InteropServices;

namespace RemoteDesktopWin;

/// <summary>
/// In full screen, the keys Windows keeps for itself go to the remote PC, as
/// in Microsoft's Remote Desktop: the Windows key, Alt+Tab, Alt+Esc and
/// Ctrl+Esc (Ctrl+Shift+Esc too). A low-level keyboard hook sees them before
/// Windows acts on them, swallows them while the viewer is the window in
/// front, and hands each one to the viewer to send. Ctrl+Alt+Del cannot be
/// taken this way; the toolbar has a button for it.
///
/// Like <see cref="PrivacyInputBlocker"/>'s, the hook runs on its own thread
/// with its own message loop, never on the busy UI thread, where a slow
/// moment would make Windows drop it.
/// </summary>
internal sealed class FullScreenKeys : IDisposable
{
    /// <summary>One key taken from Windows: its protocol code, whether it went
    /// down, and which modifiers were held as it did. A held modifier went to
    /// the viewer the usual way, which may not have sent it yet.</summary>
    public readonly record struct Captured(string Code, bool Down, bool Ctrl, bool Alt, bool Shift);

    private const uint VkTab = 0x09, VkEscape = 0x1B, VkLWin = 0x5B, VkRWin = 0x5C;
    private const int VkShift = 0x10, VkControl = 0x11;

    /// <summary>The key's code if it is one to take from Windows, going down
    /// with these modifiers held; null for every other key.</summary>
    public static string? Takes(uint vk, bool alt, bool ctrl) => vk switch
    {
        VkLWin or VkRWin => "WIN",
        VkTab when alt => "TAB",
        VkEscape when alt || ctrl => "ESC",
        _ => null,
    };

    private static string CodeOf(uint vk) => vk switch
    {
        VkTab => "TAB",
        VkEscape => "ESC",
        _ => "WIN",
    };

    private delegate IntPtr HookProc(int code, IntPtr message, IntPtr data);
    private readonly HookProc _proc;
    private readonly IntPtr _window;
    private readonly Action<Captured> _forward;
    private readonly Thread _thread;
    private readonly TaskCompletionSource _ready = new(TaskCreationOptions.RunContinuationsAsynchronously);
    // Keys whose going down was taken, so their coming up is taken too, even
    // once the modifier that made them a shortcut has been let go.
    private readonly HashSet<uint> _held = new();
    private volatile bool _stopping;
    private uint _threadId;

    /// <param name="window">Keys are taken only while this window is in front.</param>
    /// <param name="forward">Called on the hook's thread: post it on, never wait in it.</param>
    public FullScreenKeys(IntPtr window, Action<Captured> forward)
    {
        _window = window;
        _forward = forward;
        _proc = Hook;
        _thread = new Thread(Run) { IsBackground = true, Name = "Full screen keys" };
        _thread.Start();
        try { _ready.Task.WaitAsync(TimeSpan.FromSeconds(5)).GetAwaiter().GetResult(); }
        catch { Dispose(); throw; }
    }

    private void Run()
    {
        IntPtr hook = IntPtr.Zero;
        try
        {
            _threadId = GetCurrentThreadId();
            PeekMessage(out _, IntPtr.Zero, 0, 0, 0); // create the message queue
            hook = SetWindowsHookEx(13, _proc, GetModuleHandle(null), 0);
            if (hook == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
            _ready.TrySetResult();
            while (!_stopping && GetMessage(out var msg, IntPtr.Zero, 0, 0) > 0)
            {
                TranslateMessage(ref msg);
                DispatchMessage(ref msg);
            }
        }
        catch (Exception ex)
        {
            _ready.TrySetException(ex);
        }
        finally
        {
            if (hook != IntPtr.Zero) UnhookWindowsHookEx(hook);
        }
    }

    private IntPtr Hook(int code, IntPtr message, IntPtr data)
    {
        if (code < 0 || _stopping) return CallNextHookEx(IntPtr.Zero, code, message, data);
        var key = Marshal.PtrToStructure<KeyboardData>(data);
        // Injected input is someone else's, this app's own remote control included.
        if ((key.Flags & 0x10) != 0) return CallNextHookEx(IntPtr.Zero, code, message, data);
        bool down = message.ToInt64() is 0x100 or 0x104;
        bool alt = (key.Flags & 0x20) != 0;
        bool ctrl = (GetAsyncKeyState(VkControl) & 0x8000) != 0;
        bool shift = (GetAsyncKeyState(VkShift) & 0x8000) != 0;
        string? taken;
        if (down)
        {
            taken = GetForegroundWindow() == _window ? Takes(key.Vk, alt, ctrl) : null;
            if (taken == null) return CallNextHookEx(IntPtr.Zero, code, message, data);
            _held.Add(key.Vk);
        }
        else
        {
            if (!_held.Remove(key.Vk)) return CallNextHookEx(IntPtr.Zero, code, message, data);
            taken = CodeOf(key.Vk);
        }
        _forward(new Captured(taken, down, ctrl, alt, shift));
        return new IntPtr(1);
    }

    public void Dispose()
    {
        _stopping = true; // pass keys straight through, even before unhooking
        if (_threadId != 0) PostThreadMessage(_threadId, 0x12, IntPtr.Zero, IntPtr.Zero);
        if (Thread.CurrentThread != _thread) _thread.Join(TimeSpan.FromSeconds(2));
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KeyboardData { public uint Vk, Scan, Flags, Time; public IntPtr ExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    private struct Message
    {
        public IntPtr Hwnd; public uint Id; public IntPtr WParam, LParam;
        public uint Time; public int X, Y; public uint Private;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr SetWindowsHookEx(int id, HookProc proc, IntPtr module, uint threadId);
    [DllImport("user32.dll")]
    private static extern bool UnhookWindowsHookEx(IntPtr hook);
    [DllImport("user32.dll")]
    private static extern IntPtr CallNextHookEx(IntPtr hook, int code, IntPtr message, IntPtr data);
    [DllImport("user32.dll")]
    private static extern int GetMessage(out Message message, IntPtr hwnd, uint min, uint max);
    [DllImport("user32.dll")]
    private static extern bool PeekMessage(out Message message, IntPtr hwnd, uint min, uint max, uint remove);
    [DllImport("user32.dll")]
    private static extern bool TranslateMessage(ref Message message);
    [DllImport("user32.dll")]
    private static extern IntPtr DispatchMessage(ref Message message);
    [DllImport("user32.dll")]
    private static extern bool PostThreadMessage(uint thread, uint message, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")]
    private static extern short GetAsyncKeyState(int vk);
    [DllImport("kernel32.dll")]
    private static extern uint GetCurrentThreadId();
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr GetModuleHandle(string? name);
}
