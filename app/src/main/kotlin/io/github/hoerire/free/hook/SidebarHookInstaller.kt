package io.github.hoerire.free.hook

import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Process
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.free.log.ModuleLog
import io.github.hoerire.free.platform.coloros.ColorOsAllAppsContent
import io.github.hoerire.free.platform.coloros.ColorOsAllAppsEndpoint
import io.github.hoerire.free.platform.coloros.ColorOsSidebarTarget
import io.github.hoerire.free.platform.coloros.ColorOsSidebarVisualTarget
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 只注入侧边栏 UI 进程；服务方法照常执行一次，应用/工具的启动仍交给原厂路由。 */
internal class SidebarHookInstaller(private val module: XposedModule, private val configuration: ProcessConfiguration) {
    private var endpoint: ColorOsAllAppsEndpoint? = null
    private val settingsSyncInstalled = AtomicBoolean(false)

    private var visualTarget: ColorOsSidebarVisualTarget? = null
    private val resolutionStarted = AtomicBoolean(false)

    /**
     * 识屏工具的原生点击改由 system_server 代发。
     *
     * `BreenoScreenIdentifyTool.handle()` 直接对 `com.coloros.colordirectservice` 调
     * `startForegroundService`；侧边栏进程处于后台时这次启动被系统拒绝（ActivityManager:
     * "Background start not allowed"），表现为点了没反应。这里拦下它，改发一条广播，
     * 由 system_server（uid 1000，不受该限制）代发同一个 Intent。
     *
     * 取不到坐标时按 0 传：宿主只是用它定位识屏浮层，不影响能否拉起。
     */
    private fun installScreenIdentifyRedirect(loader: ClassLoader) {
        val handle = try {
            loader
                .loadClass("com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.models.tools.BreenoScreenIdentifyTool")
                .getDeclaredMethod("handle")
        } catch (_: Throwable) {
            // 该机型没有这个工具条目，不需要接管。
            return
        }
        module
            .hook(handle)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.sidebar.breeno_identify")
            .intercept { chain ->
                // 总开关关闭、或「启用交互」关闭时，都走侧边栏自己的启动逻辑，不接管。
                if (!configuration.snapshot.cornerInteractionActive) return@intercept chain.proceed()
                val sent = runCatching {
                    val service = chain.thisObject
                    val context = readContext(service)
                    // 必须开启身份共享：系统侧的 receiver 要靠 getSentFromPackage/getSentFromUid
                    // 核对发起者；普通 sendBroadcast 这两项是 null/-1，会被当作伪造请求丢掉。
                    context?.sendBroadcast(
                        Intent(io.github.hoerire.free.platform.coloros.ScreenIdentifyStarter.REQUEST_ACTION)
                            .setPackage("android")
                            .putExtra("first_point_down_x", 0f)
                            .putExtra("first_point_down_y", 0f),
                        null,
                        android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
                    ) != null
                }.getOrDefault(false)
                if (sent) {
                    log(Log.DEBUG, "SIDEBAR_SCREEN_IDENTIFY_REDIRECTED", null)
                    null
                } else {
                    chain.proceed()
                }
            }
    }

    /** 从工具实例上取 Context（AbsTool.mContext）。 */
    private fun readContext(instance: Any?): android.content.Context? {
        if (instance == null) return null
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val field = runCatching { type!!.getDeclaredField("mContext") }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return runCatching { field.get(instance) as? android.content.Context }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    fun install(loader: ClassLoader, applicationInfo: ApplicationInfo) {
        // 面板进程的诊断日志统一走框架日志，落到 /data/adb/lspd/log/modules_*.log。
        io.github.hoerire.free.log.ModuleLog.attach { priority, code, throwable ->
            module.log(priority, "Free", code, throwable)
        }
        val handles = mutableListOf<XposedInterface.HookHandle>()
        try {
            val serviceClass = loader.loadClass(ColorOsSidebarTarget.SERVICE_CLASS)
            require(Service::class.java.isAssignableFrom(serviceClass))
            val bind = serviceClass.getDeclaredMethod("onBind", Intent::class.java)
            val unbind = serviceClass.getDeclaredMethod("onUnbind", Intent::class.java)
            val destroy = serviceClass.getDeclaredMethod("onDestroy")
            require(bind.returnType == IBinder::class.java)
            val allClass = loader.loadClass(ColorOsAllAppsContent.ALL_CLASS)
            val adapter = allClass.getDeclaredMethod("getAllAppAdapter")
            require(adapter.returnType.name == ColorOsAllAppsContent.ADAPTER_CLASS)
            val mainClass = loader.loadClass(ColorOsAllAppsContent.MAIN_CLASS)
            val changeState = mainClass.getDeclaredMethod("changeState", loader.loadClass("${ColorOsAllAppsContent.MAIN_CLASS}\$State"))

            handles += module.hook(bind).setId("free.allapps.bind")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val original = chain.proceed()
                    // 「启用交互」关闭：侧边栏按原厂逻辑走，完全不接管面板与目录。
                    if (!configuration.snapshot.cornerInteractionActive) return@intercept original
                    val service = chain.thisObject as? Service
                    val intent = chain.getArg(0) as? Intent
                    // 第一次被本模块的 action 绑定时补齐设置通道：install() 时
                    // ActivityThread.currentApplication() 常为 null，拿不到 Context。
                    if (service != null &&
                        intent?.action == ColorOsSidebarTarget.BIND_ACTION &&
                        settingsSyncInstalled.compareAndSet(false, true)
                    ) {
                        installSettingsSync(service)
                    }
                    // 只保留「更多」面板的原生桥。目录读取已经改成各进程自己直读侧边栏 APK，
                    // 不再需要向侧边栏进程要数据，也没有任何跨进程通道。
                    if (service != null && intent?.action == ColorOsSidebarTarget.BIND_ACTION) {
                        if (endpoint == null) {
                            val targetUid = ColorOsSidebarTarget.supportedUid(service)
                            if (targetUid == Process.myUid()) {
                                // 「更多」面板的列表改写（隐藏应用、最近去重）要读模块设置；
                                // 注入本进程同一份快照，改设置后广播会重读，不必重开面板。
                                io.github.hoerire.free.platform.coloros.FavoriteGroupInjector
                                    .bindSettings { configuration.snapshot }
                                endpoint = ColorOsAllAppsEndpoint(
                                    service, loader, configuration, { visualTarget }, ::log,
                                )
                            } else {
                                log(
                                    Log.WARN,
                                    "ALL_APPS_TARGET_REJECT_uid" + (targetUid ?: -1) +
                                        "_me" + Process.myUid(),
                                    null,
                                )
                            }
                        }
                        endpoint?.takeIf { it.service === service }?.binder ?: original
                    } else original
                }
            handles += module.hook(unbind).setId("free.allapps.unbind")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val original = chain.proceed()
                    val intent = chain.getArg(0) as? Intent
                    if (intent?.action == ColorOsSidebarTarget.BIND_ACTION) endpoint?.onUnbound(intent.identifier)
                    original
                }
            handles += module.hook(destroy).setId("free.allapps.destroy")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    if (endpoint?.service === chain.thisObject) {
                        val previous = endpoint
                        endpoint = null
                        previous?.dispose()
                    }
                    chain.proceed()
                }
            handles += module.hook(adapter).setId("free.allapps.adapter")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val original = chain.proceed()
                    if (!configuration.snapshot.cornerInteractionActive) return@intercept original
                    endpoint?.onAdapterCreated(chain.thisObject, original)
                    original
                }
            handles += module.hook(changeState).setId("free.allapps.native_state")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val original = chain.proceed()
                    if (!configuration.snapshot.cornerInteractionActive) return@intercept original
                    endpoint?.onNativeSidebarState((chain.getArg(0) as? Enum<*>)?.name)
                    original
                }
            log(Log.INFO, "ALL_APPS_BRIDGE_INSTALLED", null)
            // 侧边栏进程就绪：通知 system_server 现在可以安全地读目录（事件驱动，不再轮询重试）。
            runCatching {
                val app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as? android.content.Context
                // 不能 setPackage(模块包名)：接收方注册在 system_server，定向到模块 App 会收不到。
                app?.sendBroadcast(
                    Intent(io.github.hoerire.free.config.ModulePreferences.ACTION_SIDEBAR_READY),
                )
                log(Log.INFO, "SIDEBAR_READY_BROADCAST_SENT", null)
                if (app != null) installSettingsSync(app)
            }.onFailure { log(Log.WARN, "SIDEBAR_READY_BROADCAST_FAILED", it) }
            // 识屏：把原生 handle() 的启动改由 system_server 代发，缺这个工具条目的机型自动跳过。
            runCatching { installScreenIdentifyRedirect(loader) }
                .onFailure { log(Log.WARN, "SCREEN_IDENTIFY_REDIRECT_FAILED", it) }
            // 「更多」面板里的工具（付款码、扫一扫这类）保持系统行为：原样交给系统 uid 代发，
            // 不由模块改成小窗。不要在这里挂 `AppPlatformUtils.n(Intent, Bundle, UserHandle)`
            // 去并小窗参数：登记入口（PanelToolLaunchBridge.arm）已不在，hook 永远匹配不上。
            resolveVisualTarget(loader, applicationInfo)
        } catch (exception: ReflectiveOperationException) {
            handles.forEach { it.unhook() }
            log(Log.WARN, "ALL_APPS_SIGNATURE_UNAVAILABLE", exception)
        } catch (exception: RuntimeException) {
            handles.forEach { it.unhook() }
            log(Log.WARN, "ALL_APPS_HOOK_INSTALL_FAILED", exception)
        } catch (error: LinkageError) {
            handles.forEach { it.unhook() }
            log(Log.WARN, "ALL_APPS_LINKAGE_FAILED", error)
        }
    }

    /**
     * 接收模块应用的设置变更广播。
     *
     * 侧边栏进程的设置来自 LSPosed 远程偏好，模块重装后那份存储会变空、进程会一直停在
     * 「总开关关闭」的旧快照上。这里收模块应用每次改设置都发的广播，载荷带整份快照，
     * 直接覆盖内存快照即可。
     */
    private fun installSettingsSync(context: android.content.Context) {
        val receiver =
            object : android.content.BroadcastReceiver() {
                override fun onReceive(receiverContext: android.content.Context?, intent: android.content.Intent?) {
                    if (intent?.action ==
                        io.github.hoerire.free.config.ModulePreferences.ACTION_PANEL_FOCUS
                    ) {
                        io.github.hoerire.free.platform.coloros.PanelFocusState.update(
                            intent.getStringExtra(
                                io.github.hoerire.free.config.ModulePreferences.EXTRA_PANEL_FOCUS_PACKAGE,
                            ),
                        )
                        return
                    }
                    if (intent?.action !=
                        io.github.hoerire.free.config.ModulePreferences.ACTION_PINNED_TARGETS_CHANGED
                    ) return
                    val map = io.github.hoerire.free.config.SettingsBridge.readMap(intent.extras)
                    if (map == null) {
                        configuration.forceReload()
                        return
                    }
                    runCatching { io.github.hoerire.free.config.SettingsBridge.decode(map) }
                        .onSuccess { configuration.override(it) }
                        .onFailure { log(Log.WARN, "SIDEBAR_SETTINGS_DECODE_FAILED", it) }
                }
            }
        runCatching {
            context.registerReceiver(
                receiver,
                android.content.IntentFilter().apply {
                    addAction(io.github.hoerire.free.config.ModulePreferences.ACTION_PINNED_TARGETS_CHANGED)
                    addAction(io.github.hoerire.free.config.ModulePreferences.ACTION_PANEL_FOCUS)
                },
                android.content.Context.RECEIVER_EXPORTED,
            )
            log(Log.INFO, "SIDEBAR_SETTINGS_SYNC_REGISTERED", null)
        }.onFailure { log(Log.WARN, "SIDEBAR_SETTINGS_SYNC_FAILED", it) }
    }

    private fun resolveVisualTarget(loader: ClassLoader, info: ApplicationInfo) {
        if (!resolutionStarted.compareAndSet(false, true)) return
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "Free-SidebarDex").apply { isDaemon = true }
        }
        val timeout = Runnable {
            if (finished.compareAndSet(false, true)) {
                executor.shutdownNow()
                log(Log.WARN, "SIDEBAR_DEX_RESOLUTION_TIMEOUT", null)
            }
        }
        handler.postDelayed(timeout, 10_000L)
        executor.execute {
            try {
                val target = ColorOsSidebarVisualTarget.resolve(
                    loader, File(info.sourceDir), File(info.dataDir, "code_cache/free/sidebar-symbols"),
                ) { code, exception -> log(if (exception == null) Log.INFO else Log.WARN, code, exception) }
                handler.post {
                    if (finished.compareAndSet(false, true)) {
                        handler.removeCallbacks(timeout)
                        visualTarget = target
                    }
                }
            } catch (exception: ReflectiveOperationException) {
                log(Log.WARN, "SIDEBAR_DEX_TARGET_UNAVAILABLE", exception)
            } catch (exception: IOException) {
                log(Log.WARN, "SIDEBAR_DEX_IO_FAILED", exception)
            } catch (exception: RuntimeException) {
                log(Log.WARN, "SIDEBAR_DEX_RESOLUTION_FAILED", exception)
            } catch (error: LinkageError) {
                log(Log.WARN, "SIDEBAR_DEX_LINKAGE_FAILED", error)
            } finally {
                handler.post {
                    if (finished.compareAndSet(false, true)) handler.removeCallbacks(timeout)
                }
            }
        }
        executor.shutdown()
    }

    private fun log(priority: Int, code: String, throwable: Throwable?) = ModuleLog.forward(priority, code, throwable)

    private companion object {
    }
}