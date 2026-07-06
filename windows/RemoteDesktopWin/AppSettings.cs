using System.IO;
using System.Text.Json;

namespace RemoteDesktopWin;

public class AppSettings
{
    public string ServerUrl { get; set; } = "ws://localhost:8090/ws";
    public string SessionKey { get; set; } = "";
    public string DeviceName { get; set; } = Environment.MachineName;
    public int Fps { get; set; } = 12;
    public int JpegQuality { get; set; } = 55;
    public int MaxStreamWidth { get; set; } = 2200;
    public bool AutoConnect { get; set; } = true;

    private static string Dir =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "RemoteDesktopWin");

    private static string FilePath => Path.Combine(Dir, "settings.json");

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath)) ?? new AppSettings();
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
