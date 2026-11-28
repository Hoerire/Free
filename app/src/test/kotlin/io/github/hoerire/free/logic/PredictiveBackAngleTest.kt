package io.github.hoerire.free.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictiveBackAngleTest {

    @Test
    fun straightUpOrDownIsAccepted() {
        assertTrue(PredictiveBackAngle.isVertical(0f, 200f))
        assertTrue(PredictiveBackAngle.isVertical(0f, -200f))
        assertTrue(PredictiveBackAngle.isRecentVertical(0f, -30f))
    }

    @Test
    fun wholeSwipeAllowsTwentyDegrees() {
        // 整段门槛 20°：19.8° 放行，30° 拒绝。
        assertTrue(PredictiveBackAngle.isVertical(36f, 100f))
        assertTrue(PredictiveBackAngle.isVertical(-36f, -100f))
        assertFalse(PredictiveBackAngle.isVertical(57.7f, 100f))
    }

    @Test
    fun recentDirectionIsStricter() {
        // 最近走向门槛 15°：14.5° 放行，19.8° 拒绝。
        assertTrue(PredictiveBackAngle.isRecentVertical(25.8f, 100f))
        assertFalse(PredictiveBackAngle.isRecentVertical(36f, 100f))
        assertFalse(PredictiveBackAngle.isRecentVertical(100f, 100f))
    }

    @Test
    fun realMisTouchTrajectoryIsRejected() {
        // 真机日志里斜划触发「更多」面板的那一帧：dx=-314 dy=550，整段 29°、最近 17~19°。
        assertFalse(PredictiveBackAngle.isVertical(-314f, 550f))
        assertEquals(29, PredictiveBackAngle.degreesFromVertical(-314f, 550f))
    }

    @Test
    fun fortyFiveDegreeDiagonalIsRejected() {
        assertFalse(PredictiveBackAngle.isVertical(100f, 100f))
        assertFalse(PredictiveBackAngle.isVertical(-100f, 100f))
        assertFalse(PredictiveBackAngle.isRecentVertical(-100f, 100f))
    }

    @Test
    fun horizontalSwipeIsRejected() {
        assertFalse(PredictiveBackAngle.isVertical(200f, 0f))
        assertFalse(PredictiveBackAngle.isRecentVertical(200f, 0f))
    }

    @Test
    fun angleReadsBackForDiagnostics() {
        assertEquals(0, PredictiveBackAngle.degreesFromVertical(0f, 100f))
        assertTrue(PredictiveBackAngle.degreesFromVertical(36.4f, 100f) in 19..20)
        assertTrue(PredictiveBackAngle.degreesFromVertical(57.7f, 100f) in 29..30)
        assertEquals(45, PredictiveBackAngle.degreesFromVertical(100f, 100f))
    }
}
