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

    @Test
    fun `两端渐隐宽度不超过可视区的一半`() {
        // 正常状态栏：10dp 的渐隐远小于可视区宽度的一半，原样返回
        assertEquals(35f, HorizontalTickerMotion.maxEdgeFade(523f, 35f), 0.01f)
        // 窄可视区：两边各让一半，不会整条发虚
        assertEquals(30f, HorizontalTickerMotion.maxEdgeFade(60f, 35f), 0.01f)
        assertEquals(0f, HorizontalTickerMotion.maxEdgeFade(0f, 35f), 0.01f)
        assertEquals(0f, HorizontalTickerMotion.maxEdgeFade(523f, 0f), 0.01f)
    }

    @Test
    fun `左端渐隐从零长出来`() {
        // 起点：第一个字不淡
        assertEquals(0f, HorizontalTickerMotion.leftFadeWidth(0f, 523f, 35f), 0.01f)
        // 已经滚出去 12px：左端淡 12px
        assertEquals(12f, HorizontalTickerMotion.leftFadeWidth(-12f, 523f, 35f), 0.01f)
        // 滚得够远：封顶在最大渐隐宽度
        assertEquals(35f, HorizontalTickerMotion.leftFadeWidth(-400f, 523f, 35f), 0.01f)
        // 位移仍在起点右侧（异常输入）：不淡
        assertEquals(0f, HorizontalTickerMotion.leftFadeWidth(20f, 523f, 35f), 0.01f)
    }

    @Test
    fun `右端渐隐随文本收尾收窄`() {
        // 起点：文本还伸出右界 300px，右端淡满
        assertEquals(35f, HorizontalTickerMotion.rightFadeWidth(0f, 523f, 823f, 35f), 0.01f)
        // 只剩 8px 伸出右界：右端只淡 8px
        assertEquals(8f, HorizontalTickerMotion.rightFadeWidth(-292f, 523f, 823f, 35f), 0.01f)
        // 最后一段完全在可视区内：右端不淡
        assertEquals(0f, HorizontalTickerMotion.rightFadeWidth(-600f, 523f, 823f, 35f), 0.01f)
    }

    @Test
    fun `渐隐遮罩的四个位置单调且落在 0 到 1 之间`() {
        val normal = HorizontalTickerMotion.fadeStopOffsets(523f, 35f, 35f)
        assertEquals(4, normal.size)
        assertEquals(0f, normal[0], 0.0001f)
        assertEquals(1f, normal[3], 0.0001f)
        assertEquals(35f / 523f, normal[1], 0.0001f)
        assertEquals(1f - 35f / 523f, normal[2], 0.0001f)
        assertTrue(normal[1] < normal[2])

        // 两端渐隐加起来超过可视区：各让一半，位置仍单调
        val tight = HorizontalTickerMotion.fadeStopOffsets(40f, 30f, 30f)
        assertEquals(0.5f, tight[1], 0.0001f)
        assertEquals(0.5f, tight[2], 0.0001f)

        // 非法宽度：给一组安全值，不抛异常
        val degenerate = HorizontalTickerMotion.fadeStopOffsets(0f, 10f, 10f)
        assertEquals(4, degenerate.size)
    }
}
