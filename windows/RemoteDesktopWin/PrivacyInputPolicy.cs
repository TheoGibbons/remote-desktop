namespace RemoteDesktopWin;

/// <summary>Recognizes the local escape without forwarding its keystrokes.</summary>
internal sealed class PrivacyInputPolicy
{
    private const string Unlock = "UNLOCK";
    private readonly HashSet<uint> _pressed = new();
    private int _matched;
    private long _lastKeyAt;

    internal static bool IsRemoteInput(bool injected, IntPtr extraInfo) =>
        injected && extraInfo == InputInjector.RemoteInputTag;

    // Called only for physical events, on the hook thread. Shift/Caps do not
    // affect letter virtual keys, so the escape is case-insensitive.
    internal bool OnKey(uint vk, bool down, long now)
    {
        if (!down) { _pressed.Remove(vk); return false; }
        if (!_pressed.Add(vk)) return false; // ignore auto-repeat
        if (vk is 0x10 or 0xA0 or 0xA1 or 0x14) return false;
        if (now - _lastKeyAt > 5000) _matched = 0;
        _lastKeyAt = now;
        if (vk == Unlock[_matched]) _matched++;
        else _matched = vk == Unlock[0] ? 1 : 0;
        if (_matched != Unlock.Length) return false;
        _matched = 0;
        return true;
    }
}
