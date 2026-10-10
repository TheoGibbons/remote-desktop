using System.Drawing;
using System.Runtime.InteropServices;
using RemoteDesktopWin;
using Forms = System.Windows.Forms;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        Check(PrivacyInputPolicy.IsRemoteInput(true, InputInjector.RemoteInputTag), "tagged remote input passes");
        Check(!PrivacyInputPolicy.IsRemoteInput(false, InputInjector.RemoteInputTag), "physical input cannot pass by tag alone");
        Check(!PrivacyInputPolicy.IsRemoteInput(true, IntPtr.Zero), "unrelated injected input is blocked");

        var policy = new PrivacyInputPolicy();
        long now = 100;
        bool Type(string word)
        {
            bool unlocked = false;
            foreach (char c in word)
            {
                unlocked |= policy.OnKey(char.ToUpperInvariant(c), true, now++);
                policy.OnKey(char.ToUpperInvariant(c), false, now++);
            }
            return unlocked;
        }
        Check(!Type("unlXock"), "wrong letters reset the escape");
        Check(Type("noiseUNLOCK"), "escape is case insensitive and works after noise");
        Check(!Type("unl"), "partial escape stays private");
        now += 5001;
        Check(!Type("ock"), "a stale partial escape expires");
        policy = new PrivacyInputPolicy();
        Check(!policy.OnKey('U', true, now++), "first U stays private");
        Check(!policy.OnKey('U', true, now++), "auto-repeat does not advance or reset");
        policy.OnKey('U', false, now++);
        policy.OnKey(0xA0, true, now++);
        Check(Type("nlock"), "Shift does not interrupt the escape");
        Check(Type("unlock"), "matcher can be reused");
        Console.WriteLine("PASS: local escape and remote-input classification");

        if (args.Contains("--capture-smoke")) CaptureSmoke();
        if (args.Contains("--input-smoke")) InputSmoke();
    }

    // Opt-in: briefly shows a small test window; never blocks physical input.
    private static void CaptureSmoke()
    {
        Forms.Application.SetHighDpiMode(Forms.HighDpiMode.PerMonitorV2);
        var primary = Forms.Screen.PrimaryScreen!.Bounds;
        var bounds = new Rectangle(primary.X + 40, primary.Y + 40, 300, 160);
        using var desktop = new TestWindow { Bounds = bounds, BackColor = Color.Magenta };
        desktop.Show();
        SetWindowPos(desktop.Handle, new IntPtr(-1), bounds.X, bounds.Y, bounds.Width, bounds.Height, 0x50);
        Pump();
        var vb = Forms.SystemInformation.VirtualScreen;
        using (var before = new Bitmap(vb.Width, vb.Height))
        using (var baseline = new GdiCaptureSource(vb))
        {
            baseline.CaptureInto(before, new List<Rectangle>(), new Rectangle(Point.Empty, before.Size));
            Check(before.GetPixel(bounds.X + 20 - vb.X, bounds.Y + 20 - vb.Y).ToArgb() == Color.Magenta.ToArgb(),
                "test desktop is visible before the overlay");
        }
        var foreground = GetForegroundWindow();
        using var overlay = new PrivacyOverlayWindow(bounds);
        overlay.ShowCovered(bounds);
        Pump();
        Check(GetWindowDisplayAffinity(overlay.Handle, out uint affinity) && affinity == 0x11,
            "overlay uses WDA_EXCLUDEFROMCAPTURE");
        var point = new NativePoint { X = bounds.X + 20, Y = bounds.Y + 20 };
        var hit = WindowFromPoint(point);
        Check(hit == desktop.Handle, "remote clicks pass through the overlay");
        Check(GetForegroundWindow() == foreground, "overlay never steals focus");

        var virtualBounds = Forms.SystemInformation.VirtualScreen;
        var sample = new Point(point.X - virtualBounds.X, point.Y - virtualBounds.Y);
        using var surface = new Bitmap(virtualBounds.Width, virtualBounds.Height, System.Drawing.Imaging.PixelFormat.Format32bppRgb);
        void Verify(ICaptureSource capture, string name)
        {
            using (capture)
            {
                var dirty = new List<Rectangle>();
                bool captured = false;
                for (int i = 0; i < 20 && !captured; i++)
                {
                    Pump();
                    captured = capture.CaptureInto(surface, dirty, new Rectangle(Point.Empty, surface.Size));
                }
                Check(captured, name + " captured a frame");
                var pixel = surface.GetPixel(sample.X, sample.Y);
                Check(pixel.R > 240 && pixel.G < 15 && pixel.B > 240,
                    name + " sees the desktop underneath privacy (got " + pixel + ")");
                Console.WriteLine("PASS: " + name + " capture excludes the privacy overlay");
            }
        }
        Verify(new GdiCaptureSource(virtualBounds), "GDI");
        Verify(new DxgiCaptureSource(virtualBounds), "DXGI");
    }

    // Downstream hooks consume every test event so nothing reaches the user's
    // applications. The privacy hooks must pass tagged input to those hooks,
    // and suppress untagged input before it gets there.
    private static void InputSmoke()
    {
        int keys = 0, mouse = 0;
        HookProc keyboardProc = (code, message, data) =>
        {
            if (code >= 0) { keys++; return new IntPtr(1); }
            return CallNextHookEx(IntPtr.Zero, code, message, data);
        };
        HookProc mouseProc = (code, message, data) =>
        {
            if (code >= 0) { mouse++; return new IntPtr(1); }
            return CallNextHookEx(IntPtr.Zero, code, message, data);
        };
        var module = GetModuleHandle(null);
        var keyboardHook = SetWindowsHookEx(13, keyboardProc, module, 0);
        var mouseHook = SetWindowsHookEx(14, mouseProc, module, 0);
        try
        {
            Check(keyboardHook != IntPtr.Zero && mouseHook != IntPtr.Zero, "observer hooks install");
            bool unlocked = false;
            using (var blocker = new PrivacyInputBlocker(() => unlocked = true))
            {
                InputInjector.TypeText("unlock");
                InputInjector.KeyEvent("ENTER", "press");
                InputInjector.Scroll(0, 1);
                Pump();
                Check(keys == 14 && mouse == 1, "remote text, keys and mouse input pass through");
                Check(!unlocked, "remote typing cannot trigger the local escape");
                var untagged = new[]
                {
                    new TestInput { Type = 1, Key = new TestKey { Vk = 0x87 } }, // F24
                    new TestInput { Type = 1, Key = new TestKey { Vk = 0x87, Flags = 2 } },
                    new TestInput { Type = 0, Mouse = new TestMouse { Flags = 0x800, Data = 120 } },
                };
                Check(SendInput((uint)untagged.Length, untagged, Marshal.SizeOf<TestInput>()) == 3,
                    "untagged test input was submitted");
                Pump();
                Check(keys == 14 && mouse == 1, "untagged input is blocked");
            }
            Console.WriteLine("PASS: native privacy hooks preserve app input and suppress untagged input");
        }
        finally
        {
            if (keyboardHook != IntPtr.Zero) UnhookWindowsHookEx(keyboardHook);
            if (mouseHook != IntPtr.Zero) UnhookWindowsHookEx(mouseHook);
            GC.KeepAlive(keyboardProc);
            GC.KeepAlive(mouseProc);
        }
    }

    private static void Pump()
    {
        Forms.Application.DoEvents();
        Thread.Sleep(100);
        Forms.Application.DoEvents();
    }
    private static void Check(bool condition, string message)
    {
        if (!condition) throw new Exception(message);
    }
    private sealed class TestWindow : Forms.Form
    {
        internal TestWindow()
        {
            FormBorderStyle = Forms.FormBorderStyle.None;
            StartPosition = Forms.FormStartPosition.Manual;
            ShowInTaskbar = false;
            TopMost = true;
        }
        protected override bool ShowWithoutActivation => true;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct NativePoint { public int X, Y; }
    [DllImport("user32.dll")]
    private static extern IntPtr WindowFromPoint(NativePoint point);
    [DllImport("user32.dll")]
    private static extern bool GetWindowDisplayAffinity(IntPtr hwnd, out uint affinity);
    [DllImport("user32.dll")]
    private static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int width, int height, uint flags);
    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();
    private delegate IntPtr HookProc(int code, IntPtr message, IntPtr data);
    [DllImport("user32.dll")]
    private static extern IntPtr SetWindowsHookEx(int id, HookProc proc, IntPtr module, uint threadId);
    [DllImport("user32.dll")]
    private static extern bool UnhookWindowsHookEx(IntPtr hook);
    [DllImport("user32.dll")]
    private static extern IntPtr CallNextHookEx(IntPtr hook, int code, IntPtr message, IntPtr data);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr GetModuleHandle(string? name);
    [StructLayout(LayoutKind.Sequential)]
    private struct TestKey { public ushort Vk, Scan; public uint Flags, Time; public IntPtr Extra; }
    [StructLayout(LayoutKind.Sequential)]
    private struct TestMouse { public int X, Y; public uint Data, Flags, Time; public IntPtr Extra; }
    [StructLayout(LayoutKind.Explicit)]
    private struct TestUnion
    {
        [FieldOffset(0)] public TestKey Key;
        [FieldOffset(0)] public TestMouse Mouse;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct TestInput
    {
        public uint Type;
        public TestUnion Union;
        public TestKey Key { set => Union.Key = value; }
        public TestMouse Mouse { set => Union.Mouse = value; }
    }
    [DllImport("user32.dll")]
    private static extern uint SendInput(uint count, TestInput[] inputs, int size);
}
