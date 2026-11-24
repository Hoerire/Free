package io.github.hoerire.free.window

import org.junit.Assert.assertEquals
import org.junit.Test

class IconContentMetricsTest {
    /** 生成一张 [size]×[size] 的 ARGB 像素表，内容区域由 [content] 决定。 */
    private fun frame(size: Int, content: (x: Int, y: Int) -> Int): IntArray =
        IntArray(size * size) { index -> content(index % size, index / size) }

    @Test
    fun fullBleedIconMeasuresAsFullFrame() {
        val pixels = frame(8) { _, _ -> 0xFFFFFFFF.toInt() }
        assertEquals(1f, IconContentMetrics.contentRatioOf(8, 8, pixels), 0.0001f)
    }

    @Test
    fun paddedIconMeasuresOnlyItsVisibleContent() {
        // 8×8 里只有中间 4×4 有像素：内容占外框一半。
        val pixels =
            frame(8) { x, y ->
                if (x in 2..5 && y in 2..5) 0xFFFFFFFF.toInt() else 0
            }
        assertEquals(0.5f, IconContentMetrics.contentRatioOf(8, 8, pixels), 0.0001f)
    }

    @Test
    fun asymmetricContentUsesTheLongerSide() {
        // 宽 6、高 3 的横条：按长边算，8 里的 6 → 0.75。
        val pixels =
            frame(8) { x, y ->
                if (x in 1..6 && y in 3..5) 0xFFFFFFFF.toInt() else 0
            }
        assertEquals(0.75f, IconContentMetrics.contentRatioOf(8, 8, pixels), 0.0001f)
    }

    @Test
    fun faintShadowDoesNotCountAsContent() {
        // alpha 20 低于可见阈值（32），整张按「没有内容」处理，回落到满幅。
        val pixels = frame(8) { _, _ -> 0x14FFFFFF }
        assertEquals(1f, IconContentMetrics.contentRatioOf(8, 8, pixels), 0.0001f)
    }

    @Test
    fun fullyTransparentIconFallsBackToFullFrame() {
        val pixels = frame(8) { _, _ -> 0 }
        assertEquals(1f, IconContentMetrics.contentRatioOf(8, 8, pixels), 0.0001f)
    }

    @Test
    fun tinyContentIsClampedSoThePlateNeverCollapses() {
        // 100×100 里只有 2×2：真实占比 0.02，但圆盘不能缩成一个点。
        val pixels =
            frame(100) { x, y ->
                if (x in 49..50 && y in 49..50) 0xFFFFFFFF.toInt() else 0
            }
        assertEquals(0.2f, IconContentMetrics.contentRatioOf(100, 100, pixels), 0.0001f)
    }

    @Test
    fun alphaJustAboveThresholdCountsAsContent() {
        // 阈值是「> 32 才算」，33 刚好越过：4×4 里占 1 格。
        val pixels = frame(4) { x, y -> if (x == 1 && y == 1) 0x21FFFFFF else 0 }
        assertEquals(0.25f, IconContentMetrics.contentRatioOf(4, 4, pixels), 0.0001f)
    }
}
