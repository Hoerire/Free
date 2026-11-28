package io.github.hoerire.free.platform.coloros

/**
 * 「更多」面板打开那一刻的前台应用包名。
 *
 * 侧边栏进程没有 GET_TASKS，查不到当前前台应用；由 system_server 在面板打开时
 * 判定一次并广播过来，面板点击时直接比对——点当前应用才改走「原地变小窗」。
 */
internal object PanelFocusState {
    @Volatile
    var focusedPackage: String? = null

    fun update(packageName: String?) {
        focusedPackage = packageName?.takeIf { it.isNotBlank() }
    }
}
