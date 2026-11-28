package io.github.hoerire.free.logic

/**
 * 自动贴边：选中的应用**上滑返回桌面**时自动收成贴边浮标。
 *
 * 判定只看一次前台切换的终点：**新前台必须是桌面**。切到别的应用、锁屏、应用互跳
 * （登录/授权/分享）都不算——那些场景把窗口收成浮标会打断流程，用户只会觉得应用坏了。
 */
internal object AutoDockPolicy {

    /**
     * 桌面包名。
     *
     * 本机是 `com.android.launcher/.Launcher`；其余几个是 ColorOS 历来的别名，
     * 命中不了也不影响——判定为「不是桌面」时只是不动作。
     */
    val HOME_PACKAGES = setOf("com.android.launcher", "com.android.launcher3", "com.oplus.launcher")

    fun isHome(packageName: String?): Boolean = packageName != null && packageName in HOME_PACKAGES

    /**
     * 回到桌面那一刻：新前台是桌面、上一个前台是名单里的全屏应用，才收。
     * 已经在小窗/迷你/贴边的任务由调用方按任务状态排除。
     */
    fun shouldDock(
        moduleEnabled: Boolean,
        packages: Collection<String>,
        previousPackage: String?,
    ): Boolean =
        moduleEnabled && packages.isNotEmpty() && previousPackage != null && packages.contains(previousPackage)
}
