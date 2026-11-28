package io.github.hoerire.free.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ElectricalServices
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.SwipeRight
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.hoerire.free.R
import io.github.hoerire.free.config.LandscapeZoomSide
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.framework.FrameworkConnectionState
import io.github.hoerire.free.ui.component.ArrowPreference
import io.github.hoerire.free.ui.component.ListChoicePreference
import io.github.hoerire.free.ui.component.PreferenceIcon
import io.github.hoerire.free.ui.component.SectionTitle
import io.github.hoerire.free.ui.component.SettingsGroup
import io.github.hoerire.free.ui.component.SwitchPreference

/**
 * 小窗交互。
 *
 * 排版遵循系统设置页的既有节奏：**分组标题在卡片外侧**，卡片内部只放原生偏好项。
 *
 * **本页所有功能都受「启用交互」总闸控制**（总闸那一行本身除外）：灰掉不是摆设，
 * 对应的 effective 判定也都在快照里与 zoomInteractionActive 相与。
 */

/** 已开启的启动场景数量，用于二级入口的 summary。 */
private fun enabledSceneCount(state: FrameworkConnectionState): Int {
    val settings = state.settings
    return listOf(
        settings.zoomLaunchNotice,
        settings.zoomLaunchHeadsUp,
        settings.zoomLaunchControlCenter,
        settings.zoomLaunchInterApp,
        settings.zoomLaunchClipboard,
    ).count { it }
}

@Composable
internal fun ZoomOptionsScreen(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onZoomInteractionEnabledChange: (Boolean) -> Unit,
    onZoomAllowAnyAppChange: (Boolean) -> Unit,
    onAutoCloseWindowChange: (Boolean) -> Unit,
    onZoomMaxWindowCountChange: (Int) -> Unit,
    onZoomLandscapeSideChange: (LandscapeZoomSide) -> Unit,
    onHideZoomInRecentsChange: (Boolean) -> Unit,
    onFloatKeepAliveChange: (Boolean) -> Unit,
    onFloatHandleMaxCountChange: (Int) -> Unit,
    onNavigateToAutoDock: () -> Unit,
    onNavigateToZoomExcluded: () -> Unit,
    onNavigateToZoomLaunchScenes: () -> Unit,
    onNavigateToGesture: () -> Unit,
) {
    val enabled = state.settingsEditable && state.settings.enabled
    // 关掉「启用交互」时整页其余项一起灰掉：参数都还在，重新打开就能继续用。
    val sub = enabled && state.settings.zoomInteractionEnabled
    SettingsPage(
        title = stringResource(R.string.zoom_limit_removed_title),
        onBack = onBack,
    ) {
        item(key = "window_title") {
            SectionTitle(text = stringResource(R.string.zoom_group_window_title))
        }
        item(key = "window") {
            SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                // 总闸放在最上面：与其它同类模块冲突时，关掉这一项即可整条让出小窗链路。
                SwitchPreference(
                    checked = state.settings.zoomInteractionEnabled,
                    onCheckedChange = onZoomInteractionEnabledChange,
                    title = stringResource(R.string.zoom_interaction_master_title),
                    summary = stringResource(R.string.zoom_interaction_master_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.LockOpen, enabled) },
                )
                ArrowPreference(
                    title = stringResource(R.string.zoom_interaction_title),
                    summary =
                        stringResource(
                            if (enabledSceneCount(state) > 0) {
                                R.string.zoom_launch_entry_on
                            } else {
                                R.string.zoom_launch_entry_off
                            },
                            enabledSceneCount(state),
                        ),
                    onClick = onNavigateToZoomLaunchScenes,
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.OpenInNew, sub) },
                )
                ArrowPreference(
                    title = stringResource(R.string.zoom_excluded_title),
                    summary = stringResource(R.string.zoom_excluded_summary),
                    onClick = onNavigateToZoomExcluded,
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.Block, sub) },
                )
                SwitchPreference(
                    checked = state.settings.zoomAllowAnyApp,
                    onCheckedChange = onZoomAllowAnyAppChange,
                    title = stringResource(R.string.zoom_allow_any_app_title),
                    summary = stringResource(R.string.zoom_allow_any_app_summary),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.LockOpen, sub) },
                )
                SwitchPreference(
                    checked = state.settings.autoCloseWindow,
                    onCheckedChange = onAutoCloseWindowChange,
                    title = stringResource(R.string.auto_close_window_title),
                    summary = stringResource(R.string.auto_close_window_summary),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.DeleteSweep, sub) },
                )
                // 隐藏卡片：紧跟「自动收窗」，两者都管窗口去留。
                SwitchPreference(
                    checked = state.settings.hideZoomInRecents,
                    onCheckedChange = onHideZoomInRecentsChange,
                    title = stringResource(R.string.hide_zoom_in_recents_title),
                    summary = stringResource(R.string.hide_zoom_in_recents_summary),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.VisibilityOff, sub) },
                )
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.Layers,
                    confirmedValue = state.settings.zoomMaxWindowCount,
                    isUpdating = state.isUpdating,
                    enabled = sub,
                    title = stringResource(R.string.zoom_max_windows_title),
                    summary = stringResource(R.string.zoom_max_windows_summary),
                    minDp = ModulePreferences.MIN_ZOOM_MAX_WINDOW_COUNT,
                    maxDp = ModulePreferences.MAX_ZOOM_MAX_WINDOW_COUNT,
                    coerceDp = ModulePreferences::coerceZoomMaxWindowCount,
                    onPreviewChange = {},
                    onCommit = onZoomMaxWindowCountChange,
                    showDpUnit = false,
                )
                ListChoicePreference(
                    title = stringResource(R.string.zoom_landscape_side_title),
                    icon = { PreferenceIcon(Icons.Rounded.SwapVert, sub) },
                    selected = state.settings.zoomLandscapeSide,
                    values = LandscapeZoomSide.entries,
                    valueText = { side ->
                        when (side) {
                            LandscapeZoomSide.FollowSystem ->
                                stringResource(R.string.zoom_landscape_side_follow)
                            LandscapeZoomSide.Left ->
                                stringResource(R.string.zoom_landscape_side_left)
                            LandscapeZoomSide.Right ->
                                stringResource(R.string.zoom_landscape_side_right)
                        }
                    },
                    onSelected = onZoomLandscapeSideChange,
                    enabled = sub,
                    description = stringResource(R.string.zoom_landscape_side_summary),
                )

                // 手势交互收成一行入口，具体开关在二级页里；summary 只列条目名。
                ArrowPreference(
                    title = stringResource(R.string.zoom_group_gesture_title),
                    summary = stringResource(R.string.zoom_gesture_entry_summary),
                    onClick = onNavigateToGesture,
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.SwipeUp, sub) },
                )
            }
        }
        // 贴边浮标从「系统增强」搬过来：它同样是小窗的形态之一。
        item(key = "float_title") {
            SectionTitle(text = stringResource(R.string.float_group_title))
        }
        item(key = "float") {
            SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = state.settings.floatKeepAlive,
                    onCheckedChange = onFloatKeepAliveChange,
                    title = stringResource(R.string.float_keep_alive_title),
                    summary = stringResource(R.string.float_keep_alive_summary),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.ElectricalServices, sub) },
                )
                // 自动贴边：名单非空即开启，进二级页维护名单。
                ArrowPreference(
                    title = stringResource(R.string.auto_dock_title),
                    summary = autoDockSummary(state),
                    onClick = onNavigateToAutoDock,
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.SwipeRight, sub) },
                )
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.SwipeRight,
                    confirmedValue = state.settings.floatHandleMaxCount,
                    isUpdating = state.isUpdating,
                    enabled = sub,
                    title = stringResource(R.string.float_handle_max_title),
                    summary = stringResource(R.string.float_handle_max_summary),
                    minDp = ModulePreferences.MIN_FLOAT_HANDLE_MAX_COUNT,
                    maxDp = ModulePreferences.MAX_FLOAT_HANDLE_MAX_COUNT,
                    coerceDp = ModulePreferences::coerceFloatHandleMaxCount,
                    onPreviewChange = {},
                    onCommit = onFloatHandleMaxCountChange,
                    showDpUnit = false,
                )
            }
        }
    }
}

/** 自动贴边入口：报条数，没配就是「关」。 */
@Composable
private fun autoDockSummary(state: FrameworkConnectionState): String {
    val count = state.settings.autoDockPackages.size
    return if (count == 0) stringResource(R.string.entry_off) else "$count 个应用"
}
