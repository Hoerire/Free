package io.github.hoerire.free.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.SwipeDown
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.hoerire.free.R
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.config.OutsideTapCloseMode
import io.github.hoerire.free.framework.FrameworkConnectionState
import io.github.hoerire.free.ui.component.ListChoicePreference
import io.github.hoerire.free.ui.component.SettingsGroup
import io.github.hoerire.free.ui.component.SwitchPreference
import io.github.hoerire.free.ui.component.PreferenceIcon

/**
 * 手势交互二级页：快速上滑、长按静音、返回增强、关闭方式。
 *
 * 一级页只保留一行入口；入口行的 summary 只列条目名，不复述本页的说明文字，
 * 依据是重复描述会让同一屏出现两份同样的信息。
 */
@Composable
internal fun GestureScreen(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onHandleSwipeUpToMiniEnabledChange: (Boolean) -> Unit,
    onLongPressMuteChange: (Boolean) -> Unit,
    onPredictiveBackEnabledChange: (Boolean) -> Unit,
    onPredictiveBackSwipeDistanceDpChange: (Int) -> Unit,
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
) {
    val enabled = state.settingsEditable && state.settings.enabled
    val outsideTapModes = OutsideTapCloseMode.entries
    val outsideTapItems =
        listOf(
            stringResource(R.string.outside_tap_mode_disabled),
            stringResource(R.string.outside_tap_mode_single),
            stringResource(R.string.outside_tap_mode_double),
            stringResource(R.string.outside_tap_mode_bottom),
        )
    SettingsPage(
        title = stringResource(R.string.zoom_group_gesture_title),
        onBack = onBack,
    ) {
        item(key = "gestureGroup") {
            SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = state.settings.handleSwipeUpToMiniEnabled,
                    onCheckedChange = onHandleSwipeUpToMiniEnabledChange,
                    title = stringResource(R.string.handle_swipe_up_to_mini_title),
                    summary = stringResource(R.string.handle_swipe_up_to_mini_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.SwipeUp, enabled) },
                )
                SwitchPreference(
                    checked = state.settings.longPressMuteEnabled,
                    onCheckedChange = onLongPressMuteChange,
                    title = stringResource(R.string.mini_long_press_title),
                    summary = stringResource(R.string.mini_long_press_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.PictureInPicture, enabled) },
                )
                // 返回增强：方向即动作——上滑切小窗、下拉打开「更多」，因此只需要一个开关。
                SwitchPreference(
                    checked = state.settings.predictiveBackEnabled,
                    onCheckedChange = onPredictiveBackEnabledChange,
                    title = stringResource(R.string.predictive_back_title),
                    summary = stringResource(R.string.predictive_back_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.Gesture, enabled) },
                )
                // 滑动距离只在返回增强开启时才有意义，关闭时置灰。
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.SwipeDown,
                    confirmedValue = state.settings.predictiveBackSwipeDistanceDp,
                    isUpdating = state.isUpdating,
                    enabled = enabled && state.settings.predictiveBackEnabled,
                    title = stringResource(R.string.predictive_back_swipe_distance_title),
                    summary = stringResource(R.string.predictive_back_swipe_distance_summary),
                    minDp = ModulePreferences.MIN_PREDICTIVE_BACK_SWIPE_DISTANCE_DP,
                    maxDp = ModulePreferences.MAX_PREDICTIVE_BACK_SWIPE_DISTANCE_DP,
                    coerceDp = ModulePreferences::coercePredictiveBackSwipeDistanceDp,
                    // 距离没有屏幕叠加预览，回调只为满足滑条接口。
                    onPreviewChange = {},
                    onCommit = onPredictiveBackSwipeDistanceDpChange,
                )
                ListChoicePreference(
                    title = stringResource(R.string.outside_tap_close_title),
                    icon = { PreferenceIcon(Icons.Rounded.TouchApp, enabled) },
                    selected =
                        outsideTapModes
                            .indexOf(state.settings.outsideTapCloseMode)
                            .coerceIn(0, outsideTapItems.lastIndex),
                    values = outsideTapItems.indices.toList(),
                    valueText = { outsideTapItems[it] },
                    onSelected = { index ->
                        outsideTapModes.getOrNull(index)?.let(onOutsideTapCloseModeChange)
                    },
                    enabled = enabled,
                )
            }
        }
    }
}
