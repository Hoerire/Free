package io.github.hoerire.free.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.SwipeRight
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
 * 手势交互二级页：快速上滑、长按静音、关闭方式。
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
    onEdgeDragFullScreenChange: (Boolean) -> Unit,
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
) {
    // 小窗交互总闸关掉时整页灰掉：这些项都只在小窗链路上生效。
    val enabled = state.settingsEditable && state.settings.enabled && state.settings.zoomInteractionEnabled
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
                // 边缘拖动：小窗左右边向外拉一点、松手变全屏。属于「拖动」类手势。
                SwitchPreference(
                    checked = state.settings.edgeDragFullScreen,
                    onCheckedChange = onEdgeDragFullScreenChange,
                    title = stringResource(R.string.edge_drag_title),
                    summary = stringResource(R.string.edge_drag_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.SwipeRight, enabled) },
                )
                SwitchPreference(
                    checked = state.settings.longPressMuteEnabled,
                    onCheckedChange = onLongPressMuteChange,
                    title = stringResource(R.string.mini_long_press_title),
                    summary = stringResource(R.string.mini_long_press_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.PictureInPicture, enabled) },
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
