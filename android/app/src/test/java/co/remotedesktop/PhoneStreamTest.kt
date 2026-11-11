package co.remotedesktop

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How a phone sends its pictures to each mix of viewers: type 5 frames of an
 * announced stream when every viewer offered `codecs`, bare type 1 JPEGs when
 * any did not, and what each is told in `screen-info`. Same setup as
 * LockStateTest: no shadows, the real objects with their fields set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneStreamTest {
    private val socket = RecordingSocket()
    private val context get() = RuntimeEnvironment.getApplication()
    private val previous = mutableMapOf<String, Any?>()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
    private lateinit var previousIds: () -> Long
    private var ids = 0xA0L

    private fun connectionField(name: String) = ConnectionManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    private fun authField(name: String) = PeerAuth::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun lockField(name: String) = LockState::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private val trustedPeers get() = authField("trustedPeers").get(PeerAuth) as MutableSet<String>

    private val captureContext = PhoneScreenCapture::class.java.getDeclaredField("appContext")
        .apply { isAccessible = true }

    private val handleJson = ConnectionManager::class.java.getDeclaredMethod("handleJson", JSONObject::class.java)
        .apply { isAccessible = true }

    @Before
    fun setup() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        for (name in listOf("appContext", "encKey", "ws", "state", "relayAddressesBinary"))
            previous[name] = connectionField(name).get(ConnectionManager)
        connectionField("appContext").set(ConnectionManager, context)
        connectionField("encKey").set(ConnectionManager, socket.key)
        connectionField("ws").set(ConnectionManager, socket)
        connectionField("state").set(ConnectionManager, "connected")
        // A current relay, so each frame says who it is for.
        connectionField("relayAddressesBinary").set(ConnectionManager, true)
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Test PC"))
        ConnectionManager.peers.add(ConnectionManager.Peer("old-pc", "windows", "Old PC"))
        ConnectionManager.peers.add(ConnectionManager.Peer("phone", "android", "Test phone"))
        ConnectionManager.peers.add(ConnectionManager.Peer("stranger", "windows", "Stranger"))
        trustedPeers.addAll(listOf("pc", "old-pc", "phone"))
        previous["allPeersTrusted"] = authField("allPeersTrusted").get(PeerAuth)
        authField("allPeersTrusted").set(PeerAuth, true)
        // It keeps the context of whichever test first set it up; this test's switches live in this one's.
        previous["capture"] = captureContext.get(PhoneScreenCapture)
        captureContext.set(PhoneScreenCapture, null)
        PhoneScreenCapture.init(context)
        // Left behind by a test that ended with a wake still to be looked at, or with a viewer.
        (ScreenWaker::class.java.getDeclaredField("checking").apply { isAccessible = true }.get(ScreenWaker) as AtomicBoolean).set(false)
        lockField("listening").set(LockState, false)
        (lockField("told").get(LockState) as MutableMap<*, *>).clear()
        // Ids a test can name: a1, a2, a3...
        previousIds = PhoneScreenCapture.newStreamId
        PhoneScreenCapture.newStreamId = { ++ids }
    }

    @After
    fun cleanup() {
        PhoneScreenCapture.onViewStopped()
        idle()
        PhoneScreenCapture.newStreamId = previousIds
        captureContext.set(PhoneScreenCapture, previous.remove("capture"))
        authField("allPeersTrusted").set(PeerAuth, previous.remove("allPeersTrusted"))
        for ((name, value) in previous) connectionField(name).set(ConnectionManager, value)
        ConnectionManager.peers.clear()
        trustedPeers.clear()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun receive(from: String, msg: JSONObject) {
        handleJson.invoke(ConnectionManager, msg.put("from", from))
        idle()
    }

    /** A viewer on a current app: it says what it can draw. */
    private fun startView(from: String, vararg codecs: String = arrayOf("jpeg")) =
        receive(from, JSONObject().put("type", "start-view").put("codecs", JSONArray(codecs.toList())))

    /** A viewer on v1.0.53 or older. */
    private fun startViewOld(from: String) = receive(from, JSONObject().put("type", "start-view"))

    private fun picture(capture: String = PhoneScreenCapture.ACCESSIBILITY, capturedAt: Long = 1234) =
        PhoneScreenCapture.send(context, jpeg, 1080, 2340, capturedAt, capture)

    private fun screenInfo(capture: String = PhoneScreenCapture.ACCESSIBILITY, width: Int = 1080, height: Int = 2340) =
        PhoneScreenCapture.sendScreenInfo(context, width, height, capture)

    private fun infos(to: String? = null) = socket.messages.filter {
        it.getString("type") == "screen-info" && (to == null || it.getString("to") == to)
    }

    private fun header(frame: RecordingSocket.Frame) = PhoneFrame.parse(frame.payload)!!

    private fun id(n: Int) = PhoneFrame.streamHex(0xA0L + n)

    @Test
    fun aViewerThatOffersJpegGetsType5FramesOfAStreamItWasToldFirst() {
        startView("pc")
        socket.clear()
        assertTrue(picture(capturedAt = 0x1_0000_0005L))

        // Told before the first frame, by itself and not broadcast.
        assertEquals(listOf("screen-info to pc", "frame 5"), socket.order)
        infos().single().let {
            assertEquals("pc", it.getString("to"))
            assertEquals(id(1), it.getString("stream"))
            assertEquals("jpeg", it.getString("codec"))
            assertEquals("accessibility", it.getString("capture"))
            assertEquals(1080, it.getInt("width"))
            assertEquals(2340, it.getInt("height"))
            assertTrue(it.has("typing"))
            assertFalse(it.has("paused"))
        }
        socket.frames.single().let { frame ->
            assertEquals(Crypto.CH_PHONE, frame.type)
            assertEquals(listOf("pc"), frame.to)
            assertEquals(PhoneFrame.Header(0xA1, 0, keyframe = true, format = PhoneFrame.JPEG, width = 1080, height = 2340,
                // The low 32 bits of the phone's clock.
                captureMs = 5), header(frame))
            assertArrayEquals(jpeg, frame.payload.copyOfRange(PhoneFrame.HEADER_SIZE, frame.payload.size))
        }

        // The stream goes on: one number up each time, and nothing more to say.
        assertTrue(picture())
        assertTrue(picture())
        assertEquals(listOf(0L, 1L, 2L), socket.frames.map { header(it).seq })
        assertEquals(setOf(0xA1L), socket.frames.map { header(it).stream }.toSet())
        assertEquals(1, infos().size)
    }

    @Test
    fun aViewerOnAnOlderAppGetsTheBareJpegAndTheScreenInfoItAlwaysDid() {
        startViewOld("old-pc")
        socket.clear()
        // Where both ways of capturing have always sent screen-info: once per viewer, as it was.
        screenInfo()
        assertTrue(picture())
        assertTrue(picture())
        infos().single().let {
            assertEquals("old-pc", it.getString("to"))
            assertEquals(setOf("type", "width", "height", "typing", "to"), it.keys().asSequence().toSet())
        }
        // Byte for byte the JPEG, with nothing in front of it.
        assertEquals(listOf(Crypto.CH_VIDEO, Crypto.CH_VIDEO), socket.frames.map { it.type })
        socket.frames.forEach {
            assertArrayEquals(jpeg, it.payload)
            assertEquals(listOf("old-pc"), it.to)
        }
        // A v1.0.53 PC sizes its window on every screen-info, so pictures alone bring it none.
        assertEquals(listOf("screen-info to old-pc", "frame 1", "frame 1"), socket.order)
    }

    @Test
    fun oneOlderViewerPutsEveryoneOnType1AndItsLeavingStartsAStream() {
        startView("pc")
        picture()
        assertEquals(Crypto.CH_PHONE, socket.frames.last().type)

        // An older viewer joins. The stream ends before the first bare JPEG.
        startViewOld("old-pc")
        socket.clear()
        assertTrue(picture())
        assertEquals(listOf("screen-info to pc", "frame 1"), socket.order)
        infos("pc").single().let {
            assertFalse(it.has("stream"))
            assertFalse(it.has("codec"))
            // Not paused: pictures are coming, as type 1.
            assertFalse(it.has("paused"))
            assertEquals("accessibility", it.getString("capture"))
        }
        assertEquals(setOf("pc", "old-pc"), socket.frames.single().to.toSet())
        assertArrayEquals(jpeg, socket.frames.single().payload)
        // The older viewer was sent nothing extra.
        assertTrue(infos("old-pc").isEmpty())
        picture()
        assertEquals(1, infos().size)

        // It leaves. A new stream, with a new id, for the one that stays.
        receive("old-pc", JSONObject().put("type", "stop-view"))
        socket.clear()
        assertTrue(picture())
        assertEquals(listOf("screen-info to pc", "frame 5"), socket.order)
        assertEquals(id(2), infos("pc").single().getString("stream"))
        assertEquals(PhoneFrame.Header(0xA2, 0, keyframe = true, format = PhoneFrame.JPEG, width = 1080, height = 2340,
            captureMs = 1234), header(socket.frames.single()))
        assertEquals(listOf("pc"), socket.frames.single().to)
    }

    @Test
    fun aPhoneAndAPcViewingTogetherShareOneStream() {
        startView("pc")
        startView("phone")
        socket.clear()
        screenInfo()
        assertTrue(picture())
        assertEquals(setOf("pc", "phone"), infos().map { it.getString("to") }.toSet())
        assertEquals(setOf(id(1)), infos().map { it.getString("stream") }.toSet())
        socket.frames.single().let {
            assertEquals(Crypto.CH_PHONE, it.type)
            assertEquals(setOf("pc", "phone"), it.to.toSet())
        }

        // A viewer that joins a stream is told its id, and the others nothing new.
        receive("phone", JSONObject().put("type", "stop-view"))
        socket.clear()
        startView("phone")
        socket.clear()
        assertTrue(picture())
        assertEquals(listOf("screen-info to phone", "frame 5"), socket.order)
        assertEquals(id(1), infos("phone").single().getString("stream"))
        assertEquals(1L, header(socket.frames.single()).seq)
    }

    @Test
    fun aListWithoutJpegIsAViewerThatCannotDrawType5() {
        // "h264" alone: nothing this phone sends is on its list.
        startView("pc", "h264")
        socket.clear()
        assertTrue(picture())
        assertEquals(Crypto.CH_VIDEO, socket.frames.single().type)
        // It did offer codecs, so it is told there is no stream.
        assertFalse(infos("pc").single().has("stream"))

        // Sending start-view again replaces the list.
        startView("pc", "h264", "jpeg")
        socket.clear()
        assertTrue(picture())
        assertEquals(Crypto.CH_PHONE, socket.frames.single().type)
        assertEquals(id(1), infos("pc").last().getString("stream"))

        // And without one, it is an older viewer from then on.
        startViewOld("pc")
        socket.clear()
        assertTrue(picture())
        assertEquals(Crypto.CH_VIDEO, socket.frames.single().type)
        assertTrue(infos().isEmpty())
    }

    @Test
    fun aViewerThatAsksAgainIsToldTheStreamAgain() {
        startView("pc")
        picture()
        socket.clear()
        // It may have started over, e.g. after this phone approved it.
        startView("pc")
        socket.clear()
        assertTrue(picture())
        assertEquals(listOf("screen-info to pc", "frame 5"), socket.order)
        // The same stream, going on.
        assertEquals(id(1), infos().single().getString("stream"))
        assertEquals(1L, header(socket.frames.single()).seq)
    }

    @Test
    fun aPauseEndsTheStreamAndTheNextOneHasANewId() {
        startView("pc")
        startViewOld("old-pc")
        receive("old-pc", JSONObject().put("type", "stop-view"))
        screenInfo()
        picture()
        assertEquals(id(1), infos("pc").last().getString("stream"))

        // A device nobody has approved joins: no pictures while it is there.
        socket.clear()
        authField("allPeersTrusted").set(PeerAuth, false)
        PhoneScreenCapture.refresh()
        idle()
        infos().single().let {
            assertEquals("pc", it.getString("to"))
            assertFalse(it.has("stream"))
            assertTrue(it.getBoolean("paused"))
        }
        assertFalse(picture())
        screenInfo()
        // Told once, however often the gate is looked at.
        PhoneScreenCapture.refresh()
        idle()
        assertEquals(1, infos().size)
        assertTrue(socket.frames.isEmpty())

        // It is approved, or leaves.
        socket.clear()
        authField("allPeersTrusted").set(PeerAuth, true)
        PhoneScreenCapture.refresh()
        idle()
        assertTrue(picture())
        assertEquals(listOf("screen-info to pc", "frame 5"), socket.order)
        assertEquals(id(2), infos("pc").single().getString("stream"))
        assertEquals(PhoneFrame.Header(0xA2, 0, keyframe = true, format = PhoneFrame.JPEG, width = 1080, height = 2340,
            captureMs = 1234), header(socket.frames.single()))
    }

    @Test
    fun anOlderViewerIsToldNothingWhenPicturesPause() {
        startViewOld("old-pc")
        screenInfo()
        picture()
        socket.clear()
        authField("allPeersTrusted").set(PeerAuth, false)
        PhoneScreenCapture.refresh()
        idle()
        assertTrue(infos().isEmpty())
    }

    @Test
    fun theStreamEndsWithItsLastViewerAndStartsAnewWhenTheCaptureChanges() {
        startView("pc")
        picture()
        receive("pc", JSONObject().put("type", "stop-view"))
        startView("pc")
        socket.clear()
        assertTrue(picture())
        assertEquals(id(2), infos("pc").single().getString("stream"))
        assertEquals(0L, header(socket.frames.single()).seq)

        // Smooth sharing takes over: a new stream, said before its first frame.
        socket.clear()
        screenInfo(PhoneScreenCapture.SCREEN_SHARE)
        assertTrue(picture(PhoneScreenCapture.SCREEN_SHARE))
        assertEquals(listOf("screen-info to pc", "frame 5"), socket.order)
        infos("pc").single().let {
            assertEquals(id(3), it.getString("stream"))
            assertEquals("screen-share", it.getString("capture"))
        }
        assertEquals(PhoneFrame.Header(0xA3, 0, keyframe = true, format = PhoneFrame.JPEG, width = 1080, height = 2340,
            captureMs = 1234), header(socket.frames.single()))
    }

    @Test
    fun aNewSizeIsSaidBeforeThePictureThatHasIt() {
        startView("pc")
        screenInfo()
        picture()
        socket.clear()
        // The phone turns: the capture says so where it always has, then sends.
        assertTrue(screenInfo(width = 2340, height = 1080))
        assertTrue(PhoneScreenCapture.send(context, jpeg, 2340, 1080, 99, PhoneScreenCapture.ACCESSIBILITY))
        assertEquals(listOf("screen-info to pc", "frame 5"), socket.order)
        infos().single().let {
            assertEquals(2340, it.getInt("width"))
            assertEquals(1080, it.getInt("height"))
            // The same stream: a turn is not a new one.
            assertEquals(id(1), it.getString("stream"))
        }
        header(socket.frames.single()).let {
            assertEquals(2340 to 1080, it.width to it.height)
            assertEquals(1L, it.seq)
        }
    }

    @Test
    fun nothingIsEverSentToNobody() {
        // Nobody is viewing. To the relay, a frame with no names on it is for everyone.
        assertFalse(picture())
        // The size was not said either, and the capture is told so: it still owes it.
        assertFalse(screenInfo())
        // Someone who may not view asked.
        startView("stranger")
        assertFalse(picture())
        // A viewer this phone no longer trusts, before anything else has caught up.
        startView("pc")
        trustedPeers.remove("pc")
        assertFalse(picture())
        screenInfo()
        // Other phones were switched off under a phone that was viewing.
        trustedPeers.add("pc")
        receive("pc", JSONObject().put("type", "stop-view"))
        startView("phone")
        Prefs(context).allowPhones = false
        assertFalse(picture())
        screenInfo()
        assertTrue(socket.frames.isEmpty())
        assertTrue(infos().none { it.has("stream") })
    }

    @Test
    fun aPictureTheQueueRefusesTakesNoNumber() {
        startView("pc")
        assertTrue(picture())
        socket.queued = ConnectionManager.VIDEO_QUEUE_LIMIT + 1
        assertFalse(picture())
        assertFalse(picture())
        socket.queued = 0
        assertTrue(picture())
        // No gap for the viewer to count: nothing was lost on the way.
        assertEquals(listOf(0L, 1L), socket.frames.map { header(it).seq })
    }

    @Test
    fun onlyADeviceThatIsViewingIsAnsweredOrHeard() {
        startView("pc")
        // Pictures another test sent are over a second old by now.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        picture()
        picture()
        socket.clear()
        fun ping(from: String) = receive(from, JSONObject().put("type", "diagnostic-ping").put("nonce", 77L))
        fun ack(from: String, stream: String, seq: Long) =
            receive(from, JSONObject().put("type", "frame-ack").put("stream", stream).put("seq", seq))

        // Approved, but not viewing; and not approved at all.
        ping("old-pc")
        ping("stranger")
        ack("old-pc", id(1), 1)
        ack("stranger", id(1), 1)
        assertTrue(socket.messages.none { it.getString("type") == "diagnostic-pong" })
        assertNull(PhoneScreenCapture.ackedSeq("old-pc"))
        assertNull(PhoneScreenCapture.ackedSeq("stranger"))

        val before = SystemClock.uptimeMillis()
        ping("pc")
        socket.messages.single { it.getString("type") == "diagnostic-pong" }.let {
            assertEquals("pc", it.getString("to"))
            assertEquals(77L, it.getLong("nonce"))
            // The clock captureMs is read from.
            assertTrue(it.getLong("hostTimeMs") in before..SystemClock.uptimeMillis())
            assertEquals(0L, it.getLong("hostQueueBytes"))
            assertEquals("jpeg", it.getString("codec"))
            assertEquals("accessibility", it.getString("capture"))
            assertEquals(2, it.getInt("fps"))
            assertFalse(it.has("bitrate"))
        }

        // Of another stream, or of a frame that was never sent: not this stream's.
        ack("pc", id(9), 1)
        ack("pc", id(1), 2)
        ack("pc", "nonsense", 0)
        assertNull(PhoneScreenCapture.ackedSeq("pc"))
        ack("pc", id(1), 1)
        assertEquals(1L, PhoneScreenCapture.ackedSeq("pc"))
        // The highest one counts.
        ack("pc", id(1), 0)
        assertEquals(1L, PhoneScreenCapture.ackedSeq("pc"))

        // With control switched off, nobody is viewing and nothing is answered.
        socket.clear()
        Prefs(context).allowControl = false
        idle()
        ping("pc")
        ack("pc", id(1), 1)
        assertTrue(socket.messages.none { it.getString("type") == "diagnostic-pong" })
        assertNull(PhoneScreenCapture.ackedSeq("pc"))
    }

    @Test
    fun thePhonesConnectionIsMadeWithNagleOff() {
        val client = connectionField("client").get(ConnectionManager) as okhttp3.OkHttpClient
        assertTrue(client.socketFactory is NoDelaySocketFactory)
    }

    private class RecordingSocket : WebSocket {
        class Frame(val type: Byte, val to: List<String>, val payload: ByteArray)

        val key = ByteArray(32) { 7 }
        val messages = mutableListOf<JSONObject>()
        val frames = mutableListOf<Frame>()
        /** `screen-info`s and frames in the order they were sent. */
        val order = mutableListOf<String>()
        var queued = 0L

        fun clear() {
            messages.clear()
            frames.clear()
            order.clear()
        }

        override fun request() = Request.Builder().url("https://example.test/").build()
        override fun queueSize() = queued
        override fun send(text: String): Boolean {
            val envelope = JSONObject(text)
            val blob = Base64.decode(envelope.getString("d"), Base64.NO_WRAP)
            val msg = JSONObject(String(Crypto.decrypt(key, blob, Crypto.CH_JSON)!!))
            messages.add(msg)
            // The relay routes by the envelope, so that is where the name has to be.
            if (msg.getString("type") == "screen-info") order.add("screen-info to " + envelope.getString("to"))
            return true
        }
        override fun send(bytes: ByteString): Boolean {
            val wire = bytes.toByteArray()
            val type = (wire[0].toInt() and 0x7F).toByte()
            val to = mutableListOf<String>()
            var at = 1
            if (wire[0].toInt() and 0x80 != 0) {
                repeat(wire[at++].toInt() and 0xFF) {
                    val length = wire[at++].toInt() and 0xFF
                    to.add(String(wire, at, length))
                    at += length
                }
            }
            frames.add(Frame(type, to, Crypto.decrypt(key, wire.copyOfRange(at, wire.size), type)!!))
            order.add("frame $type")
            return true
        }
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {}
    }
}
