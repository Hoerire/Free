package io.github.hoerire.free.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizontalTickerMotionTest {
    @Test
    fun `长文本比可视区宽时才滚`() {
        assertTrue(HorizontalTickerMotion.shouldScroll(900f, 523f))
        assertFalse(HorizontalTickerMotion.shouldScroll(523f, 523f))
        assertFalse(HorizontalTickerMotion.shouldScroll(120f, 523f))
        assertFalse(HorizontalTickerMotion.shouldScroll(900f, 0f))
        assertFalse(HorizontalTickerMotion.shouldScroll(0f, 523f))
    }

    @Test
    fun `位移按像素每秒恒定推进`() {
        // 100ms 走 92px/s × 0.1s = 9.2px，起点 0 表示铺满可见
        assertEquals(-9.2f, HorizontalTickerMotion.advanceOffset(0f, 100L, 92f, 900f), 0.01f)
    }

    @Test
    fun `长短通知的快慢一致`() {
        val shortStep = HorizontalTickerMotion.advanceOffset(0f, 1000L, 92f, 600f)
        val longStep = HorizontalTickerMotion.advanceOffset(0f, 1000L, 92f, 1800f)
        assertEquals(-92f, shortStep, 0.01f)
        assertEquals(-92f, longStep, 0.01f)
    }

    @Test
    fun `滚到终点后停在负的文本宽度`() {
        assertEquals(-900f, HorizontalTickerMotion.advanceOffset(0f, 60_000L, 92f, 900f), 0.01f)
    }

    @Test
    fun `零时长不会把位移往回推`() {
        assertEquals(0f, HorizontalTickerMotion.advanceOffset(0f, 0L, 92f, 900f), 0.01f)
        assertEquals(-5f, HorizontalTickerMotion.advanceOffset(-5f, -5L, 92f, 900f), 0.01f)
    }

    @Test
    fun `位移夹在起点与终点之间`() {
        assertEquals(-200f, HorizontalTickerMotion.clampOffset(-200f, 900f), 0.01f)
        assertEquals(0f, HorizontalTickerMotion.clampOffset(800f, 900f), 0.01f)
        assertEquals(-900f, HorizontalTickerMotion.clampOffset(-2000f, 900f), 0.01f)
        assertEquals(0f, HorizontalTickerMotion.clampOffset(-120f, 0f), 0.01f)
    }

    @Test
    fun `轮次够了才收场`() {
        assertFalse(HorizontalTickerMotion.isFinished(0, 1))
        assertTrue(HorizontalTickerMotion.isFinished(1, 1))
        assertTrue(HorizontalTickerMotion.isFinished(3, 2))
    }
}
