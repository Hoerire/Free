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

    /** 两端渐隐的最大宽度：不超过可视宽度的 1/2，窄屏上也不会整条发虚。 */
    fun maxEdgeFade(contentWidth: Float, requested: Float): Float {
        if (contentWidth <= 0f || requested <= 0f) return 0f
        return Math.min(requested, contentWidth / 2f)
    }

    /**
     * 左端渐隐宽度：文本已经滚出去多少（`-offset`），最多 [maxFade]。
     *
     * 起点 `offset = 0` 时为 0——第一个字不该一出现就发虚；
     * 文字向左滚的同时边缘才慢慢浮出来（10dp 在默认速度下约 0.4s 到满），
     * 不会出现「刚进场就有一块发白」的突兀感。
     */
    fun leftFadeWidth(offset: Float, contentWidth: Float, maxFade: Float): Float {
        val cap = maxEdgeFade(contentWidth, maxFade)
        if (cap <= 0f) return 0f
        return Math.min(cap, Math.max(0f, -offset))
    }

    /**
     * 右端渐隐宽度：还有多少文本伸出可视区右侧，最多 [maxFade]。
     *
     * 滚到末尾时这一项自然收窄到 0，最后一段文字不会在右缘提前变淡。
     */
    fun rightFadeWidth(
        offset: Float,
        contentWidth: Float,
        textWidth: Float,
        maxFade: Float,
    ): Float {
        val cap = maxEdgeFade(contentWidth, maxFade)
        if (cap <= 0f) return 0f
        val beyond = textWidth + offset - contentWidth
        return Math.min(cap, Math.max(0f, beyond))
    }

    /**
     * 渐隐遮罩的四个颜色位置（0..1，相对可视宽度）。
     *
     * 依次是：可视区左沿、左端渐隐结束、右端渐隐开始、可视区右沿。
     * 两段渐隐重叠时各让一半，保证位置单调，LinearGradient 不会报错。
     */
    fun fadeStopOffsets(contentWidth: Float, leftFade: Float, rightFade: Float): FloatArray {
        if (contentWidth <= 0f) return floatArrayOf(0f, 0f, 1f, 1f)
        val left = (leftFade / contentWidth).coerceIn(0f, 0.5f)
        val right = (1f - rightFade / contentWidth).coerceIn(0.5f, 1f)
        return floatArrayOf(0f, left, right, 1f)
    }
}
