package io.github.hoerire.free.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.hoerire.free.R
import io.github.hoerire.free.apps.SelectableEntry
import io.github.hoerire.free.bubble.CloneApp
import io.github.hoerire.free.config.PinnedTarget
import io.github.hoerire.free.framework.FrameworkConnectionState
import io.github.hoerire.free.ui.component.BasicComponent
import io.github.hoerire.free.ui.component.SettingsGroup
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 滚动通知的过滤列表：表内应用的通知不在状态栏滚动。
 *
 * 与「隐藏应用」共用同一套列表节奏（一个入口行 + 一张名单），但键是气泡白名单那一套
 * （主应用 `包名`、分身 `包名#userId`），所以主应用与分身各占一行、各筛各的：
 * 排除主应用不会连带把同包名的分身也挡掉。
 *
 * 过滤发生在 [io.github.hoerire.free.bubble.StatusTickerController] 的入队判定里，
 * 这一页只负责维护名单。
 */
@Composable
internal fun StatusScrollFilterScreen(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    clones: List<CloneApp>,
    onRefreshClones: () -> Unit,
    onBack: () -> Unit,
    onAddApps: () -> Unit,
    onStatusScrollExcludedChange: (List<String>) -> Unit,
) {
    val excluded = state.settings.statusScrollExcluded
    val enabled = state.settingsEditable
    val appByPackage = remember(apps) { apps.filter { it.target is PinnedTarget.App }.associateBy { it.packageName } }
    val cloneByKey = remember(clones) { clones.associateBy(CloneApp::key) }
    val cloneIndexByKey = remember(clones) { cloneIndexes(clones) }
    // 分身是异步扫出来的，进页就问一次，否则名单里的分身行拿不到名称与图标。
    LaunchedEffect(Unit) { onRefreshClones() }
    SettingsPage(title = stringResource(R.string.status_scroll_filter_screen_title), onBack = onBack) {
        item(key = "add") {
            SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = stringResource(R.string.status_scroll_filter_add_action),
                    summary = stringResource(R.string.status_scroll_filter_add_summary),
                    enabled = enabled,
                    onClick = onAddApps,
                )
            }
        }
        item(key = "list") {
            SettingsGroup(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                if (excluded.isEmpty()) {
                    BasicComponent(
                        title = stringResource(R.string.status_scroll_filter_empty_title),
                        summary = stringResource(R.string.status_scroll_filter_empty_summary),
                        enabled = false,
                    )
                } else {
                    excluded.forEach { key ->
                        val icon =
                            cloneByKey[key]?.icon ?: appByPackage[key.substringBefore('#')]?.icon
                        BasicComponent(
                            title = rowTitle(key, appByPackage, cloneByKey, cloneIndexByKey),
                            summary = key,
                            startAction = { FilterRowIcon(icon) },
                            endActions = {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(32.dp)
                                            .clickable(enabled = enabled) {
                                                onStatusScrollExcludedChange(excluded - key)
                                            },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Remove,
                                        contentDescription =
                                            stringResource(R.string.blacklist_remove_action),
                                        modifier = Modifier.size(20.dp),
                                        tint = COUITheme.colorScheme.labelSecondary,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 过滤列表的选择页：主应用与分身列在同一张表里，分身在后面单独一段。 */
@Composable
internal fun StatusScrollFilterPickerScreen(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    clones: List<CloneApp>,
    appsLoading: Boolean,
    onRefreshClones: () -> Unit,
    onBack: () -> Unit,
    onStatusScrollExcludedChange: (List<String>) -> Unit,
) {
    val excluded = state.settings.statusScrollExcluded
    val enabled = state.settingsEditable
    var query by remember { mutableStateOf("") }
    val appEntries = remember(apps) { apps.filter { it.target is PinnedTarget.App } }
    val mainCandidates =
        remember(appEntries, query) {
            if (query.isBlank()) {
                appEntries
            } else {
                appEntries.filter {
                    it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }
            }
        }
    val cloneCandidates =
        remember(clones, query) {
            clones
                .filter {
                    query.isBlank() ||
                        it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }
                .sortedWith(compareBy({ it.label }, { it.userId }))
        }
    val cloneIndexByKey = remember(clones) { cloneIndexes(clones) }
    LaunchedEffect(Unit) { onRefreshClones() }
    val toggle: (String) -> Unit = { key ->
        onStatusScrollExcludedChange(if (key in excluded) excluded - key else excluded + key)
    }
    SettingsPage(
        title = stringResource(R.string.status_scroll_filter_add_action),
        onBack = onBack,
    ) {
        item(key = "search") {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                label = stringResource(R.string.search_apps_only_placeholder),
                singleLine = true,
            )
        }
        item(key = "list") {
            SettingsGroup(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                when {
                    appsLoading ->
                        // 首次扫描要给数百个应用重建图标，先给一行占位，避免列表看起来没反应。
                        BasicComponent(
                            title = stringResource(R.string.available_apps_loading),
                            enabled = false,
                        )
                    mainCandidates.isEmpty() && cloneCandidates.isEmpty() ->
                        BasicComponent(
                            title = stringResource(R.string.no_matching_apps),
                            enabled = false,
                        )
                    else ->
                        mainCandidates.forEach { entry ->
                            BasicComponent(
                                title = entry.label,
                                summary = entry.packageName,
                                startAction = { FilterRowIcon(entry.icon) },
                                endActions = if (entry.packageName in excluded) {
                                    {
                                        Text(
                                            text = stringResource(R.string.blacklist_added),
                                            style = COUITheme.textStyles.bodyS,
                                            color = COUITheme.colorScheme.primary,
                                        )
                                    }
                                } else {
                                    null
                                },
                                onClick = {
                                    if (enabled) toggle(entry.packageName)
                                },
                            )
                        }
                }
            }
        }
        if (cloneCandidates.isNotEmpty() && !appsLoading) {
            item(key = "clone_intro") {
                Text(
                    text = stringResource(R.string.bubble_clones_section),
                    modifier =
                        Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                            .semantics { heading() },
                    color = COUITheme.colorScheme.labelSecondary,
                    style = COUITheme.textStyles.bodyS,
                )
            }
            item(key = "clones") {
                SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                    cloneCandidates.forEach { clone ->
                        BasicComponent(
                            title =
                                stringResource(
                                    R.string.bubble_clone_label,
                                    clone.label,
                                    cloneIndexByKey[clone.key] ?: 1,
                                ),
                            summary = clone.key,
                            startAction = { FilterRowIcon(clone.icon) },
                            endActions = if (clone.key in excluded) {
                                {
                                    Text(
                                        text = stringResource(R.string.blacklist_added),
                                        style = COUITheme.textStyles.bodyS,
                                        color = COUITheme.colorScheme.primary,
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = {
                                if (enabled) toggle(clone.key)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 名单里一行的标题：分身带序号，主应用用应用名，都对不上就退回键本身。 */
@Composable
private fun rowTitle(
    key: String,
    appByPackage: Map<String, SelectableEntry>,
    cloneByKey: Map<String, CloneApp>,
    cloneIndexByKey: Map<String, Int>,
): String {
    cloneByKey[key]?.let { clone ->
        return stringResource(R.string.bubble_clone_label, clone.label, cloneIndexByKey[key] ?: 1)
    }
    return appByPackage[key.substringBefore('#')]?.label ?: key
}

/** 分身序号按包名分组、按 userId 递增重排，与气泡页的编号保持一致。 */
private fun cloneIndexes(clones: List<CloneApp>): Map<String, Int> =
    clones
        .groupBy(CloneApp::packageName)
        .flatMap { (_, group) ->
            group.sortedBy(CloneApp::userId).mapIndexed { index, clone -> clone.key to (index + 1) }
        }
        .toMap()

@Composable
private fun FilterRowIcon(icon: Bitmap?) {
    if (icon == null) return
    Image(
        bitmap = icon.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.padding(end = 6.dp).size(32.dp),
    )
}
