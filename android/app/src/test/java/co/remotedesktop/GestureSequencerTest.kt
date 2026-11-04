package co.remotedesktop

import org.junit.Assert.assertEquals
import org.junit.Test

/** The order in which a phone plays strokes, taps and pinches from another device. Plain JVM. */
class GestureSequencerTest {

    private class Recorder : GestureSequencer.Player<String> {
        val calls = mutableListOf<String>()
        var accept = true

        override fun begin(generation: Int, x: Float, y: Float) =
            accept.also { calls += "begin $generation at ${p(x, y)}" }

        override fun extend(generation: Int, fromX: Float, fromY: Float, points: List<Pair<Float, Float>>,
                            durationMs: Long, lift: Boolean) =
            accept.also {
                calls += "extend $generation from ${p(fromX, fromY)}" +
                    points.joinToString("") { " to ${p(it.first, it.second)}" } + if (lift) " lift" else ""
            }

        override fun play(generation: Int, gesture: String) = accept.also { calls += "play $generation $gesture" }

        private fun p(x: Float, y: Float) = "${x.toInt()},${y.toInt()}"

        fun take(): List<String> = calls.toList().also { calls.clear() }
    }

    private val player = Recorder()
    private val gestures = GestureSequencer(player)

    @Test
    fun aStrokePlaysAsContinuedSegmentsWithMovesBatchedWhileOneIsPlaying() {
        gestures.down(1f, 1f)
        assertEquals(listOf("begin 1 at 1,1"), player.take())
        gestures.move(2f, 2f)
        gestures.move(3f, 3f)
        assertEquals(emptyList<String>(), player.take())
        gestures.completed(1)
        assertEquals(listOf("extend 1 from 1,1 to 2,2 to 3,3"), player.take())
        gestures.up()
        gestures.completed(1)
        assertEquals(listOf("extend 1 from 3,3 lift"), player.take())
    }

    @Test
    fun aLateCancelFromThePreviousStrokeLeavesTheNewOneIntact() {
        // A stroke whose up was lost, then a new one.
        gestures.down(0f, 0f)
        gestures.completed(1)
        gestures.down(5f, 5f)
        assertEquals(listOf("begin 1 at 0,0", "begin 2 at 5,5"), player.take())
        gestures.cancelled(1)
        gestures.completed(2)
        gestures.move(6f, 6f)
        assertEquals(listOf("extend 2 from 5,5 to 6,6"), player.take())
        gestures.cancelled(1)
        gestures.completed(2)
        gestures.up()
        assertEquals(listOf("extend 2 from 6,6 lift"), player.take())
    }

    @Test
    fun aTapWaitsForTheSegmentStillPlaying() {
        gestures.down(0f, 0f)
        gestures.up()
        gestures.whole("tap")
        assertEquals(listOf("begin 1 at 0,0"), player.take())
        gestures.completed(1)
        // The stroke's lift goes before the tap, which would otherwise cut it short.
        assertEquals(listOf("extend 1 from 0,0 lift"), player.take())
        gestures.completed(1)
        assertEquals(listOf("play 2 tap"), player.take())
        gestures.whole("pinch")
        assertEquals(emptyList<String>(), player.take())
        gestures.completed(2)
        assertEquals(listOf("play 3 pinch"), player.take())
    }

    @Test
    fun aNewStrokeAndItsMovesWaitForThePreviousLift() {
        gestures.down(0f, 0f)
        gestures.completed(1)
        gestures.up()
        gestures.down(9f, 9f)
        gestures.move(10f, 10f)
        assertEquals(listOf("begin 1 at 0,0", "extend 1 from 0,0 lift"), player.take())
        gestures.completed(1)
        assertEquals(listOf("begin 2 at 9,9"), player.take())
        gestures.completed(2)
        assertEquals(listOf("extend 2 from 9,9 to 10,10"), player.take())
    }

    @Test
    fun aStrokeAndroidCancelledTakesNoMoreMoves() {
        gestures.down(0f, 0f)
        gestures.move(1f, 1f)
        gestures.cancelled(1)
        gestures.move(2f, 2f)
        gestures.up()
        gestures.whole("tap")
        assertEquals(listOf("begin 1 at 0,0", "play 2 tap"), player.take())
    }

    @Test
    fun aRefusedDispatchHoldsNothingUp() {
        player.accept = false
        gestures.down(0f, 0f)
        gestures.move(1f, 1f)
        player.accept = true
        gestures.whole("tap")
        assertEquals(listOf("begin 1 at 0,0", "play 2 tap"), player.take())
    }
}
