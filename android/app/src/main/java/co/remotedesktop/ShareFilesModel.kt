package co.remotedesktop

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

data class ShareDesktop(val id: String, val name: String, val code: String?, val verified: Boolean)

internal interface ShareGateway {
    val configured: Boolean
    val connected: Boolean
    val allTrusted: Boolean
    val desktops: List<ShareDesktop>
    fun observe(changed: () -> Unit, message: (JSONObject) -> Unit)
    fun close()
    fun connect()
    fun checkAccess(peerId: String)
    fun nextTransferId(): Int
    fun send(peerId: String, uri: Uri, id: Int, canContinue: () -> Boolean,
             progress: (String) -> Unit, done: (Boolean, String?) -> Unit)
}

internal class ConnectionShareGateway(private val app: Application) : ShareGateway {
    override val configured get() = Prefs(app).sessionKey.length >= 16
    override val connected get() = ConnectionManager.state == "connected"
    override val allTrusted get() = PeerAuth.allPeersTrusted
    override val desktops get() = ConnectionManager.peers.filter { it.device == "windows" }.map {
        ShareDesktop(it.id, it.name, PeerAuth.fingerprintOfPeer(it.id)?.let(PeerAuth::shortCode), PeerAuth.isTrusted(it.id))
    }
    private var changed: (() -> Unit)? = null
    private var message: ((JSONObject) -> Unit)? = null
    private val stateListener: (String) -> Unit = { changed?.invoke() }
    private val trustListener: () -> Unit = { changed?.invoke() }
    private val jsonListener: (JSONObject) -> Unit = { message?.invoke(it) }

    override fun observe(changed: () -> Unit, message: (JSONObject) -> Unit) {
        this.changed = changed
        this.message = message
        ConnectionManager.stateListeners.add(stateListener)
        PeerAuth.changeListeners.add(trustListener)
        ConnectionManager.jsonListeners.add(jsonListener)
    }
    override fun close() {
        ConnectionManager.stateListeners.remove(stateListener)
        PeerAuth.changeListeners.remove(trustListener)
        ConnectionManager.jsonListeners.remove(jsonListener)
        changed = null
        message = null
    }
    override fun connect() {
        ConnectionManager.start(app)
        ConnectionService.start(app)
    }
    override fun checkAccess(peerId: String) {
        ConnectionManager.sendJson(JSONObject().put("type", "get-file-access").put("to", peerId))
    }
    override fun nextTransferId() = ConnectionManager.fs.nextXferId()
    override fun send(peerId: String, uri: Uri, id: Int, canContinue: () -> Boolean,
                      progress: (String) -> Unit, done: (Boolean, String?) -> Unit) {
        ConnectionManager.fs.sendUriAsync(peerId, uri, id, canContinue, progress, done)
    }
}

/** Retained across rotation. Never automatically retries a file or switches destinations. */
class ShareFilesModel internal constructor(app: Application, private val gateway: ShareGateway) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, ConnectionShareGateway(app))

    enum class Phase { READY, CHECKING, SENDING, CONFIRMING, DONE }
    val changes = MutableLiveData(0)
    var phase = Phase.READY
        private set
    var desktops = emptyList<ShareDesktop>()
        private set
    var selectedId: String? = null
        private set
    var status = "Choose a desktop, then tap Send. Files go to its Downloads folder."
        private set
    var remaining = emptyList<Uri>()
        private set
    var savedCount = 0
        private set
    val busy get() = phase == Phase.CHECKING || phase == Phase.SENDING || phase == Phase.CONFIRMING
    val configured get() = gateway.configured
    val canSend get() = !busy && remaining.isNotEmpty() && destinationAvailable()
    val availability: String get() = when {
        !configured -> "Set up your session first, then return here to send these files."
        !gateway.connected -> "Not connected. Connect to find your desktop."
        desktops.isEmpty() -> "No desktop is online. Open Remote Desktop on your computer and join the same session."
        !gateway.allTrusted -> "Device approval is still needed. Review devices on this phone and on the desktop."
        else -> "Choose the desktop that should receive these files."
    }
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private var initialized = false
    private var transferId: Int? = null
    private var senderFinished = false
    private var savedPath: String? = null
    private var timeout: Runnable? = null
    private var destinationName = "desktop"

    init { gateway.observe(::refresh, ::onMessage) }

    fun initialize(uris: List<Uri>, interrupted: Boolean = false, confirmedCount: Int = 0, previousStatus: String? = null) {
        if (initialized) return
        initialized = true
        remaining = uris
        savedCount = confirmedCount
        if (uris.isEmpty() && savedCount > 0) {
            phase = Phase.DONE
            status = "$savedCount file${if (savedCount == 1) "" else "s"} confirmed saved to the desktop's Downloads folder."
        }
        else if (uris.isEmpty()) status = "No readable files were shared. Select files or images in the source app and share them again."
        else if (interrupted) status = "The previous transfer was interrupted. Check the desktop's Downloads folder before sending again."
        else if (previousStatus != null) status = previousStatus
        refresh()
        if (remaining.isNotEmpty() && configured && !gateway.connected) connect()
    }

    fun connect() {
        if (configured && !busy) {
            gateway.connect()
            refresh()
        }
    }

    fun select(id: String) {
        if (!busy && desktops.any { it.id == id && it.verified }) {
            selectedId = id
            publish()
        }
    }

    private fun destinationAvailable(): Boolean = gateway.connected && gateway.allTrusted &&
        gateway.desktops.any { it.id == selectedId && it.verified }

    private fun refresh() {
        desktops = gateway.desktops
        if (busy && !destinationAvailable()) stop("Transfer stopped: the desktop disconnected or device approval changed. Check Downloads before retrying.")
        if (desktops.none { it.id == selectedId && it.verified }) selectedId = null
        publish()
    }

    fun send() {
        if (!canSend) return
        destinationName = desktops.first { it.id == selectedId }.name
        generation.incrementAndGet()
        checkNextFile()
    }

    private fun checkNextFile() {
        if (!destinationAvailable()) { stop("The selected desktop is no longer available."); return }
        phase = Phase.CHECKING
        transferId = null
        senderFinished = false
        savedPath = null
        status = "Checking file access on $destinationName..."
        armTimeout("No file-access response from $destinationName. Approve this phone on the desktop and enable file access, then try again.")
        publish()
        gateway.checkAccess(selectedId!!)
    }

    private fun onMessage(msg: JSONObject) {
        if (!busy || msg.optString("from") != selectedId) return
        when (msg.optString("type")) {
            "file-access" -> {
                if (!msg.optBoolean("allowed")) {
                    stop("File access is disabled on $destinationName, or a device is awaiting approval. Enable it on the desktop before sending.")
                } else if (phase == Phase.CHECKING && destinationAvailable()) startCurrentFile()
            }
            "auth-result" -> if (msg.optString("status") in listOf("denied", "revoked", "disconnected", "pending")) {
                stop("$destinationName has not approved this phone for transfers. Review devices on the desktop.")
            }
            "fs-saved" -> if (msg.optInt("xferId") == transferId) {
                val error = msg.optString("error")
                if (error.isNotEmpty()) stop("$destinationName could not save the file: $error")
                else if (msg.optString("path").isNotEmpty()) {
                    savedPath = msg.optString("path")
                    if (senderFinished) completeCurrentFile()
                }
            }
        }
    }

    private fun startCurrentFile() {
        clearTimeout()
        phase = Phase.SENDING
        status = "Sending file ${savedCount + 1} to $destinationName..."
        val token = generation.get()
        val id = gateway.nextTransferId()
        transferId = id
        val peerId = selectedId!!
        armTimeout("No transfer progress. The source app or connection may be unavailable. Check Downloads before retrying.", 60_000)
        publish()
        gateway.send(peerId, remaining.first(), id,
            { generation.get() == token && gateway.connected && gateway.allTrusted &&
                gateway.desktops.any { it.id == peerId && it.verified } },
            { progress -> main.post { if (generation.get() == token && phase == Phase.SENDING) {
                status = progress
                armTimeout("No transfer progress. The source app or connection may be unavailable. Check Downloads before retrying.", 60_000)
                publish()
            } } },
            { ok, error -> main.post {
                if (generation.get() == token && phase == Phase.SENDING) {
                    if (!ok) stop(error ?: "Could not send the file. Share it again from the source app.")
                    else {
                        clearTimeout()
                        senderFinished = true
                        if (savedPath != null) completeCurrentFile()
                        else {
                            phase = Phase.CONFIRMING
                            status = "Waiting for $destinationName to confirm saving the file..."
                            armTimeout("$destinationName did not confirm saving this file. It may already be in Downloads. Check before retrying; an older desktop app may need updating.", 30_000)
                            publish()
                        }
                    }
                }
            } })
    }

    private fun completeCurrentFile() {
        clearTimeout()
        savedCount++
        remaining = remaining.drop(1)
        // Invalidate late progress and completion callbacks before starting the next file.
        generation.incrementAndGet()
        if (remaining.isEmpty()) {
            phase = Phase.DONE
            status = "$savedCount file${if (savedCount == 1) "" else "s"} saved to $destinationName's Downloads folder."
            publish()
        } else checkNextFile()
    }

    fun cancel() = stop("Transfer cancelled. Check Downloads before retrying the remaining files.")

    private fun stop(message: String) {
        generation.incrementAndGet()
        clearTimeout()
        phase = Phase.READY
        transferId = null
        status = message + if (savedCount > 0) " $savedCount already saved; retry sends only the remaining files." else ""
        publish()
    }

    private fun armTimeout(message: String, delay: Long = 15_000) {
        clearTimeout()
        timeout = Runnable { stop(message) }.also { main.postDelayed(it, delay) }
    }
    private fun clearTimeout() { timeout?.let(main::removeCallbacks); timeout = null }
    private fun publish() { changes.value = (changes.value ?: 0) + 1 }
    override fun onCleared() {
        generation.incrementAndGet()
        clearTimeout()
        gateway.close()
    }
}
