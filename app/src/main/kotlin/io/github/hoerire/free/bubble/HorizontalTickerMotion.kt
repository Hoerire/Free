package io.github.hoerire.free.bubble

/**
 * 状态栏横向滚动通知的纯数值逻辑：文本宽度、位移推进与轮次收场判定。
 *
 * 这里刻意不碰 `android.graphics.*` 与任何 View，只吃浮点数、吐浮点数，
 * 这样速度换算和边界夹取可以在 JVM 单测里直接验证；真正的绘制与动画留在
 * `StatusScrollOverlay`。
 *
 * 坐标约定：位移 `0` 表示文本左缘贴着可视区左沿（一进场就铺满可见），
 * 向左滚动时位移为负，`-textWidth` 表示文本右缘刚好滑出可视区左沿。
 */
internal object HorizontalTickerMotion {
    /** 只有文本真的比可视区宽才滚；等宽或更短时保持静态左对齐。 */
    fun shouldScroll(textWidth: Float, contentWidth: Float): Boolean {
        if (contentWidth <= 0f || textWidth <= 0f) return false
        return textWidth > contentWidth
    }

    /**
     * 一帧里推进的位移：从 `0`（铺满可见）向 `-textWidth`（整条滑出）推进，
     * 速度恒定为 [pxPerSecond]，与文本长度无关。
     *
     * 返回值只会前进不会后退；越过终点后停在终点，由调用方判定轮次。
     */
    fun advanceOffset(
        offset: Float,
        elapsedMs: Long,
        pxPerSecond: Float,
        textWidth: Float,
    ): Float {
        val end = -Math.max(0f, textWidth)
        val step = if (elapsedMs <= 0L) 0f else pxPerSecond * (elapsedMs / 1000f)
        val next = offset - step
        if (next <= end) return end
        return Math.min(0f, next)
    }

    /** 把位移夹在「起点 0」与「终点 `-textWidth`」之间。 */
    fun clampOffset(offset: Float, textWidth: Float): Float {
        if (textWidth <= 0f) return 0f
        return Math.max(-textWidth, Math.min(0f, offset))
    }

    /** 一轮滚完后是否该收场：[completedRounds] 达到 [displayRounds] 即结束。 */
    fun isFinished(completedRounds: Int, displayRounds: Int): Boolean {
        return completedRounds >= displayRounds
    }
}
