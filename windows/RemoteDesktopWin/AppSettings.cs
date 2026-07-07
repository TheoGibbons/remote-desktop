using System.IO;
using System.Text.Json;

namespace RemoteDesktopWin;

public class AppSettings
{
    public string ServerUrl { get; set; } = "ws://localhost:8090/ws";
    public string SessionKey { get; set; } = "";
    public string DeviceName { get; set; } = Environment.MachineName;
    public int Fps { get; set; } = 12;
    public int JpegQuality { get; set; } = 80;
    // 0 = stream at native resolution (affordable since only dirty rects are
    // sent); set to clamp the stitched width for very slow links.
    public int MaxStreamWidth { get; set; } = 0;
    public bool AutoConnect { get; set; } = true;
    public bool StartWithWindows { get; set; }
    // Stable per-install id so the relay can replace this device's stale
    // connection on reconnect instead of listing it twice.
    public string DeviceUid { get; set; } = Guid.NewGuid().ToString("N");

    private static string Dir =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "RemoteDesktopWin");

    private static string FilePath => Path.Combine(Dir, "settings.json");

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(FilePath))
            {
                var s = JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath)) ?? new AppSettings();
                // Migrate the pre-dirty-rect defaults: full-frame streaming
                // needed a downscale + low quality; patches don't.
                if (s.MaxStreamWidth == 2200) s.MaxStreamWidth = 0;
                if (s.JpegQuality == 55) s.JpegQuality = 80;
                return s;
            }
        }
        catch { }
        return new AppSettings();
    }

    public void Save()
    {
        Directory.CreateDirectory(Dir);
        File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
    }
}
