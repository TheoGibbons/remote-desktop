package co.remotedesktop

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Makes the phone's sockets with Nagle's algorithm off (`TCP_NODELAY`).
 *
 * With it on, which is Java's default and what OkHttp leaves, a small packet
 * waits until the one before it has been acknowledged: up to a round trip. A
 * tap's answer, an acknowledgement and the tail of a picture are all small
 * packets. The relay and the PC already send theirs at once.
 *
 * OkHttp makes the plain socket through this factory and puts TLS on top of
 * it, so the setting holds for the encrypted connection too.
 */
internal class NoDelaySocketFactory(private val inner: SocketFactory = getDefault()) : SocketFactory() {
    private fun Socket.atOnce(): Socket = apply { tcpNoDelay = true }

    override fun createSocket(): Socket = inner.createSocket().atOnce()

    override fun createSocket(host: String, port: Int): Socket = inner.createSocket(host, port).atOnce()

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        inner.createSocket(host, port, localHost, localPort).atOnce()

    override fun createSocket(host: InetAddress, port: Int): Socket = inner.createSocket(host, port).atOnce()

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        inner.createSocket(address, port, localAddress, localPort).atOnce()
}
