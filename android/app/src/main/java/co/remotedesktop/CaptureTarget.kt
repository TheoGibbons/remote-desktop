package co.remotedesktop

/**
 * What the shared screen is drawn into (an ImageReader in
 * [ScreenCaptureService]). It is swapped for one of a new size when the
 * screen turns, on the capture thread, and closed when sharing stops, on the
 * main thread. The two take turns here, so a turn that lands as sharing stops
 * makes nothing, and nothing that was made is ever left open.
 *
 * Generic over the target so a JVM test can drive it.
 */
internal class CaptureTarget<T : Any>(
    private val make: (width: Int, height: Int) -> T,
    private val dispose: (T) -> Unit,
) {
    private var current: T? = null
    private var size = 0 to 0
    private var closed = false

    /** Makes the first target, or null once sharing has stopped. */
    @Synchronized fun open(width: Int, height: Int): T? {
        if (closed) return null
        current?.let(dispose)
        return make(width, height).also {
            current = it
            size = width to height
        }
    }

    /**
     * Swaps in a target of a new size. [attach] points the picture at the new
     * one; if it throws, the new one is closed, the old one stays in use and
     * the exception goes on to the caller. [retire] runs once the new one is
     * attached, just before the old one is closed.
     *
     * Returns false, having done nothing, when the size is the one in use,
     * nothing has been opened, or sharing has stopped.
     */
    @Synchronized fun replace(width: Int, height: Int, attach: (T) -> Unit, retire: () -> Unit = {}): Boolean {
        val old = current
        if (closed || old == null || size == (width to height)) return false
        val made = make(width, height)
        try {
            attach(made)
        } catch (e: RuntimeException) {
            dispose(made)
            throw e
        }
        current = made
        size = width to height
        try {
            retire()
        } finally {
            dispose(old)
        }
        return true
    }

    /** Sharing has stopped. [release] lets go of whatever draws into the
     *  target, then the target is closed. Nothing is made after this. */
    @Synchronized fun close(release: () -> Unit = {}) {
        if (closed) return
        closed = true
        try {
            release()
        } finally {
            current?.let(dispose)
            current = null
        }
    }
}
