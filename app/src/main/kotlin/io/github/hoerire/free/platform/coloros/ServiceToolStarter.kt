package io.github.hoerire.free.platform.coloros

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.hoerire.free.bubble.TranslationRelay

/**
 * 少数工具既不启动 Activity，也不走 `AppPlatformUtils`，而是直接拉起 OEM 的前台服务。
 *
 * 两个坑：
 *
 * 1. **只发 startForegroundService 时进程不会被拉起**。系统会建出 ServiceRecord，
 *    但 `dumpsys activity services` 里是 `app=null`——服务永远收不到 onStartCommand。
 *    这里先 `bindService(BIND_AUTO_CREATE)` 把进程顶起来，再发启动意图。
 * 2. **翻译服务把"同来源的再次启动"当成关闭**（源码里 `auto_exit:exit,this call is
 *    from the caller!`），所以第二次点击会把它关掉。每次先 stopService 清掉旧实例，
 *    间隔一小段时间再启动，保证每次都是全新的一轮。
 */
internal object ServiceToolStarter {
    private const val TAG = "FreeServiceTool"
    private val handler = Handler(Looper.getMainLooper())

    /** 屏幕翻译（`GlobalTranslationTool`）。 */
    private const val TRANSLATION_ALIAS = "global_translation"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val TRANSLATION_ACTION = "oplus.intent.action.GLOBAL_TRANSLATION"

    /** 应用自己声明的关闭 action（manifest 里由 ScreenTranslationReceiver 接收）。 */
    private const val TRANSLATION_EXIT_ACTION = "oplus.intent.action.GLOBAL_TRANSLATION_EXIT"

    /** 重启翻译的间隔：等旧实例真正销毁，否则新启动会被它当成同来源关闭。 */
    private const val RESTART_DELAY_MS = 350L

    fun handles(alias: String): Boolean = alias == ScreenIdentifyStarter.ALIAS || alias == TRANSLATION_ALIAS

    fun launch(context: Context, alias: String): Boolean = when (alias) {
        ScreenIdentifyStarter.ALIAS -> identify(context)
        TRANSLATION_ALIAS -> translation(context)
        else -> false
    }

    /** 识屏：沿用模块里已经在用的系统侧启动，但先要把宿主进程拉起来。 */
    private fun identify(context: Context): Boolean {
        val started = ScreenIdentifyStarter.start(context, 0f, 0f)
        Log.i(TAG, "IDENTIFY_START_" + started)
        return started
    }

    /**
     * 屏幕翻译。
     *
     * 目标服务要求调用方持有 `com.oplus.permission.safe.AI_APP`；system_server 没有这个
     * 权限（实测 `am` 报 `Error: Not found`），所以这里不自己启动，只发一条请求广播，
     * 由 SystemUI 进程（模块的气泡子系统所在进程，持有该权限）代发启动。
     */
    private fun translation(context: Context): Boolean = try {
        context.sendBroadcast(
            Intent(TranslationRelay.ACTION_REQUEST)
                .setPackage(SYSTEM_UI_PACKAGE)
                .putExtra(TranslationRelay.EXTRA_REQUESTER, TranslationRelay.REQUESTER_PACKAGE),
        )
        true
    } catch (exception: Throwable) {
        Log.w(TAG, "TRANSLATION_REQUEST_FAILED", exception)
        false
    }

}
