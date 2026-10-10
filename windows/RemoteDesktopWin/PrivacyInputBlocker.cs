using System.ComponentModel;
using System.Runtime.InteropServices;

namespace RemoteDesktopWin;

/// <summary>Hooks run on their own message thread, never on the busy UI thread.</summary>
internal sealed class PrivacyInputBlocker : IDisposable
{
    private delegate IntPtr HookProc(int code, IntPtr message, IntPtr data);
    private readonly HookProc _keyboardProc;
    private readonly HookProc _mouseProc;
    private readonly PrivacyInputPolicy _policy = new();
    private readonly Action _unlock;
    private readonly Thread _thread;
    private readonly TaskCompletionSource _ready = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private volatile bool _stopping;
    private uint _threadId;

    internal PrivacyInputBlocker(Action unlock)
    {
        _unlock = unlock;
        _keyboardProc = KeyboardHook;
        _mouseProc = MouseHook;
        _thread = new Thread(Run) { IsBackground = true, Name = "Privacy input hooks" };
        _thread.Start();
        try { _ready.Task.WaitAsync(TimeSpan.FromSeconds(5)).GetAwaiter().GetResult(); }
        catch { Dispose(); throw; }
    }

    private void Run()
    {
        IntPtr keyboard = IntPtr.Zero, mouse = IntPtr.Zero;
        try
        {
            _threadId = GetCurrentThreadId();
            PeekMessage(out _, IntPtr.Zero, 0, 0, 0); // create the message queue
            var module = GetModuleHandle(null);
            keyboard = SetWindowsHookEx(13, _keyboardProc, module, 0);
            if (keyboard == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
            mouse = SetWindowsHookEx(14, _mouseProc, module, 0);
            if (mouse == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
            _ready.TrySetResult();
            while (!_stopping && GetMessage(out var msg, IntPtr.Zero, 0, 0) > 0)
            {
                TranslateMessage(ref msg);
                DispatchMessage(ref msg);
            }
            if (!_stopping) { _stopping = true; _unlock(); }
        }
        catch (Exception ex)
        {
            _stopping = true;
            if (!_ready.TrySetException(ex)) _unlock();
        }
        finally
        {
            if (mouse != IntPtr.Zero) UnhookWindowsHookEx(mouse);
            if (keyboard != IntPtr.Zero) UnhookWindowsHookEx(keyboard);
        }
    }

    private IntPtr KeyboardHook(int code, IntPtr message, IntPtr data)
    {
        if (code < 0 || _stopping) return CallNextHookEx(IntPtr.Zero, code, message, data);
        var key = Marshal.PtrToStructure<KeyboardData>(data);
        bool injected = (key.Flags & 0x10) != 0;
        if (PrivacyInputPolicy.IsRemoteInput(injected, key.ExtraInfo))
            return CallNextHookEx(IntPtr.Zero, code, message, data);
        if (!injected && _policy.OnKey(key.Vk, message.ToInt64() is 0x100 or 0x104, Environment.TickCount64))
            _unlock(); // queues UI work; never waits in the hook
        return new IntPtr(1);
    }

    private IntPtr MouseHook(int code, IntPtr message, IntPtr data)
    {
        if (code < 0 || _stopping) return CallNextHookEx(IntPtr.Zero, code, message, data);
        var mouse = Marshal.PtrToStructure<MouseData>(data);
        return PrivacyInputPolicy.IsRemoteInput((mouse.Flags & 1) != 0, mouse.ExtraInfo)
            ? CallNextHookEx(IntPtr.Zero, code, message, data) : new IntPtr(1);
    }

    public void Dispose()
    {
        _stopping = true; // immediately pass through input, even before unhooking
        if (_threadId != 0) PostThreadMessage(_threadId, 0x12, IntPtr.Zero, IntPtr.Zero);
        if (Thread.CurrentThread != _thread) _thread.Join(TimeSpan.FromSeconds(2));
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KeyboardData { public uint Vk, Scan, Flags, Time; public IntPtr ExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    private struct MouseData { public int X, Y; public uint Data, Flags, Time; public IntPtr ExtraInfo; }
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
    [DllImport("kernel32.dll")]
    private static extern uint GetCurrentThreadId();
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr GetModuleHandle(string? name);
}
