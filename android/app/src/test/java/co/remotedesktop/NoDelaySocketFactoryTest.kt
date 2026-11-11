package co.remotedesktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.SocketFactory

/** The phone's sockets send small packets at once. Plain JVM, with a real socket on this machine. */
class NoDelaySocketFactoryTest {
    @Test
    fun everySocketItMakesHasNagleOff() {
        // What Java gives without it, and so what OkHttp had.
        Socket().use { assertFalse(it.tcpNoDelay) }

        val factory: SocketFactory = NoDelaySocketFactory()
        val local = InetAddress.getLoopbackAddress()
        ServerSocket(0, 5, local).use { server ->
            // Unconnected, which is how OkHttp asks for one, and still off once it connects.
            factory.createSocket().use { socket ->
                assertTrue(socket.tcpNoDelay)
                socket.connect(InetSocketAddress(local, server.localPort), 2000)
                server.accept().close()
                assertTrue(socket.tcpNoDelay)
            }
            // And each of the ways that connect at once.
            factory.createSocket(local, server.localPort).use { assertTrue(it.tcpNoDelay) }
            factory.createSocket(local.hostAddress, server.localPort).use { assertTrue(it.tcpNoDelay) }
            factory.createSocket(local, server.localPort, local, 0).use { assertTrue(it.tcpNoDelay) }
            factory.createSocket(local.hostAddress, server.localPort, local, 0).use { assertTrue(it.tcpNoDelay) }
        }
    }
}
