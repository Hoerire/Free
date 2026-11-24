package io.github.hoerire.free.bubble

import android.content.Context
import android.content.res.Configuration
import android.os.Build

/**
 * 通知内容卡（[NotificationPreviewView]）的背景色。
 *
 * 原实现是一整份 Material 3 调色板，卡片只用到了 `surfaceContainerHigh` 一档；
 * 这里只保留这一档，色值与取色顺序保持原样：优先系统 Monet 动态色，
 * 取不到（低版本或取色失败）时回退到青绿色静态色。
 */
internal object MaterialPalette {
    fun surfaceContainerHigh(context: Context): Int {
        val dark =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val monet =
                systemColor(
                    context,
                    if (dark) android.R.color.system_neutral2_700
                    else android.R.color.system_neutral2_100,
                )
            if (monet != 0) return monet
        }
        return if (dark) DARK_SURFACE_CONTAINER_HIGH else LIGHT_SURFACE_CONTAINER_HIGH
    }

    private fun systemColor(context: Context, id: Int): Int =
        runCatching { context.getColor(id) }.getOrDefault(0)

    /** 浅色：system_neutral2_100 的静态替身。 */
    private const val LIGHT_SURFACE_CONTAINER_HIGH = 0xFFE1E7E6.toInt()

    /** 深色：system_neutral2_700 的静态替身。 */
    private const val DARK_SURFACE_CONTAINER_HIGH = 0xFF202625.toInt()
}
