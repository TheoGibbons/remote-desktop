package co.remotedesktop

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * A viewer asks this phone for smooth sharing: who gets Android's prompt put
 * on the screen for them and who gets nothing, and what the viewer hears as
 * the prompt is answered, left, or taken away. The rules themselves are in
 * SmoothSharingTest; this is the app carrying them out. Same setup as
 * LockStateTest: no shadows, the real objects with their fields set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmoothRequestTest {
    private val socket = RecordingSocket()
    private val context get() = RuntimeEnvironment.getApplication()
    private val previous = mutableMapOf<String, Any?>()
    private val instanceField = InputAccessibilityService::class.java.getDeclaredField("instance")
        .apply { isAccessible = true }
    private lateinit var service: InputAccessibilityService
    private val captureField = InputAccessibilityService::class.java.getDeclaredField("screenCapture")
        .apply { isAccessible = true }
    private val sharingField = ScreenCaptureService::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var sharingService: org.robolectric.android.controller.ServiceController<ScreenCaptureService>? = null

    private fun connectionField(name: String) = ConnectionManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    private fun authField(name: String) = PeerAuth::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun promptField(name: String) = SmoothPrompt::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private val trustedPeers get() = authField("trustedPeers").get(PeerAuth) as MutableSet<String>

    private val captureContext = PhoneScreenCapture::class.java.getDeclaredField("appContext")
        .apply { isAccessible = true }

    private val handleJson = ConnectionManager::class.java.getDeclaredMethod("handleJson", JSONObject::class.java)
        .apply { isAccessible = true }

    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))
    private val keyguard get() = shadowOf(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)

    @Before
    fun setup() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        for (name in listOf("appContext", "encKey", "ws", "state")) previous[name] = connectionField(name).get(ConnectionManager)
        connectionField("appContext").set(ConnectionManager, context)
        connectionField("encKey").set(ConnectionManager, socket.key)
        connectionField("ws").set(ConnectionManager, socket)
        connectionField("state").set(ConnectionManager, "connected")
        ConnectionManager.peers.add(ConnectionManager.Peer("pc", "windows", "Test PC"))
        ConnectionManager.peers.add(ConnectionManager.Peer("other-pc", "windows", "Other PC"))
        ConnectionManager.peers.add(ConnectionManager.Peer("phone", "android", "Test phone"))
        ConnectionManager.peers.add(ConnectionManager.Peer("stranger", "windows", "Stranger"))
        trustedPeers.addAll(listOf("pc", "other-pc", "phone"))
        previous["allPeersTrusted"] = authField("allPeersTrusted").get(PeerAuth)
        // The stranger is in the session but has been told to wait; pictures flow all the same here.
        authField("allPeersTrusted").set(PeerAuth, true)
        previous["capture"] = captureContext.get(PhoneScreenCapture)
        captureContext.set(PhoneScreenCapture, null)
        PhoneScreenCapture.init(context)
        service = Robolectric.buildService(InputAccessibilityService::class.java).create().get()
        instanceField.set(null, service)
        // A phone on Android 11 or later with the service on: it can show its screen without smooth sharing.
        otherPicture(true)
        // Whatever an earlier test left behind.
        SmoothPrompt.rules = SmoothSharing()
        SmoothPrompt.consentIntent = ScreenShare::consentIntent
        ScreenCaptureService.awake = KeepAwake()
        promptField("activity").set(SmoothPrompt, null)
        promptField("stoppedBy").set(SmoothPrompt, null)
        promptField("listeningForScreenOff").set(SmoothPrompt, false)
        keyguard.setKeyguardLocked(false)
        notifications.setNotificationsEnabled(true)
        drain()
    }

    @After
    fun cleanup() {
        sharingField.set(null, null)
        otherPicture(false)
        instanceField.set(null, null)
        PhoneScreenCapture.onViewStopped()
        idle()
        SmoothPrompt.rules = SmoothSharing()
        captureContext.set(PhoneScreenCapture, previous.remove("capture"))
        authField("allPeersTrusted").set(PeerAuth, previous.remove("allPeersTrusted"))
        for ((name, value) in previous) connectionField(name).set(ConnectionManager, value)
        ConnectionManager.peers.clear()
        trustedPeers.clear()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** Whether the phone can show its screen by Accessibility pictures, smooth sharing or not. */
    private fun otherPicture(can: Boolean) {
        (captureField.get(service) as AccessibilityScreenCapture?)?.close()
        captureField.set(service, if (!can) null else
            AccessibilityScreenCapture(Robolectric.buildService(CaptureTestService::class.java).create().get()))
    }

    /** Smooth sharing is running: the capture service, without Android's screen sharing behind it. */
    private fun sharing(): org.robolectric.android.controller.ServiceController<ScreenCaptureService> =
        Robolectric.buildService(ScreenCaptureService::class.java).create().also {
            sharingService = it
            sharingField.set(null, it.get())
            PhoneScreenCapture.refresh()
            idle()
        }

    private fun stopAsked(): Boolean = generateSequence { shadowOf(context).nextStoppedService }.toList()
        .any { it.component?.className == ScreenCaptureService::class.java.name }

    private fun screenInfo(to: String) =
        socket.messages.last { it.getString("type") == "screen-info" && it.getString("to") == to }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun later(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Forgets activities and services an earlier step asked Android for. */
    private fun drain() {
        val app = shadowOf(context)
        while (app.nextStartedActivity != null) Unit
        while (app.nextStartedService != null) Unit
    }

    private fun receive(from: String, type: String) = receive(from, JSONObject().put("type", type))

    private fun receive(from: String, msg: JSONObject) {
        handleJson.invoke(ConnectionManager, msg.put("from", from))
        idle()
    }

    /** A current app: it says what it can draw, and so is told about smooth sharing. */
    private fun view(from: String) =
        receive(from, JSONObject().put("type", "start-view").put("codecs", JSONArray().put("jpeg")))

    private fun statuses(to: String? = null) = socket.messages.filter {
        it.getString("type") == "smooth-status" && (to == null || it.getString("to") == to)
    }

    private fun said(msg: JSONObject) = msg.getString("state") + (if (msg.has("reason")) ": " + msg.getString("reason") else "")

    /** Whether Android has been asked to start the prompt's activity since this was last looked at. */
    private fun promptStart(): Intent? = generateSequence { shadowOf(context).nextStartedActivity }.toList()
        .lastOrNull { it.component?.className == ScreenShareRequestActivity::class.java.name }

    /** The activity Android was asked to start for the prompt, started as Android would. */
    private fun promptActivity(): ActivityController<ScreenShareRequestActivity> =
        Robolectric.buildActivity(ScreenShareRequestActivity::class.java, promptStart()!!).setup()

    /** Android's own prompt, if the activity has asked for it since this was last looked at. */
    private fun consent(activity: Activity): Intent? =
        generateSequence { shadowOf(activity).nextStartedActivityForResult }.map { it.intent }.toList()
            .lastOrNull { it.component?.className?.contains("MediaProjection") == true }

    private fun wholeScreen(intent: Intent) = intent.extras?.keySet().orEmpty().any { it.endsWith("MEDIA_PROJECTION_CONFIG") }

    @Test
    fun aDeviceThatMayNotAskGetsNoPromptAndNoAnswer() {
        fun nothingHappened(who: String) {
            idle()
            later(SmoothPrompt.SHOW_WAIT_MS)
            assertNull(who, promptStart())
            assertNull(who, shadowOf(context).nextStartedService)
            assertEquals(who, emptyList<JSONObject>(), statuses())
            assertEquals(who, 0, notifications.allNotifications.size)
            assertFalse(who, SmoothPrompt.pending)
            assertFalse(who, stopAsked())
        }
        // Asked to start while the phone is not sharing, and to stop while it is: a stop
        // that arrives when nothing is shared does nothing whoever sends it, and would prove nothing.
        val shared = Robolectric.buildService(ScreenCaptureService::class.java).create().get()
        fun ask(from: String) {
            receive(from, "request-smooth")
            sharingField.set(null, shared)
            assertTrue(ScreenCaptureService.isRunning)
            receive(from, "stop-smooth")
            sharingField.set(null, null)
        }

        // In the session with the key, never approved. Asking to view changes nothing for it.
        receive("stranger", "start-view")
        ask("stranger")
        nothingHappened("a device this phone has not approved")

        // Approved, and not viewing.
        ask("pc")
        nothingHappened("an approved PC that is not viewing")

        // It viewed, and stopped.
        view("pc")
        receive("pc", "stop-view")
        ask("pc")
        nothingHappened("a PC that has stopped viewing")

        // Another phone, while Allow other phones is off: its start-view is refused, so it is not viewing.
        Prefs(context).allowPhones = false
        view("phone")
        ask("phone")
        nothingHappened("a phone while other phones are not allowed")
        // And one that was viewing when the switch went off.
        Prefs(context).allowPhones = true
        view("phone")
        Prefs(context).allowPhones = false
        ask("phone")
        nothingHappened("a phone that was viewing when other phones were shut out")
        Prefs(context).allowPhones = true
        receive("phone", "stop-view")

        // Viewing, and then no longer approved.
        view("other-pc")
        trustedPeers.remove("other-pc")
        ask("other-pc")
        nothingHappened("a PC whose approval was taken back")
        trustedPeers.add("other-pc")
        receive("other-pc", "stop-view")

        // Viewing, with control switched off on the phone.
        view("pc")
        Prefs(context).allowControl = false
        ask("pc")
        nothingHappened("any device while control is switched off")
        Prefs(context).allowControl = true

        // A device nobody has heard of.
        ask("nobody")
        nothingHappened("a device that is not in the session")
    }

    @Test
    fun aViewerGetsThePromptAndHearsItDeclined() {
        view("pc")
        receive("pc", "request-smooth")
        assertEquals(listOf("prompting"), statuses("pc").map(::said))
        assertEquals(1, statuses().size)

        val prompt = promptActivity()
        val asked = consent(prompt.get())!!
        // The whole screen: a share of one app would break taps.
        assertTrue(wholeScreen(asked))

        shadowOf(prompt.get()).receiveResult(asked, Activity.RESULT_CANCELED, null)
        idle()
        assertEquals(listOf("prompting", "declined"), statuses("pc").map(::said))
        assertTrue(prompt.get().isFinishing)
        assertNull(shadowOf(context).nextStartedService)
        assertFalse(SmoothPrompt.pending)

        // Asked again straight away, the phone's holder is left alone.
        receive("pc", "request-smooth")
        assertEquals("unavailable: " + SmoothSharing.askAgainIn(SmoothSharing.REPEAT_WAIT_MS), said(statuses("pc").last()))
        assertNull(promptStart())
    }

    @Test
    fun approvingThePromptStartsSharing() {
        view("pc")
        receive("pc", "request-smooth")
        val prompt = promptActivity()
        val asked = consent(prompt.get())!!
        val token = Intent().putExtra("token", 7)
        shadowOf(prompt.get()).receiveResult(asked, Activity.RESULT_OK, token)
        idle()

        val started = shadowOf(context).nextStartedService
        assertEquals(ScreenCaptureService::class.java.name, started.component?.className)
        assertEquals(Activity.RESULT_OK, started.getIntExtra(ScreenCaptureService.EXTRA_RESULT_CODE, 0))
        assertTrue(prompt.get().isFinishing)
        // "started" is said by the service once it has the screen, not before.
        assertEquals(listOf("prompting"), statuses("pc").map(::said))
        assertFalse(SmoothPrompt.pending)
    }

    @Test
    fun aPromptIsTakenAwayWhenSharingStartsAnotherWay() {
        view("pc")
        receive("pc", "request-smooth")
        val prompt = promptActivity()
        val asked = consent(prompt.get())!!

        // Turned on in the phone's Permissions screen, with the viewer's prompt still up.
        val shared = sharing()
        SmoothPrompt.sharingStarted()
        idle()
        assertTrue(prompt.get().isFinishing)
        assertEquals(listOf("prompting", "started"), statuses("pc").map(::said))

        // The prompt that was taken away is approved all the same: nothing starts twice, and nothing more is said.
        shadowOf(prompt.get()).receiveResult(asked, Activity.RESULT_OK, Intent())
        later(SmoothSharing.ANSWER_MS)
        assertNull(shadowOf(context).nextStartedService)
        assertEquals(listOf("prompting", "started"), statuses("pc").map(::said))
        assertSame(shared.get(), sharingField.get(null))
    }

    @Test
    fun aSecondRequestWaitsForTheSamePrompt() {
        view("pc")
        view("other-pc")
        receive("pc", "request-smooth")
        val prompt = promptActivity()
        val asked = consent(prompt.get())!!

        receive("pc", "request-smooth")
        receive("other-pc", "request-smooth")
        // No second activity, and both are told it is up.
        assertNull(promptStart())
        assertEquals(listOf("prompting", "prompting"), statuses("pc").map(::said))
        assertEquals(listOf("prompting"), statuses("other-pc").map(::said))

        shadowOf(prompt.get()).receiveResult(asked, Activity.RESULT_CANCELED, null)
        idle()
        assertEquals("declined", said(statuses("pc").last()))
        assertEquals("declined", said(statuses("other-pc").last()))
    }

    @Test
    fun aTurnedPhoneDoesNotAskTwice() {
        view("pc")
        receive("pc", "request-smooth")
        val prompt = promptActivity()
        assertNotNull(consent(prompt.get()))

        // Android makes the activity anew, as it would for a change it does not handle itself.
        prompt.recreate()
        idle()
        assertNull("the prompt was asked for a second time", consent(prompt.get()))
        assertTrue(SmoothPrompt.pending)
        assertEquals(listOf("prompting"), statuses("pc").map(::said))

        // The answer to the prompt that was already up comes to the new one.
        shadowOf(prompt.get()).callOnActivityResult(1, Activity.RESULT_OK, Intent())
        idle()
        assertEquals(ScreenCaptureService::class.java.name, shadowOf(context).nextStartedService.component?.className)
    }

    @Test
    fun thePromptGoesWhenTheViewerThatAskedStops() {
        view("pc")
        view("other-pc")
        receive("pc", "request-smooth")
        val prompt = promptActivity()
        val asked = consent(prompt.get())!!

        receive("pc", "stop-view")
        assertTrue(prompt.get().isFinishing)
        assertFalse(SmoothPrompt.pending)
        // Back at once to ask again: it waits like anyone else.
        view("pc")
        receive("pc", "request-smooth")
        assertEquals("unavailable: " + SmoothSharing.askAgainIn(SmoothSharing.REPEAT_WAIT_MS), said(statuses("pc").last()))
        assertNull(promptStart())
        socket.messages.removeAll { it.getString("type") == "smooth-status" && it.optString("state") == "unavailable" }
        // Nobody is told: the one that asked has gone, and the other never asked.
        assertEquals(listOf("prompting"), statuses().map(::said))

        // An approval that arrives for the prompt that was taken away starts nothing.
        shadowOf(prompt.get()).receiveResult(asked, Activity.RESULT_OK, Intent())
        idle()
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test
    fun thePromptGoesWhenThePhonesScreenTurnsOff() {
        view("pc")
        receive("pc", "request-smooth")
        val prompt = promptActivity()
        consent(prompt.get())!!

        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        idle()
        assertTrue(prompt.get().isFinishing)
        assertEquals("declined: " + SmoothSharing.SCREEN_TURNED_OFF, said(statuses("pc").last()))
        assertEquals(2, statuses().size)
    }

    @Test
    fun aPromptNobodyAnswersIsTakenAwayAfterAMinute() {
        view("pc")
        receive("pc", "request-smooth")
        val prompt = promptActivity()
        consent(prompt.get())!!

        later(SmoothSharing.ANSWER_MS - 1_000)
        assertFalse(prompt.get().isFinishing)
        later(1_000)
        assertTrue(prompt.get().isFinishing)
        assertEquals("declined: " + SmoothSharing.NOBODY_ANSWERED, said(statuses("pc").last()))
        assertEquals(2, statuses().size)

        // Asked again at once, the phone's holder is left alone for a while; after that it is shown again.
        receive("pc", "request-smooth")
        assertEquals("unavailable: " + SmoothSharing.askAgainIn(SmoothSharing.REPEAT_WAIT_MS), said(statuses("pc").last()))
        assertNull(promptStart())
        later(SmoothSharing.REPEAT_WAIT_MS)
        receive("pc", "request-smooth")
        assertNotNull(promptStart())
    }

    @Test
    fun aLockedPhoneSaysToUnlockItFirst() {
        view("pc")
        keyguard.setKeyguardLocked(true)
        receive("pc", "request-smooth")
        assertEquals(listOf("unavailable: " + SmoothSharing.UNLOCK_FIRST), statuses("pc").map(::said))
        assertNull(promptStart())
    }

    @Test
    fun whereThePromptDoesNotAppearANotificationAsksInstead() {
        view("pc")
        receive("pc", "request-smooth")
        // Android was asked for the activity and, on this phone, never starts it.
        assertNotNull(promptStart())
        later(SmoothPrompt.SHOW_WAIT_MS)

        val shown = notifications.allNotifications.single()
        assertEquals("Test PC is asking for smooth video", shadowOf(shown).contentTitle.toString())
        assertEquals(listOf("prompting", "prompting: " + SmoothSharing.TAP_NOTIFICATION), statuses("pc").map(::said))

        // Tapping it opens the activity, which shows the prompt.
        val opened = shadowOf(shown.contentIntent).savedIntent
        val prompt = Robolectric.buildActivity(ScreenShareRequestActivity::class.java, opened).setup()
        assertNotNull(consent(prompt.get()))
        assertEquals(0, notifications.allNotifications.size)
    }

    @Test
    fun withTheAccessibilityServiceOffTheNotificationIsTheOnlyWay() {
        view("pc")
        instanceField.set(null, null)
        receive("pc", "request-smooth")
        assertNull(promptStart())
        assertEquals(1, notifications.allNotifications.size)
        assertEquals("prompting: " + SmoothSharing.TAP_NOTIFICATION, said(statuses("pc").last()))

        // Left alone, it goes with the request.
        later(SmoothSharing.ANSWER_MS)
        assertEquals(0, notifications.allNotifications.size)
        assertEquals("declined: " + SmoothSharing.NOBODY_ANSWERED, said(statuses("pc").last()))
    }

    @Test
    fun withNoWayToAskThePhoneSaysSo() {
        view("pc")
        notifications.setNotificationsEnabled(false)
        // The service is there, but on this phone the activity never comes.
        receive("pc", "request-smooth")
        later(SmoothPrompt.SHOW_WAIT_MS)
        assertEquals(listOf("prompting", "unavailable: " + SmoothSharing.NO_NOTIFICATIONS), statuses("pc").map(::said))
        assertFalse(SmoothPrompt.pending)

        // And with the service off as well, it says so at once.
        instanceField.set(null, null)
        receive("pc", "request-smooth")
        assertEquals("unavailable: " + SmoothSharing.NO_NOTIFICATIONS, said(statuses("pc").last()))
        assertEquals(0, notifications.allNotifications.size)
    }

    /**
     * The app's notifications are on, and the one kind that asks for the
     * prompt has been switched off in the phone's settings. Posting it would
     * show nothing, and the viewer would hear "Nobody answered" a minute on.
     */
    @Test
    fun withJustTheRequestNotificationsOffThePhoneSaysSoToo() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            android.app.NotificationChannel("requests", "Requests from your devices", NotificationManager.IMPORTANCE_NONE))
        view("pc")
        receive("pc", "request-smooth")
        // On this phone the activity never comes.
        later(SmoothPrompt.SHOW_WAIT_MS)
        assertEquals(listOf("prompting", "unavailable: " + SmoothSharing.NO_NOTIFICATIONS), statuses("pc").map(::said))
        assertEquals(0, notifications.allNotifications.size)
        assertFalse(SmoothPrompt.pending)
        // A minute later nothing more is said.
        later(SmoothSharing.ANSWER_MS)
        assertEquals(2, statuses().size)

        // With the service off as well there is no way to ask, and screen-info says so.
        instanceField.set(null, null)
        assertEquals("unavailable", SmoothPrompt.offer(context))
        receive("pc", "request-smooth")
        assertEquals("unavailable: " + SmoothSharing.NO_NOTIFICATIONS, said(statuses("pc").last()))
        context.getSystemService(NotificationManager::class.java).deleteNotificationChannel("requests")
    }

    /** A phone whose Android has no screen-sharing prompt to show: nobody declined, and nobody need wait. */
    @Test
    fun aPhoneWithNoPromptToShowSaysThatAndNotThatSomeoneDeclined() {
        SmoothPrompt.consentIntent = { throw android.content.ActivityNotFoundException("no prompt on this phone") }
        view("pc")
        receive("pc", "request-smooth")
        val prompt = promptActivity()
        idle()
        assertNull(consent(prompt.get()))
        assertTrue(prompt.get().isFinishing)
        assertEquals(listOf("prompting", "unavailable: " + SmoothSharing.CANNOT_SHOW), statuses("pc").map(::said))
        assertFalse(SmoothPrompt.pending)

        // Not a refusal: it may be asked for again at once, and nothing is waited out.
        SmoothPrompt.consentIntent = ScreenShare::consentIntent
        receive("pc", "request-smooth")
        assertEquals("prompting", said(statuses("pc").last()))
        assertNotNull(promptStart())
    }

    @Test
    fun aNotificationTappedTooLateShowsNothing() {
        // Nobody is waiting for a prompt: the activity closes without asking Android for one.
        val prompt = Robolectric.buildActivity(ScreenShareRequestActivity::class.java,
            ScreenShareRequestActivity.intent(context)).setup()
        assertNull(consent(prompt.get()))
        assertTrue(prompt.get().isFinishing)
    }

    @Test
    fun aViewerStopsSmoothSharingAndEveryViewerHearsWhoDid() {
        view("pc")
        view("other-pc")
        val shared = sharing()
        assertEquals("on", screenInfo("pc").getString("smooth"))

        receive("pc", "stop-smooth")
        assertTrue(stopAsked())
        // Android stops the service, and that is when it is over.
        assertEquals(emptyList<JSONObject>(), statuses())
        shared.destroy()
        idle()
        for (viewer in listOf("pc", "other-pc"))
            assertEquals(listOf("ended: Smooth video was stopped from Test PC."), statuses(viewer).map(::said))
    }

    /**
     * Android 10 and earlier, or the Accessibility service off: smooth
     * sharing is the only picture there is. Stopped from a viewer, the PC
     * would have no picture, and no way to see the prompt that brings it back.
     */
    @Test
    fun aViewerCannotStopSmoothSharingWhereNothingElseShowsTheScreen() {
        otherPicture(false)
        view("pc")
        sharing()
        assertEquals("only", screenInfo("pc").getString("smooth"))

        receive("pc", "stop-smooth")
        assertFalse(stopAsked())
        assertEquals(listOf("unavailable: " + SmoothSharing.ONLY_WAY), statuses("pc").map(::said))
        assertTrue(ScreenCaptureService.isRunning)
    }

    @Test
    fun whereThereIsNoPictureTheViewerIsToldThePromptIsAnsweredOnThePhone() {
        otherPicture(false)
        view("pc")
        receive("pc", "request-smooth")
        assertEquals(listOf("prompting: " + SmoothSharing.ANSWER_ON_PHONE), statuses("pc").map(::said))
        assertNotNull(promptStart())
    }

    @Test
    fun screenInfoTellsACurrentViewerWhetherItCanAsk() {
        view("pc")
        // An app from before type 5 frames: it is sent only what it has always been sent.
        receive("other-pc", "start-view")
        socket.messages.clear()
        assertTrue(PhoneScreenCapture.sendScreenInfo(context, 1080, 2340, PhoneScreenCapture.ACCESSIBILITY))
        fun info(to: String) = socket.messages.single { it.getString("type") == "screen-info" && it.getString("to") == to }
        assertEquals("available", info("pc").getString("smooth"))
        assertFalse(info("other-pc").has("smooth"))
    }

    private fun screenInfos() = socket.messages.filter { it.getString("type") == "screen-info" }

    /** The Accessibility service is switched on again in Android's settings. */
    private fun serviceOn() {
        instanceField.set(null, service)
        otherPicture(true)
        PhoneScreenCapture.refresh()
        idle()
    }

    /**
     * The Accessibility service is switched off, or on, while a PC views in
     * smooth sharing. Nothing about the stream changes, but whether the PC
     * may stop smooth sharing does: with the service off it is the only
     * picture there is. The PC's menu row goes by what it was last told.
     */
    @Test
    fun aViewerIsToldWhenOnlyTheAccessibilityServiceGoesOffOrOn() {
        view("pc")
        sharing()
        val before = screenInfo("pc")
        assertEquals("on", before.getString("smooth"))
        socket.messages.clear()

        service.onDestroy()
        idle()
        assertTrue("The viewer was told nothing when the service went off", screenInfos().isNotEmpty())
        assertEquals("only", screenInfo("pc").getString("smooth"))
        // The same stream as before: it goes on.
        assertEquals(before.optString("stream"), screenInfo("pc").optString("stream"))
        assertEquals(before.optString("capture"), screenInfo("pc").optString("capture"))

        socket.messages.clear()
        serviceOn()
        assertTrue("The viewer was told nothing when the service came back", screenInfos().isNotEmpty())
        assertEquals("on", screenInfo("pc").getString("smooth"))
    }

    /** Without smooth sharing the same switch decides whether the phone can put up the prompt at all. */
    @Test
    fun aViewerIsToldWhenThePhoneCanNoLongerBeAskedAndAnOlderViewerIsNot() {
        // With notifications off, the service is the only way the prompt can be shown.
        notifications.setNotificationsEnabled(false)
        view("pc")
        // An app from before type 5 frames sizes its window on every screen-info, and is sent no more of them.
        receive("other-pc", "start-view")
        assertTrue(PhoneScreenCapture.sendScreenInfo(context, 1080, 2340, PhoneScreenCapture.ACCESSIBILITY))
        assertEquals("available", screenInfo("pc").getString("smooth"))
        socket.messages.clear()

        service.onDestroy()
        idle()
        assertEquals(listOf("pc"), screenInfos().map { it.getString("to") })
        assertEquals("unavailable", screenInfo("pc").getString("smooth"))
        assertEquals(1080, screenInfo("pc").getInt("width"))

        // Said once: nothing more goes while nothing changes.
        socket.messages.clear()
        PhoneScreenCapture.refresh()
        idle()
        assertEquals(emptyList<JSONObject>(), screenInfos())

        serviceOn()
        assertEquals(listOf("pc"), screenInfos().map { it.getString("to") })
        assertEquals("available", screenInfo("pc").getString("smooth"))
    }

    /**
     * The ten idle minutes after which the screen is let go in smooth
     * sharing. A PC whose connection dropped comes back under a new id and
     * asks for the screen again by itself. It is the same PC, by its
     * identity, and nobody did anything.
     */
    @Test
    fun aPcThatReconnectsIsTheSameViewerAndStartsNoNewTenMinutes() {
        @Suppress("UNCHECKED_CAST")
        val identities = authField("peerFingerprint").get(PeerAuth) as MutableMap<String, String>
        identities["pc"] = "identity of the PC"
        val awake = ScreenCaptureService.awake
        fun held() = awake.holdFor(android.os.SystemClock.uptimeMillis())
        try {
            awake.sharing(true, android.os.SystemClock.uptimeMillis())
            view("pc")
            assertEquals(KeepAwake.IDLE_MS, held())
            // Asking again while viewing is nothing new either.
            later(60_000)
            view("pc")
            assertEquals(KeepAwake.IDLE_MS - 60_000, held())

            later(8 * 60_000L)
            handleJson.invoke(ConnectionManager, JSONObject().put("type", "peer-left").put("id", "pc"))
            idle()
            assertNull(held())
            ConnectionManager.peers.add(ConnectionManager.Peer("pc-again", "windows", "Test PC"))
            trustedPeers.add("pc-again")
            identities["pc-again"] = "identity of the PC"
            later(2_000)
            view("pc-again")
            assertEquals(KeepAwake.IDLE_MS - 9 * 60_000L - 2_000, held())

            // Closed by the person at it, and opened again: that is someone doing something.
            receive("pc-again", "stop-view")
            assertNull(held())
            view("pc-again")
            assertEquals(KeepAwake.IDLE_MS, held())

            // A device that was not viewing before is new, too.
            later(9 * 60_000L)
            view("other-pc")
            assertEquals(KeepAwake.IDLE_MS, held())
        } finally {
            identities.remove("pc")
            identities.remove("pc-again")
            trustedPeers.remove("pc-again")
        }
    }

    @Test
    fun askingAndStoppingAreNotInput() {
        // Neither wakes a sleeping phone, nor counts towards keeping it awake.
        for (type in listOf("request-smooth", "stop-smooth"))
            assertFalse(InputAccessibilityService.wakesPhone(JSONObject().put("type", type)))
    }

    private class RecordingSocket : WebSocket {
        val key = ByteArray(32) { 7 }
        val messages = mutableListOf<JSONObject>()
        override fun request() = Request.Builder().url("https://example.test/").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            val blob = Base64.decode(JSONObject(text).getString("d"), Base64.NO_WRAP)
            messages.add(JSONObject(String(Crypto.decrypt(key, blob, Crypto.CH_JSON)!!)))
            return true
        }
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {}
    }
}
