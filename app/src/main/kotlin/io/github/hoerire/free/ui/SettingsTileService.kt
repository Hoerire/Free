package io.github.hoerire.free.ui

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import io.github.hoerire.free.MainActivity

/**
 * 快捷设置里的「Free 设置」磁贴。
 *
 * 隐藏桌面图标时禁用的是唯一带 LAUNCHER 的组件，LSPosed 管理器按桌面入口解析模块设置，
 * 因此会一起消失；这个磁贴不依赖桌面组件，是隐藏状态下稳定的设置入口（用户需自己添加一次）。
 */
class SettingsTileService : TileService() {
    override fun onClick() {
        val intent =
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending =
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
