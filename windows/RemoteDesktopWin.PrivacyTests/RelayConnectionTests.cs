using System.Collections.Concurrent;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using RemoteDesktopWin;

/// <summary>
/// The real WsClient against a relay on this machine that lets it in and then
/// goes quiet, as one does behind a link that has stopped carrying anything.
/// </summary>
internal static class RelayConnectionTests
{
    internal static void Run() => Task.Run(ReconnectsWhenTheRelayStopsAnswering).GetAwaiter().GetResult();

    // On 2026-11-23 a PC lost the relay this way and never came back: a missed
    // pong surfaces as an OperationCanceledException, and the reconnect loop took
    // that for Stop(). The PC kept showing "connected" and the devices it last
    // saw, while those devices no longer saw it.
    private static async Task ReconnectsWhenTheRelayStopsAnswering()
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;
        int accepted = 0;
        var cameBack = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        _ = Task.Run(async () =>
        {
            while (true)
            {
                TcpClient client;
                try { client = await listener.AcceptTcpClientAsync(); }
                catch { return; }
                if (Interlocked.Increment(ref accepted) == 2) cameBack.TrySetResult();
                _ = Task.Run(() => LetInThenGoQuiet(client));
            }
        });

        var keepAlive = WsClient.KeepAlive;
        WsClient.KeepAlive = TimeSpan.FromMilliseconds(500);
        var states = new ConcurrentQueue<string>();
        var ws = new WsClient();
        ws.StateChanged += states.Enqueue;
        try
        {
            ws.Start($"ws://127.0.0.1:{port}/", "relay-connection-test-key", "Test PC", "relay-connection-test");
            if (await Task.WhenAny(cameBack.Task, Task.Delay(15_000)) != cameBack.Task)
                throw new Exception("After the relay stopped answering pings, this PC never connected again. " +
                    "It said: " + string.Join(" | ", states));
            if (!states.Any(s => s.StartsWith("disconnected: the relay stopped answering")))
                throw new Exception("This PC did not say it had lost the relay. It said: " + string.Join(" | ", states));
        }
        finally
        {
            ws.Stop();
            WsClient.KeepAlive = keepAlive;
            listener.Stop();
        }
        Console.WriteLine("PASS: a PC whose relay stops answering says so and connects again");
    }

    /// <summary>Completes the WebSocket handshake, then reads and answers nothing, pings included.</summary>
    private static async Task LetInThenGoQuiet(TcpClient client)
    {
        using var _ = client;
        var stream = client.GetStream();
        var buffer = new byte[8192];
        var request = new StringBuilder();
        try
        {
            while (!request.ToString().Contains("\r\n\r\n"))
            {
                int n = await stream.ReadAsync(buffer);
                if (n == 0) return;
                request.Append(Encoding.ASCII.GetString(buffer, 0, n));
            }
            var key = request.ToString().Split("\r\n")
                .First(line => line.StartsWith("Sec-WebSocket-Key:", StringComparison.OrdinalIgnoreCase))
                .Split(':', 2)[1].Trim();
            var accept = Convert.ToBase64String(SHA1.HashData(Encoding.ASCII.GetBytes(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")));
            await stream.WriteAsync(Encoding.ASCII.GetBytes(
                "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                $"Sec-WebSocket-Accept: {accept}\r\n\r\n"));
            while (await stream.ReadAsync(buffer) > 0) { }
        }
        catch (IOException) { } // the PC gave up on this connection
    }
}
