package io.github.hoerire.free.config

/**
 * 侧滑扩展的手势模式；存储值属于已发布配置格式，不得改作其他含义。
 *
 * 三种模式描述的是「内滑之后往哪个方向滑、执行什么」：
 * - [ZoomUpMoreDown]：上滑切「小窗 ↔ 全屏」，下滑打开「更多」；
 * - [ZoomDown]：下滑切「小窗 ↔ 全屏」；
 * - [MoreDown]：下滑打开「更多」。
 *
 * 后两种只有下滑一个方向，另一种动作留给别的手势或扇形菜单。
 */
internal enum class PredictiveBackMode(val storedValue: Int) {
    ZoomUpMoreDown(0),
    ZoomDown(1),
    MoreDown(2),
    ;

    companion object {
        fun fromStoredValue(value: Int): PredictiveBackMode =
            entries.firstOrNull { it.storedValue == value } ?: ZoomUpMoreDown
    }
}
