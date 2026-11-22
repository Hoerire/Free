package io.github.hoerire.free.hook

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.hoerire.free.log.ModuleLog
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.free.gesture.AdaptiveCornerGestureConfig
import io.github.hoerire.free.gesture.CornerGestureConfig
import io.github.hoerire.free.gesture.CornerGestureEngine
import io.github.hoerire.free.gesture.CornerTriggerRegion
import io.github.hoerire.free.gesture.GestureAction
import io.github.hoerire.free.gesture.GesturePhase
import io.github.hoerire.free.platform.coloros.ColorOsLauncherInputTarget
import io.github.hoerire.free.platform.coloros.RecentsFilterQuery
import java.io.File
import java.io.IOException
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** ColorOS Quickstep 输入入口；命中角落的指针流从 DOWN 起不再进入系统手势链。 */
@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
internal class LauncherHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val gestureEngine = CornerGestureEngine()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val environmentState = ModuleEnvironmentState(configuration) { code, exception ->
        ModuleLog.warn(code, exception)
    }
    private var environmentStarted = false
    private var activeConfig: CornerGestureConfig? = null
    private var activePointerId = -1
    private var suppressUntilTerminal = false
    private var pilfered = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var inputMonitorField: Field? = null
    private var pilferPointers: ((Any) -> Unit)? = null
    private var lastClaimLogAt = -CLAIM_LOG_INTERVAL_MS
    private var lastPilferFailureAt = -PILFER_FAILURE_LOG_INTERVAL_MS

    private val installationStarted = AtomicBoolean(false)
    private val settingsSyncInstalled = AtomicBoolean(false)

    fun install(classLoader: ClassLoader, applicationInfo: ApplicationInfo) {
        if (!installationStarted.compareAndSet(false, true)) return
        val apk = File(applicationInfo.sourceDir)
        val cache = File(applicationInfo.dataDir, "code_cache/free/launcher-symbols")
        val finished = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "Free-LauncherDex").apply { isDaemon = true }
        }
        val timeout = Runnable {
            if (finished.compareAndSet(false, true)) {
                executor.shutdownNow()
                ModuleLog.warn("LAUNCHER_DEX_RESOLUTION_TIMEOUT")
            }
        }
        mainHandler.postDelayed(timeout, RESOLUTION_TIMEOUT_MS)
        // 解析前的事件完整交给桌面；发布 Hook 后也只从下一次 DOWN 开始接管。
        executor.execute {
            try {
                val target = ColorOsLauncherInputTarget.resolve(classLoader, apk, cache) { code, exception ->
                    ModuleLog.forward(if (exception == null) Log.INFO else Log.WARN, code, exception)
                }
                mainHandler.post {
                    if (finished.compareAndSet(false, true)) {
                        mainHandler.removeCallbacks(timeout)
                        installResolved(target)
                    }
                }
            } catch (exception: ReflectiveOperationException) {
                ModuleLog.warn("LAUNCHER_CORNER_INPUT_TARGET_UNAVAILABLE", exception)
            } catch (exception: IOException) {
                ModuleLog.warn("LAUNCHER_DEX_IO_FAILED", exception)
            } catch (exception: RuntimeException) {
                ModuleLog.warn("LAUNCHER_DEX_RESOLUTION_FAILED", exception)
            } catch (exception: LinkageError) {
                ModuleLog.warn("LAUNCHER_CORNER_INPUT_TARGET_LINKAGE_FAILED", exception)
            } finally {
                mainHandler.post {
                    if (finished.compareAndSet(false, true)) mainHandler.removeCallbacks(timeout)
                }
            }
            // 最近任务卡片过滤：与角落手势互不依赖，失败只影响这个开关。
            runCatching { installRecentsCardFilter(apk, classLoader) }
                .onFailure { ModuleLog.warn("LAUNCHER_RECENTS_FILTER_FAILED", it as? RuntimeException) }
        }
        // 只有这一项任务；完成后线程退出，不保留后台线程池或重试循环。
        executor.shutdown()
    }

    private fun installResolved(target: ColorOsLauncherInputTarget) {
        try {
            inputMonitorField = target.monitorField
            pilferPointers = { wrapper ->
                try {
                    target.pilferMethod.invoke(wrapper)
                } catch (exception: InvocationTargetException) {
                    throw exception.targetException
                }
            }
            module
                .hook(target.inputMethod)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.launcher.corner_input_owner")
                .intercept { chain ->
                    val event = chain.getArg(0) as? MotionEvent
                        ?: return@intercept chain.proceed()
                    val owner = chain.thisObject as? Context
                        ?: return@intercept chain.proceed()
                    if (shouldSuppress(owner, event)) {
                        null
                    } else {
                        chain.proceed()
                    }
                }
            ModuleLog.info("LAUNCHER_CORNER_INPUT_HOOK_INSTALLED")
        } catch (exception: IllegalArgumentException) {
            ModuleLog.warn("LAUNCHER_CORNER_INPUT_HOOK_REJECTED", exception)
        } catch (exception: LinkageError) {
            ModuleLog.warn("LAUNCHER_CORNER_INPUT_TARGET_LINKAGE_FAILED", exception)
        }
    }

    /**
     * 最近任务里隐藏小窗卡片。
     *
     * 列表由桌面拼装，过滤点也在桌面进程内；这里只把「当前是小窗的任务」这一条判定
     * 塞进桌面的过滤器：命中返回 true（不显示这张卡），其余原样交给桌面。
     */
    private fun installRecentsCardFilter(apk: File, classLoader: ClassLoader) {
        try {
            val filter = RecentsFilterQuery.resolve(classLoader, apk)
            module
                .hook(filter)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.launcher.recents_hide")
                .intercept { chain ->
                    if (!configuration.snapshot.hideZoomInRecentsEffective) {
                        return@intercept chain.proceed()
                    }
                    val grouped = chain.getArg(2)
                    if (grouped != null && isSmallWindowGroup(grouped)) {
                        true
                    } else {
                        chain.proceed()
                    }
                }
            ModuleLog.info("LAUNCHER_RECENTS_FILTER_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            ModuleLog.warn("LAUNCHER_RECENTS_FILTER_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            ModuleLog.warn("LAUNCHER_RECENTS_FILTER_LINKAGE_FAILED", exception)
        } catch (exception: RuntimeException) {
            ModuleLog.warn("LAUNCHER_RECENTS_FILTER_FAILED", exception)
        }
    }

    /** 一组卡片里任意一个任务是「小窗」就整组不显示。 */
    private fun isSmallWindowGroup(grouped: Any): Boolean =
        taskInfosOf(grouped).any(::isSmallWindowTask)

    /** GroupedTaskInfo 里无参、返回 TaskInfo 的方法就是组内任务（主任务与分屏副任务）。 */
    private fun taskInfosOf(grouped: Any): List<android.app.TaskInfo> =
        grouped.javaClass.methods.mapNotNull { method ->
            if (method.parameterCount != 0 || method.returnType != android.app.TaskInfo::class.java) {
                return@mapNotNull null
            }
            runCatching { method.invoke(grouped) as? android.app.TaskInfo }.getOrNull()
        }

    /**
     * 是否处于小窗形态。
     *
     * ColorOS 的小窗任务在任务栈里窗口模式仍报全屏，所以先排除分屏，再看：
     * 1. 窗口模式是自由窗（5）；
     * 2. 任务配置里的窗口边界明显小于可用最大边界；
     * 3. 厂商状态位（小窗/迷你窗/悬浮等非普通、非分屏取值）。
     */
    private fun isSmallWindowTask(taskInfo: android.app.TaskInfo): Boolean {
        val mode = runCatching {
            taskInfo.javaClass.getMethod("getWindowingMode").invoke(taskInfo) as? Int
        }.getOrNull() ?: 0
        if (mode == WINDOWING_MODE_SPLIT_PRIMARY || mode == WINDOWING_MODE_SPLIT_SECONDARY) {
            return false
        }
        if (mode == WINDOWING_MODE_FREEFORM) return true
        val configuration = runCatching {
            taskInfo.javaClass.getField("configuration").get(taskInfo)
                as? android.content.res.Configuration
        }.getOrNull()
        if (configuration != null) {
            val window = runCatching {
                configuration.javaClass.getField("windowConfiguration").get(configuration)
            }.getOrNull()
            val bounds = rectOf(window, "getBounds")
            val max = rectOf(window, "getMaxBounds")
            if (bounds != null && max != null &&
                max.width() > 0 && max.height() > 0 &&
                (bounds.width() < max.width() * BOUNDS_WIDTH_RATIO ||
                    bounds.height() < max.height() * BOUNDS_HEIGHT_RATIO)
            ) {
                return true
            }
        }
        val state = flexibleState(taskInfo)
        return state != null && state != FLEXIBLE_STATE_IDLE && state != FLEXIBLE_STATE_TILING
    }

    private fun rectOf(owner: Any?, method: String): android.graphics.Rect? = runCatching {
        val target = owner ?: return null
        target.javaClass.getMethod(method).invoke(target) as? android.graphics.Rect
    }.getOrNull()

    /** 厂商加在 TaskInfo extras 里的窗口形态；读不到返回 null。 */
    private fun flexibleState(taskInfo: android.app.TaskInfo): Int? = runCatching {
        val field = taskInfo.javaClass.getField("mOplusExtraBundle")
        val bundle = field.get(taskInfo) as? android.os.Bundle ?: return null
        if (!bundle.containsKey(KEY_FLEXIBLE_STATE)) return null
        bundle.getInt(KEY_FLEXIBLE_STATE)
    }.getOrNull()

    private fun shouldSuppress(owner: Context, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            resetStream()
            val config = createEligibleConfig(owner, event) ?: return false
            downRawX = event.rawX
            downRawY = event.rawY
            activePointerId = event.getPointerId(0)
            gestureEngine.down(activePointerId, event.rawX, event.rawY, config)
            if (gestureEngine.phase == GesturePhase.Armed) {
                activeConfig = config
                suppressUntilTerminal = true
                return true
            }
            resetStream()
            return false
        }
        if (!suppressUntilTerminal) return false

        if (!environmentState.isGestureAllowed()) cancelClaim()

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val config = activeConfig
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (config != null && pointerIndex >= 0) {
                    val action =
                        gestureEngine.move(
                            pointerId = activePointerId,
                            pointerCount = event.pointerCount,
                            x = event.getRawX(pointerIndex),
                            y = event.getRawY(pointerIndex),
                            config = config,
                        )
                    // 尽早抢流：只在 Activate 时抢的话，从 DOWN 到越过 touchSlop
                    // 之间的那几个事件仍归桌面，滑得快时会被桌面当成自己的手势。
                    // 位移超过 3dp 就抢，普通点击（几乎不位移）仍旧留给桌面。
                    if (!pilfered) {
                        val slop = PILFER_SLOP_DP * owner.resources.displayMetrics.density
                        val dx = event.getRawX(pointerIndex) - downRawX
                        val dy = event.getRawY(pointerIndex) - downRawY
                        if (dx * dx + dy * dy >= slop * slop) {
                            forcePilferPointers(owner)
                            pilfered = true
                            logClaimed()
                        }
                    }
                    if (!pilfered && action is GestureAction.Activate) {
                        forcePilferPointers(owner)
                        pilfered = true
                        logClaimed()
                    }
                } else {
                    gestureEngine.cancel()
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> gestureEngine.cancel()

            MotionEvent.ACTION_UP -> {
                gestureEngine.up(event.getPointerId(event.actionIndex))
                resetStream()
            }

            MotionEvent.ACTION_CANCEL -> resetStream()

            else -> Unit
        }
        return true
    }

    private fun createEligibleConfig(
        owner: Context,
        event: MotionEvent,
    ): CornerGestureConfig? {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return null
        // 无条件挂上设置通道：只在「模块未启用」时注册广播接收器，会让桌面进程
        // 开着模块时收不到后续设置推送 —— 关掉「启用交互」
        // 它仍然拿着启动时的旧快照继续抢底角输入。方法内部有幂等保护，重复调用无代价。
        installSettingsSync(owner)
        val settings = configuration.snapshot
        if (!settings.enabled) return null
        // 「启用交互」关掉 = 整套底角手势关掉：桌面这边也不再接管上滑与扇形呼出。
        if (!settings.cornerInteractionActive) return null
        if (!configuration.isAvailable) return null
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return null
        if (event.pointerCount != 1 || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return null
        startEnvironment(owner)
        if (!environmentState.isGestureAllowed(refreshKeyguard = true)) return null

        val metrics = owner.resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        if (width <= 0f || height <= 0f) return null
        return AdaptiveCornerGestureConfig.create(
            displayWidth = width,
            displayHeight = height,
            touchSlop = ViewConfiguration.get(owner).scaledTouchSlop.toFloat(),
            density = metrics.density,
            triggerRangeDp = settings.cornerTriggerRangeDp,
            leftEnabled = settings.leftCornerEnabled,
            rightEnabled = settings.rightCornerEnabled,
        ).takeIf { config ->
            CornerTriggerRegion.detectSide(
                x = event.rawX,
                y = event.rawY,
                displayWidth = config.displayWidth,
                displayHeight = config.displayHeight,
                radius = config.triggerRadius,
                leftEnabled = config.leftEnabled,
                rightEnabled = config.rightEnabled,
            ) != null
        }
    }

    private fun resetStream() {
        gestureEngine.cancel()
        activeConfig = null
        activePointerId = -1
        suppressUntilTerminal = false
        pilfered = false
    }

    private fun startEnvironment(owner: Context) {
        if (environmentStarted) return
        environmentStarted = true
        val start = {
            environmentState.start(owner)
            environmentState.observe {
                if (!environmentState.isGestureAllowed()) cancelClaim()
            }
            configuration.observe {
                mainHandler.post {
                    // 环境不允许、或「启用交互」被关掉，都要立刻放弃已接管的那条流。
                    if (!environmentState.isGestureAllowed() ||
                        !configuration.snapshot.cornerInteractionActive
                    ) {
                        cancelClaim()
                    }
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) start() else mainHandler.post { start() }
    }

    private fun cancelClaim() {
        gestureEngine.cancel()
        activeConfig = null
        // 已拦下 DOWN 的旧流继续收尾，不能把缺少 DOWN 的 MOVE/UP 交回桌面。
    }

    private fun forcePilferPointers(owner: Context) {
        try {
            val monitor = inputMonitorField?.get(owner) ?: return
            pilferPointers?.invoke(monitor)
        } catch (exception: ReflectiveOperationException) {
            logPilferFailure(exception)
        } catch (exception: RuntimeException) {
            logPilferFailure(exception)
        }
    }

    private fun logPilferFailure(exception: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPilferFailureAt < PILFER_FAILURE_LOG_INTERVAL_MS) return
        lastPilferFailureAt = now
        ModuleLog.warn("LAUNCHER_CORNER_INPUT_PILFER_FAILED", exception)
    }

    private fun logClaimed() {
        val now = SystemClock.uptimeMillis()
        if (now - lastClaimLogAt < CLAIM_LOG_INTERVAL_MS) return
        lastClaimLogAt = now
        ModuleLog.info("LAUNCHER_CORNER_INPUT_CLAIMED")
    }

    /** 向 system_server 索取一次权威设置快照，并接收后续推送。 */
    private fun installSettingsSync(context: android.content.Context) {
        if (!settingsSyncInstalled.compareAndSet(false, true)) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    io.github.hoerire.free.config.ModulePreferences.ACTION_PINNED_TARGETS_CHANGED ->
                        configuration.forceReload()
                    io.github.hoerire.free.config.ModulePreferences.ACTION_SETTINGS_PUSHED,
                    io.github.hoerire.free.config.ModulePreferences.ACTION_SETTINGS_SNAPSHOT -> {
                        val map = io.github.hoerire.free.config.SettingsBridge.readMap(intent.extras) ?: return
                        runCatching { io.github.hoerire.free.config.SettingsBridge.decode(map) }
                            .onSuccess { configuration.override(it) }
                    }
                }
            }
        }
        runCatching {
            context.registerReceiver(receiver, android.content.IntentFilter().apply {
                addAction(io.github.hoerire.free.config.ModulePreferences.ACTION_PINNED_TARGETS_CHANGED)
                addAction(io.github.hoerire.free.config.ModulePreferences.ACTION_SETTINGS_PUSHED)
                addAction(io.github.hoerire.free.config.ModulePreferences.ACTION_SETTINGS_SNAPSHOT)
            }, android.content.Context.RECEIVER_EXPORTED)
            context.sendBroadcast(android.content.Intent(io.github.hoerire.free.config.ModulePreferences.ACTION_SETTINGS_REQUEST).apply {
                setPackage("android")
                putExtra(io.github.hoerire.free.config.ModulePreferences.EXTRA_REQUESTER_PACKAGE, "com.android.launcher")
            })
            ModuleLog.info("LAUNCHER_SETTINGS_SYNC_REQUESTED")
        }.onFailure { ModuleLog.warn("LAUNCHER_SETTINGS_SYNC_FAILED", it) }
    }

    private companion object {
        const val WINDOWING_MODE_FREEFORM = 5
        const val WINDOWING_MODE_SPLIT_PRIMARY = 3
        const val WINDOWING_MODE_SPLIT_SECONDARY = 4
        const val BOUNDS_WIDTH_RATIO = 0.98f
        const val BOUNDS_HEIGHT_RATIO = 0.90f
        const val KEY_FLEXIBLE_STATE = "key_flexible_task_state"
        const val FLEXIBLE_STATE_IDLE = 0
        const val FLEXIBLE_STATE_TILING = 9

        const val TAG = "Free"
        const val RESOLUTION_TIMEOUT_MS = 10_000L
        /** 位移到这个距离就抢流，不等引擎的 touchSlop 判定。 */
        const val PILFER_SLOP_DP = 3f
        const val CLAIM_LOG_INTERVAL_MS = 2_000L
        const val PILFER_FAILURE_LOG_INTERVAL_MS = 10_000L
    }

}
