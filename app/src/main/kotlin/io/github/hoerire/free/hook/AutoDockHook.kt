package io.github.hoerire.free.hook

import android.os.Handler
import android.os.Looper
import io.github.hoerire.free.log.ModuleLog
import io.github.hoerire.free.logic.AutoDockPolicy
import io.github.hoerire.free.platform.coloros.ColorOsAutoDock

/**
 * 「自动贴边」：名单里的应用**全屏**时**上滑返回桌面**，自动收成贴边浮标继续运行。
 *
 * 手势导航下没有 Home 键，上滑回桌面在系统里最终落成一次「桌面成为前台」。
 * 所以入口就是前台观察钩子（`ActivityTaskManagerService#setLastResumedActivityUncheckLocked`）：
 * 只有**新前台是桌面**、且上一个前台是名单里的应用时才接管——切到别的应用、锁屏、
 * 应用互跳（登录/授权/分享）的终点不是桌面，一律不触发。
 *
 * 更早的入口（`startHomeActivity` / `ActivityStarter` 里的 HOME intent）试过：那一刻
 * 焦点任务表已经是 `?|f0v0z0`，找不到要收的任务，日志 `AUTO_DOCK_HOME_NO_FOCUS_*`。
 * 该入口已删除，不要再加回来。
 */
internal class AutoDockHook(private val configuration: ProcessConfiguration) {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var executor: ColorOsAutoDock? = null

    /** 控制器在 `systemReady` 之后才有实例，由 SystemServerHookInstaller 回填。 */
    fun attach(controller: Any) {
        if (executor != null) return
        executor = ColorOsAutoDock(controller) { code, error -> logDeferred(code, error) }
    }

    /** 前台切换：新前台是桌面、上一个前台在名单里时才收（主线程下一轮动手）。 */
    fun onForeground(packageName: String?, previousPackage: String?) {
        val snapshot = configuration.snapshot
        if (!snapshot.enabled || snapshot.autoDockPackages.isEmpty()) return
        // 只有回到桌面才接管：切应用、锁屏、跳转第三方的终点都不是桌面。
        if (!AutoDockPolicy.isHome(packageName)) return
        if (!AutoDockPolicy.shouldDock(snapshot.enabled, snapshot.autoDockPackages, previousPackage)) return
        val target = previousPackage ?: return
        handler.post {
            val ready = executor
            if (ready == null) {
                if (notReady.compareAndSet(false, true)) ModuleLog.warn("AUTO_DOCK_NOT_READY", null)
                return@post
            }
            runCatching { ready.dockOnReturnHome(target) }
                .onFailure { ModuleLog.warn("AUTO_DOCK_THROWN", it as? RuntimeException) }
        }
    }

    private fun logDeferred(code: String, error: Throwable? = null) {
        handler.post { ModuleLog.warn(code, error) }
    }

    private companion object {
        /** 「还没就绪」只报一次，避免开机阶段刷屏。 */
        val notReady = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
