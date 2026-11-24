package io.github.hoerire.free.window

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * 图标位图处理。
 *
 * 只保留两件事：把位图「压成圆」，以及量出**可见内容**占外框多大一块。
 * 绘制期用 clipPath 或 PorterDuff 蒙版都不稳定（满底位图不裁、硬件位图会吞掉
 * Xfermode），因此在生成目录时直接把像素做成圆，绘制层只负责缩放绘制。
 */
internal object IconContentMetrics {
    /**
     * 判定「这里有内容」的 alpha 下限。
     *
     * 压得太低会把图标四周的柔和投影也算成内容，量出来永远是满框；
     * 32/255（约 12%）刚好把投影滤掉、留下真正看得见的图形。
     */
    private const val VISIBLE_ALPHA = 32

    /** 内容占比的下限：再小就不是"图标偏小"而是图本身有问题，别把圆盘缩成一个点。 */
    private const val MIN_RATIO = 0.2f

    /** 裁圆结果缓存：同一位图只裁一次，避免每次呼出扇形都重做合成。 */
    private val circleCache = java.util.WeakHashMap<Bitmap, Bitmap>()

    /** 内容占比缓存：目录重建时会重复问同一批位图。 */
    private val ratioCache = java.util.WeakHashMap<Bitmap, Float>()

    /**
     * 把图标压成圆。
     *
     * 关键是**先取内容再裁圆**，不能只做圆形裁剪：主题图标常常是"带透明内边距的圆角方块"，
     * 图形比裁剪圈小一圈，只裁圆的话图形原样留着，看上去还是方的（第三主题实测如此）。
     * 这里先把有像素的那块裁成正方形、铺满整张，再裁圆——圆角方块的四个角正好被切掉，
     * 得到的是真正的圆形图标；系统那种满幅图标则与原来完全一致（内容本来就占满外框）。
     */
    fun circular(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= 0 || bitmap.height <= 0) return bitmap
        circleCache[bitmap]?.let { return it }
        val size = maxOf(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val content = contentRectOf(bitmap)
        // 取内容里最大的居中正方形，保住长宽比不被拉变形；正方形铺满整张后多余部分由圆裁掉。
        val side = maxOf(1f, minOf(content.width(), content.height()))
        val source =
            RectF(
                content.centerX() - side / 2f,
                content.centerY() - side / 2f,
                content.centerX() + side / 2f,
                content.centerY() + side / 2f,
            )
        val shader =
            BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(
                    Matrix().apply {
                        setRectToRect(
                            source,
                            RectF(0f, 0f, size.toFloat(), size.toFloat()),
                            Matrix.ScaleToFit.FILL,
                        )
                    },
                )
            }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.shader = shader
        }
        Canvas(output).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        circleCache[bitmap] = output
        return output
    }

    /** 可见内容的包围盒（像素坐标）；整张全透明时回落到整张位图。 */
    fun contentRectOf(bitmap: Bitmap): RectF {
        if (bitmap.width <= 0 || bitmap.height <= 0) return RectF()
        val bounds = boundsOf(bitmap.width, bitmap.height, pixelsOf(bitmap))
        return if (bounds == null) {
            RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        } else {
            RectF(
                bounds[0].toFloat(),
                bounds[1].toFloat(),
                bounds[2] + 1f,
                bounds[3] + 1f,
            )
        }
    }

    private fun pixelsOf(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels
    }

    /**
     * 可见内容直径占外框的比例（0.2..1）。
     *
     * 「更多」圆盘没有自己的图标可量，只能跟着同圈图标的可见大小走；这里给出量化的依据。
     */
    fun contentDiameterRatio(bitmap: Bitmap): Float {
        if (bitmap.width <= 0 || bitmap.height <= 0) return 1f
        ratioCache[bitmap]?.let { return it }
        val ratio = contentRatioOf(bitmap.width, bitmap.height, pixelsOf(bitmap))
        ratioCache[bitmap] = ratio
        return ratio
    }

    /** [contentDiameterRatio] 的纯计算版本：给一组 ARGB 像素求内容边长占比，便于单测。 */
    fun contentRatioOf(width: Int, height: Int, argb: IntArray): Float {
        require(width > 0 && height > 0) { "bitmap must not be empty" }
        require(argb.size >= width * height) { "pixel buffer is smaller than the bitmap" }
        // 整张全透明：没有内容可量，按满幅处理，别让圆盘凭空缩一圈。
        val bounds = boundsOf(width, height, argb) ?: return 1f
        val content = maxOf(bounds[2] - bounds[0] + 1, bounds[3] - bounds[1] + 1).toFloat()
        return (content / maxOf(width, height)).coerceIn(MIN_RATIO, 1f)
    }

    /** 返回 `[minX, minY, maxX, maxY]`；没有任何可见像素时返回 null。 */
    private fun boundsOf(width: Int, height: Int, argb: IntArray): IntArray? {
        var minX = width
        var maxX = -1
        var minY = height
        var maxY = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (argb[row + x] ushr 24 <= VISIBLE_ALPHA) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        return if (maxX < minX || maxY < minY) null else intArrayOf(minX, minY, maxX, maxY)
    }
}
