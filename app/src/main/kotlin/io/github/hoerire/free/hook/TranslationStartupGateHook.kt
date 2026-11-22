package io.github.hoerire.free.hook

import android.content.Intent
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 放行「屏幕翻译」的服务启动。
 *
 * ColorOS 在 AMS 里挂了一层自启动管理（`com.android.server.am.OplusAppStartupManager`）。
 * 启动 `com.coloros.translate` 的 `ScreenTranslationService` 时，不管调用方是
 * system_server、SystemUI 还是别的系统应用，都会被判成自启动并拦下：
 *
 * ```
 * OplusAppStartupManager: prevent start com.coloros.translate,
 *     cmp {...ScreenTranslationService} by com.android.systemui callingUid 10240
 * ```
 *
 * 原生侧边栏之所以能用，是因为它在厂商的白名单里（同一个类里维护）。
 *
 * 这里只对「屏幕翻译」这一个 action 放行，其它启动请求原样交给系统判断，
 * 不影响任何应用的自启动策略。
 */
internal class TranslationStartupGateHook(
    private val module: XposedModule,
    private val logger: (Int, String, Throwable?) -> Unit,
) {
    fun install(classLoader: ClassLoader) {
        val manager = runCatching { classLoader.loadClass(MANAGER_CLASS) }.getOrNull() ?: run {
            logger(Log.WARN, "TRANSLATION_GATE_CLASS_MISSING", null)
            return
        }
        var installed = 0
        for (method in manager.declaredMethods) {
            val verdict = when (method.name) {
                "isAllowStartFromService" -> true
                "shouldPreventStartService" -> false
                else -> continue
            }
            val intentIndex = method.parameterTypes.indexOfFirst { it == Intent::class.java }
            if (intentIndex < 0) continue
            method.isAccessible = true
            runCatching {
                module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("free.translation.gate." + method.name)
                    .intercept { chain ->
                        val intent = chain.getArg(intentIndex) as? Intent
                        if (intent?.action == TRANSLATION_ACTION) {
                            verbose("TRANSLATION_GATE_ALLOW_" + method.name)
                            verdict
                        } else {
                            chain.proceed()
                        }
                    }
                installed++
            }.onFailure { logger(Log.WARN, "TRANSLATION_GATE_HOOK_FAILED_" + method.name, it) }
        }
        if (installed > 0) {
            verbose("TRANSLATION_GATE_INSTALLED_" + installed)
        } else {
            logger(Log.WARN, "TRANSLATION_GATE_NONE", null)
        }
    }

    /** 放行本身属于成功路径：只在「详细日志」打开时输出，避免每次翻译都刷 WARN。 */
    private fun verbose(code: String) {
        if (io.github.hoerire.free.log.VerboseLog.enabled) logger(Log.DEBUG, code, null)
    }

    private companion object {
        const val MANAGER_CLASS = "com.android.server.am.OplusAppStartupManager"
        const val TRANSLATION_ACTION = "oplus.intent.action.GLOBAL_TRANSLATION"
    }
}
