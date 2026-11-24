package io.github.hoerire.free.ui

import android.content.ActivityNotFoundException
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ElectricalServices
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SwipeRight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import io.github.hoerire.free.BuildConfig
import io.github.hoerire.free.R
import io.github.hoerire.free.config.OutsideTapCloseMode
import io.github.hoerire.free.framework.FrameworkConnectionIssue
import io.github.hoerire.free.framework.FrameworkConnectionState
import io.github.hoerire.free.framework.FrameworkConnectionStatus
import io.github.hoerire.free.ui.component.ArrowPreference
import io.github.hoerire.free.ui.component.BasicComponent
import io.github.hoerire.free.ui.component.SettingsGroup
import io.github.hoerire.free.ui.component.SliderRow
import io.github.hoerire.free.ui.component.SwitchPreference
import io.github.hoerire.free.update.UpdateState
import io.github.hoerire.free.ui.component.HomeScaffold
import io.github.hoerire.free.ui.component.PreferenceIcon
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlin.math.roundToInt

@Composable
internal fun ControlScreen(
    state: FrameworkConnectionState,
    update: UpdateState,
    onDismissUpdate: (String) -> Unit,
    onModuleEnabledChange: (Boolean) -> Unit,
    onCornerSidesChange: (Boolean, Boolean) -> Unit,
    onCornerTriggerRangeChange: (Int) -> Unit,
    onRadialHitRangeChange: (Int) -> Unit,
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
    onHandleSwipeUpToMiniEnabledChange: (Boolean) -> Unit,
    onPauseInLandscapeChange: (Boolean) -> Unit,
    onPauseInGameModeChange: (Boolean) -> Unit,
    onRequestScopes: () -> Unit,
    onNavigateToPinnedApps: () -> Unit,
    onNavigateToRadialSettings: () -> Unit,
    onNavigateToBlacklist: () -> Unit,
    onNavigateToZoomOptions: () -> Unit,
    onNavigateToFreeDrag: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToBubble: () -> Unit,
) {
    val featuresEnabled = state.settingsEditable && state.settings.enabled

    Box(modifier = Modifier.fillMaxSize()) {
        HomeScaffold(
            title = stringResource(R.string.app_name),
            overlay = {
                UpdateOverlay(
                    update = update,
                    onDismissUpdate = onDismissUpdate,
                )
            },
        ) {
                    item(key = "runtime") {
                        RuntimeCard(
                            state = state,
                            onModuleEnabledChange = onModuleEnabledChange,
                            onRequestScopes = onRequestScopes,
                        )
                    }
                    item(key = "settings") {
                        EntryCard(
                            featuresEnabled = featuresEnabled,
                            onNavigateToRadialSettings = onNavigateToRadialSettings,
                            onNavigateToZoomOptions = onNavigateToZoomOptions,
                            onNavigateToBlacklist = onNavigateToBlacklist,
                            onNavigateToFreeDrag = onNavigateToFreeDrag,
                            onNavigateToBubble = onNavigateToBubble,
                        )
                    }
            item(key = "about") {
                AboutCard(onNavigateToAbout = onNavigateToAbout)
            }
        }
    }
}

/** 三个配置分组的入口；具体开关在各自的二级页面内。 */
@Composable
private fun EntryCard(
    featuresEnabled: Boolean,
    onNavigateToRadialSettings: () -> Unit,
    onNavigateToZoomOptions: () -> Unit,
    onNavigateToBlacklist: () -> Unit,
    onNavigateToFreeDrag: () -> Unit,
    onNavigateToBubble: () -> Unit,
) {
    SettingsGroup(modifier = Modifier.fillMaxWidth()) {
        ArrowPreference(
            title = stringResource(R.string.radial_settings_title),
            summary = stringResource(R.string.radial_settings_summary),
            onClick = onNavigateToRadialSettings,
            enabled = featuresEnabled,
            startAction = { PreferenceIcon(Icons.Rounded.SwipeRight, featuresEnabled) },
        )
        ArrowPreference(
            title = stringResource(R.string.zoom_limit_removed_title),
            summary = stringResource(R.string.zoom_options_summary),
            onClick = onNavigateToZoomOptions,
            enabled = featuresEnabled,
            startAction = { PreferenceIcon(Icons.Rounded.LockOpen, featuresEnabled) },
        )
        ArrowPreference(
            title = stringResource(R.string.blacklist_title),
            summary = stringResource(R.string.blacklist_summary),
            onClick = onNavigateToBlacklist,
            enabled = featuresEnabled,
            startAction = { PreferenceIcon(Icons.Rounded.Block, featuresEnabled) },
        )
        // 气泡与自由传送原本各是独立卡片，并进这张卡后只留一行入口，
        // 具体开关与滑杆都在各自的二级页里。
        ArrowPreference(
            title = stringResource(R.string.bubble_feature_title),
            summary = stringResource(R.string.bubble_feature_summary),
            onClick = onNavigateToBubble,
            enabled = featuresEnabled,
            startAction = { PreferenceIcon(Icons.Rounded.Notifications, featuresEnabled) },
        )
        ArrowPreference(
            title = stringResource(R.string.free_drag_title),
            summary = stringResource(R.string.free_drag_summary),
            onClick = onNavigateToFreeDrag,
            enabled = featuresEnabled,
            startAction = {
                PreferenceIcon(ImageVector.vectorResource(R.drawable.ic_free_share), featuresEnabled)
            },
        )
    }
}

/** 模块总开关与框架连接状态：配置能否写入取决于这里的连接结果。 */
@Composable
private fun RuntimeCard(
    state: FrameworkConnectionState,
    onModuleEnabledChange: (Boolean) -> Unit,
    onRequestScopes: () -> Unit,
) {
    val presentation = frameworkPresentation(state)
    // 写入配置期间不切换文案：isUpdating 只用于禁用交互。
    // 换个更短的文案会让该行行数变化，整页高度随之跳动。
    val moduleSummary =
        when {
            !state.canChangeSettings && state.status != FrameworkConnectionStatus.Connected ->
                stringResource(R.string.module_enabled_summary_waiting)
            state.missingScopes.isNotEmpty() -> stringResource(R.string.module_enabled_summary_scope)
            state.settings.enabled -> stringResource(R.string.module_enabled_summary_on)
            else -> stringResource(R.string.module_enabled_summary_off)
        }
    val showScopeAction =
        state.status == FrameworkConnectionStatus.Connected && state.missingScopes.isNotEmpty()
    SettingsGroup(modifier = Modifier.fillMaxWidth()) {
        SwitchPreference(
            checked = state.settings.enabled,
            onCheckedChange = onModuleEnabledChange,
            title = stringResource(R.string.module_enabled_title),
            summary = moduleSummary,
            enabled = state.settingsEditable,
            startAction = {
                PreferenceIcon(Icons.Rounded.PowerSettingsNew, state.settingsEditable)
            },
        )
        BasicComponent(
            title = stringResource(R.string.framework_service_title),
            summary = presentation.summary,
            onClick = null,
            endActions = {
                Text(
                    text = presentation.label,
                    color = COUITheme.colorScheme.labelSecondary,
                    style = COUITheme.textStyles.bodyS,
                )
            },
            startAction = { PreferenceIcon(Icons.Rounded.ElectricalServices) },
        )
        AnimatedVisibility(visible = showScopeAction) {
            ArrowPreference(
                title = stringResource(R.string.scope_title),
                summary =
                    if (state.isRequestingScope) stringResource(R.string.scope_requesting)
                    else if (state.issue == FrameworkConnectionIssue.ScopeRequestFailed) {
                        stringResource(R.string.scope_request_failed)
                    } else {
                        stringResource(
                            R.string.scope_missing,
                            state.missingScopes.joinToString(" · "),
                        )
                    },
                onClick = onRequestScopes,
                enabled = state.canRequestScope,
                startAction = { PreferenceIcon(Icons.Rounded.AccountTree, state.canRequestScope) },
            )
        }
    }
}

/**
 * 带确认时机的整数滑条行。
 *
 * 名字里的 Dp 是历史原因：参数是「整数 + 区间 + 钳位函数」，单位由调用方决定——
 * 尺寸项用默认的 `dp` 文案，时长项传 [valueUnitRes]（如 `%1$d ms`），数量类传
 * `showDpUnit = false`。拖动只走 [onPreviewChange]，抬手才 [onCommit]。
 */
@Composable
internal fun RemoteDpSliderPreference(
    icon: ImageVector,
    confirmedValue: Int,
    isUpdating: Boolean,
    enabled: Boolean,
    title: String,
    summary: String,
    /** 数值区间与钳位：单位由调用方约定，见函数注释。 */
    minDp: Int,
    maxDp: Int,
    coerceDp: (Int) -> Int,
    onPreviewChange: (Int?) -> Unit,
    onCommit: (Int) -> Unit,
    /** false 时只显示数值，不带 dp 单位（用于「个数」这类非尺寸项）。 */
    showDpUnit: Boolean = true,
    /** 单位文案资源（如 `%1$d ms`）：给定时优先于 [showDpUnit]，用于非 dp 的数值项。 */
    valueUnitRes: Int? = null,
) {
    var draftValue by remember { mutableFloatStateOf(coerceDp(confirmedValue).toFloat()) }
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(confirmedValue, isUpdating, enabled) {
        if (!enabled && pressed) {
            pressed = false
            onPreviewChange(null)
        }
        if (!pressed && !isUpdating) draftValue = coerceDp(confirmedValue).toFloat()
    }
    val commit =
        {
            onPreviewChange(null)
            val committed = coerceDp(draftValue.roundToInt())
            draftValue = committed.toFloat()
            if (committed != confirmedValue) onCommit(committed)
        }
    val latestCommit by rememberUpdatedState(commit)
    // Initial 阶段只观察不消费；按下才允许改值，抬手才提交。
    Box(
        modifier =
            Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) {
                            pressed = true
                        } else if (pressed && event.changes.none { it.pressed }) {
                            // 抬手或被系统手势打断都算结束，避免按下状态卡住。
                            pressed = false
                            latestCommit()
                        }
                    }
                }
            },
    ) {
        SliderRow(
            title = title,
            summary = summary,
            value = draftValue,
            valueRange = minDp.toFloat()..maxDp.toFloat(),
            onValueChange = { value ->
                // 只认真实按下时的回调。滑条在数值同步时也会推送数值，
                // 将那些帧误判为拖动会写入配置，导致数值持续漂移。
                if (pressed) {
                    draftValue = value.coerceIn(minDp.toFloat(), maxDp.toFloat())
                    onPreviewChange(coerceDp(draftValue.roundToInt()))
                }
            },
            onValueChangeFinished = {},
            valueText =
                when {
                    valueUnitRes != null ->
                        stringResource(valueUnitRes, coerceDp(draftValue.roundToInt()))
                    showDpUnit ->
                        stringResource(R.string.dp_value, coerceDp(draftValue.roundToInt()))
                    else -> coerceDp(draftValue.roundToInt()).toString()
                },
            icon = { PreferenceIcon(icon, enabled) },
            enabled = enabled,
        )
    }
}

@Composable
internal fun CornerRangePreview(
    rangeDp: Int,
    leftEnabled: Boolean,
    rightEnabled: Boolean,
) {
    val activeColor = COUITheme.colorScheme.primary
    val inactiveColor = COUITheme.colorScheme.labelSecondary
    Canvas(modifier = Modifier.fillMaxSize()) {
        val radius = rangeDp.dp.toPx()
        val arcSize = Size(radius * 2f, radius * 2f)
        val strokeWidth = 2.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))

        fun drawCorner(startAngle: Float, topLeft: Offset, enabled: Boolean) {
            val color = if (enabled) activeColor else inactiveColor
            drawArc(
                color = color.copy(alpha = if (enabled) 0.22f else 0.08f),
                startAngle = startAngle,
                sweepAngle = 90f,
                useCenter = true,
                topLeft = topLeft,
                size = arcSize,
            )
            drawArc(
                color = color.copy(alpha = if (enabled) 0.82f else 0.30f),
                startAngle = startAngle,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, pathEffect = dash),
            )
        }

        drawCorner(
            startAngle = 270f,
            topLeft = Offset(-radius, size.height - radius),
            enabled = leftEnabled,
        )
        drawCorner(
            startAngle = 180f,
            topLeft = Offset(size.width - radius, size.height - radius),
            enabled = rightEnabled,
        )
    }
}

@Composable
private fun AboutCard(onNavigateToAbout: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    SettingsGroup(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = stringResource(R.string.about_feedback_title),
            summary = stringResource(R.string.about_feedback_summary),
            onClick = {
                try {
                    uriHandler.openUri(FEEDBACK_GROUP_URL)
                } catch (_: IllegalArgumentException) {
                } catch (_: ActivityNotFoundException) {
                }
            },
            startAction = { PreferenceIcon(Icons.Rounded.Forum) },
        )
        ArrowPreference(
            title = stringResource(R.string.about_app_title),
            // 只显示版本名：后面的 versionCode 是日期型整数，放在这里没有意义。
            summary = stringResource(R.string.about_version_format, BuildConfig.VERSION_NAME),
            onClick = onNavigateToAbout,
            startAction = { PreferenceIcon(Icons.Rounded.Info) },
        )
    }
}


@Composable
private fun frameworkPresentation(state: FrameworkConnectionState): FrameworkPresentation =
    when (state.status) {
        FrameworkConnectionStatus.Waiting -> FrameworkPresentation(stringResource(R.string.framework_status_waiting), stringResource(R.string.framework_waiting_summary))
        FrameworkConnectionStatus.Connected -> {
            val unknown = stringResource(R.string.framework_unknown_value)
            FrameworkPresentation(
                stringResource(R.string.framework_status_connected),
                stringResource(
                    R.string.framework_connected_summary,
                    state.frameworkName ?: unknown,
                    state.frameworkVersion ?: unknown,
                    state.apiVersion?.toString() ?: unknown,
                ),
            )
        }
        FrameworkConnectionStatus.Incompatible -> FrameworkPresentation(stringResource(R.string.framework_status_incompatible), stringResource(state.issue.incompatibleSummary()))
        FrameworkConnectionStatus.Error -> FrameworkPresentation(stringResource(R.string.framework_status_error), stringResource(state.issue.errorSummary()))
    }

@StringRes
private fun FrameworkConnectionIssue?.incompatibleSummary(): Int =
    when (this) {
        FrameworkConnectionIssue.ServiceApiTooOld -> R.string.framework_api_too_old_summary
        FrameworkConnectionIssue.RemoteCapabilityMissing -> R.string.framework_remote_missing_summary
        FrameworkConnectionIssue.SystemCapabilityMissing -> R.string.framework_system_missing_summary
        FrameworkConnectionIssue.MultipleServices -> R.string.framework_multiple_services_summary
        else -> R.string.framework_connection_failed_summary
    }

@StringRes
private fun FrameworkConnectionIssue?.errorSummary(): Int =
    when (this) {
        FrameworkConnectionIssue.WriteFailed -> R.string.framework_write_failed_summary
        else -> R.string.framework_connection_failed_summary
    }

private data class FrameworkPresentation(val label: String, val summary: String)

internal const val OWN_REPO_URL = "https://github.com/Hoerire/Free"
internal const val UPSTREAM_REPO_URL = "https://github.com/Mangi-11/Flyna"

/** 交流反馈：QQ 群邀请链接，点击后交给系统/QQ 打开。 */
internal const val FEEDBACK_GROUP_URL = "https://qm.qq.com/q/OrRJTQDcsw"

internal val ScreenHorizontalMargin = 12.dp
internal val ScreenContentMaxWidth = 600.dp
