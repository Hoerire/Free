package io.github.hoerire.free.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.PictureInPicture
import io.github.hoerire.free.config.ModulePreferences
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.hoerire.free.R
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
    onPredictiveBackActionChange: (Int) -> Unit,
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
                val predictiveBackActions =
                    listOf(
                        ModulePreferences.PREDICTIVE_BACK_ACTION_DISABLED,
                        ModulePreferences.PREDICTIVE_BACK_ACTION_ZOOM,
                        ModulePreferences.PREDICTIVE_BACK_ACTION_MORE_PANEL,
                    )
                val predictiveBackItems =
                    listOf(
                        stringResource(R.string.predictive_back_action_disabled),
                        stringResource(R.string.predictive_back_action_zoom),
                        stringResource(R.string.predictive_back_action_panel),
                    )
                val predictiveBackSelected =
                    predictiveBackActions
                        .indexOf(state.settings.predictiveBackAction)
                        .coerceIn(0, predictiveBackItems.lastIndex)
                // 说明文字跟着当前选中的动作走：切到「更多」之后还写"在两者之间切换"会误导。
                val predictiveBackDescription =
                    stringResource(
                        when (predictiveBackActions[predictiveBackSelected]) {
                            ModulePreferences.PREDICTIVE_BACK_ACTION_ZOOM ->
                                R.string.predictive_back_desc_zoom
                            ModulePreferences.PREDICTIVE_BACK_ACTION_MORE_PANEL ->
                                R.string.predictive_back_desc_panel
                            else -> R.string.predictive_back_desc_off
                        },
                    )
                ListChoicePreference(
                    title = stringResource(R.string.predictive_back_title),
                    icon = { PreferenceIcon(Icons.Rounded.Gesture, enabled) },
                    selected = predictiveBackSelected,
                    values = predictiveBackItems.indices.toList(),
                    valueText = { predictiveBackItems[it] },
                    onSelected = { index ->
                        predictiveBackActions.getOrNull(index)?.let(onPredictiveBackActionChange)
                    },
                    enabled = enabled,
                    description = predictiveBackDescription,
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
