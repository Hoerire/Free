package io.github.hoerire.free.platform.coloros

import android.os.SystemClock

/**
 * 气泡启动刚刚把旧窗口退成迷你窗的那一小段时间。
 *
 * 退成迷你窗要先播一段动画，系统的数量裁决可能仍按「旧窗口还是普通小窗」算一次，
 * 于是把刚出现的新窗口当成超限对象踢掉。这里留一个短时间标记：
 * 标记有效期内，系统针对这次启动发起的退场直接放行不执行。
 */
internal object BubbleDemotionGuard {
    /** 只覆盖「退迷你 → 新窗口创建 → 超限裁决」这一小段。 */
    private const val WINDOW_MS = 2_000L

    /** 数量上限的宽限窗口：覆盖从 cut 成迷你到新窗口创建完成这一段。 */
    private const val GRACE_MS = 1_200L

    @Volatile
    private var armedAt = 0L

    fun arm() {
        armedAt = SystemClock.elapsedRealtime()
    }

    /** 窗口切换还没结算完：一小段时间内数量上限临时多放一个，等迷你窗状态生效。 */
    fun recent(): Boolean {
        val at = armedAt
        if (at <= 0L) return false
        return SystemClock.elapsedRealtime() - at in 0L..GRACE_MS
    }

    /** 取走标记：只有一次生效，避免压住之后真正需要的退场。 */
    fun consume(): Boolean {
        val at = armedAt
        if (at <= 0L) return false
        armedAt = 0L
        val elapsed = SystemClock.elapsedRealtime() - at
        return elapsed in 0L..WINDOW_MS
    }
}
