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
    fun wholeSwipeAllowsThirtyDegrees() {
        // 产品口径：垂直 90°、最高 120°（±30°）。
        assertTrue(PredictiveBackAngle.isVertical(57.7f, 100f))
        assertTrue(PredictiveBackAngle.isVertical(-57.7f, -100f))
    }

    @Test
    fun recentDirectionIsStricter() {
        // 最近走向收紧到 20°：25° 与 45° 的斜滑都算「斜滑」。
        assertTrue(PredictiveBackAngle.isRecentVertical(36f, 100f))
        assertFalse(PredictiveBackAngle.isRecentVertical(47f, 100f))
        assertFalse(PredictiveBackAngle.isRecentVertical(100f, 100f))
    }

    @Test
    fun beyondThirtyDegreesIsRejectedForWholeSwipe() {
        assertFalse(PredictiveBackAngle.isVertical(100f, 100f))
        assertFalse(PredictiveBackAngle.isVertical(-100f, 100f))
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
