package co.remotedesktop

import co.remotedesktop.PhoneTouchInput.Pinch
import co.remotedesktop.PhoneTouchInput.Tap
import co.remotedesktop.PhoneTouchInput.Touch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fingers on the picture of another phone, and what that phone is sent. Plain JVM. */
class PhoneTouchInputTest {
    // A 1024 x 2048 picture with 100 px black bars to its left and right, so
    // positions on it are exact fractions: x 612 is 0.5, y 1024 is 0.5.
    private val input = PhoneTouchInput(touchSlop = 10f).apply { setPicture(100f, 0f, 1024f, 2048f) }

    private fun down(x: Double, y: Double) = Touch("down", x, y)
    private fun move(x: Double, y: Double) = Touch("move", x, y)
    private fun up(x: Double, y: Double) = Touch("up", x, y)

    @Test
    fun aQuickTouchIsOneTapWhereItStarted() {
        assertEquals(emptyList<Any>(), input.down(0, 612f, 1024f, 0))
        assertEquals(150L, input.nextTick())
        // Jitter under the touch slop is still a tap.
        assertEquals(emptyList<Any>(), input.move(0, 617f, 1027f, 60))
        assertEquals(listOf(Tap(0.5, 0.5)), input.up(0, 617f, 1027f, 149))
        assertNull(input.nextTick())
    }

    @Test
    fun aStillPressGoesDownAfterTheWindowAndKeepsItsLength() {
        input.down(0, 612f, 1024f, 1_000)
        assertEquals(emptyList<Any>(), input.tick(1_149))
        assertEquals(listOf(down(0.5, 0.5)), input.tick(1_150))
        // Lifted after 800 ms: the up goes as much later as the down did, so
        // the other phone sees an 800 ms press too.
        assertEquals(emptyList<Any>(), input.up(0, 612f, 1024f, 1_800))
        assertEquals(1_950L, input.nextTick())
        assertEquals(emptyList<Any>(), input.tick(1_949))
        assertEquals(listOf(up(0.5, 0.5)), input.tick(1_950))
        assertNull(input.nextTick())
    }

    @Test
    fun aNewTouchSendsTheHeldBackUpFirst() {
        input.down(0, 612f, 1024f, 0)
        input.tick(150)
        input.up(0, 612f, 1024f, 300)
        assertEquals(listOf(up(0.5, 0.5)), input.down(0, 356f, 512f, 310))
        assertEquals(listOf(Tap(0.25, 0.25)), input.up(0, 356f, 512f, 380))
    }

    @Test
    fun aDragGoesDownWhereItStartedMovesEvery30MsAndEndsWhereItLifted() {
        input.down(0, 612f, 1024f, 0)
        // Past the slop within the window: no waiting for it.
        assertEquals(listOf(down(0.5, 0.5), move(0.5, 0.53125)), input.move(0, 612f, 1088f, 20))
        assertEquals(emptyList<Any>(), input.move(0, 612f, 1152f, 36))
        assertEquals(50L, input.nextTick())
        assertEquals(listOf(move(0.5, 0.5625)), input.tick(50))
        assertEquals(listOf(move(0.5, 0.59375)), input.move(0, 612f, 1216f, 80))
        // Lifted 10 ms later, further on: the last move carries the end of the drag.
        assertEquals(listOf(move(0.5, 0.625), up(0.5, 0.625)), input.up(0, 612f, 1280f, 90))
        assertNull(input.nextTick())
    }

    @Test
    fun aPressThatThenMovesBecomesADragAndLiftsAtOnce() {
        input.down(0, 612f, 1024f, 0)
        assertEquals(listOf(down(0.5, 0.5)), input.tick(150))
        assertEquals(emptyList<Any>(), input.move(0, 615f, 1024f, 400))
        assertEquals(listOf(move(0.75, 0.5)), input.move(0, 868f, 1024f, 600))
        assertEquals(listOf(up(0.75, 0.5)), input.up(0, 868f, 1024f, 650))
        assertNull(input.nextTick())
    }

    @Test
    fun movesThatLeaveThePictureStopAtItsEdge() {
        input.down(0, 612f, 1024f, 0)
        assertEquals(listOf(down(0.5, 0.5), move(1.0, 0.0)), input.move(0, 2000f, -300f, 20))
        assertEquals(listOf(move(0.0, 1.0), up(0.0, 1.0)), input.up(0, 0f, 2500f, 90))
    }

    @Test
    fun aTouchStartingInTheBlackBarsIsIgnored() {
        assertEquals(emptyList<Any>(), input.down(0, 50f, 1024f, 0))
        assertEquals(emptyList<Any>(), input.move(0, 612f, 1024f, 50))
        assertNull(input.nextTick())
        assertEquals(emptyList<Any>(), input.tick(500))
        assertEquals(emptyList<Any>(), input.up(0, 612f, 1024f, 600))
    }

    @Test
    fun twoFingersWithinTheWindowAreOneProportionalPinch() {
        input.down(0, 612f, 1024f, 0)
        assertEquals(emptyList<Any>(), input.pointerDown(1, 612f, 1280f, 60))
        // No touch down for a pinch.
        assertNull(input.nextTick())
        input.move(0, 612f, 896f, 200)
        input.move(1, 612f, 1408f, 200)
        // Spread 256 to 512, centred between where the fingers landed.
        assertEquals(listOf(Pinch(0.5, 0.5625, zoomIn = true, scale = 2.0)), input.pointerUp(1, 612f, 1408f, 300))
        assertEquals(emptyList<Any>(), input.up(0, 612f, 896f, 320))

        input.down(0, 612f, 768f, 1_000)
        input.pointerDown(1, 612f, 1280f, 1_100)
        assertEquals(emptyList<Any>(), input.move(1, 612f, 1024f, 1_200))
        assertEquals(listOf(Pinch(0.5, 0.5, zoomIn = false, scale = 0.5)), input.up(1, 612f, 1024f, 1_300))
    }

    @Test
    fun aSecondFingerAfterTheStrokeStartedIsIgnored() {
        input.down(0, 612f, 1024f, 0)
        assertEquals(listOf(down(0.5, 0.5)), input.tick(150))
        assertEquals(emptyList<Any>(), input.pointerDown(1, 868f, 1024f, 200))
        assertEquals(emptyList<Any>(), input.move(1, 868f, 1536f, 300))
        assertEquals(listOf(move(0.5, 0.75)), input.move(0, 612f, 1536f, 300))
        assertEquals(emptyList<Any>(), input.pointerUp(1, 868f, 1536f, 400))
        assertEquals(listOf(up(0.5, 0.75)), input.up(0, 612f, 1536f, 450))
    }

    @Test
    fun cancellingNeverLeavesAFingerDownThere() {
        input.down(0, 612f, 1024f, 0)
        // Nothing went out yet, so there is nothing to lift.
        assertEquals(emptyList<Any>(), input.cancel())
        assertNull(input.nextTick())

        input.down(0, 612f, 1024f, 1_000)
        input.tick(1_150)
        assertEquals(listOf(up(0.5, 0.5)), input.cancel())

        // A still press's held-back up goes at once.
        input.down(0, 612f, 1024f, 2_000)
        input.tick(2_150)
        input.up(0, 612f, 1024f, 2_400)
        assertEquals(listOf(up(0.5, 0.5)), input.cancel())
        assertNull(input.nextTick())
    }
}
