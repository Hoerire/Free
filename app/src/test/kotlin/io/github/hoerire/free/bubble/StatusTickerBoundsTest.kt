package io.github.hoerire.free.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定「滚动长度被砍一半」这个回归：本机真实几何 + 几种挖孔位置的机型。
 *
 * 本机（1272 宽、density 3.5、挖孔 598..674）：left 68、图标区 688、edgeInset 7、systemInset 84。
 */
class StatusTickerBoundsTest {

    @Test
    fun withoutCutoutUsesSystemBoundary() {
        val right = StatusTickerBounds.rightBoundary(
            left = 68, cameraLeft = null, cameraRight = null,
            systemLeft = 688, edgeInset = 7, systemInset = 84,
        )
        assertEquals(604, right)
    }

    /**
     * 本机：挖孔居中，左侧 523px 够用，按旧行为停在挖孔左边——行为不变。
     * 这条用例的作用是防止「顺手改成无条件取较大者」把本机也改了。
     */
    @Test
    fun centeredCutoutKeepsOldBehaviourWhenWideEnough() {
        val right = StatusTickerBounds.rightBoundary(
            left = 68, cameraLeft = 598, cameraRight = 674,
            systemLeft = 688, edgeInset = 7, systemInset = 84,
        )
        assertEquals(591, right)
    }

    /**
     * 挖孔偏左、把区间压到 200px 出头：必须退到图标区左沿，宽度至少翻倍。
     * 这条就是用户反馈的场景。
     */
    @Test
    fun leftCutoutFallsBackInsteadOfHalvingTheRow() {
        val right = StatusTickerBounds.rightBoundary(
            left = 68, cameraLeft = 300, cameraRight = 376,
            systemLeft = 688, edgeInset = 7, systemInset = 84,
        )
        assertEquals(604, right)
        val fixed = right - 68
        val broken = 293 - 68
        assertTrue("修正后应明显更宽", fixed > broken * 2)
    }

    /** 挖孔整段都在图标区左沿右边：不在滚动条能到的范围里，不该为它让路。 */
    @Test
    fun cutoutBeyondSystemBoundaryIsIgnored() {
        val right = StatusTickerBounds.rightBoundary(
            left = 68, cameraLeft = 900, cameraRight = 976,
            systemLeft = 888, edgeInset = 7, systemInset = 84,
        )
        assertEquals(893, right)
    }

    /** 挖孔靠右、左侧很宽：停在挖孔左边的旧行为，不做任何改动。 */
    @Test
    fun rightCutoutKeepsCutoutCandidate() {
        val right = StatusTickerBounds.rightBoundary(
            left = 68, cameraLeft = 1000, cameraRight = 1076,
            systemLeft = 1150, edgeInset = 7, systemInset = 84,
        )
        assertEquals(993, right)
    }

    /** 任何输入都不得返回负数，也不得比旧逻辑更窄。 */
    @Test
    fun neverWorseThanOldLogic() {
        val samples = listOf(
            intArrayOf(68, 200, 276),
            intArrayOf(68, 300, 376),
            intArrayOf(68, 598, 674),
            intArrayOf(68, 900, 976),
            intArrayOf(0, 3, 20),
        )
        for (s in samples) {
            val right = StatusTickerBounds.rightBoundary(
                left = s[0], cameraLeft = s[1], cameraRight = s[2],
                systemLeft = 688, edgeInset = 7, systemInset = 84,
            )
            val old = Math.max(0, s[1] - 7)
            assertTrue("不得为负", right >= 0)
            assertTrue("不得比旧逻辑更窄 (cam=" + s[1] + ")", right >= old)
        }
    }
}
