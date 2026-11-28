package io.github.hoerire.free.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.hoerire.free.R
import io.github.hoerire.free.apps.SelectableEntry
import io.github.hoerire.free.framework.FrameworkConnectionState

/**
 * 自动贴边：名单里的应用被「返回桌面」盖住时，自动收成贴边浮标。
 *
 * 与「隐藏应用」同一套列表骨架，键是普通包名（这里不区分分身：分身回桌面同样收）。
 * 没有独立开关——名单为空就是关闭。
 */
@Composable
internal fun AutoDockScreen(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    onBack: () -> Unit,
    onAddApps: () -> Unit,
    onAutoDockPackagesChange: (List<String>) -> Unit,
) {
    PackageListScreen(
        state = state,
        apps = apps,
        onBack = onBack,
        onAddApps = onAddApps,
        packages = state.settings.autoDockPackages,
        onChange = onAutoDockPackagesChange,
        title = stringResource(R.string.auto_dock_screen_title),
        subtitle = stringResource(R.string.auto_dock_subtitle),
        addAction = stringResource(R.string.auto_dock_add_action),
        addSummary = stringResource(R.string.auto_dock_add_summary),
        emptyTitle = stringResource(R.string.auto_dock_empty_title),
        emptySummary = stringResource(R.string.auto_dock_empty_summary),
    )
}

/** 自动贴边的应用选择页。 */
@Composable
internal fun AutoDockPickerScreen(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    appsLoading: Boolean,
    onBack: () -> Unit,
    onAutoDockPackagesChange: (List<String>) -> Unit,
) {
    PackagePickerScreen(
        state = state,
        apps = apps,
        appsLoading = appsLoading,
        onBack = onBack,
        packages = state.settings.autoDockPackages,
        onChange = onAutoDockPackagesChange,
        title = stringResource(R.string.auto_dock_add_action),
        addedLabel = stringResource(R.string.blacklist_added),
    )
}
