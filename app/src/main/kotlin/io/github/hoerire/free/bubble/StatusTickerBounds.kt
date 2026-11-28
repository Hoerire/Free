package io.github.hoerire.free.bubble

/**
 * 状态栏滚动条右侧可用边界的裁决。
 *
 * 旧逻辑是「有挖孔就顶到挖孔左沿」，它默认挖孔总在屏幕右侧。现实不是这样：
 * 挖孔居中时（本机 1272 宽、挖孔 598..674，中心 636 正好是屏幕中线），左沿 598 把可视区
 * 压到 523px；挖孔再偏左一点就只剩两三百像素——用户看到的就是「滚动长度被砍一半」。
 * 挖孔两侧本是空的，真正该避开的只有挖孔本身。
 *
 * 现行规则（纯数值，可 JVM 单测）：按「挖孔是否真的挡在文字路径上」决定。
 *   - 挖孔完全落在右界之外（挖孔右沿 ≤ 图标区左沿）：根本挡不住文字，直接用图标区左沿；
 *   - 挖孔确实挡路：停在挖孔左边（候选 A），与原厂对摄像头的关系一致；
 *   - 但停过去之后剩得太窄（[MIN_USABLE_WIDTH_PX] 以下）：退到图标区左沿，
 *     宁可文字从挖孔下方掠过，也不砍掉一半。
 *
 * 三个分支的右界都取「不比旧逻辑更窄」，所以不可能引入「改完反而更短」的回归。
 * 代价说清楚：退让时文字会从挖孔下方掠过（本机挖孔占满 0..141 整个状态栏高度）——
 * 掠过是轻微视觉妥协，被砍一半是功能失败，这个取舍是刻意的。
 */
internal object StatusTickerBounds {

    /**
     * 「够用」的下限（px）。低于它说明挖孔把区间压扁了，退到图标区左沿。
     *
     * 取值依据：本机挖孔前区间 523px，能放下约 14 个 11sp 汉字，属于可用；
     * 400px 以下不足 10 个字，故障反馈集中在这一档。阈值只影响「是否退让」，
     * 调大只会让更多机型拿到更宽的区间，不会让任何机型变窄。
     */
    const val MIN_USABLE_WIDTH_PX = 400f

    /**
     * 选出右界。
     *
     * @param left 滚动条左沿（对齐时钟左沿）
     * @param cameraLeft 挖孔左沿（宿主坐标）；没有挖孔传 null
     * @param cameraRight 挖孔右沿（宿主坐标）；没有挖孔传 null
     * @param systemLeft 系统图标区左沿（宿主坐标）
     * @param edgeInset 有挖孔时让出的余量（dp 换算后的 px）
     * @param systemInset 无挖孔时让出的余量：图标区自带留白，要多让一些
     */
    fun rightBoundary(
        left: Int,
        cameraLeft: Int?,
        cameraRight: Int?,
        systemLeft: Int,
        edgeInset: Int,
        systemInset: Int,
    ): Int {
        val systemCandidate = Math.max(0, systemLeft - systemInset)
        if (cameraLeft == null) return systemCandidate
        val cutoutCandidate = Math.max(0, cameraLeft - edgeInset)
        // 挖孔整段都在图标区左沿右侧：它根本不在滚动条能到的范围里，不该为它让路。
        val cutoutBeyondRow = cameraRight != null && cameraRight <= systemCandidate
        if (cutoutBeyondRow) return Math.max(cutoutCandidate, systemCandidate)
        // 挖孔挡路但停在前面的空间够用：维持旧行为，文字不碰挖孔。
        if (cutoutCandidate - left >= MIN_USABLE_WIDTH_PX) return cutoutCandidate
        // 挡路且太窄：退到图标区左沿；绝不比旧逻辑更窄。
        return Math.max(cutoutCandidate, systemCandidate)
    }
}
