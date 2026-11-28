package io.github.hoerire.free.config

/**
 * 横屏时小窗落在屏幕哪一侧。
 *
 * 系统的摆放按「哪边还有空位」算，同一个应用两次打开可能一左一右；这里把它固定下来。
 * 只在横屏生效——竖屏的小窗位置由系统按比例摆，没有左右之分。
 */
internal enum class LandscapeZoomSide(val storedValue: Int) {
    /** 跟随系统：不干预。 */
    FollowSystem(0),

    /** 固定靠左。 */
    Left(1),

    /** 固定靠右。 */
    Right(2),
    ;

    companion object {
        fun fromStoredValue(value: Int): LandscapeZoomSide =
            entries.firstOrNull { it.storedValue == value } ?: FollowSystem
    }
}
