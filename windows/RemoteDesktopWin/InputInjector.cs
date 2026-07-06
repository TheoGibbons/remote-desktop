using System.Runtime.InteropServices;

namespace RemoteDesktopWin;

/// <summary>
/// Injects mouse and keyboard events via SendInput. Mouse coordinates arrive
/// normalized (0..1) over the stitched virtual desktop and are mapped to the
/// 0..65535 absolute range that MOUSEEVENTF_VIRTUALDESK expects.
/// </summary>
public static class InputInjector
{
    #region Win32

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx, dy;
        public uint mouseData, dwFlags, time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk, wScan;
        public uint dwFlags, time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct INPUTUNION
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint type;
        public INPUTUNION u;
    }

    private const uint INPUT_MOUSE = 0;
    private const uint INPUT_KEYBOARD = 1;
    private const uint MOUSEEVENTF_MOVE = 0x0001;
    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    private const uint MOUSEEVENTF_LEFTUP = 0x0004;
    private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    private const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    private const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
    private const uint MOUSEEVENTF_WHEEL = 0x0800;
    private const uint MOUSEEVENTF_HWHEEL = 0x1000;
    private const uint MOUSEEVENTF_ABSOLUTE = 0x8000;
    private const uint MOUSEEVENTF_VIRTUALDESK = 0x4000;
    private const uint KEYEVENTF_KEYUP = 0x0002;
    private const uint KEYEVENTF_UNICODE = 0x0004;

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [DllImport("user32.dll")]
    private static extern short VkKeyScan(char ch);

    #endregion

    private static void Send(params INPUT[] inputs) =>
        SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());

    private static INPUT Mouse(uint flags, int dx = 0, int dy = 0, uint data = 0) => new()
    {
        type = INPUT_MOUSE,
        u = new INPUTUNION { mi = new MOUSEINPUT { dx = dx, dy = dy, dwFlags = flags, mouseData = data } },
    };

    private static INPUT Key(ushort vk, bool up) => new()
    {
        type = INPUT_KEYBOARD,
        u = new INPUTUNION { ki = new KEYBDINPUT { wVk = vk, dwFlags = up ? KEYEVENTF_KEYUP : 0 } },
    };

    private static (int x, int y) ToAbs(double nx, double ny)
    {
        // Normalized 0..1 over the virtual desktop -> 0..65535 absolute.
        return ((int)(Math.Clamp(nx, 0, 1) * 65535), (int)(Math.Clamp(ny, 0, 1) * 65535));
    }

    public static void MouseMove(double nx, double ny)
    {
        var (x, y) = ToAbs(nx, ny);
        Send(Mouse(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK, x, y));
    }

    public static void MouseButton(string button, bool down, double nx, double ny)
    {
        var (x, y) = ToAbs(nx, ny);
        uint flag = (button, down) switch
        {
            ("left", true) => MOUSEEVENTF_LEFTDOWN,
            ("left", false) => MOUSEEVENTF_LEFTUP,
            ("right", true) => MOUSEEVENTF_RIGHTDOWN,
            ("right", false) => MOUSEEVENTF_RIGHTUP,
            ("middle", true) => MOUSEEVENTF_MIDDLEDOWN,
            _ => MOUSEEVENTF_MIDDLEUP,
        };
        Send(
            Mouse(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK, x, y),
            Mouse(flag | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK, x, y));
    }

    public static void Scroll(double dx, double dy)
    {
        if (Math.Abs(dy) > 0.01) Send(Mouse(MOUSEEVENTF_WHEEL, data: (uint)(int)(dy * 120)));
        if (Math.Abs(dx) > 0.01) Send(Mouse(MOUSEEVENTF_HWHEEL, data: (uint)(int)(dx * 120)));
    }

    private static readonly Dictionary<string, ushort> VkMap = new(StringComparer.OrdinalIgnoreCase)
    {
        ["ENTER"] = 0x0D, ["ESC"] = 0x1B, ["TAB"] = 0x09, ["SPACE"] = 0x20,
        ["BACKSPACE"] = 0x08, ["DELETE"] = 0x2E, ["INSERT"] = 0x2D,
        ["HOME"] = 0x24, ["END"] = 0x23, ["PGUP"] = 0x21, ["PGDN"] = 0x22,
        ["UP"] = 0x26, ["DOWN"] = 0x28, ["LEFT"] = 0x25, ["RIGHT"] = 0x27,
        ["WIN"] = 0x5B, ["CTRL"] = 0x11, ["ALT"] = 0x12, ["SHIFT"] = 0x10,
        ["CAPS"] = 0x14, ["PRINTSCREEN"] = 0x2C, ["MENU"] = 0x5D,
    };

    static InputInjector()
    {
        for (char c = 'A'; c <= 'Z'; c++) VkMap[c.ToString()] = (ushort)c;
        for (char c = '0'; c <= '9'; c++) VkMap[c.ToString()] = (ushort)c;
        for (int f = 1; f <= 12; f++) VkMap["F" + f] = (ushort)(0x70 + f - 1);
    }

    /// <returns>false if the key name is unknown</returns>
    public static bool KeyEvent(string code, string action)
    {
        ushort vk;
        if (!VkMap.TryGetValue(code, out vk))
        {
            if (code.Length != 1) return false;
            short scan = VkKeyScan(code[0]);
            if (scan == -1) { TypeText(code); return true; }
            vk = (ushort)(scan & 0xFF);
        }

        switch (action)
        {
            case "down": Send(Key(vk, false)); break;
            case "up": Send(Key(vk, true)); break;
            default: Send(Key(vk, false), Key(vk, true)); break;
        }
        return true;
    }

    /// <summary>
    /// Best-effort Ctrl+Alt+Del. Injecting the real secure attention sequence
    /// is blocked by Windows for normal apps; SendSAS works only when allowed
    /// by policy, so fall back to opening Task Manager (the most common reason
    /// to want Ctrl+Alt+Del remotely).
    /// </summary>
    public static void CtrlAltDel()
    {
        try
        {
            SendSAS(false);
        }
        catch
        {
            // sas.dll unavailable or not permitted.
        }
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("taskmgr.exe")
            {
                UseShellExecute = true,
            });
        }
        catch { }
    }

    [DllImport("sas.dll")]
    private static extern void SendSAS(bool asUser);

    public static void TypeText(string text)
    {
        var inputs = new List<INPUT>(text.Length * 2);
        foreach (char c in text)
        {
            inputs.Add(new INPUT
            {
                type = INPUT_KEYBOARD,
                u = new INPUTUNION { ki = new KEYBDINPUT { wScan = c, dwFlags = KEYEVENTF_UNICODE } },
            });
            inputs.Add(new INPUT
            {
                type = INPUT_KEYBOARD,
                u = new INPUTUNION { ki = new KEYBDINPUT { wScan = c, dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP } },
            });
        }
        Send(inputs.ToArray());
    }
}
