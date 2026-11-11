package co.remotedesktop

/**
 * How pictures of this phone's screen are sent, which is one way for all
 * its viewers: the best that every one of them can draw. Encoding twice for
 * viewers that differ is not worth the battery.
 *
 * Each viewer says what it can draw in the `codecs` of its `start-view`:
 * 1. A viewer that says nothing is on an app from before type 5 frames. With
 *    one of those among them, or one that does not list `jpeg`, everyone
 *    gets bare [TYPE_1] JPEGs.
 * 2. [H264] when every viewer lists it and the phone has video to send:
 *    smooth sharing is on and its encoder works.
 * 3. Otherwise [JPEG] in type 5 frames.
 *
 * Nothing here touches Android.
 */
internal enum class StreamFormat(
    /** As `screen-info` and `diagnostic-pong` name it; null where there is no stream to name. */
    val codec: String?,
) {
    TYPE_1(null),
    JPEG("jpeg"),
    H264("h264");

    companion object {
        /**
         * @param offers what each viewer offered; an empty set for one that offered nothing.
         * @param video whether the phone has H.264 to send.
         */
        fun of(offers: Collection<Set<String>>, video: Boolean): StreamFormat = when {
            offers.any { JPEG.codec !in it } -> TYPE_1
            video && offers.isNotEmpty() && offers.all { H264.codec in it } -> H264
            else -> JPEG
        }
    }
}
