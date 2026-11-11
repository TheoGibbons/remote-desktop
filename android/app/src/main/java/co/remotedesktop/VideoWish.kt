package co.remotedesktop

/**
 * Whether the capture service should be making H.264 just now.
 *
 * It should while pictures are going out and every viewer can draw it. But
 * an encoder can fail, and then pictures go on as JPEGs. Each time pictures
 * start, and each time the viewers come to agree on H.264 while they are
 * going, counts as one **try**. A try that failed is not made again; the
 * next one is. ([PhoneVideo] ends that after two failures.)
 *
 * [update] is for the main thread and [tried], [failed] for the capture
 * thread; [wanted] may be read on either. Nothing here touches Android.
 */
internal class VideoWish {
    @Volatile private var agreed = false
    @Volatile private var streaming = false
    @Volatile private var current = 0
    @Volatile private var failedTry = -1

    /** What changed in [update]. */
    data class Change(val started: Boolean, val stopped: Boolean, val format: Boolean) {
        val any: Boolean get() = started || stopped || format
    }

    /**
     * Pictures are going out or not ([on]), and every viewer can draw H.264
     * or not ([allTakeVideo]). Says what is different from last time.
     */
    fun update(on: Boolean, allTakeVideo: Boolean): Change {
        val agreedNow = on && allTakeVideo
        val change = Change(started = on && !streaming, stopped = !on && streaming, format = agreedNow != agreed)
        if (agreedNow && (change.started || change.format)) current++
        agreed = agreedNow
        streaming = on
        return change
    }

    /** Whether H.264 is what should be made now. */
    val wanted: Boolean get() = agreed && failedTry != current

    /** The try that is being made now; handed back to [failed] if it fails. */
    fun tried(): Int = current

    /** The try [tried] gave has failed. A newer one, begun meanwhile, has not. */
    fun failed(tried: Int) {
        failedTry = tried
    }
}
