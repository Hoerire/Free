package io.github.hoerire.free.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.hoerire.free.R
import io.github.hoerire.free.apps.SelectableEntry
import io.github.hoerire.free.framework.FrameworkConnectionState

/**
 * 内容拖拽：给名单里的应用补通用拖拽白名单，任意页面都能长按拖起文字/图片。
 *
 * 与「自动贴边」同一套列表骨架，键是普通包名。**名单为空即关闭**——不再默认给所有应用
 * 补配置：条目随 ApplicationInfo 进 binder，部分机型/应用会在 bind 阶段直接崩。
 */
@Composable
internal fun DragUnlockScreen(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    onBack: () -> Unit,
    onAddApps: () -> Unit,
    onDragUnlockPackagesChange: (List<String>) -> Unit,
) {
    PackageListScreen(
        state = state,
        apps = apps,
        onBack = onBack,
        onAddApps = onAddApps,
        packages = state.settings.dragUnlockPackages,
        onChange = onDragUnlockPackagesChange,
        title = stringResource(R.string.drag_unlock_screen_title),
        subtitle = stringResource(R.string.drag_unlock_subtitle),
        addAction = stringResource(R.string.drag_unlock_add_action),
        addSummary = stringResource(R.string.drag_unlock_add_summary),
        emptyTitle = stringResource(R.string.drag_unlock_empty_title),
        emptySummary = stringResource(R.string.drag_unlock_empty_summary),
    )
}

/** 内容拖拽的应用选择页。 */
@Composable
internal fun DragUnlockPickerScreen(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    appsLoading: Boolean,
    onBack: () -> Unit,
    onDragUnlockPackagesChange: (List<String>) -> Unit,
) {
    PackagePickerScreen(
        state = state,
        apps = apps,
        appsLoading = appsLoading,
        onBack = onBack,
        packages = state.settings.dragUnlockPackages,
        onChange = onDragUnlockPackagesChange,
        title = stringResource(R.string.drag_unlock_add_action),
        addedLabel = stringResource(R.string.blacklist_added),
    )
}
