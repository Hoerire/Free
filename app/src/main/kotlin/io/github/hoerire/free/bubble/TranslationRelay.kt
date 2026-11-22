package io.github.hoerire.free.bubble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 屏幕翻译的系统侧代启。
 *
 * `com.coloros.translate` 的 `ScreenTranslationService` 要求调用方持有
 * `com.oplus.permission.safe.AI_APP` 权限——实测 system_server（uid 1000）调用会被拒
 * （`am` 直接报 `Error: Not found`），而 SystemUI / 桌面 / 设置持有该权限。
 *
 * 模块本来就在 SystemUI 里有气泡子系统，所以把"启动翻译"这一件事转给 SystemUI 代发：
 * 两个进程之间只有一条单向广播，不传数据、不需要回执。
 */
internal class TranslationRelay(
    private val context: Context,
    private val logger: (code: String, throwable: Throwable?) -> Unit,
) {
    private val TAG = "FreeTranslationRelay"

    fun start() {
        runCatching {
            context.registerReceiver(
                receiver,
                IntentFilter(ACTION_REQUEST),
                Context.RECEIVER_EXPORTED,
            )
        }.onSuccess { Log.w(TAG, "TRANSLATION_RELAY_REGISTERED", null) }
            .onFailure { Log.w(TAG, "TRANSLATION_RELAY_REGISTER_FAILED", it) }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != ACTION_REQUEST) return
            if (intent.getStringExtra(EXTRA_REQUESTER) != REQUESTER_PACKAGE) return
            launch(receiverContext ?: context)
        }
    }

    private fun launch(context: Context) {
        val service = runCatching {
            context.packageManager
                .queryIntentServices(Intent(ACTION_TRANSLATE), PackageManager.ResolveInfoFlags.of(0))
                .firstOrNull()
                ?.serviceInfo
        }.getOrNull()
        if (service == null) {
            Log.w(TAG, "TRANSLATION_RELAY_NO_SERVICE", null)
            return
        }
        // 服务是 START_STICKY 且进程里带"已初始化"静态标志：先让它自己退出，再启动，
        // 否则第二次点击不会重新显示。
        runCatching {
            context.sendBroadcast(Intent(ACTION_EXIT).setPackage(service.packageName))
        }
        Log.w(TAG, "TRANSLATION_RELAY_LAUNCH_" + service.packageName, null)
        Handler(Looper.getMainLooper()).postDelayed({
            val started = runCatching {
                context.startForegroundService(
                    Intent(ACTION_TRANSLATE).setClassName(service.packageName, service.name),
                )
            }.onFailure { Log.w(TAG, "TRANSLATION_RELAY_START_FAILED", it) }.isSuccess
            Log.w(TAG, "TRANSLATION_RELAY_START_" + started, null)
        }, RESTART_DELAY_MS)
    }

    internal companion object {
        const val ACTION_REQUEST = "io.github.hoerire.free.action.START_SCREEN_TRANSLATION"
        const val EXTRA_REQUESTER = "requester"
        const val REQUESTER_PACKAGE = "io.github.hoerire.free"

        private const val ACTION_TRANSLATE = "oplus.intent.action.GLOBAL_TRANSLATION"
        private const val ACTION_EXIT = "oplus.intent.action.GLOBAL_TRANSLATION_EXIT"
        private const val RESTART_DELAY_MS = 350L
    }
}
