package io.github.hoerire.free.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadialGeometryTest {
    @Test
    fun hiddenMenuCannotSelectAnItemAtTheCollapsedOrigin() {
        val layout = RadialGeometry.layout(CornerSide.Left, 400f, 890f, 0f, 7)
        assertNull(RadialGeometry.selection(layout, 0f, 890f, 0, 0f, 0f))
    }

    @Test
    fun listOrderRunsFromOuterSlotTowardMore() {
        val layout = RadialGeometry.layout(CornerSide.Left, 400f, 890f, 242f, 7)
        val degrees = layout.itemCenters.map { RadialGeometry.polarAngle(layout, it) * 180f / kotlin.math.PI.toFloat() }
        degrees.zip(listOf(-81f, -69f, -57f, -45f, -33f, -21f, -9f)).forEach { (actual, expected) ->
            assertEquals(expected, actual, 0.001f)
        }
        val onlyMore = RadialGeometry.layout(CornerSide.Left, 400f, 890f, 242f, 1)
        assertEquals(-45f, RadialGeometry.polarAngle(onlyMore, onlyMore.itemCenters.single()) * 180f / kotlin.math.PI.toFloat(), 0.001f)
    }

    @Test
    fun leftAndRightLayoutsAreExactMirrors() {
        val left = RadialGeometry.layout(CornerSide.Left, 1000f, 2000f, 400f, 7)
        val right = RadialGeometry.layout(CornerSide.Right, 1000f, 2000f, 400f, 7)
        left.itemCenters.zip(right.itemCenters).forEach { (l, r) ->
            assertEquals(1000f, l.x + r.x, 0.001f)
            assertEquals(l.y, r.y, 0.001f)
        }
    }

    @Test
    fun previousSelectionKeepsCurrentCellOnlyWithinKeepRadius() {
        val layout = RadialGeometry.layout(CornerSide.Left, 1000f, 2000f, 400f, 7)
        val center = layout.itemCenters[2]
        // 在保持圈内：不换格。
        assertEquals(2, RadialGeometry.selection(layout, center.x + 39f, center.y, 2, 30f, 42f))
        // 保持圈大到能盖住相邻格时，即使手指已在该格上仍保持上一格。
        val neighbor = layout.itemCenters[3]
        assertEquals(2, RadialGeometry.selection(layout, neighbor.x, neighbor.y, 2, 30f, 100f))
        // 生产路径传 0：没有保持圈，立刻按最近距离换格。
        assertEquals(3, RadialGeometry.selection(layout, neighbor.x, neighbor.y, 2, 30f, 0f))
    }

    @Test
    fun collapsedProgressSelectsNothingEvenFarFromOrigin() {
        val layout = RadialGeometry.layout(CornerSide.Left, 1000f, 2000f, 400f, 7)
        // 目标格的最终位置：展开进度为 0 时图标还在原点，这个位置不该命中任何一格。
        val far = layout.itemCenters[3]
        assertNull(RadialGeometry.selection(layout, far.x, far.y, null, 120f, 0f, progress = 0f))
        // 低进度下命中圈缩在原点附近，同样不认这个远处的位置。
        assertNull(RadialGeometry.selection(layout, far.x, far.y, null, 120f, 0f, progress = 0.35f))
    }

    @Test
    fun hitRingFollowsDrawnProgress() {
        val layout = RadialGeometry.layout(CornerSide.Left, 1000f, 2000f, 400f, 7)
        val index = 3
        val finalCenter = layout.itemCenters[index]
        // 展开到 50%：命中的应该是"画到一半"的那一格，而不是最终位置。
        val midCenter =
            GesturePoint(
                x = layout.origin.x + (finalCenter.x - layout.origin.x) * 0.5f,
                y = layout.origin.y + (finalCenter.y - layout.origin.y) * 0.5f,
            )
        assertEquals(
            index,
            RadialGeometry.selection(layout, midCenter.x, midCenter.y, null, 120f, 0f, progress = 0.5f),
        )
        // 反之，展开途中把手指放到最终位置（半径已满处）不该命中。
        assertNull(RadialGeometry.selection(layout, finalCenter.x, finalCenter.y, null, 120f, 0f, progress = 0.5f))
        // 完全展开后最终位置当然命中同一格。
        assertEquals(index, RadialGeometry.selection(layout, finalCenter.x, finalCenter.y, null, 120f, 0f))
    }

    @Test
    fun appCentersStayInsideScreenAndMoreRemainsClosestToCorner() {
        val width = 1000f
        val height = 2000f
        val left = RadialGeometry.layout(CornerSide.Left, width, height, 400f, 7)
        val right = RadialGeometry.layout(CornerSide.Right, width, height, 400f, 7)

        assertTrue(left.itemCenters.dropLast(1).all { center -> center.x > 0f })
        assertTrue(right.itemCenters.dropLast(1).all { center -> center.x < width })
        assertTrue(left.itemCenters.last().y > left.itemCenters.dropLast(1).maxOf { it.y })
        assertTrue(right.itemCenters.last().y > right.itemCenters.dropLast(1).maxOf { it.y })
    }

}
