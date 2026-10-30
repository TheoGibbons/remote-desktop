using System.Collections.ObjectModel;
using System.ComponentModel;

namespace RemoteDesktopWin;

/// <summary>One file in the transfers window, updated as it moves on.</summary>
public sealed class TransferRow : INotifyPropertyChanged
{
    public uint XferId { get; }
    public bool Sending { get; }
    /// <summary>The device at the other end, named when the transfer began.</summary>
    public string PeerName { get; }
    public string Name { get; private set; } = "";
    public long Size { get; private set; } = -1;
    public int Percent { get; private set; } = -1;
    public TransferState State { get; private set; }
    public string? Error { get; private set; }

    public bool Finished => State is TransferState.Done or TransferState.Failed or TransferState.Cancelled;
    public bool CanCancel => !Finished;
    public bool HasFailed => State == TransferState.Failed;
    public bool ShowProgress => State == TransferState.Running && Percent >= 0;
    public string Arrow => Sending ? "↑" : "↓";

    /// <summary>"To Pixel · 45% of 12.0 MB", or how it ended.</summary>
    public string Detail => $"{(Sending ? "To" : "From")} {PeerName} · " + State switch
    {
        TransferState.Waiting => "Waiting",
        TransferState.Running when Percent < 0 => Sending ? "Starting" : "Receiving",
        TransferState.Running => Size > 0 ? $"{Percent}% of {FileEntry.FormatSize(Size)}" : $"{Percent}%",
        TransferState.Done => (Sending ? "Sent" : "Received") + (Size > 0 ? $", {FileEntry.FormatSize(Size)}" : ""),
        TransferState.Failed => $"Failed: {Error}",
        _ => "Cancelled",
    };

    public event PropertyChangedEventHandler? PropertyChanged;

    internal TransferRow(uint xferId, bool sending, string peerName)
    {
        XferId = xferId;
        Sending = sending;
        PeerName = peerName;
    }

    internal void Update(TransferUpdate u)
    {
        Name = u.Name;
        Size = u.Size;
        Percent = u.Percent;
        State = u.State;
        Error = u.Error;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(null));
    }
}

/// <summary>
/// The files this PC has sent and received this session, with every device,
/// newest first. Used on the UI thread: FsService's updates are posted to it.
/// </summary>
public sealed class TransferList
{
    private readonly FsService _fs;
    private readonly Func<string, string> _peerName;
    // Kept after the window clears them, so late news can't bring one back.
    private readonly Dictionary<uint, TransferRow> _rows = new();

    public ObservableCollection<TransferRow> Rows { get; } = new();

    public bool HasFinished => Rows.Any(r => r.Finished);

    /// <summary>How many files are waiting, sending or receiving.</summary>
    public int ActiveCount { get; private set; }

    /// <summary>Raised after any row is added, moved on or cleared.</summary>
    public event Action? Changed;

    /// <param name="peerName">Names a device from its id while it is online.</param>
    public TransferList(FsService fs, Func<string, string> peerName)
    {
        _fs = fs;
        _peerName = peerName;
    }

    /// <returns>The row it moved on, or null for news of a transfer that has already ended.</returns>
    public TransferRow? Apply(TransferUpdate u)
    {
        if (!_rows.TryGetValue(u.XferId, out var row))
        {
            row = new TransferRow(u.XferId, u.Sending, _peerName(u.Peer));
            _rows[u.XferId] = row;
            Rows.Insert(0, row);
            ActiveCount++;
        }
        // A cancel can land between a chunk and its progress; don't bring that file back.
        else if (row.Finished) return null;
        row.Update(u);
        if (row.Finished) ActiveCount--;
        Changed?.Invoke();
        return row;
    }

    public void Cancel(TransferRow row)
    {
        if (!row.Finished) _fs.Cancel(row.XferId);
    }

    public void ClearFinished()
    {
        foreach (var row in Rows.Where(r => r.Finished).ToList()) Rows.Remove(row);
        Changed?.Invoke();
    }
}
