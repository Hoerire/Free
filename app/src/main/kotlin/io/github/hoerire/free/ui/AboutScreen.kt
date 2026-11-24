package io.github.hoerire.free.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import io.github.hoerire.free.BuildConfig
import io.github.hoerire.free.logic.LauncherIcon
import io.github.hoerire.free.R
import io.github.hoerire.free.update.UpdateState
import io.github.hoerire.free.ui.component.BasicComponent
import io.github.hoerire.free.ui.component.SettingsGroup
import io.github.hoerire.free.ui.component.SwitchPreference
import io.github.hoerire.free.ui.component.BackOnlyScaffold
import io.github.hoerire.free.ui.component.PreferenceIcon
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.overlay.OverlayDialog
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 关于页。
 *
 * 版本号、更新检测、诊断开关与两个仓库地址集中在同一张卡片内；
 * 有新版本时，更新动作插在检测结果之后。
 */
@Composable
internal fun AboutScreen(
    update: UpdateState,
    verboseLogEnabled: Boolean,
    onBack: () -> Unit,
    onVerboseLogChange: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onDismissUpdate: (String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val available = update as? UpdateState.Available
    var showReward by remember { mutableStateOf(false) }
    BackOnlyScaffold(
        onBack = onBack,
        overlay = {
            UpdateOverlay(
                update = update,
                onDismissUpdate = onDismissUpdate,
            )
            RewardOverlay(show = showReward, onDismiss = { showReward = false })
        },
    ) {
                item(key = "about") {
                    SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                        BasicComponent(
                            title = stringResource(R.string.about_check_update_title),
                            summary = updateSummary(update),
                            onClick = onCheckUpdate,
                            startAction = { PreferenceIcon(Icons.Rounded.Refresh) },
                        )
                        if (available != null) {
                            BasicComponent(
                                title = stringResource(R.string.update_available_title, available.version),
                                summary =
                                    stringResource(
                                        R.string.update_available_summary,
                                        BuildConfig.VERSION_NAME,
                                    ),
                                startAction = { PreferenceIcon(Icons.Rounded.SystemUpdate) },
                            )
                            BasicComponent(
                                title = stringResource(R.string.update_open_action),
                                summary = stringResource(R.string.update_open_summary),
                                onClick = {
                                    try {
                                        uriHandler.openUri(available.pageUrl)
                                    } catch (_: IllegalArgumentException) {
                                    } catch (_: ActivityNotFoundException) {
                                    }
                                },
                            )
                            BasicComponent(
                                title = stringResource(R.string.update_dismiss_action),
                                summary = stringResource(R.string.update_dismiss_summary),
                                onClick = { onDismissUpdate(available.version) },
                            )
                        }
                        BasicComponent(
                            title = stringResource(R.string.about_own_repo_title),
                            summary = stringResource(R.string.about_own_repo_summary),
                            onClick = {
                                try {
                                    uriHandler.openUri(OWN_REPO_URL)
                                } catch (_: IllegalArgumentException) {
                                } catch (_: ActivityNotFoundException) {
                                }
                            },
                            startAction = {
                                PreferenceIcon(ImageVector.vectorResource(R.drawable.ic_github))
                            },
                        )
                        BasicComponent(
                            title = stringResource(R.string.about_upstream_title),
                            summary = stringResource(R.string.about_upstream_summary),
                            onClick = {
                                try {
                                    uriHandler.openUri(UPSTREAM_REPO_URL)
                                } catch (_: IllegalArgumentException) {
                                } catch (_: ActivityNotFoundException) {
                                }
                            },
                            startAction = {
                                PreferenceIcon(ImageVector.vectorResource(R.drawable.ic_github))
                            },
                        )
                        BasicComponent(
                            title = stringResource(R.string.about_support_title),
                            summary = stringResource(R.string.about_support_summary),
                            onClick = { showReward = true },
                            startAction = { PreferenceIcon(Icons.Rounded.Favorite) },
                        )
                        SwitchPreference(
                            checked = verboseLogEnabled,
                            onCheckedChange = onVerboseLogChange,
                            title = stringResource(R.string.verbose_log_title),
                            summary = stringResource(R.string.verbose_log_summary),
                            startAction = {
                                PreferenceIcon(
                                    ImageVector.vectorResource(R.drawable.ic_log),
                                )
                            },
                        )
                        val context = LocalContext.current
                        var launcherHidden by remember { mutableStateOf(LauncherIcon.isHidden(context)) }
                        SwitchPreference(
                            checked = launcherHidden,
                            onCheckedChange = { hidden ->
                                launcherHidden = hidden
                                LauncherIcon.setHidden(context, hidden)
                            },
                            title = stringResource(R.string.hide_launcher_icon_title),
                            summary = stringResource(R.string.hide_launcher_icon_summary),
                            startAction = {
                                PreferenceIcon(Icons.Rounded.VisibilityOff)
                            },
                        )
                    }
        }
    }
}

/** 检查更新这一行的小字：随检测状态变化。 */
@Composable
private fun updateSummary(update: UpdateState): String =
    when (update) {
        UpdateState.Idle -> stringResource(R.string.about_check_update_summary_idle)
        UpdateState.Checking -> stringResource(R.string.about_check_update_summary_checking)
        UpdateState.UpToDate -> stringResource(R.string.about_check_update_summary_latest)
        UpdateState.Failed -> stringResource(R.string.about_check_update_summary_failed)
        is UpdateState.Available ->
            stringResource(R.string.about_check_update_summary_available, update.version)
    }

/**
 * 「支持作者」弹窗：显示微信赞赏码。
 *
 * 二维码用 `drawable-nodpi` 存一份 960px 的图，按原像素贴、不做平滑缩放
 * （`FilterQuality.None`）——插值会把码点边缘抹成灰，扫不出来。
 * 弹窗本身跟着 `show` 常驻组合，关掉时只切可见性，保留 COUI 的关闭动画。
 */
@Composable
private fun RewardOverlay(
    show: Boolean,
    onDismiss: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = stringResource(R.string.about_support_dialog_title),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                bitmap = ImageBitmap.imageResource(R.drawable.ic_reward_qr),
                contentDescription = stringResource(R.string.about_support_dialog_title),
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.about_support_dialog_hint),
                style = COUITheme.textStyles.bodyS,
                color = COUITheme.colorScheme.labelSecondary,
            )
        }
        DialogButtonBar(
            negative = null,
            positive =
                DialogButtonBarAction(
                    text = stringResource(R.string.about_support_close),
                    onClick = onDismiss,
                ),
        )
    }
}
