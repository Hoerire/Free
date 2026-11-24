package io.github.hoerire.free.settings

import io.github.libxposed.api.XposedModule

/**
 * 模块实例的登记处。
 *
 * 少数几处代码跑在作用域进程里、却不在钩子安装链上（例如侧边栏进程内的
 * `SidebarLocalTools`），需要 Module 实例才能挂 hook；`onModuleLoaded` 时把实例登记到这里，
 * 它们再按需取用。取不到（应用进程）就跳过，不影响主流程。
 *
 * 这里**不做设置读写**：设置一律走 `ProcessConfiguration` 的远程偏好快照，
 * 免得同一个进程里出现两份配置。
 */
internal object ModuleRuntime {
    @Volatile
    var module: XposedModule? = null
}
