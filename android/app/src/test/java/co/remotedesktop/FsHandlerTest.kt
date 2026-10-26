package co.remotedesktop

import android.util.Base64
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FsHandlerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val saved = mutableListOf<String>()
    private val errors = mutableListOf<String>()
    private val socket = RecordingSocket()
    private val previous = mutableMapOf<String, Any?>()

    private fun connectionField(name: String) = ConnectionManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    @Before
    fun connect() {
        for (name in listOf("encKey", "ws")) previous[name] = connectionField(name).get(ConnectionManager)
        connectionField("encKey").set(ConnectionManager, socket.key)
        connectionField("ws").set(ConnectionManager, socket)
    }

    @After
    fun disconnect() {
        for ((name, value) in previous) connectionField(name).set(ConnectionManager, value)
    }

    private fun FsHandler.expect(xferId: Int, from: String? = "pc") =
        expectDownload(xferId, { saved.add(it) }, { errors.add(it) }, from)

    private fun FsHandler.begin(xferId: Int, size: Long, from: String = "pc", accessAllowed: Boolean = false) =
        handleJson(JSONObject().put("type", "fs-begin").put("from", from)
            .put("xferId", xferId).put("name", "a.txt").put("size", size), accessAllowed)

    private fun FsHandler.chunk(xferId: Int, bytes: Int) =
        handleFileChunk(byteArrayOf(2, (xferId shr 24).toByte(), (xferId shr 16).toByte(),
            (xferId shr 8).toByte(), xferId.toByte()) + ByteArray(bytes))

    private fun FsHandler.end(xferId: Int, ok: Boolean, error: String? = null) =
        handleJson(JSONObject().put("type", "fs-end").put("from", "pc").put("xferId", xferId).put("ok", ok)
            .apply { if (error != null) put("error", error) }, false)

    private fun FsHandler.cancelFrom(peer: String, xferId: Int) =
        handleJson(JSONObject().put("type", "fs-cancel").put("from", peer).put("xferId", xferId), true)

    private fun sent(type: String, xferId: Int) =
        socket.messages.filter { it.optString("type") == type && it.optInt("xferId") == xferId }

    private fun partFiles() = context.cacheDir.listFiles { f -> f.name.endsWith(".part") }.orEmpty()

    @Test
    fun aRefusedDownloadReportsItsErrorEvenWithoutFsBegin() {
        val fs = FsHandler(context)
        fs.expect(5)
        fs.end(5, ok = false, error = "File access is disabled on the remote device")
        assertEquals(listOf("File access is disabled on the remote device"), errors)
        // Reported once, and never for a transfer nobody asked for.
        fs.end(5, ok = false, error = "again")
        fs.end(6, ok = false, error = "unasked")
        assertEquals(1, errors.size)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun aTransferMissingBytesIsNeverSaved() {
        val fs = FsHandler(context)
        fs.expect(7)
        fs.begin(7, size = 5)
        fs.chunk(7, 3) // The relay dropped a chunk for a slow receiver.
        fs.end(7, ok = true)
        assertTrue(saved.isEmpty())
        assertEquals(listOf("Only 3 of 5 bytes arrived. Try again on a faster connection."), errors)
        assertTrue(partFiles().isEmpty())
    }

    @Test
    fun transfersThatCanNoLongerFinishFailInsteadOfWaiting() {
        val fs = FsHandler(context)
        fs.expect(8, from = "pc")
        fs.expect(9, from = "other")
        fs.expect(10, from = "pc")
        fs.begin(10, size = 100)
        fs.chunk(10, 10)

        fs.abort("pc", "Desk disconnected")
        assertEquals(listOf("Desk disconnected", "Desk disconnected"), errors)
        assertTrue("the half-received file is deleted", partFiles().isEmpty())

        fs.abort(reason = "The connection was lost")
        assertEquals(3, errors.size)
        assertEquals("The connection was lost", errors.last())
        // Nothing is reported twice, and late frames for an aborted transfer are ignored.
        fs.chunk(10, 10)
        fs.end(10, ok = true)
        fs.end(9, ok = false, error = "late")
        assertEquals(3, errors.size)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun aCancelledDownloadAsksTheSenderToStopAndKeepsNothing() {
        val fs = FsHandler(context)
        fs.expect(11)
        fs.begin(11, size = 10)
        fs.chunk(11, 3)
        assertTrue(fs.isActive(11))
        fs.cancel(11)
        assertFalse(fs.isActive(11))
        assertEquals("pc", sent("fs-cancel", 11).single().getString("to"))
        assertTrue("the half-received file is deleted", partFiles().isEmpty())
        // An older desktop ignores fs-cancel and finishes the file.
        fs.chunk(11, 7)
        fs.end(11, ok = true)
        assertTrue(saved.isEmpty() && errors.isEmpty() && sent("fs-saved", 11).isEmpty())

        fs.expect(12)
        fs.cancel(12)
        assertEquals("A download can be cancelled before it begins", 1, sent("fs-cancel", 12).size)
        // With file access on, its late file must still not be taken for a push.
        fs.begin(12, size = 1, accessAllowed = true)
        fs.chunk(12, 1)
        fs.end(12, ok = true)
        assertTrue(saved.isEmpty() && errors.isEmpty() && partFiles().isEmpty() && sent("fs-saved", 12).isEmpty())
    }

    @Test
    fun eitherEndCanCancelASendButNoOtherDevice() {
        val fs = FsHandler(context)
        val size = 3 * 256 * 1024
        socket.onChunk = { n -> fs.cancelFrom(if (n == 1) "someone-else" else "pc", 21) }
        assertFalse(fs.sendFile("pc", ByteArrayInputStream(ByteArray(size)), "big.bin", size.toLong(), 21))
        assertEquals("Only the receiving device can cancel", 2, socket.chunks)
        val end = sent("fs-end", 21).single()
        assertFalse(end.getBoolean("ok"))
        assertEquals("Cancelled by the receiver", end.getString("error"))
        assertFalse(fs.isActive(21))

        socket.chunks = 0
        socket.onChunk = { fs.cancel(22) }
        assertFalse(fs.sendFile("pc", ByteArrayInputStream(ByteArray(size)), "big.bin", size.toLong(), 22))
        assertEquals(1, socket.chunks)
        assertEquals("Cancelled by the sender", sent("fs-end", 22).single().getString("error"))
    }

    @Test
    fun anFsCancelRightBehindTheRequestStopsTheFileBeforeItStarts() {
        val fs = FsHandler(context)
        val exec = FsHandler::class.java.getDeclaredField("exec").apply { isAccessible = true }.get(fs) as ExecutorService
        val source = File(context.cacheDir, "source.bin").apply { writeBytes(ByteArray(1024)) }
        val gate = CountDownLatch(1)
        exec.execute { gate.await() } // The worker is busy with an earlier file.
        try {
            fs.handleJson(JSONObject().put("type", "fs-get").put("from", "pc")
                .put("path", source.path).put("xferId", 31), true)
            fs.cancelFrom("pc", 31)
            gate.countDown()
            exec.submit {}.get(5, TimeUnit.SECONDS)
            assertTrue(sent("fs-begin", 31).isEmpty())
            assertEquals("Cancelled by the receiver", sent("fs-end", 31).single().getString("error"))
            assertEquals(0, socket.chunks)
        } finally {
            exec.shutdownNow()
            source.delete()
        }
    }

    private class RecordingSocket : WebSocket {
        val key = ByteArray(32) { 7 }
        val messages: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
        @Volatile var chunks = 0
        /** Called with the number of file chunks sent so far. */
        var onChunk: ((Int) -> Unit)? = null
        override fun request() = Request.Builder().url("https://example.test/").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            val blob = Base64.decode(JSONObject(text).getString("d"), Base64.NO_WRAP)
            messages.add(JSONObject(String(Crypto.decrypt(key, blob, Crypto.CH_JSON)!!)))
            return true
        }
        override fun send(bytes: ByteString): Boolean {
            onChunk?.invoke(++chunks)
            return true
        }
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {}
    }
}
