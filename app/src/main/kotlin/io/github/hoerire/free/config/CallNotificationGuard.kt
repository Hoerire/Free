package io.github.hoerire.free.config

import android.app.Notification
import android.content.Intent

/**
 * 通话类通知与启动的特判。
 *
 * ColorOS 对第三方 VoIP（微信等）有系统自己的接听浮标与通话页。模块不接管通话相关的一切，
 * 只保证一件事：**通话界面不进小窗**。这里给出统一判据——通话通知不参与气泡、状态栏滚动、
 * 横幅抑制、分身深链捕获与通知启动改写；通话界面（voip / incall 类 Activity）不参与小窗改写。
 *
 * 注意：这里**没有任何启动拦截**。微信的主动拨号与来电自动弹出在 ActivityStarter 上是同形的，
 * 拦自动弹出必然连主动拨号一起拦掉，因此来电一律放行、只约束通话界面的窗口形态。
 */
internal object CallNotificationGuard {
    /** 是否为通话邀请通知：category=call，或走 voip 通道。 */
    fun isCallNotification(notification: Notification?): Boolean {
        if (notification == null) return false
        if (notification.category == Notification.CATEGORY_CALL) return true
        val channel = notification.channelId ?: return false
        return channel.contains("voip", ignoreCase = true)
    }

    /** 启动目标是否为通话界面（voip / incall 类 Activity）。 */
    fun isCallActivity(intent: Intent?): Boolean {
        val className = intent?.component?.className ?: return false
        return className.contains("voip", ignoreCase = true) ||
            className.contains("incall", ignoreCase = true)
    }
}
