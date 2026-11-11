namespace RemoteDesktopWin;

/// <summary>Holds the app's instance lock until shutdown, including while hidden in the tray.</summary>
internal sealed class SingleInstanceGuard : IDisposable
{
    private readonly Mutex _mutex;

    private SingleInstanceGuard(Mutex mutex) => _mutex = mutex;

    /// <param name="name">The lock's name: <see cref="AppProfile.InstanceLock"/> for the app itself.</param>
    internal static SingleInstanceGuard? TryAcquire(TimeSpan timeout, string name)
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
