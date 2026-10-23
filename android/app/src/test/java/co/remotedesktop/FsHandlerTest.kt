package co.remotedesktop

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FsHandlerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val saved = mutableListOf<String>()
    private val errors = mutableListOf<String>()

    private fun FsHandler.expect(xferId: Int, from: String? = "pc") =
        expectDownload(xferId, { saved.add(it) }, { errors.add(it) }, from)

    private fun FsHandler.begin(xferId: Int, size: Long, from: String = "pc") =
        handleJson(JSONObject().put("type", "fs-begin").put("from", from)
            .put("xferId", xferId).put("name", "a.txt").put("size", size), false)

    private fun FsHandler.chunk(xferId: Int, bytes: Int) =
        handleFileChunk(byteArrayOf(2, (xferId shr 24).toByte(), (xferId shr 16).toByte(),
            (xferId shr 8).toByte(), xferId.toByte()) + ByteArray(bytes))

    private fun FsHandler.end(xferId: Int, ok: Boolean, error: String? = null) =
        handleJson(JSONObject().put("type", "fs-end").put("from", "pc").put("xferId", xferId).put("ok", ok)
            .apply { if (error != null) put("error", error) }, false)

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
}
