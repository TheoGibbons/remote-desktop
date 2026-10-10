namespace RemoteDesktopWin;

/// <summary>Holds the app's instance lock until shutdown, including while hidden in the tray.</summary>
internal sealed class SingleInstanceGuard : IDisposable
{
    // Use a stable name so copies launched from different folders share the lock.
    // Local scopes the lock to the interactive Windows session.
    private const string MutexName = @"Local\RemoteDesktopWin.SingleInstance";
    private readonly Mutex _mutex;

    private SingleInstanceGuard(Mutex mutex) => _mutex = mutex;

    internal static SingleInstanceGuard? TryAcquire(TimeSpan timeout, string name = MutexName)
    {
        Mutex mutex;
        try { mutex = new Mutex(initiallyOwned: false, name); }
        catch (UnauthorizedAccessException)
        {
            // An elevated instance may own a mutex this process cannot open.
            return null;
        }

        bool acquired = false;
        try
        {
            try { acquired = mutex.WaitOne(timeout); }
            catch (AbandonedMutexException)
            {
                // Windows grants ownership when the previous owner crashed.
                acquired = true;
            }
            return acquired ? new SingleInstanceGuard(mutex) : null;
        }
        finally
        {
            if (!acquired) mutex.Dispose();
        }
    }

    public void Dispose()
    {
        _mutex.ReleaseMutex();
        _mutex.Dispose();
    }
}
