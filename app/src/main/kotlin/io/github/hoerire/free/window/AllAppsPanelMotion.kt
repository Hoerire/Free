package io.github.hoerire.free.window

import kotlin.math.PI
import kotlin.math.exp

/**
 * 面板动画参数。
 *
 * 入场跟随系统启动动画：归一化时长 0.7 s（受系统"动画时长缩放"影响，由调用方的
 * elapsed/durationScale 体现），曲线用系统的 fast_out_slow_in（cubic-bezier(0.4, 0, 0.2, 1)）。
 *
 * 入场只有一组数值 a：底板与内容一起淡入（alpha 从 0 跟随 a），同时从 90% 轻微撑开到 100%。
 * 早期版本底板恒为不透明、只缩放，宽度 30% 时看着像一条"白色长条"；去掉缩放后又整块白闪一下。
 * 现在底板透明度也走 a，不再有"先白后内容"的两段感。
 * 退场仍保留临界阻尼弹簧（与 ColorOS 原生面板退场一致）。
 */
internal object AllAppsPanelMotion {
    data class Frame(val alpha: Float, val scale: Float)

    /** 入场归一化时长（秒）：进度 = 已过秒数 / 它。 */
    private const val ENTER_DURATION_SECONDS = 0.7f

    /** 入场结束阈值（毫秒）：必须与上面同口径，否则会在 450ms 处提前判定结束。 */
    const val ENTER_DURATION_MS = 700L

    // cubic-bezier(0.4, 0, 0.2, 1) 自己算，不引 android.view.animation：
    // 那个类在单测环境里是桩，会直接抛异常，等于把这条曲线挡在测试之外。
    private fun enterEase(progress: Float): Float {
        var low = 0f
        var high = 1f
        var u = progress
        repeat(8) {
            if (bezierAxis(0.4f, 0.2f, u) < progress) low = u else high = u
            u = (low + high) / 2f
        }
        // 取上界而不是中点：中点会在曲线顶部留下 1/256 的抖动（alpha 会出现 4e-5 的倒退），
        // 上界随 progress 单调不减，完成判定不会再被判成"往回走"。
        return bezierAxis(0f, 1f, high)
    }

    private fun bezierAxis(p1: Float, p2: Float, u: Float): Float {
        val v = 1f - u
        return 3f * v * v * u * p1 + 3f * v * u * u * p2 + u * u * u
    }

    fun exit(seconds: Float): Frame = Frame(
        alpha = remaining(seconds, 0.3f),
        scale = 0.3f + 0.7f * remaining(seconds, 0.4f),
    )

    fun enter(seconds: Float): Frame {
        val progress = (seconds / ENTER_DURATION_SECONDS).coerceIn(0f, 1f)
        val eased = enterEase(progress)
        // 缩放只从 90% 撑到 100%：不再出现 30% 宽的白条。
        return Frame(alpha = eased, scale = 0.1f * eased + 0.9f)
    }

    private fun remaining(seconds: Float, period: Float): Float {
        val time = seconds.coerceAtLeast(0f)
        val phase = 2.0 * PI * time / period
        return ((1.0 + phase) * exp(-phase)).toFloat().coerceIn(0f, 1f)
    }

    // 与原生 alpha 的最小可见变化量及其位移阈值一致。
    const val EXIT_ALPHA_THRESHOLD = 0.00390625f * 0.75f
    const val MAX_DURATION_MS = 1_000L
}
