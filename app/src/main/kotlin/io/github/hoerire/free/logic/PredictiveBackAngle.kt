package io.github.hoerire.free.logic

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.PI

/**
 * 侧滑扩展的方向约束：只认「接近竖直」的那一段位移。
 *
 * 约束只用在**下拉**方向：下拉是顺着手指自然回落，方向一斜就很容易把「更多」面板
 * 误触出来；上滑要先克服手腕内旋、本来就不容易触发，不加限制。这里按「与竖直方向的夹角」判定：
 *
 * - 竖直 = 90°（相对水平轴）；允许到 120°，也就是**偏离竖直 ±30°**以内；
 * - 等价条件：|dx| <= |dy| × tan(30°)，纯数值，放进单测里钉住。
 *
 * [dx]/[dy] 传的是**锚点之后**的位移，不是整段行程：边缘内滑本身是横向的，
 * 把它算进去会让正常竖滑被判成斜滑（见 PredictiveBackSwipeHook 的注释）。
 */
internal object PredictiveBackAngle {

    /**
     * 整段位移允许的最大偏角（相对竖直方向）。产品口径是「垂直 90°、最高 120°」即 ±30°。
     *
     * 只判整段不够：斜着起手、后段转正会把累计角度「洗白」，所以另有一条
     * [MAX_RECENT_DEGREES_FROM_VERTICAL] 专门看最近走向，比这里更严。
     */
    const val MAX_DEGREES_FROM_VERTICAL = 30f

    /**
     * 最近一小段走向允许的最大偏角。
     *
     * 真机日志里误触的轨迹是「整段 29°、最近 24°」一路蹭到阈值再触发；
     * 收紧到 20° 后这类轨迹会被挡下，正常的近竖直下拉不受影响。
     */
    const val MAX_RECENT_DEGREES_FROM_VERTICAL = 20f

    private val MAX_RADIANS = MAX_DEGREES_FROM_VERTICAL * (PI.toFloat() / 180f)
    private val MAX_RECENT_RADIANS = MAX_RECENT_DEGREES_FROM_VERTICAL * (PI.toFloat() / 180f)

    /** 整段位移是否仍算「竖直方向」。dy 为 0（纯横滑）一律不算。 */
    fun isVertical(dx: Float, dy: Float): Boolean = within(dx, dy, MAX_RADIANS)

    /** 最近一小段的走向是否仍算「竖直方向」。 */
    fun isRecentVertical(dx: Float, dy: Float): Boolean = within(dx, dy, MAX_RECENT_RADIANS)

    private fun within(dx: Float, dy: Float, limit: Float): Boolean {
        if (abs(dy) <= 0f) return false
        // 取绝对值再算角度：上滑 dy 是负数，直接 atan2(dx, dy) 会落到 180° 那一侧。
        return atan2(abs(dx), abs(dy)) <= limit
    }

    /** 位移与竖直方向的夹角（度），诊断用。 */
    fun degreesFromVertical(dx: Float, dy: Float): Int {
        if (abs(dy) <= 0f && abs(dx) <= 0f) return 0
        return Math.toDegrees(atan2(abs(dx), abs(dy)).toDouble()).toInt()
    }
}
