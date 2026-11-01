package co.remotedesktop

import android.content.Context
import android.net.NetworkCapabilities
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLException

/**
 * This phone's own record of its relay connection: each connect, drop and
 * network change, with the phone's reason, kept across restarts. The relay
 * logs its side of a drop; this is the other end, which used to exist only in
 * logcat and was gone within a couple of hours.
 */
object ConnectionLog {

    enum class Kind {
        /** The app's process started, so a gap before it means Android stopped the app. */
        STARTED,
        CONNECTED,
        /** A connection that had been admitted ended. */
        LOST,
        /** An attempt that never got in; repeats with the same reason are merged. */
        FAILED,
        /** Closed on purpose on this phone: retry, a settings change, or turned off. */
        STOPPED,
        NETWORK,
    }

    data class Entry(
        val kind: Kind,
        /** Wall clock, for lining up with the relay's log. */
        val atMs: Long,
        /** What happened, in words: the reason, or for NETWORK the new network. */
        val reason: String? = null,
        /** The raw exception or close code, for whoever reads it next. */
        val detail: String? = null,
        val network: String? = null,
        /** LOST: how long it had been connected. CONNECTED: how long it had been offline. */
        val durationMs: Long = 0,
        /** FAILED: tries merged into this entry, the last of them at [untilMs]. */
        val count: Int = 1,
        val untilMs: Long = atMs,
    )

    const val MAX_ENTRIES = 200
    private const val FILE_NAME = "connection-log.json"

    private val entries = ArrayList<Entry>() // oldest first
    private var file: AtomicFile? = null

    /** Invoked on the main thread after each change. */
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var postToMain: (() -> Unit) -> Unit = { it() }

    /** Binds to this app's storage and loads what earlier runs recorded. */
    @Synchronized
    fun init(context: Context, post: (() -> Unit) -> Unit = { it() }) {
        postToMain = post
        val path = File(context.filesDir, FILE_NAME)
        if (file?.baseFile == path) return
        file = AtomicFile(path)
        entries.clear()
        entries.addAll(load())
    }

    @Synchronized
    fun entries(): List<Entry> = entries.asReversed().toList()

    /** The most recent drop or failed attempt, unless a connection has succeeded since. */
    @Synchronized
    fun currentProblem(): Entry? = entries.lastOrNull { it.kind != Kind.NETWORK }
        ?.takeIf { it.kind == Kind.LOST || it.kind == Kind.FAILED }

    /** The most recent drop or failed attempt, however long ago. */
    @Synchronized
    fun lastDrop(): Entry? = entries.lastOrNull { it.kind == Kind.LOST || it.kind == Kind.FAILED }

    @Synchronized
    fun add(entry: Entry) {
        val last = entries.lastOrNull()
        // A long outage retries every 30 s; one line says so better than 120.
        if (entry.kind == Kind.FAILED && last?.kind == Kind.FAILED &&
            last.reason == entry.reason && last.network == entry.network) {
            entries[entries.size - 1] = last.copy(count = last.count + 1, untilMs = entry.atMs, detail = entry.detail)
        } else {
            entries.add(entry)
            while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        }
        Log.i("ConnectionLog", "${entry.kind} ${entry.reason.orEmpty()} ${entry.detail.orEmpty()} ${entry.network.orEmpty()}")
        save()
        postToMain { listeners.forEach { it() } }
    }

    @Synchronized
    internal fun clear() {
        entries.clear()
        save()
    }

    /** Why a socket failed, in words. The exception text goes in the detail. */
    fun reasonFor(t: Throwable, httpCode: Int? = null): String {
        if (httpCode != null) return "The relay answered HTTP $httpCode instead of connecting"
        val msg = t.message.orEmpty()
        return when {
            t is SocketTimeoutException && msg.startsWith("sent ping") ->
                "No reply to a ping within ${ConnectionManager.PING_INTERVAL_S} s"
            t is UnknownHostException -> "Couldn't look up the relay's address"
            t is NoRouteToHostException -> "No route to the relay"
            t is ConnectException -> "Couldn't reach the relay"
            t is SocketTimeoutException -> "Timed out waiting for the relay"
            t is SSLException -> "The secure connection to the relay failed"
            // ECONNABORTED: Android closes a lost network's sockets.
            msg.contains("connection abort", ignoreCase = true) -> "This phone's network went away"
            msg.contains("reset", ignoreCase = true) || msg.contains("broken pipe", ignoreCase = true) ->
                "The connection was cut"
            t is EOFException -> "The connection ended without a goodbye"
            else -> "Connection error"
        }
    }

    fun detailFor(t: Throwable): String =
        t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")

    /** "Wi-Fi", "Mobile data via VPN" and so on. */
    fun describe(caps: NetworkCapabilities): String {
        val base = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> return "VPN"
            else -> "Other network"
        }
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) "$base via VPN" else base
    }

    /** "8 h 18 m", "4 m 12 s", "10 s", "0.8 s". */
    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return when {
            s >= 3600 -> "${s / 3600} h ${s % 3600 / 60} m"
            s >= 60 -> "${s / 60} m ${s % 60} s"
            ms >= 10_000 -> "$s s"
            else -> "%.1f s".format(ms / 1000.0)
        }
    }

    /** One line saying what happened, e.g. "Lost the connection after 8 h 18 m". */
    fun title(e: Entry): String = when (e.kind) {
        Kind.STARTED -> "App started"
        Kind.CONNECTED ->
            if (e.durationMs >= 1000) "Connected after ${formatDuration(e.durationMs)} offline" else "Connected"
        Kind.LOST -> "Lost the connection after ${formatDuration(e.durationMs)}"
        Kind.FAILED -> if (e.count > 1) "Couldn't connect (${e.count} tries)" else "Couldn't connect"
        Kind.STOPPED -> "Disconnected on this phone"
        Kind.NETWORK -> "Network: ${e.reason}"
    }

    /** One line per entry, as given (newest first), with UTC alongside this
     *  phone's time so it lines up with the relay's log. For sharing. */
    fun toText(entries: List<Entry>, zone: TimeZone = TimeZone.getDefault()): String {
        val local = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).apply { timeZone = zone }
        val utc = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return entries.joinToString("\n") { e ->
            var what = title(e)
            if (e.count > 1) what += ", the last at ${utc.format(Date(e.untilMs))} UTC"
            val parts = listOfNotNull(what, if (e.kind == Kind.NETWORK) e.detail else e.reason, e.network)
            val raw = e.detail?.takeIf { e.kind != Kind.NETWORK }?.let { " [$it]" }.orEmpty()
            "${local.format(Date(e.atMs))} (${utc.format(Date(e.atMs))} UTC)  ${parts.joinToString(". ")}$raw"
        }
    }

    private fun save() {
        val f = file ?: return
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().apply {
                put("kind", e.kind.name)
                put("at", e.atMs)
                e.reason?.let { put("reason", it) }
                e.detail?.let { put("detail", it) }
                e.network?.let { put("network", it) }
                if (e.durationMs != 0L) put("duration", e.durationMs)
                if (e.count != 1) { put("count", e.count); put("until", e.untilMs) }
            })
        }
        var out: java.io.FileOutputStream? = null
        try {
            out = f.startWrite()
            out.write(arr.toString().toByteArray())
            f.finishWrite(out)
        } catch (e: Exception) {
            out?.let { f.failWrite(it) }
            Log.w("ConnectionLog", "Couldn't save the connection history", e)
        }
    }

    private fun load(): List<Entry> {
        val f = file ?: return emptyList()
        return try {
            val arr = JSONArray(String(f.readFully()))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val kind = Kind.entries.firstOrNull { it.name == o.optString("kind") } ?: return@mapNotNull null
                val at = o.getLong("at")
                Entry(kind, at, o.optString("reason").ifEmpty { null }, o.optString("detail").ifEmpty { null },
                    o.optString("network").ifEmpty { null }, o.optLong("duration"),
                    o.optInt("count", 1), o.optLong("until", at))
            }
        } catch (_: java.io.FileNotFoundException) {
            emptyList()
        } catch (e: Exception) {
            Log.w("ConnectionLog", "Couldn't read the connection history", e)
            emptyList()
        }
    }
}
