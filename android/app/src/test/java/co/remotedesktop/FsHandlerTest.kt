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
    @Test
    fun aRefusedDownloadReportsItsErrorEvenWithoutFsBegin() {
        val fs = FsHandler(RuntimeEnvironment.getApplication())
        val saved = mutableListOf<String>()
        val errors = mutableListOf<String>()
        fs.expectDownload(5, { saved.add(it) }, { errors.add(it) })
        fun end(xferId: Int) = fs.handleJson(JSONObject().put("type", "fs-end").put("from", "pc")
            .put("xferId", xferId).put("ok", false).put("error", "File access is disabled on the remote device"), false)
        end(5)
        assertEquals(listOf("File access is disabled on the remote device"), errors)
        // Reported once, and never for a transfer nobody asked for.
        end(5)
        end(6)
        assertEquals(1, errors.size)
        assertTrue(saved.isEmpty())
    }
}
