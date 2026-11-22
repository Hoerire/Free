package io.github.hoerire.free.platform.coloros

import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.hoerire.free.bubble.TranslationRelay

/**
 * 少数工具既不启动 Activity，也不走 `AppPlatformUtils`，而是直接拉起 OEM 的前台服务。
 *
 * 屏幕翻译的目标服务要求调用方持有 `com.oplus.permission.safe.AI_APP`，system_server
 * 没有这个权限，因此这里只发一条请求广播，由 SystemUI 进程代发启动（见 [TranslationRelay]）。
 * 小布识屏沿用模块里已有的系统侧启动。
 */
internal object ServiceToolStarter {
    private const val TAG = "FreeServiceTool"

    /** 屏幕翻译（`GlobalTranslationTool`）。 */
    private const val TRANSLATION_ALIAS = "global_translation"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

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
