package io.github.hoerire.free.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.UserHandle
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import io.github.hoerire.free.platform.coloros.SidebarEntryProtocol

/**
 * 分身的角标。
 *
 * 优先让系统画：`PackageManager.getUserBadgedIcon(空图, 分身用户)` 出来的就是原生那枚
 * 用户角标，位置与比例由系统决定。取不到（没有跨用户权限、或该 ROM 不画角标）时，
 * 自己画一个小圆标，保证「哪个是分身」在界面里仍然可分辨。
 */
internal object CloneBadges {
    fun of(context: Context, userId: Int): Bitmap? {
        if (userId <= 0) return null
        val size = SidebarEntryProtocol.ICON_SIZE
        val blank = createBitmap(size, size, Bitmap.Config.ARGB_8888).toDrawable(context.resources)
        val user = UserHandle.getUserHandleForUid(userId * PER_USER_RANGE)
        val badged = runCatching { context.packageManager.getUserBadgedIcon(blank, user) }.getOrNull()
        if (badged != null && badged !== blank) {
            val bitmap = badged.toIconBitmap(size)
            if (bitmap.hasVisiblePixels()) return bitmap
        }
        return drawn(size)
    }

    /** 兜底：右下角一个实心圆 + 白色「分」字。 */
    private fun drawn(size: Int): Bitmap {
        val bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = size * 0.33f
        val cx = size - radius
        val cy = size - radius
        canvas.drawCircle(
            cx,
            cy,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2F6BFF.toInt() },
        )
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = radius * 1.15f
            textAlign = Paint.Align.CENTER
        }
        val metrics = text.fontMetrics
        canvas.drawText("分", cx, cy - (metrics.ascent + metrics.descent) / 2f, text)
        return bitmap
    }

    private fun Drawable.toIconBitmap(size: Int): Bitmap =
        createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            setBounds(0, 0, it.width, it.height)
            draw(Canvas(it))
        }

    private fun Bitmap.hasVisiblePixels(): Boolean {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.any { it ushr 24 != 0 }
    }

    private const val PER_USER_RANGE = 100_000
}
