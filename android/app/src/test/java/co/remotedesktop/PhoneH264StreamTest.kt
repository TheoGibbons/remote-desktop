package co.remotedesktop

import android.content.Context
import android.os.Looper
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
 * H.264 pictures on their way out of the phone: which viewers get them,
 * what stream they belong to, that one is never dropped once encoded, and
 * when a keyframe is asked of the encoder. The encoder itself is not here;
 * the test hands over pictures as the capture service would. Same setup as
 * PhoneStreamTest: no shadows, the real objects with their fields set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneH264StreamTest {
    private val socket = RecordingSocket()
    private val context get() = RuntimeEnvironment.getApplication()
    private val previous = mutableMapOf<String, Any?>()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
    private val keyUnit = byteArrayOf(0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x68, 2, 0, 0, 0, 1, 0x65, 3)
    private val unit = byteArrayOf(0, 0, 0, 1, 0x41, 4)
    private lateinit var previousIds: () -> Long
    private var ids = 0xA0L
    /** What the capture service was asked for: "key", "key by viewer", "resend". */
    private val asked = mutableListOf<String>()

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
        connectionField("relayAddressesBinary").set(ConnectionManager, true)
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Test PC"))
        ConnectionManager.peers.add(ConnectionManager.Peer("pc2", "windows", "Second PC"))
        ConnectionManager.peers.add(ConnectionManager.Peer("old-pc", "windows", "Old PC"))
        ConnectionManager.peers.add(ConnectionManager.Peer("phone", "android", "Test phone"))
        ConnectionManager.peers.add(ConnectionManager.Peer("stranger", "windows", "Stranger"))
        trustedPeers.addAll(listOf("pc", "pc2", "old-pc", "phone"))
        previous["allPeersTrusted"] = authField("allPeersTrusted").get(PeerAuth)
        authField("allPeersTrusted").set(PeerAuth, true)
        previous["capture"] = captureContext.get(PhoneScreenCapture)
        captureContext.set(PhoneScreenCapture, null)
        PhoneScreenCapture.init(context)
        (ScreenWaker::class.java.getDeclaredField("checking").apply { isAccessible = true }.get(ScreenWaker) as AtomicBoolean).set(false)
        lockField("listening").set(LockState, false)
        (lockField("told").get(LockState) as MutableMap<*, *>).clear()
        (PhoneScreenCapture::class.java.getDeclaredField("sentAt").apply { isAccessible = true }
            .get(PhoneScreenCapture) as MutableCollection<*>).clear()
        previousIds = PhoneScreenCapture.newStreamId
        PhoneScreenCapture.newStreamId = { ++ids }
        PhoneScreenCapture.askKeyframe = { byViewer -> asked += if (byViewer) "key by viewer" else "key" }
        PhoneScreenCapture.askResend = { asked += "resend" }
    }

    @After
    fun cleanup() {
        PhoneScreenCapture.onViewStopped()
        idle()
        PhoneScreenCapture.newStreamId = previousIds
        PhoneScreenCapture.askKeyframe = { ScreenCaptureService.keyframeWanted(it) }
        PhoneScreenCapture.askResend = { ScreenCaptureService.resendWanted() }
        captureContext.set(PhoneScreenCapture, previous.remove("capture"))
        authField("allPeersTrusted").set(PeerAuth, previous.remove("allPeersTrusted"))
        for ((name, value) in previous) connectionField(name).set(ConnectionManager, value)
        ConnectionManager.peers.clear()
        trustedPeers.clear()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun later(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun receive(from: String, msg: JSONObject) {
        handleJson.invoke(ConnectionManager, msg.put("from", from))
        idle()
    }

    /** A PC on a current app, with Windows' decoder: it offers both. */
    private fun startView(from: String, vararg codecs: String = arrayOf("h264", "jpeg")) =
        receive(from, JSONObject().put("type", "start-view").put("codecs", JSONArray(codecs.toList())))

    private fun startViewOld(from: String) = receive(from, JSONObject().put("type", "start-view"))
    private fun stopView(from: String) = receive(from, JSONObject().put("type", "stop-view"))

    private fun ack(from: String, stream: String, seq: Long) =
        receive(from, JSONObject().put("type", "frame-ack").put("stream", stream).put("seq", seq))

    private fun requestKeyframe(from: String, stream: String?) =
        receive(from, JSONObject().put("type", "request-keyframe").apply { if (stream != null) put("stream", stream) })

    /** What the capture service says when pictures start, or the size or the format changes. */
    private fun announce(video: Boolean = true, width: Int = 1080, height: Int = 2340,
                         capture: String = PhoneScreenCapture.SCREEN_SHARE) =
        PhoneScreenCapture.sendScreenInfo(context, width, height, capture, video)

    /** One encoded picture out of the capture service's encoder. */
    private fun video(keyframe: Boolean = false, repeat: Boolean = false, resend: Boolean = false,
                      width: Int = 1080, height: Int = 2340, capturedAt: Long = 1234) =
        PhoneScreenCapture.sendVideo(context, if (keyframe) keyUnit else unit, keyframe, repeat, resend,
            width, height, capturedAt)

    private fun picture(capture: String = PhoneScreenCapture.SCREEN_SHARE) =
        PhoneScreenCapture.send(context, jpeg, 1080, 2340, 1234, capture)

    private fun infos(to: String? = null) = socket.messages.filter {
        it.getString("type") == "screen-info" && (to == null || it.getString("to") == to)
    }

    private fun header(frame: RecordingSocket.Frame) = PhoneFrame.parse(frame.payload)!!
    private fun body(frame: RecordingSocket.Frame) = frame.payload.copyOfRange(PhoneFrame.HEADER_SIZE, frame.payload.size)
    private fun id(n: Int) = PhoneFrame.streamHex(0xA0L + n)

    // ---------------- who gets H.264 ----------------

    @Test
    fun aPcThatOffersH264GetsItAsType5FramesOfAStreamAnnouncedAsSuch() {
        startView("pc")
        assertTrue(PhoneScreenCapture.videoWanted())
        socket.clear()
        assertTrue(announce())
        infos().single().let {
            assertEquals("pc", it.getString("to"))
            assertEquals(id(1), it.getString("stream"))
            assertEquals("h264", it.getString("codec"))
            assertEquals("screen-share", it.getString("capture"))
            assertEquals(1080 to 2340, it.getInt("width") to it.getInt("height"))
        }

        assertEquals(VideoSent.SENT, video(keyframe = true, capturedAt = 0x1_0000_0005L))
        assertEquals(VideoSent.SENT, video())
        assertEquals(VideoSent.SENT, video(repeat = true))
        assertEquals(listOf("screen-info to pc", "frame 5", "frame 5", "frame 5"), socket.order)
        assertEquals(PhoneFrame.Header(0xA1, 0, keyframe = true, format = PhoneFrame.H264, width = 1080, height = 2340,
            captureMs = 5), header(socket.frames[0]))
        // One whole access unit and nothing else behind the header.
        assertArrayEquals(keyUnit, body(socket.frames[0]))
        assertEquals(PhoneFrame.Header(0xA1, 1, keyframe = false, format = PhoneFrame.H264, width = 1080, height = 2340,
            captureMs = 1234), header(socket.frames[1]))
        assertArrayEquals(unit, body(socket.frames[1]))
        assertEquals(PhoneFrame.Header(0xA1, 2, keyframe = false, format = PhoneFrame.H264, repeat = true,
            width = 1080, height = 2340, captureMs = 1234), header(socket.frames[2]))
        assertTrue(socket.frames.all { it.type == Crypto.CH_PHONE && it.to == listOf("pc") })
        // Nothing more was said, and no keyframe asked for: the stream began with one.
        assertEquals(1, infos().size)
        assertTrue(asked.isEmpty())

        receive("pc", JSONObject().put("type", "diagnostic-ping").put("nonce", 5L))
        assertEquals("h264", socket.messages.single { it.getString("type") == "diagnostic-pong" }.getString("codec"))
    }

    @Test
    fun aV1053PcGetsBareJpegsExactlyAsBefore() {
        startViewOld("old-pc")
        assertFalse(PhoneScreenCapture.videoWanted())
        socket.clear()
        // Whatever the capture service believed, nothing of an encoder's reaches it.
        assertTrue(announce(video = true))
        assertEquals(VideoSent.NOT_SENT, video(keyframe = true))
        assertTrue(socket.frames.isEmpty())
        infos().single().let {
            assertEquals(setOf("type", "width", "height", "typing", "to"), it.keys().asSequence().toSet())
        }
        assertTrue(picture())
        socket.frames.single().let {
            assertEquals(Crypto.CH_VIDEO, it.type)
            assertArrayEquals(jpeg, it.payload)
        }
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aPcThatDoesNotOfferH264AndAPhoneGetJpegInType5ExactlyAsBefore() {
        for (viewer in listOf("pc", "phone")) {
            startView(viewer, "jpeg")
            assertFalse(PhoneScreenCapture.videoWanted())
            socket.clear()
            assertTrue(announce(video = true))
            assertEquals("jpeg", infos().single().getString("codec"))
            assertEquals(VideoSent.NOT_SENT, video(keyframe = true))
            assertTrue(socket.frames.isEmpty())
            assertTrue(picture())
            socket.frames.single().let {
                assertEquals(Crypto.CH_PHONE, it.type)
                assertEquals(PhoneFrame.JPEG, header(it).format)
                assertTrue(header(it).keyframe)
                assertArrayEquals(jpeg, body(it))
            }
            // The stream that was announced is the one the JPEG went in: one stream, said once.
            assertEquals(1, infos().size)
            stopView(viewer)
        }
        assertTrue(asked.isEmpty())
    }

    @Test
    fun viewersThatDifferAllGetWhatTheLeastOfThemCanDraw() {
        // A PC with H.264 and a phone: JPEG for both.
        startView("pc")
        startView("phone", "jpeg")
        assertFalse(PhoneScreenCapture.videoWanted())
        assertEquals(VideoSent.NOT_SENT, video(keyframe = true))
        socket.clear()
        assertTrue(picture())
        assertEquals(PhoneFrame.JPEG, header(socket.frames.single()).format)
        assertEquals(setOf("pc", "phone"), socket.frames.single().to.toSet())

        // With an older app among them: bare JPEGs for all three.
        startViewOld("old-pc")
        assertFalse(PhoneScreenCapture.videoWanted())
        socket.clear()
        assertEquals(VideoSent.NOT_SENT, video(keyframe = true))
        assertTrue(picture())
        assertEquals(Crypto.CH_VIDEO, socket.frames.single().type)

        // Two PCs that both offer it: H.264, one stream for both.
        stopView("old-pc")
        stopView("phone")
        startView("pc2")
        assertTrue(PhoneScreenCapture.videoWanted())
        socket.clear()
        announce()
        assertEquals(VideoSent.SENT, video(keyframe = true))
        assertEquals(setOf("pc", "pc2"), socket.frames.single().to.toSet())
        assertEquals(setOf("h264"), infos().map { it.getString("codec") }.toSet())
        assertEquals(1, infos().map { it.getString("stream") }.toSet().size)
    }

    @Test
    fun accessibilityViewingStaysJpegWhateverTheViewersOffer() {
        startView("pc")
        socket.clear()
        // Its pictures are JPEGs, and that is what the stream is said to be.
        PhoneScreenCapture.sendScreenInfo(context, 1080, 2340, PhoneScreenCapture.ACCESSIBILITY)
        assertEquals("jpeg", infos().single().getString("codec"))
        assertTrue(picture(PhoneScreenCapture.ACCESSIBILITY))
        assertEquals(PhoneFrame.JPEG, header(socket.frames.single()).format)

        // Smooth sharing starts: a new stream, in H.264.
        socket.clear()
        announce()
        assertEquals(id(2), infos().single().getString("stream"))
        assertEquals("h264", infos().single().getString("codec"))
        assertEquals(VideoSent.SENT, video(keyframe = true))
        // And ends: JPEG again, in a third.
        socket.clear()
        assertTrue(picture(PhoneScreenCapture.ACCESSIBILITY))
        assertEquals(id(3), infos().single().getString("stream"))
        assertEquals("jpeg", infos().single().getString("codec"))
        assertEquals(PhoneFrame.Header(0xA3, 0, keyframe = true, format = PhoneFrame.JPEG, width = 1080, height = 2340,
            captureMs = 1234), header(socket.frames.single()))
    }

    // ---------------- an encoded picture is never dropped; what happens instead ----------------

    @Test
    fun anEncodedPictureIsSentHoweverFullTheQueueIs() {
        startView("pc")
        announce()
        assertEquals(VideoSent.SENT, video(keyframe = true))
        // Far past where a JPEG is passed over.
        socket.queued = 50 * ConnectionManager.VIDEO_QUEUE_LIMIT
        assertEquals(VideoSent.SENT, video())
        assertEquals(VideoSent.SENT, video())
        assertEquals(listOf(0L, 1L, 2L), socket.frames.map { header(it).seq })
        // What a full queue stops is the next encode: there is no room.
        assertFalse(PhoneScreenCapture.hasRoom())
        socket.queued = 0
    }

    @Test
    fun thereIsNoRoomToEncodeWhileAViewerIsBehind() {
        startView("pc")
        announce()
        video(keyframe = true)
        ack("pc", id(1), 0)
        assertTrue(PhoneScreenCapture.hasRoom())
        // The viewer acknowledged in an instant; now a picture goes unacknowledged for longer than that.
        video()
        later(InFlight.SLACK_MS + 50)
        assertFalse(PhoneScreenCapture.hasRoom())
        ack("pc", id(1), 1)
        assertTrue(PhoneScreenCapture.hasRoom())
    }

    @Test
    fun aStreamStartsWithAKeyframeAndNothingElseIsSentUntilOneComes() {
        startView("pc")
        announce()
        socket.clear()
        // The encoder was in the middle of things: these belong to no stream a viewer could draw.
        assertEquals(VideoSent.NEEDS_KEYFRAME, video())
        assertEquals(VideoSent.NEEDS_KEYFRAME, video(repeat = true))
        assertTrue(socket.frames.isEmpty())
        assertEquals(VideoSent.SENT, video(keyframe = true))
        assertEquals(VideoSent.SENT, video())
        // They took no numbers: the viewer counts no gap.
        assertEquals(listOf(0L, 1L), socket.frames.map { header(it).seq })
        assertEquals(listOf(true, false), socket.frames.map { header(it).keyframe })
    }

    @Test
    fun aPictureTheSocketWouldNotTakeIsFollowedByAKeyframeOrNothing() {
        startView("pc")
        announce()
        assertEquals(VideoSent.SENT, video(keyframe = true))
        assertEquals(VideoSent.SENT, video())
        socket.refuse = true
        assertEquals(VideoSent.NEEDS_KEYFRAME, video())
        socket.refuse = false
        // The pictures after it were built on it.
        assertEquals(VideoSent.NEEDS_KEYFRAME, video())
        assertEquals(VideoSent.NEEDS_KEYFRAME, video(repeat = true))
        assertEquals(2, socket.frames.size)
        assertEquals(VideoSent.SENT, video(keyframe = true))
        assertEquals(VideoSent.SENT, video())
        assertEquals(listOf(0L, 1L, 2L, 3L), socket.frames.map { header(it).seq })
        assertEquals(listOf(true, false, true, false), socket.frames.map { header(it).keyframe })
        // The same stream throughout.
        assertEquals(setOf(0xA1L), socket.frames.map { header(it).stream }.toSet())
    }

    @Test
    fun aPictureThatWentUnsentInABlinkOfTheGateIsFollowedByAKeyframe() {
        startView("pc")
        announce()
        assertEquals(VideoSent.SENT, video(keyframe = true))
        assertEquals(VideoSent.SENT, video())
        socket.clear()

        // A paired device joins the session: until its reply comes, not every peer is known.
        // Its reply comes before the main thread has looked, so the stream is never paused.
        authField("allPeersTrusted").set(PeerAuth, false)
        assertEquals(VideoSent.NOT_SENT, video())
        assertEquals(VideoSent.NOT_SENT, video(repeat = true))
        authField("allPeersTrusted").set(PeerAuth, true)

        // The stream goes on, but not with a picture built on ones the viewer never got.
        assertEquals(VideoSent.NEEDS_KEYFRAME, video())
        assertEquals(VideoSent.NEEDS_KEYFRAME, video(repeat = true))
        assertTrue(socket.frames.isEmpty())
        assertEquals(VideoSent.SENT, video(keyframe = true))
        assertEquals(VideoSent.SENT, video())
        // The same stream, and no gap in its numbers: nothing the viewer was sent is missing.
        assertEquals(listOf(2L, 3L), socket.frames.map { header(it).seq })
        assertEquals(listOf(true, false), socket.frames.map { header(it).keyframe })
        assertEquals(setOf(0xA1L), socket.frames.map { header(it).stream }.toSet())
        assertTrue(infos().isEmpty())
    }

    @Test
    fun aViewerWithoutVideoThatComesAndGoesInOneBreathLeavesAKeyframeOwed() {
        startView("pc")
        announce()
        video(keyframe = true)
        video()
        socket.clear()
        // A phone starts viewing and stops again before the main thread has turned.
        val viewers = PhoneScreenCapture::class.java.getDeclaredField("viewers").apply { isAccessible = true }
            .get(PhoneScreenCapture) as MutableMap<String, Set<String>>
        viewers["phone"] = setOf("jpeg")
        assertEquals(VideoSent.NOT_SENT, video())
        viewers.remove("phone")
        assertEquals(VideoSent.NEEDS_KEYFRAME, video())
        assertEquals(VideoSent.SENT, video(keyframe = true))
        assertEquals(PhoneFrame.Header(0xA1, 2, keyframe = true, format = PhoneFrame.H264, width = 1080, height = 2340,
            captureMs = 1234), header(socket.frames.single()))
    }

    @Test
    fun whenPicturesMayGoAgainAStillScreenIsAskedForTheKeyframeItOwes() {
        startView("pc")
        announce()
        video(keyframe = true)
        video()
        assertTrue(asked.isEmpty())
        // Nothing is owed: looking at who is viewing asks nothing of the encoder.
        PhoneScreenCapture.refresh()
        idle()
        assertTrue(asked.isEmpty())

        // The gate blinks, a picture goes unsent, and the screen then stays as it is.
        authField("allPeersTrusted").set(PeerAuth, false)
        assertEquals(VideoSent.NOT_SENT, video())
        authField("allPeersTrusted").set(PeerAuth, true)
        PhoneScreenCapture.refresh()
        idle()
        assertEquals(listOf("key"), asked)
        // The keyframe goes, and nothing more is asked.
        assertEquals(VideoSent.SENT, video(keyframe = true, repeat = true))
        PhoneScreenCapture.refresh()
        idle()
        assertEquals(listOf("key"), asked)
    }

    // ---------------- streams ----------------

    @Test
    fun changingBetweenH264AndJpegIsANewStreamEachTime() {
        startView("pc")
        announce()
        video(keyframe = true)
        assertEquals(id(1), infos("pc").last().getString("stream"))

        // A phone joins. The PC's H.264 stream is over: what the encoder still gives goes nowhere.
        startView("phone", "jpeg")
        socket.clear()
        assertEquals(VideoSent.NOT_SENT, video())
        assertEquals(VideoSent.NOT_SENT, video(keyframe = true))
        assertTrue(socket.frames.isEmpty())
        // The capture service, asked again by the phone's start-view, says what is true now.
        assertTrue(announce(video = true))
        assertEquals(setOf(id(2)), infos().map { it.getString("stream") }.toSet())
        assertEquals(setOf("jpeg"), infos().map { it.getString("codec") }.toSet())
        assertEquals(setOf("pc", "phone"), infos().map { it.getString("to") }.toSet())
        assertTrue(picture())
        assertEquals(PhoneFrame.Header(0xA2, 0, keyframe = true, format = PhoneFrame.JPEG, width = 1080, height = 2340,
            captureMs = 1234), header(socket.frames.single()))

        // The phone leaves: H.264 again, in a third stream that begins with a keyframe.
        stopView("phone")
        socket.clear()
        assertTrue(PhoneScreenCapture.sendScreenInfo(context, 1080, 2340, PhoneScreenCapture.SCREEN_SHARE,
            video = true, always = false))
        infos().single().let {
            assertEquals("pc", it.getString("to"))
            assertEquals(id(3), it.getString("stream"))
            assertEquals("h264", it.getString("codec"))
        }
        assertEquals(VideoSent.NEEDS_KEYFRAME, video())
        assertEquals(VideoSent.SENT, video(keyframe = true))
        assertEquals(PhoneFrame.Header(0xA3, 0, keyframe = true, format = PhoneFrame.H264, width = 1080, height = 2340,
            captureMs = 1234), header(socket.frames.single()))
    }

    @Test
    fun theEncoderFailingMidStreamStartsAJpegStream() {
        startView("pc")
        announce()
        video(keyframe = true)
        video()
        socket.clear()
        // The capture service has gone back to its reader: it says so, and the JPEGs follow.
        assertTrue(announce(video = false))
        infos().single().let {
            assertEquals(id(2), it.getString("stream"))
            assertEquals("jpeg", it.getString("codec"))
            assertFalse(it.has("paused"))
        }
        assertTrue(picture())
        assertTrue(picture())
        assertEquals(listOf("screen-info to pc", "frame 5", "frame 5"), socket.order)
        assertEquals(listOf(0L, 1L), socket.frames.map { header(it).seq })
        assertTrue(socket.frames.all { header(it).format == PhoneFrame.JPEG && header(it).stream == 0xA2L })
    }

    @Test
    fun aTurnedPhoneKeepsItsStreamAndSaysTheNewSizeFirst() {
        startView("pc")
        announce()
        video(keyframe = true)
        video()
        socket.clear()
        // The capture service has a new encoder at the new shape.
        assertTrue(announce(width = 2340, height = 1080))
        infos().single().let {
            assertEquals(id(1), it.getString("stream"))
            assertEquals(2340 to 1080, it.getInt("width") to it.getInt("height"))
        }
        assertEquals(VideoSent.SENT, video(keyframe = true, width = 2340, height = 1080))
        assertEquals(PhoneFrame.Header(0xA1, 2, keyframe = true, format = PhoneFrame.H264, width = 2340, height = 1080,
            captureMs = 1234), header(socket.frames.single()))
        assertEquals(listOf("screen-info to pc", "frame 5"), socket.order)
    }

    @Test
    fun aPauseEndsTheStreamAndTheNextBeginsWithAKeyframe() {
        startView("pc")
        announce()
        video(keyframe = true)
        video()

        // A device nobody has approved joins.
        socket.clear()
        authField("allPeersTrusted").set(PeerAuth, false)
        PhoneScreenCapture.refresh()
        idle()
        assertTrue(infos().single().getBoolean("paused"))
        assertEquals(VideoSent.NOT_SENT, video())
        assertEquals(VideoSent.NOT_SENT, video(keyframe = true))
        assertTrue(socket.frames.isEmpty())
        assertFalse(announce())

        // It is approved. The encoder is the same one, mid-stream; the stream is new.
        socket.clear()
        authField("allPeersTrusted").set(PeerAuth, true)
        PhoneScreenCapture.refresh()
        idle()
        assertTrue(announce())
        assertEquals(id(2), infos().single().getString("stream"))
        assertEquals(VideoSent.NEEDS_KEYFRAME, video())
        assertEquals(VideoSent.SENT, video(keyframe = true))
        assertEquals(PhoneFrame.Header(0xA2, 0, keyframe = true, format = PhoneFrame.H264, width = 1080, height = 2340,
            captureMs = 1234), header(socket.frames.single()))
    }

    @Test
    fun theLastViewerLeavingEndsTheStream() {
        startView("pc")
        announce()
        video(keyframe = true)
        stopView("pc")
        socket.clear()
        assertFalse(PhoneScreenCapture.videoWanted())
        assertEquals(VideoSent.NOT_SENT, video())
        assertEquals(VideoSent.NOT_SENT, video(keyframe = true))
        assertFalse(announce())
        assertTrue(socket.frames.isEmpty())
        assertTrue(socket.messages.isEmpty())
        // Nothing is waited for or asked of the encoder afterwards.
        later(60_000)
        assertTrue(asked.isEmpty())
    }

    // ---------------- keyframes ----------------

    @Test
    fun aViewerThatJoinsARunningStreamIsToldItsIdAndAKeyframeIsMade() {
        startView("pc")
        announce()
        video(keyframe = true)
        video()
        assertTrue(asked.isEmpty())

        startView("pc2")
        socket.clear()
        // Its start-view has the capture service say how things stand.
        assertTrue(announce())
        assertEquals(id(1), infos("pc2").single().getString("stream"))
        assertEquals(listOf("key"), asked)
        // The stream goes on meanwhile for the viewer that was there.
        assertEquals(VideoSent.SENT, video())
        assertEquals(VideoSent.SENT, video(keyframe = true, repeat = true))
        assertEquals(listOf(2L, 3L), socket.frames.map { header(it).seq })
        assertEquals(listOf("key"), asked)

        // A viewer that asks again, having started over, gets one too.
        startView("pc")
        assertTrue(announce())
        assertEquals(listOf("key", "key"), asked)
    }

    @Test
    fun aViewerJoiningAStreamThatHasSentNothingNeedsNoExtraKeyframe() {
        startView("pc")
        announce()
        startView("pc2")
        announce()
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aViewersRequestForAKeyframeIsPassedOnOnlyForTheStreamBeingSent() {
        startView("pc")
        announce()
        video(keyframe = true)
        ack("pc", id(1), 0)
        video()

        requestKeyframe("pc", id(1))
        assertEquals(listOf("key by viewer"), asked)
        // Another stream's, which the viewer has from before a change it has not heard of yet.
        requestKeyframe("pc", id(7))
        requestKeyframe("pc", "nonsense")
        assertEquals(1, asked.size)
        // One that names no stream is taken to mean this one.
        requestKeyframe("pc", null)
        assertEquals(2, asked.size)

        // Approved but not viewing, and not approved at all.
        requestKeyframe("pc2", id(1))
        requestKeyframe("stranger", id(1))
        assertEquals(2, asked.size)
        // With control switched off nobody is viewing.
        Prefs(context).allowControl = false
        idle()
        requestKeyframe("pc", id(1))
        assertEquals(2, asked.size)
    }

    @Test
    fun aRequestThatCrossedAKeyframeOnItsWayIsDropped() {
        startView("pc")
        announce()
        video(keyframe = true)
        ack("pc", id(1), 0)
        video()
        video(keyframe = true)
        // The viewer has not had frame 2 yet when it asks.
        requestKeyframe("pc", id(1))
        assertTrue(asked.isEmpty())
        // It has had it, and still asks: its decoder failed on it.
        ack("pc", id(1), 2)
        requestKeyframe("pc", id(1))
        assertEquals(listOf("key by viewer"), asked)

        // A keyframe that never arrives stops counting after a second.
        video(keyframe = true)
        later(999)
        requestKeyframe("pc", id(1))
        assertEquals(1, asked.size)
        later(1)
        requestKeyframe("pc", id(1))
        assertEquals(2, asked.size)
    }

    @Test
    fun nobodyAsksAKeyframeOfAJpegStream() {
        startView("pc", "jpeg")
        picture()
        requestKeyframe("pc", id(1))
        requestKeyframe("pc", null)
        later(60_000)
        assertTrue(asked.none { it.startsWith("key") })
    }

    @Test
    fun anUnacknowledgedPictureHasTheScreenEncodedAgain() {
        startView("pc")
        announce()
        video(keyframe = true)
        ack("pc", id(1), 0)
        video()
        socket.clear()
        // This viewer has been answering, so for two seconds it is only behind.
        later(1_999)
        assertTrue(asked.isEmpty())
        later(101)
        assertEquals(listOf("resend"), asked)
        // Nothing can be sent a second time: no frame went out by itself.
        assertTrue(socket.frames.isEmpty())

        // The encoder's answer goes out marked as what it is, and the wait doubles.
        assertEquals(VideoSent.SENT, video(keyframe = true, repeat = true, resend = true))
        assertTrue(header(socket.frames.single()).repeat)
        later(1_900)
        assertEquals(1, asked.size)
        later(200)
        assertEquals(2, asked.size)

        // Acknowledged: nothing more is asked.
        video(keyframe = true, repeat = true, resend = true)
        ack("pc", id(1), 3)
        later(60_000)
        assertEquals(2, asked.size)
    }

    private class RecordingSocket : WebSocket {
        class Frame(val type: Byte, val to: List<String>, val payload: ByteArray)

        val key = ByteArray(32) { 7 }
        val messages = mutableListOf<JSONObject>()
        val frames = mutableListOf<Frame>()
        val order = mutableListOf<String>()
        var queued = 0L
        /** The socket takes no more: it is closing, or its own limit is passed. */
        var refuse = false

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
            if (msg.getString("type") == "screen-info") order.add("screen-info to " + envelope.getString("to"))
            return true
        }
        override fun send(bytes: ByteString): Boolean {
            if (refuse) return false
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
