package co.remotedesktop

import co.remotedesktop.LockScreenReader.Entry
import co.remotedesktop.LockScreenReader.Found
import co.remotedesktop.LockScreenReader.Read

/**
 * Decides when the lock screen is read again and what viewers are told of
 * it, while [LockState] has it read. Reading is a walk through another
 * process's window, so it is done as seldom as will do:
 *
 * - at once when a window changes (the unlock screen coming up or going);
 * - at most once a second when only something inside a window changes, and
 *   less and less often, down to once in eight seconds, while those reads
 *   keep finding nothing new (a clock ticking, an animation);
 * - never just because time has passed.
 *
 * And what a read finds is not taken at face value:
 *
 * - A grid or a pad of keys is told to viewers only once two reads a moment
 *   apart agree on where it is. The unlock screen slides in, and read
 *   mid-slide its keys are not yet where they will be.
 * - A read that failed or was cut short says nothing. What viewers were
 *   told stands, and it is tried again a few times. Only a read from end to
 *   end that finds no grid or keys takes them away.
 *
 * Bookkeeping only, with the clock passed in, so a JVM test can drive it.
 * Call everything on one thread.
 */
internal class LockScreenWatch {
    companion object {
        /** How far apart the two reads are that must agree on a grid or pad. */
        const val SETTLE_MS = 250L
        /** The least time between reads brought on by changes inside a window. */
        const val CONTENT_MS = 1_000L
        /** What that grows to while such reads find nothing new. */
        const val CONTENT_MAX_MS = 8_000L
        /** How soon a read that failed is tried again, and how often. */
        const val RETRY_MS = 300L
        const val RETRIES = 3
    }

    /** What viewers are told is on the unlock screen. */
    var published = Found.NOTHING
        private set

    /** When to read next, on the clock the callers pass in; null for not until something changes. */
    var nextDue: Long? = null
        private set

    private var candidate: Entry? = null
    private var lastComplete: Found? = null
    private var lastReadAt: Long? = null
    private var contentSpacing = CONTENT_MS
    private var retries = 0
    /** The read that is due was brought on by a change inside a window and by nothing else. */
    private var dueForContent = false
    private var readingForContent = false

    /** Reading starts (or starts over): nothing is known, and the screen is read now. */
    fun start(now: Long) {
        published = Found.NOTHING
        candidate = null
        lastComplete = null
        lastReadAt = null
        contentSpacing = CONTENT_MS
        retries = 0
        nextDue = now
        dueForContent = false
    }

    /** A window appeared, went or changed places. */
    fun windowChanged(now: Long) {
        contentSpacing = CONTENT_MS
        due(now, forContent = false)
    }

    /** Something inside a window changed: a line of text, a clock, a frame of an animation. */
    fun contentChanged(now: Long) {
        val last = lastReadAt
        due(if (last == null) now else maxOf(now, last + contentSpacing), forContent = true)
    }

    private fun due(at: Long, forContent: Boolean) {
        val already = nextDue
        if (already != null && already <= at) return
        nextDue = at
        dueForContent = forContent
    }

    /** A read is starting now. */
    fun reading(now: Long) {
        nextDue = null
        lastReadAt = now
        readingForContent = dueForContent
        dueForContent = false
    }

    /** The read that was started has come back. Returns whether [published] changed. */
    fun read(result: Read, now: Long): Boolean {
        val found = when (result) {
            Read.Unknown -> {
                if (retries < RETRIES) {
                    retries++
                    due(now + RETRY_MS, forContent = false)
                }
                return false
            }
            is Read.Complete -> result.found
        }
        retries = 0
        val same = found == lastComplete
        lastComplete = found
        if (readingForContent) contentSpacing = if (same) minOf(contentSpacing * 2, CONTENT_MAX_MS) else CONTENT_MS

        val entry = found.entry
        if (entry != null && entry != published.entry && entry != candidate) {
            // Seen for the first time, or not where it was a moment ago. Look again.
            candidate = entry
            due(now + SETTLE_MS, forContent = false)
            return false
        }
        candidate = null
        if (found == published) return false
        published = found
        return true
    }
}
