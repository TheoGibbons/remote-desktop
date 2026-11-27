package co.remotedesktop

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What a viewer of a PC keeps for the Stats panel's Export: the panel's
 * figures every few seconds for the last several minutes, and what happened
 * along the way. The panel shows the figures of this moment only, and by the
 * time anybody opens it the moment that mattered has passed.
 *
 * It costs a viewing session next to nothing, and that is a rule for anything
 * added here. A figure is read off counters the viewer keeps anyway, a few
 * times a minute; what happened is written down only when it happens; nothing
 * is sent to the PC or saved to the phone for it, and both lists are short and
 * forget their oldest. It lasts as long as the viewer does.
 *
 * Any thread: pictures arrive on the socket's.
 */
internal class ViewerStatsLog(
    private val maxSamples: Int = MAX_SAMPLES,
    private val maxEvents: Int = MAX_EVENTS,
) {
    /**
     * The Stats panel's figures at one moment.
     * @param atMs the time by the phone's clock
     * @param rttMs null when it was not being measured: only an open panel asks the PC
     * @param kind how the last picture came, "H.264" or "JPEG", or null before the first
     */
    data class Sample(
        val atMs: Long,
        val state: String,
        val fps: Double,
        val screenMbps: Double,
        val wireMbps: Double,
        val frames: Long,
        val keyframes: Long,
        val sequenceGaps: Long,
        val decodeErrors: Long,
        val lastFrameAgoMs: Long?,
        val lastPacketAgoMs: Long?,
        val rttMs: Long?,
        val kind: String?,
    )

    /**
     * Something that happened.
     * @param times how often in a row; [untilMs] is then the last time
     */
    data class Event(val atMs: Long, val what: String, val times: Int = 1, val untilMs: Long = atMs, val key: String? = null)

    private val samples = ArrayDeque<Sample>()
    private val events = ArrayDeque<Event>()

    @Synchronized
    fun sample(sample: Sample) {
        while (samples.size >= maxSamples) samples.removeFirst()
        samples.addLast(sample)
    }

    /**
     * Writes down that something happened. The same thing again straight
     * after is counted, not written again: a viewer that gets no picture asks
     * for one every two seconds. With a [key], it also replaces what the
     * last one said, so that only where a run of them ended up is kept, as
     * for the part of the desktop on show while it is being dragged about.
     */
    @Synchronized
    fun event(atMs: Long, what: String, key: String? = null) {
        val last = events.lastOrNull()
        if (last != null && (if (key != null) last.key == key else last.key == null && last.what == what)) {
            events[events.size - 1] = last.copy(what = what, times = last.times + 1, untilMs = atMs)
            return
        }
        while (events.size >= maxEvents) events.removeFirst()
        events.addLast(Event(atMs, what, key = key))
    }

    @Synchronized fun samples(): List<Sample> = samples.toList()
    @Synchronized fun events(): List<Event> = events.toList()

    /** Who is looking at what, for the top of the export. */
    data class Heading(
        val exportedAtMs: Long,
        val phoneName: String,
        val appVersion: String,
        val android: String,
        val model: String,
        val pcName: String,
        val pcVersion: String?,
    )

    companion object {
        /** A figure every [SAMPLE_EVERY_MS] for ten minutes. */
        const val MAX_SAMPLES = 300
        const val MAX_EVENTS = 120
        const val SAMPLE_EVERY_MS = 2_000L

        /**
         * The text that Export shares.
         * @param now the Stats panel as it reads at this moment
         * @param connectionHistory the newest of the phone's own connection history, or empty
         * @param pcReport what the PC said of itself when asked, or null
         * @param noPcReport why there is none, when there is none
         */
        fun export(
            heading: Heading,
            now: String,
            samples: List<Sample>,
            events: List<Event>,
            connectionHistory: String,
            pcReport: String?,
            noPcReport: String?,
            zone: TimeZone = TimeZone.getDefault(),
        ): String {
            val local = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).apply { timeZone = zone }
            val utc = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val clock = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = zone }
            val text = StringBuilder()
            val at = Date(heading.exportedAtMs)
            text.append("Remote Desktop stats, ").append(local.format(at)).append(" (").append(utc.format(at)).append(" UTC)\n")
            text.append("This phone: ").append(heading.phoneName).append(", app ").append(heading.appVersion)
                .append(", Android ").append(heading.android).append(", ").append(heading.model).append('\n')
            text.append("Viewing: ").append(heading.pcName)
                .append(heading.pcVersion?.let { ", app $it" } ?: ", app version not given").append('\n')

            text.append("\n== Now ==\n").append(now.trimEnd()).append('\n')

            text.append("\n== What the PC says about itself ==\n")
            text.append((pcReport ?: noPcReport ?: "Nothing.").trimEnd()).append('\n')

            text.append("\n== What happened, oldest first, in this phone's time ==\n")
            if (events.isEmpty()) text.append("Nothing recorded.\n")
            for (e in events) {
                text.append(clock.format(Date(e.atMs))).append("  ").append(e.what)
                if (e.times > 1) text.append(" (").append(e.times).append(" times, the last at ")
                    .append(clock.format(Date(e.untilMs))).append(')')
                text.append('\n')
            }

            text.append("\n== The figures every ").append(SAMPLE_EVERY_MS / 1000).append(" s, oldest first ==\n")
            if (samples.isEmpty()) text.append("Nothing recorded.\n")
            else {
                text.append("time      connection    FPS  screen Mbps  wire Mbps   frames  keyfr  gaps  errors  last frame  last packet  round trip  as\n")
                for (s in samples) {
                    text.append(String.format(
                        Locale.US, "%s  %-12s %5.1f  %11.2f  %9.2f  %7d  %5d  %4d  %6d  %-10s  %-11s  %-10s  %s\n",
                        clock.format(Date(s.atMs)), s.state, s.fps, s.screenMbps, s.wireMbps,
                        s.frames, s.keyframes, s.sequenceGaps, s.decodeErrors,
                        s.lastFrameAgoMs?.let(::age) ?: "never", s.lastPacketAgoMs?.let(::age) ?: "never",
                        s.rttMs?.let { "$it ms" } ?: "-", s.kind ?: "-",
                    ))
                }
            }

            text.append("\n== This phone's connection history, newest first ==\n")
            text.append(connectionHistory.trimEnd().ifEmpty { "Nothing recorded." }).append('\n')
            return text.toString()
        }

        private fun age(ms: Long): String = when {
            ms < 1_000 -> "$ms ms"
            ms < 60_000 -> String.format(Locale.US, "%.1f s", ms / 1000.0)
            else -> String.format(Locale.US, "%d:%02d", ms / 60_000, (ms / 1_000) % 60)
        }
    }
}
