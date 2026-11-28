package io.github.hoerire.free.logic

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.PI

/**
 * 侧滑扩展的方向约束：只认「拉出来之后竖直下拉」的那一段位移。
 *
 * 约束只用在**下拉**方向：下拉是顺着手指自然回落，方向一斜就很容易把「更多」面板
 * 误触出来；上滑要先克服手腕内旋、本来就不容易触发，不加限制。这里按「与竖直方向的夹角」判定。
 *
 * 门槛按**跟踪窗口内**的角度定，不是物理手势的角度：返回手势的 DOWN 落在边缘内滑之后，
 * 斜划时横向位移已经走掉大半，剩下的位移看着只有 ~29°（真机日志 t29_r17 就是这么触发的）。
 * 所以整段收到 ±20°、最近一段 ±15°；正常「拉出 → 竖直下拉」的窗口角度在 10° 以内，照样触发。
 *
 * - 等价条件：|dx| <= |dy| × tan(20°)，纯数值，放进单测里钉住。
 *
 * [dx]/[dy] 传的是**锚点之后**的位移，不是整段行程：边缘内滑本身是横向的，
 * 把它算进去会让正常竖滑被判成斜滑（见 PredictiveBackSwipeHook 的注释）。
 */
internal object PredictiveBackAngle {

    /**
     * 整段位移允许的最大偏角（相对竖直方向）。
     *
     * 30° 放过了「斜划顺手往下带」的轨迹（实测 29°），收到 20° 才挡得住；
     * 只判整段不够：斜着起手、后段转正会把累计角度「洗白」，所以另有一条
     * [MAX_RECENT_DEGREES_FROM_VERTICAL] 专门看最近走向，比这里更严。
     */
    const val MAX_DEGREES_FROM_VERTICAL = 20f

    /**
     * 最近一小段走向允许的最大偏角。
     *
     * 真机日志里误触的轨迹是「整段 29°、最近 17~19°」一路蹭到阈值再触发；
     * 收紧到 15° 后这类轨迹会被挡下，正常的近竖直下拉（窗口角度 <10°）不受影响。
     */
    const val MAX_RECENT_DEGREES_FROM_VERTICAL = 15f

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
