package io.github.hoerire.free.hook

import android.annotation.SuppressLint
import android.content.Context
import io.github.hoerire.free.log.ModuleLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.platform.coloros.ColorOsSystemUiApplicationTarget
import io.github.hoerire.free.platform.coloros.RomFlavor
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("PrivateApi")
internal class SystemUiHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val bound = AtomicBoolean(false)

    fun install(classLoader: ClassLoader) {
        // 不在 onNotificationClicked / onStartFloatWindow 上接管通知点击。
        //
        // 这两个入口在 ColorOS 17 上回调不稳定：改写后一旦浮窗通道返回空值，原始点击即被丢弃，
        // 常驻通知与自动分组通知会表现为点击无响应。
        // 窗口形态改由 NotifBundleHook 组装启动参数，通知中心与通知横幅各自只受自己的开关约束。
        try {
            val target =
                ColorOsSystemUiApplicationTarget.resolve { className ->
                    classLoader.loadClass(className)
                } ?: throw NoSuchMethodException("SystemUI application onCreate")
            val onCreate = target.onCreate
            module
                .hook(onCreate)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.systemui.corner_spy_owner")
                .intercept { chain ->
                    val result = chain.proceed()
                    val context = chain.thisObject as? Context
                    if (context != null && bound.compareAndSet(false, true)) {
                        // 通知点击小窗：改 Bundle（含剥离 remote 转场），让窗口不被横幅压住。
                        NotifBundleHook(module, configuration, context).install(classLoader)
                        // 气泡通知：独立子系统，任何失败都不影响 SystemUI 自身启动。
                        runCatching {
                            io.github.hoerire.free.bubble.BubbleCoordinator(
                                module,
                                context,
                                configuration,
                            ).start(classLoader)
                        }.onFailure {
                            ModuleLog.warn("SYSTEMUI_BUBBLE_INSTALL_FAILED", it)
                        }
                        installSettingsObserver(context)
                        // 全面屏手势指示条：与「横条沉浸」共用一个开关，空闲时不绘制、触摸时出现。
                        // 不撤 inset、不改布局，所以应用不会像官方开关那样整体下沉。
                        runCatching {
                            NavigationHandleImmersiveHook(module, configuration)
                                .install(classLoader)
                        }.onFailure {
                            ModuleLog.warn("SYSTEMUI_NAV_HANDLE_IMMERSIVE_FAILED", it)
                        }
                        // 侧滑扩展：边缘内滑后按方向执行动作。
                        // 只在 ColorOS 17+ 接线（返回增强手势与本地小窗切换都是这一代才有）。
                        if (RomFlavor.isColorOs17OrNewer) {
                            runCatching {
                                PredictiveBackSwipeHook(module, configuration, context)
                                    .install(classLoader)
                            }.onFailure {
                                ModuleLog.warn("SYSTEMUI_PREDICTIVE_BACK_INSTALL_FAILED", it)
                            }
                            // 厂商对竖向位移的取消判据不对称（上滑 1.0×、下拉 2.5×），
                            // 上滑会被提前掐断，模块的距离判据走不到。这里放行那一条。
                            runCatching {
                                SideGestureVerticalCancelHook(module, configuration)
                                    .install(classLoader)
                            }.onFailure {
                                ModuleLog.warn("SYSTEMUI_SIDE_VERTICAL_INSTALL_FAILED", it)
                            }
                        }
                        // 角区抢流（SystemUI 侧 InputMonitor + pilferPointers）**只在 ColorOS 17+ 启用**。
                        //
                        // 这条通道在低版系统上会引起 SystemUI 崩溃循环；17 上角区抢流已改由
                        // system_server 内建 monitor 承担，本通道在 17 上整场收不到事件
                        //（日志可见 CORNER_TRACK 一直为 0）。所以低版直接不启动它：
                        // 角区手势退回 WMS 旁观者（扇形可用、左滑穿透存在）——
                        // 宁可保留这个缺陷，也不能让低版用户的系统界面崩。
                        if (RomFlavor.isColorOs17OrNewer) {
                            runCatching {
                                CornerInputPilferMonitor(configuration).start(context)
                            }.onSuccess {
                                io.github.hoerire.free.log.ModuleLog.info(
                                    "SYSTEMUI_CORNER_SPY_STARTED",
                                )
                            }.onFailure {
                                ModuleLog.warn("SYSTEMUI_CORNER_SPY_FAILED", it)
                            }
                        } else {
                            ModuleLog.info(
                                "SYSTEMUI_CORNER_SPY_SKIPPED_LEGACY_ROM_" +
                                    android.os.Build.VERSION.SDK_INT,
                            )
                        }
                    }
                    result
                }
            io.github.hoerire.free.log.ModuleLog.info("SYSTEMUI_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            ModuleLog.warn("SYSTEMUI_APPLICATION_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            ModuleLog.warn("SYSTEMUI_APPLICATION_TARGET_LINKAGE_FAILED", exception)
        }
    }

    /**
     * 接收设置变更广播并重读磁盘配置。
     *
     * SystemUI 不在服务端的实时推送范围内时，本进程的开关会停在开机那一刻的取值，
     * 表现为「通知横幅」改完要重启才生效。这里只做重读，不校验发起者：
     * 伪造广播最多换来一次读盘，不影响任何判定。
     */
    private fun installSettingsObserver(context: Context) {
        val receiver =
            object : android.content.BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: android.content.Intent?) {
                    when (intent?.action) {
                        ModulePreferences.ACTION_PINNED_TARGETS_CHANGED -> {
                            // 这条广播带的是整份设置快照：直接覆盖内存快照，改动即时生效。
                            // 只 reload 远程偏好不够——模块应用刚写进去的值，本进程的
                            // SharedPreferences 句柄未必看得到（「屏蔽键盘」等新开关就卡在这里）。
                            val pushed =
                                io.github.hoerire.free.config.SettingsBridge
                                    .readMap(intent.extras)
                                    ?.let { map ->
                                        runCatching {
                                            io.github.hoerire.free.config.SettingsBridge.decode(map)
                                        }.getOrNull()
                                    }
                            if (pushed != null) {
                                configuration.override(pushed)
                            } else {
                                configuration.forceReload()
                            }
                            io.github.hoerire.free.log.ModuleLog.info(
                                "SYSTEMUI_SETTINGS_RELOADED",
                            )
                        }
                    }
                }
            }
        runCatching {
            context.registerReceiver(
                receiver,
                android.content.IntentFilter().apply {
                    addAction(ModulePreferences.ACTION_PINNED_TARGETS_CHANGED)
                },
                Context.RECEIVER_EXPORTED,
            )
        }.onSuccess {
            io.github.hoerire.free.log.ModuleLog.info("SYSTEMUI_SETTINGS_OBSERVER_REGISTERED")
        }.onFailure {
            ModuleLog.warn("SYSTEMUI_SETTINGS_OBSERVER_FAILED", it as? RuntimeException)
        }
    }

    private companion object {
        const val TAG = "Free"
    }
}
