package io.github.hoerire.free.config

/**
 * 侧滑扩展的手势模式；存储值属于已发布配置格式，不得改作其他含义。
 *
 * 现在只有两个双选项，两个方向各管一件事：
 * - [ZoomUpMoreDown]（0）：上滑切「小窗 ↔ 全屏」，下滑打开「更多」；
 * - [ZoomDownMoreUp]（3）：上滑打开「更多」，下滑切「小窗 ↔ 全屏」。
 *
 * 历史值 1（下滑切窗）与 2（下滑更多）已删除：它们只有一个方向可用，另一个方向完全失效，
 * 用户侧没有保留价值。这两个值在 [fromStoredValue] 里回落到 0，保证旧配置可用。
 * **不要复用 1 / 2**——那会让旧安装的配置被解读成别的动作。
 */
internal enum class PredictiveBackMode(val storedValue: Int) {
    ZoomUpMoreDown(0),
    ZoomDownMoreUp(3),
    ;

    companion object {
        fun fromStoredValue(value: Int): PredictiveBackMode =
            entries.firstOrNull { it.storedValue == value } ?: ZoomUpMoreDown
    }
}
