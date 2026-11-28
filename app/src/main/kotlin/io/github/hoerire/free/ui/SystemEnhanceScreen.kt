package io.github.hoerire.free.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ElectricalServices
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Notifications
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
 * 只有单一开关、没有参数的项（横条沉浸、隐藏卡片）留在总览页，不为它们单开一页；
 * 需要选择生效应用的项（内容拖拽、自动贴边）一律进二级页。
 *
 * 二级页走与顶层路由同一套滑动转场：graphicsLayer 平移，不重排页面、不产生输入阻断层。
 */
private enum class SystemEnhancePage {
    Overview,
    StatusScroll,
    PredictiveBack,
}

@Composable
internal fun SystemEnhanceScreen(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onExternalHandleEnabledChange: (Boolean) -> Unit,
    onNavigateToDragUnlock: () -> Unit,
    onStatusScrollEnabledChange: (Boolean) -> Unit,
    onStatusScrollRoundsChange: (Int) -> Unit,
    onStatusScrollSpeedChange: (Int) -> Unit,
    onPredictiveBackEnabledChange: (Boolean) -> Unit,
    onPredictiveBackModeChange: (PredictiveBackMode) -> Unit,
    onPredictiveBackSwipeDistanceDpChange: (Int) -> Unit,
    onNavigateToPanelHidden: () -> Unit,
    onNavigateToStatusScrollFilter: () -> Unit,
    onNavigateToBubble: () -> Unit,
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

                            // 内容拖拽：按应用开启，默认关闭。全局补配置会让部分机型/应用
                            // 在 bind 阶段崩溃，所以参数（生效应用名单）放进二级页。
                            ArrowPreference(
                                title = stringResource(R.string.drag_unlock_title),
                                summary = dragUnlockSummary(state),
                                onClick = onNavigateToDragUnlock,
                                enabled = enabled,
                                startAction = { PreferenceIcon(Icons.Rounded.LockOpen, enabled) },
                            )

                        }
                    }
                    item(key = "entries") {
                        SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                            // 气泡通知整组入口从首页搬到这里，排在滚动通知之上：
                            // 两者是同一类「通知怎么显示」的能力，放一起才找得到。
                            ArrowPreference(
                                title = stringResource(R.string.bubble_feature_title),
                                summary = stringResource(R.string.bubble_feature_summary),
                                onClick = onNavigateToBubble,
                                enabled = enabled,
                                startAction = { PreferenceIcon(Icons.Rounded.Notifications, enabled) },
                            )
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
                            // 隐藏应用：有列表要维护，按本页约定单开二级页。
                            ArrowPreference(
                                title = stringResource(R.string.panel_hidden_title),
                                summary = panelHiddenSummary(state),
                                onClick = onNavigateToPanelHidden,
                                enabled = enabled,
                                startAction = {
                                    PreferenceIcon(Icons.Rounded.VisibilityOff, enabled)
                                },
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
                            // 过滤列表：有名单要维护，按本页约定单开二级页（与「隐藏应用」同一节奏）。
                            ArrowPreference(
                                title = stringResource(R.string.status_scroll_filter_title),
                                summary = statusScrollFilterSummary(state),
                                onClick = onNavigateToStatusScrollFilter,
                                enabled = enabled,
                                startAction = { PreferenceIcon(Icons.Rounded.Block, enabled) },
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
                            // 滑动距离只在侧滑扩展开启时才有意义，关闭时置灰。
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

        }
    }
}

/** 内容拖拽入口：报条数，没配就是「关」。 */
@Composable
private fun dragUnlockSummary(state: FrameworkConnectionState): String {
    val count = state.settings.dragUnlockPackages.size
    return if (count == 0) {
        stringResource(R.string.drag_unlock_summary)
    } else {
        stringResource(R.string.drag_unlock_summary_active, count)
    }
}


@Composable
private fun panelHiddenSummary(state: FrameworkConnectionState): String {
    val count = state.settings.panelHiddenPackages.size
    return if (count == 0) stringResource(R.string.entry_off) else "$count 个应用"
}

/** 过滤列表入口：报条数，没配就把作用一句话说清。 */
@Composable
private fun statusScrollFilterSummary(state: FrameworkConnectionState): String {
    val count = state.settings.statusScrollExcluded.size
    return if (count == 0) {
        stringResource(R.string.status_scroll_filter_summary)
    } else {
        stringResource(R.string.status_scroll_filter_entry_on, count)
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
