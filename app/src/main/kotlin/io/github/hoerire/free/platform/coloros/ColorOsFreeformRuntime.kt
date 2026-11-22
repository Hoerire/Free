package io.github.hoerire.free.platform.coloros

/**
 * system_server 里当前生效的浮动窗口协调器。
 *
 * 协调器在 FlexibleTaskController 就绪后创建；启动链路上的一些判定（例如气泡点开新小窗
 * 前先把旧窗口退成迷你窗）发生在更早的 ActivityStarter 钩子里，需要通过这里取到实例。
 */
internal object ColorOsFreeformRuntime {
    @Volatile
    var coordinator: ColorOsFreeformCoordinator? = null
}
