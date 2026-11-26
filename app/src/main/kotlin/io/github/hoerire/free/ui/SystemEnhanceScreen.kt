package io.github.hoerire.free.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ElectricalServices
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.SwipeDown
import androidx.compose.material.icons.rounded.SwipeRight
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.hoerire.free.R
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.config.PredictiveBackMode
import io.github.hoerire.free.framework.FrameworkConnectionState
import io.github.hoerire.free.ui.component.ArrowPreference
import io.github.hoerire.free.ui.component.ListChoicePreference
import io.github.hoerire.free.ui.component.PreferenceIcon
import io.github.hoerire.free.ui.component.SettingsGroup
import io.github.hoerire.free.ui.component.SwitchPreference

/**
 * 系统增强。
 *
 * 总览页只放「一功能一行」的入口，**参数一律进二级页**：这一层以后还要不断加功能，
 * 把开关和滑条都平铺在这里，页面会很快长到没人愿意翻。
 * 只有单一开关、没有参数的项（横条沉浸、自由拖拽）留在总览页，不为它们单开一页。
 *
 * 二级页走与顶层路由同一套滑动转场：graphicsLayer 平移，不重排页面、不产生输入阻断层。
 */
private enum class SystemEnhancePage {
    Overview,
    StatusScroll,
    PredictiveBack,
    FloatHandle,
}

@Composable
internal fun SystemEnhanceScreen(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onExternalHandleEnabledChange: (Boolean) -> Unit,
    onDragUnlockEnabledChange: (Boolean) -> Unit,
    onHideZoomInRecentsChange: (Boolean) -> Unit,
    onStatusScrollEnabledChange: (Boolean) -> Unit,
    onStatusScrollRoundsChange: (Int) -> Unit,
    onStatusScrollSpeedChange: (Int) -> Unit,
    onPredictiveBackEnabledChange: (Boolean) -> Unit,
    onPredictiveBackModeChange: (PredictiveBackMode) -> Unit,
    onPredictiveBackSwipeDistanceDpChange: (Int) -> Unit,
    onFloatKeepAliveChange: (Boolean) -> Unit,
    onFloatHandleMaxCountChange: (Int) -> Unit,
    onZoomMaxWindowCountChange: (Int) -> Unit,
) {
    val pageStack = remember { mutableStateListOf(SystemEnhancePage.Overview) }
    val transition = remember { RouteTransitionState(SystemEnhancePage.Overview) }
    val page = pageStack.last()
    val navigateTo: (SystemEnhancePage) -> Unit = { target ->
        if (pageStack.last() != target) {
            pageStack.add(target)
            transition.switchTo(target, forward = true)
        }
    }
    val backToOverview: () -> Unit = {
        if (pageStack.size > 1) {
            pageStack.removeAt(pageStack.lastIndex)
            transition.switchTo(SystemEnhancePage.Overview, forward = false)
        }
    }
    val goBack: () -> Unit = { if (page == SystemEnhancePage.Overview) onBack() else backToOverview() }
    val enabled = state.settingsEditable && state.settings.enabled

    // 二级页在顶层路由之内：系统返回键必须先回总览，否则会直接退出到主页。
    // 顶层 FreeNavHost 的 BackHandler 只在栈深 > 1 时接手，这里要抢在它前面。
    BackHandler(enabled = page != SystemEnhancePage.Overview) { backToOverview() }

    PageStackHost(
        stack = pageStack,
        state = transition,
        modifier = Modifier.fillMaxWidth(),
        stateKey = { route -> route.name },
    ) { current ->
        when (current) {
            SystemEnhancePage.Overview ->
                SettingsPage(
                    title = stringResource(R.string.system_enhance_title),
                    onBack = goBack,
                ) {
                    item(key = "basic") {
                        SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                            SwitchPreference(
                                checked = state.settings.externalHandleEnabled,
                                onCheckedChange = onExternalHandleEnabledChange,
                                title = stringResource(R.string.external_handle_title),
                                summary = stringResource(R.string.external_handle_summary),
                                enabled = enabled,
                                startAction = { PreferenceIcon(Icons.Rounded.UnfoldMore, enabled) },
                            )
                            // 解除限制：给内容传送门补通用拖拽白名单；与其它模块的拖拽增强
                            // 撞车时单独关掉即可，不影响上面几项。
                            SwitchPreference(
                                checked = state.settings.dragUnlockEnabled,
                                onCheckedChange = onDragUnlockEnabledChange,
                                title = stringResource(R.string.drag_unlock_title),
                                summary = stringResource(R.string.drag_unlock_summary),
                                enabled = enabled,
                                startAction = { PreferenceIcon(Icons.Rounded.LockOpen, enabled) },
                            )
                            // 小窗数量：单一可调项，没有从属开关，直接留在总览页。
                            // 不受「启用交互」总闸约束，只跟模块总开关走。
                            RemoteDpSliderPreference(
                                icon = Icons.Rounded.Layers,
                                confirmedValue = state.settings.zoomMaxWindowCount,
                                isUpdating = state.isUpdating,
                                enabled = enabled,
                                title = stringResource(R.string.zoom_max_windows_title),
                                summary = stringResource(R.string.zoom_max_windows_summary),
                                minDp = ModulePreferences.MIN_ZOOM_MAX_WINDOW_COUNT,
                                maxDp = ModulePreferences.MAX_ZOOM_MAX_WINDOW_COUNT,
                                coerceDp = ModulePreferences::coerceZoomMaxWindowCount,
                                onPreviewChange = {},
                                onCommit = onZoomMaxWindowCountChange,
                                showDpUnit = false,
                            )
                            // 隐藏卡片：小窗应用不出现在最近任务；同属「系统增强」，不受总闸约束。
                            SwitchPreference(
                                checked = state.settings.hideZoomInRecents,
                                onCheckedChange = onHideZoomInRecentsChange,
                                title = stringResource(R.string.hide_zoom_in_recents_title),
                                summary = stringResource(R.string.hide_zoom_in_recents_summary),
                                enabled = enabled,
                                startAction = {
                                    PreferenceIcon(Icons.Rounded.VisibilityOff, enabled)
                                },
                            )
                        }
                    }
                    item(key = "entries") {
                        SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                            ArrowPreference(
                                title = stringResource(R.string.status_scroll_title),
                                summary = statusScrollSummary(state),
                                onClick = { navigateTo(SystemEnhancePage.StatusScroll) },
                                enabled = enabled,
                                startAction = { PreferenceIcon(Icons.Rounded.Notifications, enabled) },
                            )
                            ArrowPreference(
                                title = stringResource(R.string.predictive_back_title),
                                summary = predictiveBackSummary(state),
                                onClick = { navigateTo(SystemEnhancePage.PredictiveBack) },
                                enabled = enabled,
                                startAction = { PreferenceIcon(Icons.Rounded.Gesture, enabled) },
                            )
                            ArrowPreference(
                                title = stringResource(R.string.float_group_title),
                                summary = floatHandleSummary(state),
                                onClick = { navigateTo(SystemEnhancePage.FloatHandle) },
                                enabled = enabled,
                                startAction = { PreferenceIcon(Icons.Rounded.PushPin, enabled) },
                            )
                        }
                    }
                }
            SystemEnhancePage.StatusScroll ->
                SettingsPage(title = stringResource(R.string.status_scroll_title), onBack = goBack) {
                    item(key = "statusScroll") {
                        SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                            SwitchPreference(
                                checked = state.settings.statusScrollEnabled,
                                onCheckedChange = onStatusScrollEnabledChange,
                                title = stringResource(R.string.bubble_status_scroll_enabled_title),
                                summary = stringResource(R.string.bubble_status_scroll_enabled_summary),
                                enabled = enabled,
                                startAction = { PreferenceIcon(Icons.Rounded.Notifications, enabled) },
                            )
                            RemoteDpSliderPreference(
                                icon = Icons.Rounded.Layers,
                                confirmedValue = state.settings.statusScrollRounds,
                                isUpdating = state.isUpdating,
                                enabled = enabled && state.settings.statusScrollEnabled,
                                title = stringResource(R.string.bubble_status_scroll_rounds_title),
                                summary = stringResource(R.string.bubble_status_scroll_rounds_summary),
                                minDp = ModulePreferences.MIN_STATUS_SCROLL_ROUNDS,
                                maxDp = ModulePreferences.MAX_STATUS_SCROLL_ROUNDS,
                                coerceDp = ModulePreferences::coerceStatusScrollRounds,
                                onPreviewChange = {},
                                onCommit = onStatusScrollRoundsChange,
                                showDpUnit = false,
                            )
                            RemoteDpSliderPreference(
                                icon = Icons.Rounded.Speed,
                                confirmedValue = state.settings.statusScrollSpeed,
                                isUpdating = state.isUpdating,
                                enabled = enabled && state.settings.statusScrollEnabled,
                                title = stringResource(R.string.bubble_status_scroll_speed_title),
                                summary = stringResource(
                                    R.string.bubble_status_scroll_speed_summary,
                                    state.settings.statusScrollSpeed,
                                ),
                                minDp = ModulePreferences.MIN_STATUS_SCROLL_SPEED,
                                maxDp = ModulePreferences.MAX_STATUS_SCROLL_SPEED,
                                coerceDp = ModulePreferences::coerceStatusScrollSpeed,
                                onPreviewChange = {},
                                onCommit = onStatusScrollSpeedChange,
                                showDpUnit = false,
                            )
                        }
                    }
                }
            SystemEnhancePage.PredictiveBack ->
                SettingsPage(title = stringResource(R.string.predictive_back_title), onBack = goBack) {
                    item(key = "predictiveBack") {
                        SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                            SwitchPreference(
                                checked = state.settings.predictiveBackEnabled,
                                onCheckedChange = onPredictiveBackEnabledChange,
                                title = stringResource(R.string.predictive_back_enable_title),
                                summary = stringResource(R.string.predictive_back_summary),
                                enabled = enabled,
                                startAction = { PreferenceIcon(Icons.Rounded.Gesture, enabled) },
                            )
                            // 手势模式：只有两个双选项，两个方向各管一件事。
                            ListChoicePreference(
                                title = stringResource(R.string.predictive_back_mode_title),
                                icon = { PreferenceIcon(Icons.Rounded.SwapVert, enabled) },
                                selected = state.settings.predictiveBackMode,
                                values = PredictiveBackMode.entries,
                                valueText = {
                                    when (it) {
                                        PredictiveBackMode.ZoomUpMoreDown ->
                                            stringResource(R.string.predictive_back_mode_both)
                                        PredictiveBackMode.ZoomDownMoreUp ->
                                            stringResource(R.string.predictive_back_mode_more_up)
                                    }
                                },
                                onSelected = onPredictiveBackModeChange,
                                enabled = enabled && state.settings.predictiveBackEnabled,
                                description = stringResource(R.string.predictive_back_mode_summary),
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
                        }
                    }
                }
            SystemEnhancePage.FloatHandle ->
                SettingsPage(title = stringResource(R.string.float_group_title), onBack = goBack) {
                    item(key = "floatHandle") {
                        SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                            SwitchPreference(
                                checked = state.settings.floatKeepAlive,
                                onCheckedChange = onFloatKeepAliveChange,
                                title = stringResource(R.string.float_keep_alive_title),
                                summary = stringResource(R.string.float_keep_alive_summary),
                                enabled = enabled,
                                startAction = {
                                    PreferenceIcon(Icons.Rounded.ElectricalServices, enabled)
                                },
                            )
                            RemoteDpSliderPreference(
                                icon = Icons.Rounded.SwipeRight,
                                confirmedValue = state.settings.floatHandleMaxCount,
                                isUpdating = state.isUpdating,
                                enabled = enabled,
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
    }
}

/** 入口行只报「现在是什么状态」，不复述二级页的说明文字。 */
@Composable
private fun statusScrollSummary(state: FrameworkConnectionState): String =
    if (state.settings.statusScrollEnabled) {
        stringResource(
            R.string.status_scroll_entry_on,
            state.settings.statusScrollRounds,
            state.settings.statusScrollSpeed,
        )
    } else {
        stringResource(R.string.entry_off)
    }

@Composable
private fun predictiveBackSummary(state: FrameworkConnectionState): String =
    if (state.settings.predictiveBackEnabled) {
        stringResource(
            R.string.predictive_back_entry_on,
            ModulePreferences.coercePredictiveBackSwipeDistanceDp(
                state.settings.predictiveBackSwipeDistanceDp,
            ),
        )
    } else {
        stringResource(R.string.entry_off)
    }

@Composable
private fun floatHandleSummary(state: FrameworkConnectionState): String =
    if (state.settings.floatKeepAlive) {
        stringResource(
            R.string.float_entry_on,
            ModulePreferences.coerceFloatHandleMaxCount(state.settings.floatHandleMaxCount),
        )
    } else {
        stringResource(R.string.entry_off)
    }
