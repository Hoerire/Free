package io.github.hoerire.free.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.hoerire.free.R
import io.github.hoerire.free.framework.FrameworkConnectionState
import io.github.hoerire.free.ui.component.ArrowPreference
import io.github.hoerire.free.ui.component.SectionTitle
import io.github.hoerire.free.ui.component.SettingsGroup
import io.github.hoerire.free.ui.component.SwitchPreference
import io.github.hoerire.free.ui.component.PreferenceIcon

/**
 * 小窗交互。
 *
 * 排版遵循系统设置页的既有节奏：**分组标题在卡片外侧**，卡片内部只放原生偏好项。
 * 不往分组里塞自定义标题行——那会破坏卡片的内边距与分隔线节奏。
 *
 * 信息结构：
 * - 「轻量打开」是启动场景的二级入口，summary 显示已开启的场景数量；
 * - 其余窗口行为项各自独立，互不依赖；
 * - 「手势与关闭」独立成组。
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

    }
}
