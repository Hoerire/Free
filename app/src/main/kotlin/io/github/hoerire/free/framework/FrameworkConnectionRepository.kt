package io.github.hoerire.free.framework

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.config.RadialShimmer
import io.github.hoerire.free.config.ModuleSettingsSnapshot
import io.github.hoerire.free.config.OutsideTapCloseMode
import io.github.hoerire.free.config.PinnedTarget
import io.github.hoerire.free.config.PinnedTargetCodec
import io.github.hoerire.free.config.RadialGroup
import io.github.hoerire.free.config.RadialGroupSide
import io.github.hoerire.free.config.SettingsBridge
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Service 102 的进程级所有者；所有同步 Binder 调用都串行限制在单一后台线程。 */
internal class FrameworkConnectionRepository(private val context: Context) {
    private val started = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, WORKER_THREAD_NAME) }
    private val liveServices = IdentityHashMap<XposedService, Unit>()
    private val diedBeforeBind = IdentityHashMap<XposedService, Unit>()
    private val pendingServices = IdentityHashMap<XposedService, Unit>()
    private val unavailableServices = IdentityHashMap<XposedService, FrameworkConnectionState>()
    private var activeConnection: ActiveConnection? = null

    private val mutableState = MutableStateFlow(FrameworkConnectionState())
    val state: StateFlow<FrameworkConnectionState> = mutableState.asStateFlow()

    private val serviceListener =
        object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                worker.execute { handleServiceBind(service) }
            }

            override fun onServiceDied(service: XposedService) {
                worker.execute { handleServiceDied(service) }
            }
        }

    fun start() {
        check(started.compareAndSet(false, true)) { "Xposed Service listener is already registered" }
        XposedServiceHelper.registerListener(serviceListener)
    }

    fun setModuleEnabled(enabled: Boolean) =
        updateSettings { it.copy(enabled = enabled) }

    fun setCornerSides(leftEnabled: Boolean, rightEnabled: Boolean) =
        updateSettings {
            val bothDisabled = !leftEnabled && !rightEnabled
            it.copy(
                leftCornerEnabled = if (bothDisabled) true else leftEnabled,
                rightCornerEnabled = if (bothDisabled) true else rightEnabled,
            )
        }

    fun setCornerTriggerRangeDp(rangeDp: Int) =
        updateSettings {
            it.copy(
                cornerTriggerRangeDp =
                    ModulePreferences.coerceCornerTriggerRangeDp(rangeDp),
            )
        }

    fun setRadialHitRangeDp(rangeDp: Int) =
        updateSettings {
            it.copy(
                radialHitRangeDp =
                    ModulePreferences.coerceRadialHitRangeDp(rangeDp),
            )
        }

    fun setRadialExpandDurationMs(durationMs: Int) =
        updateSettings {
            it.copy(
                radialExpandDurationMs =
                    ModulePreferences.coerceRadialExpandDurationMs(durationMs),
            )
        }

    fun setRadialRetractDurationMs(durationMs: Int) =
        updateSettings {
            it.copy(
                radialRetractDurationMs =
                    ModulePreferences.coerceRadialRetractDurationMs(durationMs),
            )
        }

    fun setBackdropLevel(level: Int) =
        updateSettings {
            it.copy(backdropLevel = ModulePreferences.coerceBackdropLevel(level))
        }

    fun setOutsideTapCloseMode(mode: OutsideTapCloseMode) =
        updateSettings { it.copy(outsideTapCloseMode = mode) }


    fun setHandleSwipeUpToMiniEnabled(enabled: Boolean) =
        updateSettings { it.copy(handleSwipeUpToMiniEnabled = enabled) }

    fun setExternalHandleEnabled(enabled: Boolean) =
        updateSettings { it.copy(externalHandleEnabled = enabled) }

    fun setPauseInLandscape(enabled: Boolean) =
        updateSettings { it.copy(pauseInLandscape = enabled) }

    fun setPauseInGameMode(enabled: Boolean) =
        updateSettings { it.copy(pauseInGameMode = enabled) }

    /** 屏蔽键盘：输入法弹出时暂停扇形手势。 */
    fun setPauseInKeyboard(enabled: Boolean) =
        updateSettings { it.copy(pauseInKeyboard = enabled) }

    fun setVerboseLogEnabled(enabled: Boolean) =
        updateSettings { it.copy(verboseLogEnabled = enabled) }

    fun setRadialKeepOpen(enabled: Boolean) =
        updateSettings { it.copy(radialKeepOpen = enabled) }

    /** 扇形交互总开关：关闭后底角不再呼出扇形菜单。 */
    fun setRadialInteractionEnabled(enabled: Boolean) =
        updateSettings { it.copy(radialInteractionEnabled = enabled) }

    /** 流光特效：选中项外沿的动效，与「选中光圈」互相独立。 */
    fun setRadialShimmerEnabled(enabled: Boolean) =
        updateSettings { it.copy(radialShimmerEnabled = enabled) }

    /** 呼吸特效：扇形图标持续轻微起伏。 */
    fun setRadialBreathingEnabled(enabled: Boolean) =
        updateSettings { it.copy(radialBreathingEnabled = enabled) }

    /** 选中流光的参数集合。 */
    fun setRadialShimmer(value: RadialShimmer) =
        updateSettings { it.copy(radialShimmer = value) }

    /** 扇形半径百分比：左右两侧同步缩放。 */
    fun setRadialRadiusPercent(value: Int) =
        updateSettings {
            it.copy(radialRadiusPercent = ModulePreferences.coerceRadialRadiusPercent(value))
        }

    /** 解除限制：改写系统长按拖拽的准入名单。 */
    fun setFreeDragEnabled(enabled: Boolean) =
        updateSettings { it.copy(freeDragEnabled = enabled) }

    /** 快速分享：拖拽时弹出模块自绘的分享面板。 */
    fun setQuickShareEnabled(enabled: Boolean) =
        updateSettings { it.copy(quickShareEnabled = enabled) }

    /** 拖拽触发延迟（毫秒）：写回配置，由 system_server 应用到系统长按阈值。 */
    fun setDragLongPressMs(ms: Int) =
        updateSettings { it.copy(dragLongPressMs = ModulePreferences.coerceDragLongPressMs(ms)) }

    /**
     * 气泡设置：整页只提交「下一个快照」，具体字段由页面决定。
     *
     * 这样新增气泡开关时不用在仓库里加十几个 setter；写入仍走统一的乐观上屏 + 回执校正。
     */
    fun setBubbleSettings(transform: (ModuleSettingsSnapshot) -> ModuleSettingsSnapshot) =
        updateSettings(transform)

    fun setRadialIconSizeDp(value: Int) =
        updateSettings {
            it.copy(radialIconSizeDp = ModulePreferences.coerceRadialIconSizeDp(value))
        }

    /** 小窗并发上限。 */
    fun setZoomMaxWindowCount(count: Int) =
        updateSettings {
            it.copy(
                zoomMaxWindowCount = ModulePreferences.coerceZoomMaxWindowCount(count),
            )
        }

    fun setZoomAllowAnyApp(enabled: Boolean) =
        updateSettings { it.copy(zoomAllowAnyApp = enabled) }

    // 小窗跳转的细分场景，各自独立存盘、独立判定。
    fun setZoomLaunchNotice(enabled: Boolean) =
        updateSettings { it.copy(zoomLaunchNotice = enabled) }

    fun setZoomLaunchHeadsUp(enabled: Boolean) =
        updateSettings { it.copy(zoomLaunchHeadsUp = enabled) }

    fun setZoomLaunchControlCenter(enabled: Boolean) =
        updateSettings { it.copy(zoomLaunchControlCenter = enabled) }

    fun setZoomLaunchInterApp(enabled: Boolean) =
        updateSettings { it.copy(zoomLaunchInterApp = enabled) }



    fun setZoomLaunchClipboard(enabled: Boolean) =
        updateSettings { it.copy(zoomLaunchClipboard = enabled) }

    /** 窗口超过数量限制时是否自动关闭旧窗口（默认关闭，保持系统收成浮标的行为）。 */
    fun setAutoCloseWindow(enabled: Boolean) =
        updateSettings { it.copy(autoCloseWindow = enabled) }

    /** 最近任务里隐藏小窗：开启后小窗任务不再出现在最近任务卡片里。 */
    fun setHideZoomInRecents(enabled: Boolean) =
        updateSettings { it.copy(hideZoomInRecents = enabled) }

    /** 长按迷你窗静音/恢复。 */
    fun setLongPressMute(enabled: Boolean) =
        updateSettings { it.copy(longPressMuteEnabled = enabled) }

    /** 返回增强：边缘内滑不松手超过 0.5 秒后执行的动作（关闭 / 原地小窗 / 更多面板）。 */
    fun setPredictiveBackAction(action: Int) =
        updateSettings {
            it.copy(
                predictiveBackAction = ModulePreferences.coercePredictiveBackAction(action),
            )
        }


    fun setFloatHandleMaxCount(count: Int) =
        updateSettings {
            it.copy(floatHandleMaxCount = ModulePreferences.coerceFloatHandleMaxCount(count))
        }
    fun setFloatKeepAlive(enabled: Boolean) =
        updateSettings { it.copy(floatKeepAlive = enabled) }


    fun setBlacklistPackages(packages: List<String>) =
        updateSettings { it.copy(blacklistPackages = ModulePreferences.decodePackages(ModulePreferences.encodePackages(packages))) }

    /** 「禁用浮窗」列表；与排除列表各存各的键，互不影响。 */
    fun setZoomExcludedPackages(packages: List<String>) =
        updateSettings {
            it.copy(zoomExcludedPackages = ModulePreferences.decodePackages(ModulePreferences.encodePackages(packages)))
        }

    /**
     * 某一组扇形固定项。
     *
     * 同一项不允许同时出现在两组：写入前按另一组过滤一次，界面上的拦截之外再兜一层。
     */
    fun setPinnedTargets(group: RadialGroup, components: List<PinnedTarget>) =
        updateSettings { settings ->
            val other = settings.pinsFor(group.opposite).toSet()
            val cleaned = PinnedTargetCodec.normalize(components.filterNot { it in other })
            when (group) {
                RadialGroup.Primary -> settings.copy(pinsSaved = true, pinnedTargets = cleaned)
                RadialGroup.Alternate -> settings.copy(alternatePinnedTargets = cleaned)
            }
        }

    /** 第二组生效位置：不启用 / 左侧 / 右侧。 */
    fun setSecondaryGroupSide(side: RadialGroupSide) =
        updateSettings { it.copy(secondaryGroupSide = side) }

    fun requestMissingScopes() {
        worker.execute {
            val connection = activeConnection ?: return@execute
            val current = mutableState.value
            if (!current.canRequestScope) return@execute
            val requested = current.missingScopes.toList()
            val requesting = current.copy(isRequestingScope = true, issue = null)
            activeConnection = connection.copy(confirmedState = requesting)
            mutableState.value = requesting
            try {
                connection.service.requestScope(
                    requested,
                    object : XposedService.OnScopeEventListener {
                        override fun onScopeRequestApproved(approved: List<String>) {
                            worker.execute { refreshAfterScopeRequest(connection) }
                        }

                        override fun onScopeRequestFailed(message: String) {
                            worker.execute { finishScopeRequestWithError(connection) }
                        }
                    },
                )
            } catch (exception: XposedService.ServiceException) {
                finishScopeRequestWithError(connection)
            } catch (exception: UnsupportedOperationException) {
                finishScopeRequestWithError(connection)
            } catch (exception: SecurityException) {
                finishScopeRequestWithError(connection)
            } catch (exception: RuntimeException) {
                finishScopeRequestWithError(connection)
            }
        }
    }

    private fun updateSettings(transform: (ModuleSettingsSnapshot) -> ModuleSettingsSnapshot) {
        worker.execute {
            val connection = activeConnection ?: return@execute
            val previous = mutableState.value
            if (!previous.canChangeSettings) return@execute
            val next = transform(previous.settings)
            if (next == previous.settings) return@execute
            mutableState.value = previous.copy(isUpdating = true, issue = null)
            val committed = commitSettings(connection.preferences, next)
            if (activeConnection?.service !== connection.service) return@execute
            if (committed) {
                val confirmed = previous.copy(settings = next, isUpdating = false, issue = null)
                activeConnection = connection.copy(confirmedState = confirmed)
                mutableState.value = confirmed
                // 任何设置变更都推送一次：跨进程 SharedPreferences 的变更监听不可靠，
                // 只推固定项会让"详细日志""自动收窗"这类开关停留在旧快照里。
                notifySettingsChanged(next)
            } else {
                isolateFailedConnection(connection, previous)
            }
        }
    }

    /**
     * 把整份设置推给各作用域进程。
     *
     * 框架配置存储在本进程不可写，设置只能由模块应用下发：system_server 落盘并生效，
     * SystemUI 与侧边栏各自覆盖内存快照。载荷带发起者身份，接收方校验 UID。
     */
    private fun notifySettingsChanged(settings: ModuleSettingsSnapshot) {
        val options =
            android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
        // 2.2 的做法：每次设置变更都发 PINNED_TARGETS_CHANGED（带 enabled/pinsSaved），
        // 接收方先 forceReload 远程偏好再叠加这次的固定项，改动实时生效、无需重启进程。
        val extras = android.os.Bundle()
        SettingsBridge.putMap(extras, SettingsBridge.encode(settings))
        extras.putString(
            ModulePreferences.EXTRA_PINNED_TARGETS,
            PinnedTargetCodec.encodeList(settings.pinnedTargets),
        )
        extras.putBoolean(ModulePreferences.EXTRA_PINS_SAVED, settings.pinsSaved)
        extras.putBoolean(ModulePreferences.EXTRA_MODULE_ENABLED, settings.enabled)
        listOf(
            "android",
            "com.android.launcher",
            "com.android.systemui",
            "com.coloros.smartsidebar",
            "com.oplus.contentportal",
        ).forEach { target ->
            val intent =
                Intent(ModulePreferences.ACTION_PINNED_TARGETS_CHANGED).apply {
                    setPackage(target)
                    putExtras(extras)
                }
            try {
                context.sendBroadcast(intent, null, options)
            } catch (_: RuntimeException) {
                // 目标进程未运行时忽略：它启动后会主动索取当前设置。
            }
        }
    }

    private fun handleServiceBind(service: XposedService) {
        if (diedBeforeBind.remove(service) != null) return
        if (liveServices.put(service, Unit) != null) return
        pendingServices[service] = Unit
        reconcileServices()
    }

    private fun handleServiceDied(service: XposedService) {
        if (liveServices.remove(service) == null) {
            diedBeforeBind[service] = Unit
            return
        }
        pendingServices.remove(service)
        unavailableServices.remove(service)
        if (activeConnection?.service === service) activeConnection = null
        reconcileServices()
    }

    private fun reconcileServices() {
        if (liveServices.size > 1) {
            mutableState.value = multipleServicesState()
            return
        }
        val service = liveServices.keys.firstOrNull()
        if (service == null) {
            activeConnection = null
            mutableState.value = FrameworkConnectionState()
            return
        }
        activeConnection?.takeIf { it.service === service }?.let { connection ->
            mutableState.value = connection.confirmedState
            return
        }
        unavailableServices[service]?.let { state ->
            mutableState.value = state
            return
        }
        if (pendingServices.remove(service) != null) {
            activateService(service)
            return
        }
        mutableState.value =
            FrameworkConnectionState(
                status = FrameworkConnectionStatus.Error,
                issue = FrameworkConnectionIssue.ConnectionFailed,
            )
    }

    private fun activateService(service: XposedService) {
        val result = probeService(service)
        if (result.connection != null) {
            unavailableServices.remove(service)
            activeConnection = result.connection
        } else {
            activeConnection = null
            unavailableServices[service] = result.state
        }
        mutableState.value = result.state
    }

    private fun probeService(service: XposedService): ProbeResult {
        var frameworkName: String? = null
        var frameworkVersion: String? = null
        var apiVersion: Int? = null
        return try {
            apiVersion = service.apiVersion
            frameworkName = service.frameworkName.normalizedMetadata()
            frameworkVersion = service.frameworkVersion.normalizedMetadata()
            val properties = service.frameworkProperties
            when {
                apiVersion < XposedService.API_102 ->
                    ProbeResult.failure(frameworkName, frameworkVersion, apiVersion, FrameworkConnectionIssue.ServiceApiTooOld)
                properties and XposedService.PROP_CAP_REMOTE == 0L ->
                    ProbeResult.failure(frameworkName, frameworkVersion, apiVersion, FrameworkConnectionIssue.RemoteCapabilityMissing)
                properties and XposedService.PROP_CAP_SYSTEM == 0L ->
                    ProbeResult.failure(frameworkName, frameworkVersion, apiVersion, FrameworkConnectionIssue.SystemCapabilityMissing)
                else -> {
                    val preferences = service.getRemotePreferences(ModulePreferences.GROUP)
                    val settings = readOrRepairSettings(preferences) ?: return ProbeResult.error(
                        frameworkName,
                        frameworkVersion,
                        apiVersion,
                        FrameworkConnectionIssue.WriteFailed,
                    )
                    val scopes = service.scope.filterTo(linkedSetOf()) { it in FrameworkConnectionState.REQUIRED_SCOPES }
                    ProbeResult.connected(service, preferences, frameworkName, frameworkVersion, apiVersion, settings, scopes)
                }
            }
        } catch (exception: XposedService.ServiceException) {
            ProbeResult.error(frameworkName, frameworkVersion, apiVersion)
        } catch (exception: UnsupportedOperationException) {
            ProbeResult.failure(frameworkName, frameworkVersion, apiVersion, FrameworkConnectionIssue.RemoteCapabilityMissing)
        } catch (exception: SecurityException) {
            ProbeResult.error(frameworkName, frameworkVersion, apiVersion)
        } catch (exception: RuntimeException) {
            ProbeResult.error(frameworkName, frameworkVersion, apiVersion)
        }
    }

    private fun readOrRepairSettings(preferences: SharedPreferences): ModuleSettingsSnapshot? =
        try {
            ModuleSettingsSnapshot.readFrom(preferences)
        } catch (exception: ClassCastException) {
            ModuleSettingsSnapshot().takeIf { defaults -> commitSettings(preferences, defaults) }
        }

    // KTX edit 返回 Unit；这里必须将远端 commit 的失败结果传回 UI。
    @SuppressLint("UseKtx")
    private fun commitSettings(preferences: SharedPreferences, settings: ModuleSettingsSnapshot): Boolean =
        try {
            settings.writeTo(preferences.edit()).commit()
        } catch (exception: XposedService.ServiceException) {
            false
        } catch (exception: UnsupportedOperationException) {
            false
        } catch (exception: SecurityException) {
            false
        } catch (exception: RuntimeException) {
            false
        }

    private fun isolateFailedConnection(connection: ActiveConnection, previous: FrameworkConnectionState) {
        activeConnection = null
        val failed =
            previous.copy(
                status = FrameworkConnectionStatus.Error,
                isUpdating = false,
                isRequestingScope = false,
                issue = FrameworkConnectionIssue.WriteFailed,
            )
        unavailableServices[connection.service] = failed
        mutableState.value = failed
    }

    private fun refreshAfterScopeRequest(connection: ActiveConnection) {
        if (activeConnection?.service !== connection.service) return
        pendingServices[connection.service] = Unit
        activeConnection = null
        reconcileServices()
    }

    private fun finishScopeRequestWithError(connection: ActiveConnection) {
        if (activeConnection?.service !== connection.service) return
        val failed =
            connection.confirmedState.copy(
                isRequestingScope = false,
                issue = FrameworkConnectionIssue.ScopeRequestFailed,
            )
        activeConnection = connection.copy(confirmedState = failed)
        mutableState.value = if (liveServices.size > 1) multipleServicesState() else failed
    }

    private fun multipleServicesState() =
        FrameworkConnectionState(
            status = FrameworkConnectionStatus.Incompatible,
            issue = FrameworkConnectionIssue.MultipleServices,
        )

    private fun String.normalizedMetadata(): String? = trim().take(MAX_METADATA_LENGTH).ifEmpty { null }

    private data class ActiveConnection(
        val service: XposedService,
        val preferences: SharedPreferences,
        val confirmedState: FrameworkConnectionState,
    )

    private data class ProbeResult(val connection: ActiveConnection?, val state: FrameworkConnectionState) {
        companion object {
            fun connected(
                service: XposedService,
                preferences: SharedPreferences,
                frameworkName: String?,
                frameworkVersion: String?,
                apiVersion: Int,
                settings: ModuleSettingsSnapshot,
                scopes: Set<String>,
            ): ProbeResult {
                val state =
                    FrameworkConnectionState(
                        status = FrameworkConnectionStatus.Connected,
                        frameworkName = frameworkName,
                        frameworkVersion = frameworkVersion,
                        apiVersion = apiVersion,
                        settings = settings,
                        grantedScopes = scopes,
                    )
                return ProbeResult(ActiveConnection(service, preferences, state), state)
            }

            fun failure(name: String?, version: String?, api: Int?, issue: FrameworkConnectionIssue) =
                ProbeResult(
                    null,
                    FrameworkConnectionState(
                        status = FrameworkConnectionStatus.Incompatible,
                        frameworkName = name,
                        frameworkVersion = version,
                        apiVersion = api,
                        issue = issue,
                    ),
                )

            fun error(
                name: String?,
                version: String?,
                api: Int?,
                issue: FrameworkConnectionIssue = FrameworkConnectionIssue.ConnectionFailed,
            ) =
                ProbeResult(
                    null,
                    FrameworkConnectionState(
                        status = FrameworkConnectionStatus.Error,
                        frameworkName = name,
                        frameworkVersion = version,
                        apiVersion = api,
                        issue = issue,
                    ),
                )
        }
    }

    private companion object {
        const val WORKER_THREAD_NAME = "Free-Service"
        const val MAX_METADATA_LENGTH = 80
    }
}
