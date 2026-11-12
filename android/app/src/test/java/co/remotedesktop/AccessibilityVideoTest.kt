package co.remotedesktop

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.HandlerThread
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Accessibility service's screenshots on their way out as video: the
 * real capture class, this phone's real stream and socket, and a made-up
 * encoder in place of the phone's. Who gets video and who gets JPEGs exactly
 * as before, that a still screen sends nothing, what a turn, a pause and a
 * change to and from smooth sharing do to the stream, and that whatever
 * happens to the encoder the next screenshot reaches the viewer as a JPEG.
 * Same setup as PhoneH264StreamTest: no shadows, the real objects with
 * their fields set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilityVideoTest {
    private val socket = RecordingSocket()
    private val context get() = RuntimeEnvironment.getApplication()
    private val previous = mutableMapOf<String, Any?>()
    private lateinit var previousIds: () -> Long
    private lateinit var previousWedge: VideoWedge
    private var ids = 0xA0L

    /** The clock a stalled encoder is remembered by: one that goes on while the phone sleeps. */
    private var wedgeClock = 1_000_000L

    // The phone's encoder, made up.
    private val timer = FakeVideoTimer()
    private val pipes = ArrayList<FakeVideoPipe>()
    private var refuse: (VideoSetup.Attempt) -> Boolean = { false }
    private var quit = 0
    private val parts = VideoParts(
        encoders = { listOf(VideoSetup.Encoder("hardware", hardware = true, constantBitrate = true) { _, _ -> true }) },
        open = { attempt, _, _, events ->
            if (refuse(attempt)) throw IllegalStateException("cannot configure ${attempt.width}")
            FakeVideoPipe(attempt, events).also { pipe ->
                // As on a phone: the texture says it has the picture a moment after it was handed over.
                pipe.afterShow = { timer.post { pipe.arrived() } }
                pipes += pipe
            }
        },
        timer = timer,
        post = { it.run() },
        clock = { timer.now },
        quit = { quit++ },
    )

    private lateinit var service: CaptureTestService
    private lateinit var capture: AccessibilityScreenCapture
    private lateinit var input: ServiceController<InputAccessibilityService>
    private var sharing: ServiceController<ScreenCaptureService>? = null

    private fun connectionField(name: String) = ConnectionManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    private fun authField(name: String) = PeerAuth::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun lockField(name: String) = LockState::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun captureField(name: String) = AccessibilityScreenCapture::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private val trustedPeers get() = authField("trustedPeers").get(PeerAuth) as MutableSet<String>

    private val captureContext = PhoneScreenCapture::class.java.getDeclaredField("appContext")
        .apply { isAccessible = true }

    private val inputInstance = InputAccessibilityService::class.java.getDeclaredField("instance")
        .apply { isAccessible = true }

    private val sharingInstance = ScreenCaptureService::class.java.getDeclaredField("instance")
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
        ScreenCaptureService.videoWedged.clear()

        // The Accessibility service, switched on, with this test's capture in place of its own:
        // viewers coming and going, and requests for keyframes, reach the capture the app's own way.
        service = Robolectric.buildService(CaptureTestService::class.java).create().get()
        previousWedge = AccessibilityScreenCapture.wedged
        AccessibilityScreenCapture.wedged = VideoWedge({ wedgeClock })
        capture = AccessibilityScreenCapture(service, { parts }) { buffer, _ -> wrap(buffer) }
        input = Robolectric.buildService(InputAccessibilityService::class.java).create()
        InputAccessibilityService::class.java.getDeclaredField("screenCapture")
            .apply { isAccessible = true }.set(input.get(), capture)
        inputInstance.set(null, input.get())
        settle()
        socket.clear()
    }

    @After
    fun cleanup() {
        sharingInstance.set(null, null)
        PhoneScreenCapture.onViewStopped()
        settle()
        capture.close()
        inputInstance.set(null, null)
        PhoneScreenCapture.newStreamId = previousIds
        AccessibilityScreenCapture.wedged = previousWedge
        captureContext.set(PhoneScreenCapture, previous.remove("capture"))
        authField("allPeersTrusted").set(PeerAuth, previous.remove("allPeersTrusted"))
        for ((name, value) in previous) connectionField(name).set(ConnectionManager, value)
        ConnectionManager.peers.clear()
        trustedPeers.clear()
        ScreenCaptureService.videoWedged.clear()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** Lets the main thread and the capture's own thread finish what they have been asked to do. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
        if (::capture.isInitialized) {
            val worker = captureField("thread").get(capture) as HandlerThread
            if (worker.isAlive) shadowOf(worker.looper).idle()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun later(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        settle()
    }

    private fun receive(from: String, msg: JSONObject) {
        handleJson.invoke(ConnectionManager, msg.put("from", from))
        settle()
    }

    /** A PC on a current app, with Windows' decoder: it offers both. */
    private fun startView(from: String, vararg codecs: String = arrayOf("h264", "jpeg")) =
        receive(from, JSONObject().put("type", "start-view").put("codecs", JSONArray(codecs.toList())))

    private fun startViewOld(from: String) = receive(from, JSONObject().put("type", "start-view"))
    private fun stopView(from: String) = receive(from, JSONObject().put("type", "stop-view"))

    private fun infos(to: String? = null) = socket.messages.filter {
        it.getString("type") == "screen-info" && (to == null || it.getString("to") == to)
    }

    private fun header(frame: RecordingSocket.Frame) = PhoneFrame.parse(frame.payload)!!
    private fun body(frame: RecordingSocket.Frame) = frame.payload.copyOfRange(PhoneFrame.HEADER_SIZE, frame.payload.size)
    private fun id(n: Int) = PhoneFrame.streamHex(0xA0L + n)
    private val pipe get() = pipes.last()
    private val openPipes get() = pipes.count { it.closed == 0 }

    // What stands for the bitmap Android makes over a screenshot's graphics memory. Robolectric has none.
    private val wrapped = HashMap<HardwareBuffer, Bitmap>()
    private val colours = HashMap<HardwareBuffer, Int>()
    private var wrapFails = false

    private fun wrap(buffer: HardwareBuffer): Bitmap? {
        if (wrapFails) return null
        return Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(colours.getValue(buffer))
            wrapped[buffer] = it
        }
    }

    private fun result(buffer: HardwareBuffer): AccessibilityService.ScreenshotResult =
        AccessibilityService.ScreenshotResult::class.java.getDeclaredConstructor(HardwareBuffer::class.java,
            ColorSpace::class.java, java.lang.Long.TYPE).apply { isAccessible = true }
            .newInstance(buffer, ColorSpace.get(ColorSpace.Named.SRGB), SystemClock.uptimeMillis())

    private fun graphicsMemory(colour: Int): HardwareBuffer =
        HardwareBuffer.create(WIDTH, HEIGHT, HardwareBuffer.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
            .also { colours[it] = colour }

    /**
     * Android answers the capture's request with a screenshot, in graphics
     * memory, through the callback the capture gave it: the whole way a
     * screenshot takes on a phone. Returns the memory, which whoever has the
     * screenshot last must close.
     */
    private fun androidGives(colour: Int): HardwareBuffer {
        // Past the 400 ms Android asks for between screenshots, so that one has been asked for.
        later(400)
        val buffer = graphicsMemory(colour)
        service.callback.onSuccess(result(buffer))
        settle()
        return buffer
    }

    /** A screenshot reaches the capture, as Android would hand it over. A colour stands for what the screen shows. */
    private fun screenshot(colour: Int, width: Int = WIDTH, height: Int = HEIGHT, capturedAt: Long = timer.now) {
        settle()
        val send = AccessibilityScreenCapture::class.java.getDeclaredMethod("send", Bitmap::class.java,
            java.lang.Long.TYPE, java.lang.Long.TYPE).apply { isAccessible = true }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(colour)
        // Not recycled here: the capture keeps the one it sent, to compare the next with.
        send.invoke(capture, bitmap, captureField("generation").getLong(capture), capturedAt)
        settle()
    }

    /**
     * Android answers the screenshot request that is waiting, so that the
     * capture is free to ask for the next one when it wants one. (The
     * screenshots themselves are handed over by [screenshot].)
     */
    private fun screenshotRequestEnds() {
        service.callback.onFailure(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT)
        settle()
    }

    /**
     * The encoder does its part, [ms] going by meanwhile: what was handed to
     * it reaches its texture, what is drawn comes out, and each viewer
     * acknowledges what it was sent.
     */
    private fun encode(ms: Long = 0) {
        timer.advance(ms)
        for (made in pipes) if (made.closed == 0) {
            if (!configured.contains(made)) {
                configured += made
                made.config()
            }
            made.answerAll()
        }
        acknowledge()
        settle()
    }

    private val configured = HashSet<FakeVideoPipe>()

    private fun acknowledge() {
        val last = socket.frames.lastOrNull { it.type == Crypto.CH_PHONE } ?: return
        val head = header(last)
        for (viewer in last.to) receive(viewer, JSONObject().put("type", "frame-ack")
            .put("stream", PhoneFrame.streamHex(head.stream)).put("seq", head.seq))
    }

    private fun videoFrames() = socket.frames.filter { it.type == Crypto.CH_PHONE && header(it).format == PhoneFrame.H264 }
    private fun jpegFrames() = socket.frames.filter { it.type == Crypto.CH_VIDEO ||
        (it.type == Crypto.CH_PHONE && header(it).format == PhoneFrame.JPEG) }

    // ---------------- who gets video ----------------

    @Test
    fun aPcThatOffersH264GetsEachScreenshotAsVideoAndAStillScreenSendsNothing() {
        startView("pc")
        socket.clear()
        val takenAt = timer.now - 30
        screenshot(RED, capturedAt = takenAt)
        // The viewer is told of the stream before anything of it is sent.
        assertEquals(listOf("screen-info to pc"), socket.order)
        infos().single().let {
            assertEquals(id(1), it.getString("stream"))
            assertEquals("h264", it.getString("codec"))
            assertEquals("accessibility", it.getString("capture"))
            assertEquals(WIDTH to HEIGHT, it.getInt("width") to it.getInt("height"))
        }
        assertEquals(1, pipe.shown.size)
        encode()
        assertEquals(listOf("screen-info to pc", "frame 5"), socket.order)
        socket.frames.single().let {
            // A keyframe first, and the time is when the screenshot was taken.
            assertEquals(PhoneFrame.Header(0xA1, 0, keyframe = true, format = PhoneFrame.H264, width = WIDTH,
                height = HEIGHT, captureMs = takenAt), header(it))
            assertArrayEquals(FakeVideoPipe.SETS + FakeVideoPipe.KEY, body(it))
            assertEquals(listOf("pc"), it.to)
        }
        // No JPEG was made of it.
        assertTrue(jpegFrames().isEmpty())

        // The screen stays as it is. It is sharpened twice, and each of those says it is the same screen.
        encode(1_000)
        assertEquals(listOf(false, true, true), videoFrames().map { header(it).repeat })
        // The screenshots that follow show nothing new: none is handed to the encoder, and nothing is sent.
        socket.clear()
        repeat(5) {
            encode(400)
            screenshot(RED)
        }
        encode(10_000)
        assertEquals(1, pipe.shown.size)
        assertTrue(socket.frames.isEmpty())
        assertTrue(infos().isEmpty())

        // It changes: an ordinary picture, in the same stream.
        val changedAt = timer.now - 20
        screenshot(BLUE, capturedAt = changedAt)
        encode()
        assertEquals(PhoneFrame.Header(0xA1, 3, keyframe = false, format = PhoneFrame.H264, width = WIDTH,
            height = HEIGHT, captureMs = changedAt), header(socket.frames.single()))
        assertEquals(1, pipes.size)
        assertTrue(infos().isEmpty())

        // The readout on the PC is told the truth about it.
        receive("pc", JSONObject().put("type", "diagnostic-ping").put("nonce", 5L))
        socket.messages.single { it.getString("type") == "diagnostic-pong" }.let {
            assertEquals("h264", it.getString("codec"))
            assertEquals("accessibility", it.getString("capture"))
            assertEquals(pipe.bitrate, it.getInt("bitrate"))
        }
    }

    @Test
    fun viewersThatDidNotOfferH264GetJpegsExactlyAsBefore() {
        // A PC on v1.0.53: bare JPEGs, and no encoder is ever made for it.
        startViewOld("old-pc")
        socket.clear()
        screenshot(RED)
        socket.frames.single().let {
            assertEquals(Crypto.CH_VIDEO, it.type)
            val picture = BitmapFactory.decodeByteArray(it.payload, 0, it.payload.size)
            assertEquals(WIDTH to HEIGHT, picture.width to picture.height)
            picture.recycle()
        }
        assertEquals(setOf("type", "width", "height", "typing", "to"), infos().single().keys().asSequence().toSet())
        stopView("old-pc")

        // A PC with no decoder, and another phone: JPEGs behind a header.
        for (viewer in listOf("pc", "phone")) {
            startView(viewer, "jpeg")
            socket.clear()
            screenshot(RED)
            assertEquals("jpeg", infos().single().getString("codec"))
            socket.frames.single().let {
                assertEquals(Crypto.CH_PHONE, it.type)
                assertEquals(PhoneFrame.JPEG, header(it).format)
                assertTrue(header(it).keyframe)
            }
            stopView(viewer)
        }
        assertTrue(pipes.isEmpty())
    }

    @Test
    fun viewersThatDifferGetJpegsUntilTheOneThatCannotDrawVideoLeaves() {
        // A PC that can draw video and a phone that cannot: JPEGs for both.
        startView("pc")
        startView("phone", "jpeg")
        socket.clear()
        screenshot(RED)
        assertEquals(PhoneFrame.JPEG, header(socket.frames.single()).format)
        assertEquals(setOf("pc", "phone"), socket.frames.single().to.toSet())
        assertTrue(pipes.isEmpty())
        acknowledge()

        // The phone leaves. The PC gets video from the next screenshot on, though the screen
        // has not changed: a new stream, said first, that begins with a keyframe.
        stopView("phone")
        socket.clear()
        screenshot(RED)
        encode()
        assertEquals(listOf("screen-info to pc", "frame 5"), socket.order)
        assertEquals("h264", infos().single().getString("codec"))
        assertEquals(id(2), infos().single().getString("stream"))
        assertEquals(PhoneFrame.Header(0xA2, 0, keyframe = true, format = PhoneFrame.H264, width = WIDTH,
            height = HEIGHT, captureMs = timer.now), header(socket.frames.single()))
        encode(1_000)

        // It comes back: the encoder is let go of, and both get the screen as a JPEG, changed or not.
        startView("phone", "jpeg")
        socket.clear()
        screenshot(RED)
        assertEquals(1, pipe.closed)
        assertEquals(0, openPipes)
        assertEquals(setOf(id(3)), infos().map { it.getString("stream") }.toSet())
        assertEquals(setOf("jpeg"), infos().map { it.getString("codec") }.toSet())
        assertEquals(setOf("pc", "phone"), infos().map { it.getString("to") }.toSet())
        socket.frames.single().let {
            assertEquals(PhoneFrame.JPEG, header(it).format)
            assertEquals(0xA3L, header(it).stream)
            assertEquals(setOf("pc", "phone"), it.to.toSet())
        }
        // An older app joins as well: bare JPEGs for all three.
        startViewOld("old-pc")
        socket.clear()
        screenshot(RED)
        assertEquals(Crypto.CH_VIDEO, socket.frames.single().type)
        assertEquals(1, pipes.size)
    }

    // ---------------- keyframes ----------------

    @Test
    fun aKeyframeAskedForOnAStillScreenIsAnsweredFromTheLastScreenshot() {
        startView("pc")
        screenshot(RED)
        encode()
        encode(2_000)
        socket.clear()

        // The PC lost a frame, or has just opened a second window: it asks.
        receive("pc", JSONObject().put("type", "request-keyframe").put("stream", id(1)))
        encode()
        socket.frames.single().let {
            assertTrue(header(it).keyframe)
            // The screen as it was: no new screenshot was needed, and the delay figure leaves it out.
            assertTrue(header(it).repeat)
            assertEquals(PhoneFrame.H264, header(it).format)
        }
        assertEquals(1, pipe.shown.size)
    }

    @Test
    fun aViewerThatJoinsIsToldTheStreamAndAKeyframeIsMadeForIt() {
        startView("pc")
        screenshot(RED)
        encode()
        encode(2_000)
        socket.clear()

        startView("pc2")
        screenshot(RED)
        encode()
        infos("pc2").last().let {
            assertEquals(id(1), it.getString("stream"))
            assertEquals("h264", it.getString("codec"))
        }
        val first = socket.frames.first()
        assertTrue(header(first).keyframe)
        assertEquals(setOf("pc", "pc2"), first.to.toSet())
        assertEquals(1, pipes.size)
    }

    @Test
    fun aLastPictureThatGoesUnacknowledgedIsEncodedAgainAsAKeyframe() {
        startView("pc")
        screenshot(RED)
        encode()
        encode(2_000)
        // It changes, and this picture is never acknowledged: the relay dropped it, say.
        screenshot(BLUE)
        timer.advance(0)
        pipe.answerAll()
        timer.advance(2_000)
        pipe.answerAll()
        socket.clear()

        // The viewer has said nothing for two seconds and more: the screen is encoded again.
        later(2_500)
        timer.advance(0)
        pipe.answerAll()
        val again = socket.frames.first()
        assertTrue(header(again).keyframe)
        assertTrue(header(again).repeat)
        assertEquals(1, pipes.size)
    }

    // ---------------- a turned phone, a pause, nobody viewing ----------------

    @Test
    fun aTurnedPhoneKeepsItsStreamAndSaysTheNewSizeBeforeAKeyframeAtThatSize() {
        startView("pc")
        screenshot(RED)
        encode()
        encode(1_000)
        socket.clear()

        // Turned on its side. An encoder has one size for life, so another is made.
        screenshot(RED, width = HEIGHT, height = WIDTH)
        encode()
        assertEquals(2, pipes.size)
        assertEquals(1, pipes[0].closed)
        assertEquals(HEIGHT to WIDTH, pipe.width to pipe.height)
        assertEquals(listOf("screen-info to pc", "frame 5"), socket.order)
        infos().single().let {
            assertEquals(id(1), it.getString("stream"))
            assertEquals("h264", it.getString("codec"))
            assertEquals(HEIGHT to WIDTH, it.getInt("width") to it.getInt("height"))
        }
        header(socket.frames.single()).let {
            assertTrue(it.keyframe)
            assertEquals(0xA1L, it.stream)
            assertEquals(HEIGHT to WIDTH, it.width to it.height)
        }
    }

    @Test
    fun aShortPauseKeepsTheEncoderAndNobodyViewingLetsGoOfEverything() {
        startView("pc")
        screenshot(RED)
        encode()
        encode(1_000)
        val drawn = pipe.drawn.size

        // A device nobody has approved joins: pictures stop, and the viewer is told so.
        socket.clear()
        authField("allPeersTrusted").set(PeerAuth, false)
        PhoneScreenCapture.refresh()
        settle()
        assertTrue(infos().single().getBoolean("paused"))
        assertEquals(0, pipe.closed)
        encode(2_000)
        assertEquals(drawn, pipe.drawn.size)

        // It is approved. The same encoder goes on, in a new stream, and what starts it is the
        // next screenshot: the picture from before the pause is not sent as if it were the screen now.
        socket.clear()
        authField("allPeersTrusted").set(PeerAuth, true)
        PhoneScreenCapture.refresh()
        settle()
        encode(300)
        assertTrue(socket.frames.isEmpty())
        val takenAt = timer.now - 15
        screenshot(RED, capturedAt = takenAt)
        encode()
        assertEquals(1, pipes.size)
        assertEquals(listOf("screen-info to pc", "frame 5"), socket.order)
        assertEquals(id(2), infos().single().getString("stream"))
        assertEquals(PhoneFrame.Header(0xA2, 0, keyframe = true, format = PhoneFrame.H264, width = WIDTH,
            height = HEIGHT, captureMs = takenAt), header(socket.frames.single()))
        encode(1_000)

        // The last viewer leaves, which looks the same at first.
        screenshotRequestEnds()
        stopView("pc")
        assertEquals(0, pipe.closed)
        later(4_999)
        assertEquals(0, pipe.closed)
        // Five seconds later the encoder is let go of, and the last screenshot is not kept either.
        later(1)
        assertEquals(1, pipe.closed)
        assertNull(captureField("lastSent").get(capture))
        assertTrue(pipe.shown.all { (it as BitmapPicture).bitmap.isRecycled })
        // No screenshot is asked for, and nothing is drawn or sent, however long nobody views.
        val requests = service.requests
        val drawnAtTheEnd = pipe.drawn.size
        socket.clear()
        later(600_000)
        timer.advance(600_000)
        assertEquals(requests, service.requests)
        assertEquals(drawnAtTheEnd, pipe.drawn.size)
        assertTrue(socket.frames.isEmpty())
        assertTrue(socket.messages.isEmpty())

        // A viewer comes back: a new encoder, and a keyframe.
        startView("pc")
        screenshot(RED)
        encode()
        assertEquals(2, pipes.size)
        assertTrue(header(socket.frames.single()).keyframe)
    }

    // ---------------- to smooth sharing and back ----------------

    @Test
    fun smoothSharingTakingOverEndsThisStreamAndItsEncoderAndGivingBackStartsANewOne() {
        startView("pc")
        screenshot(RED)
        encode()
        encode(1_000)
        screenshot(BLUE)
        timer.advance(0)
        val owed = pipe.drawn.last()
        screenshotRequestEnds()
        socket.clear()

        // Smooth sharing starts, from the PC's menu or on the phone.
        val shared = Robolectric.buildService(ScreenCaptureService::class.java).create().also { sharing = it }
        sharingInstance.set(null, shared.get())
        // A picture this encoder had in hand is not sent: it would start a stream of its own
        // and end the one smooth sharing is about to start.
        pipe.answer(owed)
        assertTrue(socket.frames.isEmpty())
        PhoneScreenCapture.refresh()
        settle()
        // The viewer is told of smooth sharing's stream, which is another one.
        infos().last().let {
            assertEquals("screen-share", it.getString("capture"))
            assertEquals(id(2), it.getString("stream"))
        }
        // This encoder goes at once, not five seconds later: smooth sharing has its own, and a phone has few.
        assertEquals(1, pipe.closed)
        assertEquals(0, openPipes)
        // And no screenshot is taken while it lasts.
        val requests = service.requests
        later(2_000)
        assertEquals(requests, service.requests)

        // Smooth sharing ends: by the PC's menu, on the phone, or because the phone locked.
        socket.clear()
        sharingInstance.set(null, null)
        PhoneScreenCapture.refresh()
        settle()
        assertEquals(requests + 1, service.requests)
        val takenAt = timer.now
        screenshot(BLUE, capturedAt = takenAt)
        encode(40)
        // A third stream, as video again, announced first and begun with a keyframe nobody asked for.
        assertEquals(2, pipes.size)
        infos().last().let {
            assertEquals("accessibility", it.getString("capture"))
            assertEquals("h264", it.getString("codec"))
            assertEquals(id(3), it.getString("stream"))
        }
        assertEquals("frame 5", socket.order.last())
        assertEquals(PhoneFrame.Header(0xA3, 0, keyframe = true, format = PhoneFrame.H264, width = WIDTH,
            height = HEIGHT, captureMs = takenAt), header(socket.frames.single()))
    }

    @Test
    fun atTheHandOverOneEncoderIsGoneBeforeTheOtherIsStarted() {
        // Smooth sharing has just ended. Its encoder is still open: letting go of it was
        // left to its own thread, which has not got to it yet.
        val smoothTimer = FakeVideoTimer()
        val smoothPipes = ArrayList<FakeVideoPipe>()
        val smooth = PhoneVideo(
            encoders = { listOf(VideoSetup.Encoder("hardware", hardware = true, constantBitrate = true) { _, _ -> true }) },
            open = { attempt, _, _, events -> FakeVideoPipe(attempt, events).also { smoothPipes += it } },
            timer = smoothTimer, clock = { smoothTimer.now }, hasRoom = { true }, send = { VideoSent.SENT },
            onFailed = { _, _ -> }, spare = { it() })
        val smoothLane = VideoLane(smooth, post = { it.run() }, clock = { smoothTimer.now })
        assertTrue(smoothLane.start(WIDTH, HEIGHT))
        val ending = ScreenCaptureService::class.java.getDeclaredField("ending").apply { isAccessible = true }
        ending.set(null, smoothLane)
        try {
            // A phone with one encoder to give: a second will not start while the first is open.
            refuse = { smoothPipes.any { it.closed == 0 } }
            startView("pc")
            socket.clear()
            screenshot(RED)
            encode()
            // The screenshots' encoder started, which it could only do with the other one gone.
            assertEquals(1, smoothPipes.single().closed)
            assertEquals(1, pipes.size)
            assertEquals("h264", infos().last().getString("codec"))
            assertEquals(PhoneFrame.H264, header(socket.frames.single()).format)
            // What smooth sharing left behind is let go of once, and not asked for again.
            assertNull(ending.get(null))
            assertFalse(smooth.usable)

            // The other way: smooth sharing is about to start its encoder, and has this one let go of first.
            assertEquals(0, pipe.closed)
            input.get().endVideo()
            assertEquals(1, pipe.closed)
        } finally {
            ending.set(null, null)
        }
    }

    // ---------------- when the encoder fails ----------------

    @Test
    fun aFailedEncoderIsFollowedByAJpegOfTheNextScreenshotThoughTheScreenHasNotChanged() {
        startView("pc")
        screenshot(RED)
        encode()
        encode(1_000)
        socket.clear()

        // The encoder reports an error.
        pipe.events.failed(IllegalStateException("codec error"))
        timer.advance(0)
        settle()
        assertEquals(1, pipe.closed)
        ConnectionLog.entries().first().let {
            assertEquals(ConnectionLog.Kind.VIDEO, it.kind)
            assertEquals("The video encoder stopped working.", it.reason)
        }
        // The viewer is told of a JPEG stream, and not that pictures have paused.
        infos().single().let {
            assertEquals(id(2), it.getString("stream"))
            assertEquals("jpeg", it.getString("codec"))
            assertEquals("accessibility", it.getString("capture"))
            assertFalse(it.has("paused"))
        }
        // The screen is as it was. The next screenshot goes all the same: the viewer's last
        // picture came from an encoder that failed.
        screenshot(RED)
        socket.frames.single().let {
            assertEquals(PhoneFrame.Header(0xA2, 0, keyframe = true, format = PhoneFrame.JPEG, width = WIDTH,
                height = HEIGHT, captureMs = timer.now), header(it))
            val picture = BitmapFactory.decodeByteArray(it.payload, PhoneFrame.HEADER_SIZE, it.payload.size - PhoneFrame.HEADER_SIZE)
            assertEquals(WIDTH to HEIGHT, picture.width to picture.height)
            picture.recycle()
        }
        acknowledge()
        // After that a still screen sends nothing again, and a changed one a JPEG.
        screenshot(RED)
        assertEquals(1, socket.frames.size)
        screenshot(BLUE)
        assertEquals(2, socket.frames.size)
        assertEquals(PhoneFrame.JPEG, header(socket.frames.last()).format)
        assertEquals(1, pipes.size)
        acknowledge()

        // The viewer closes and opens its window: video again, at the next size down, said as such.
        stopView("pc")
        startView("pc")
        socket.clear()
        screenshot(BLUE)
        encode()
        assertEquals(2, pipes.size)
        val smaller = VideoSetup.even(WIDTH * 3 / 4) to VideoSetup.even(HEIGHT * 3 / 4)
        infos().last().let {
            assertEquals("h264", it.getString("codec"))
            assertEquals(smaller, it.getInt("width") to it.getInt("height"))
        }
        header(socket.frames.single()).let {
            assertEquals(PhoneFrame.H264, it.format)
            assertTrue(it.keyframe)
            assertEquals(smaller, it.width to it.height)
        }
    }

    @Test
    fun aPhoneWhoseEncoderWillNotStartSendsJpegsFromTheFirstScreenshot() {
        refuse = { true }
        startView("pc")
        socket.clear()
        screenshot(RED)
        assertTrue(pipes.isEmpty())
        assertEquals("The video encoder could not be started.", ConnectionLog.entries().first().reason)
        // What the viewer is left believing is the JPEG stream, and the picture is in it.
        assertEquals("jpeg", infos().last().getString("codec"))
        socket.frames.single().let {
            assertEquals(PhoneFrame.JPEG, header(it).format)
            assertEquals(PhoneFrame.parseStream(infos().last().getString("stream")), header(it).stream)
        }
        assertEquals("frame 5", socket.order.last())
        acknowledge()

        // It stays so, without the encoder being tried at every screenshot.
        refuse = { false }
        screenshot(BLUE)
        screenshot(RED)
        assertEquals(3, jpegFrames().size)
        assertTrue(pipes.isEmpty())
    }

    @Test
    fun anEncoderThatStopsAnsweringIsGivenUpAndTheScreenshotsGoOnAsJpegs() {
        startView("pc")
        screenshot(RED)
        encode()
        encode(1_000)
        socket.clear()

        // The next draw into the encoder never comes back. Two seconds on, a screenshot arrives.
        pipe.whileDrawing = {
            pipe.whileDrawing = null
            timer.now += VideoLane.PATIENCE_MS
            screenshot(GREEN)
        }
        screenshot(BLUE)
        timer.advance(0)
        settle()
        assertTrue(AccessibilityScreenCapture.wedged.get())
        // It was the screenshots' way into the encoder that stalled. Smooth sharing's video is not given up with it.
        assertFalse(ScreenCaptureService.videoWedged.get())
        assertEquals(ScreenOutputs.WEDGED, ConnectionLog.entries().first().reason)
        assertEquals("jpeg", infos().last().getString("codec"))
        socket.frames.single().let {
            assertEquals(PhoneFrame.JPEG, header(it).format)
            assertEquals(PhoneFrame.parseStream(infos().last().getString("stream")), header(it).stream)
        }
        acknowledge()

        // Video is not tried again that day, whoever views.
        stopView("pc")
        startView("pc")
        screenshot(RED)
        assertEquals(1, pipes.size)
        assertEquals(PhoneFrame.JPEG, header(socket.frames.last()).format)
        acknowledge()
        wedgeClock += VideoWedge.LASTS_MS - 1
        stopView("pc")
        startView("pc")
        screenshot(BLUE)
        assertEquals(1, pipes.size)
        assertEquals(PhoneFrame.JPEG, header(socket.frames.last()).format)
        acknowledge()
        assertEquals(0, quit)

        // A day after it happened the Accessibility service is still running, as it does for weeks.
        // Video is tried again, with everything made anew: the thread that was lost is not waited for.
        wedgeClock += 1
        stopView("pc")
        startView("pc")
        socket.clear()
        screenshot(RED)
        encode(40)
        assertEquals(2, pipes.size)
        assertEquals(1, quit)
        assertEquals("h264", infos().last().getString("codec"))
        header(socket.frames.single()).let {
            assertEquals(PhoneFrame.H264, it.format)
            assertTrue(it.keyframe)
        }
        // And it goes on working.
        screenshot(GREEN)
        encode(40)
        assertEquals(PhoneFrame.H264, header(socket.frames.last()).format)
        assertFalse(AccessibilityScreenCapture.wedged.get())
    }

    // ---------------- who closes a screenshot ----------------

    @Test
    fun aScreenshotThatGoesAsVideoIsTheEncodersUntilTheNextHasTakenItsPlace() {
        startView("pc")
        val first = androidGives(RED)
        encode()
        // The encoder was handed the screenshot itself, not a copy, and it is still open: it may be drawn from.
        assertSame(wrapped.getValue(first), (pipe.shown.single() as BitmapPicture).bitmap)
        assertFalse(first.isClosed)
        assertFalse(wrapped.getValue(first).isRecycled)
        assertTrue(header(socket.frames.last { it.type == Crypto.CH_PHONE }).keyframe)
        encode(1_000)

        // The screen has not changed: this one goes nowhere, and is closed there and then.
        val same = androidGives(RED)
        assertEquals(1, pipe.shown.size)
        assertTrue(same.isClosed)
        assertTrue(wrapped.getValue(same).isRecycled)
        assertFalse(first.isClosed)

        // It changes: the new one takes the first one's place, and the first is closed.
        val second = androidGives(BLUE)
        encode()
        assertEquals(2, pipe.shown.size)
        assertTrue(first.isClosed)
        assertTrue(wrapped.getValue(first).isRecycled)
        assertFalse(second.isClosed)

        // The last one goes with the encoder, five seconds after the last viewer.
        screenshotRequestEnds()
        stopView("pc")
        assertFalse(second.isClosed)
        later(5_000)
        assertEquals(1, pipe.closed)
        assertTrue(second.isClosed)
        assertTrue(wrapped.getValue(second).isRecycled)
        assertTrue(colours.keys.all { it.isClosed })
    }

    @Test
    fun aScreenshotThatGoesAsAJpegIsClosedAsSoonAsTheJpegIsMade() {
        startView("pc", "jpeg")
        socket.clear()
        val shot = androidGives(RED)
        assertEquals(PhoneFrame.JPEG, header(socket.frames.single()).format)
        assertTrue(shot.isClosed)
        assertTrue(wrapped.getValue(shot).isRecycled)
        assertTrue(pipes.isEmpty())
        // And so is one that showed nothing new.
        val same = androidGives(RED)
        assertEquals(1, socket.frames.size)
        assertTrue(same.isClosed)
    }

    @Test
    fun aScreenshotNobodyIsWaitingForAnyMoreIsClosedUnseen() {
        startView("pc")
        // Android is still taking it when the viewer closes its window.
        val callback = service.callback
        stopView("pc")
        socket.clear()
        val late = graphicsMemory(RED)
        callback.onSuccess(result(late))
        settle()
        assertTrue(late.isClosed)
        assertTrue(wrapped.isEmpty())
        assertTrue(pipes.isEmpty())
        assertTrue(socket.frames.isEmpty())
    }

    @Test
    fun aScreenshotAndroidWillNotMakeABitmapOfIsClosedAndTheNextIsAskedFor() {
        startView("pc")
        socket.clear()
        wrapFails = true
        val requests = service.requests
        val lost = androidGives(RED)
        assertTrue(lost.isClosed)
        assertTrue(socket.frames.isEmpty())
        // The capture has not got stuck on it.
        wrapFails = false
        val next = androidGives(RED)
        encode()
        assertTrue(service.requests > requests)
        assertFalse(next.isClosed)
        assertEquals(PhoneFrame.H264, header(socket.frames.single()).format)
    }

    @Test
    fun aScreenshotTheEncoderCannotDrawIsClosedAndTheNextGoesAsAJpeg() {
        startView("pc")
        val first = androidGives(RED)
        encode()
        encode(1_000)
        socket.clear()

        // The surface will not give its canvas: what `lockHardwareCanvas` does when it has gone wrong.
        pipe.failShows = IllegalStateException("Surface has already been released.")
        val second = androidGives(BLUE)
        assertTrue(second.isClosed)
        assertTrue(wrapped.getValue(second).isRecycled)
        // The encoder is let go of, and with it the screenshot it still had.
        assertEquals(1, pipe.closed)
        assertTrue(first.isClosed)
        assertEquals("The video encoder stopped working.", ConnectionLog.entries().first().reason)
        assertEquals("jpeg", infos().last().getString("codec"))

        // The screen is still as that screenshot showed it. The viewer never got it, so it goes now, as a JPEG.
        val third = androidGives(BLUE)
        assertEquals(PhoneFrame.JPEG, header(socket.frames.single()).format)
        assertTrue(third.isClosed)
        assertTrue(colours.keys.all { it.isClosed })
    }

    // ---------------- a phone that is asleep ----------------

    @Test
    fun theWordsForAScreenThatCannotBeCapturedGoAsVideoToo() {
        startView("pc")
        socket.clear()
        // The phone is asleep: Android gives no screenshot, and the capture draws its own picture.
        service.callback.onFailure(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
        settle()
        encode()
        assertEquals(1, pipe.shown.size)
        assertEquals("h264", infos().last().getString("codec"))
        header(socket.frames.single()).let {
            assertEquals(PhoneFrame.H264, it.format)
            assertTrue(it.keyframe)
            assertEquals(pipe.width to pipe.height, it.width to it.height)
        }
    }

    private companion object {
        // A small screen keeps the test quick. Both sides even, as a phone's are.
        const val WIDTH = 360
        const val HEIGHT = 780
        val RED = 0xFFCC2200.toInt()
        val BLUE = 0xFF0033CC.toInt()
        val GREEN = 0xFF00AA44.toInt()
    }

    private class RecordingSocket : WebSocket {
        class Frame(val type: Byte, val to: List<String>, val payload: ByteArray)

        val key = ByteArray(32) { 7 }
        val messages = mutableListOf<JSONObject>()
        val frames = mutableListOf<Frame>()
        val order = mutableListOf<String>()

        fun clear() {
            messages.clear()
            frames.clear()
            order.clear()
        }

        override fun request() = Request.Builder().url("https://example.test/").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            val envelope = JSONObject(text)
            val blob = Base64.decode(envelope.getString("d"), Base64.NO_WRAP)
            val msg = JSONObject(String(Crypto.decrypt(key, blob, Crypto.CH_JSON)!!))
            messages.add(msg)
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
