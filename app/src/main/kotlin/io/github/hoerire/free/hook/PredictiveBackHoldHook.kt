package io.github.hoerire.free.hook

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.log.ModuleLog
import java.lang.reflect.Method

/**
 * 返回增强：边缘内滑后不松手，超过 0.5 秒执行设置里选定的那一项
 * ——「小窗」（全屏 ↔ 小窗切换）或「更多」（打开更多面板），也可以整个关掉。
 *
 * 原厂行为是「松手才返回」：手势过程中只是预览，`ACTION_UP` 才真正提交返回。
 * 这里接在返回增强手势的入口 `BackAnimationController.onMotionEvent(x, y, action, index,
 * displayId)` 上，只观察、不改写前 0.5 秒的任何事件——快滑松手走的是原厂路径，行为完全不变。
 *
 * 计时到期时做两件事：
 *   1. 给 system_server 发一条广播，由它执行动作（小窗切换 / 打开面板，链路与扇形菜单一致）；
 *   2. 立刻向控制器注入一次 `ACTION_CANCEL`，结束这次返回手势，之后松手不会再返回。
 *
 * 判断依据只有时间：手势开始后 0.5 秒内松手 = 原厂返回；不松手 = 执行所选动作。
 */
@SuppressLint("PrivateApi")
internal class PredictiveBackHoldHook(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
    private val context: Context,
) {
    private val handler = Handler(Looper.getMainLooper())

    /** 本次手势是否在计时。 */
    @Volatile private var armed = false

    /** 本次手势是否已经触发过动作：触发后到松手之间的事件全部吞掉。 */
    @Volatile private var fired = false

    /** 正在注入取消事件：让这次调用原样穿过钩子，不再被自己的逻辑改写。 */
    @Volatile private var injecting = false

    private var controller: Any? = null
    private var motionMethod: Method? = null

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastIndex = 0
    private var lastDisplay = 0

    /** 起手在屏幕左半边：打开面板时按这一侧选分组。 */
    private var leftEdge = true

    private val screenWidth = context.resources.displayMetrics.widthPixels.toFloat()

    private val timeout = Runnable { fire() }

    fun install(classLoader: ClassLoader) {
        try {
            val type = classLoader.loadClass(CONTROLLER_CLASS)
            val method =
                type.getDeclaredMethod(
                    "onMotionEvent",
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.systemui.predictive_back_hold")
                .intercept { chain ->
                    if (injecting) return@intercept chain.proceed()
                    val action = (chain.getArg(2) as? Number)?.toInt() ?: -1
                    when (action) {
                        ACTION_DOWN -> onDown(chain)
                        ACTION_MOVE -> if (fired) return@intercept null
                        ACTION_UP, ACTION_CANCEL -> {
                            cancelTimer()
                            if (fired) {
                                // 手势已经结束，剩下的抬手事件不再交给原厂。
                                resetGesture()
                                return@intercept null
                            }
                        }
                    }
                    chain.proceed()
                }
            ModuleLog.info("PREDICTIVE_BACK_HOLD_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            ModuleLog.warn("PREDICTIVE_BACK_HOLD_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            ModuleLog.warn("PREDICTIVE_BACK_HOLD_TARGET_LINKAGE_FAILED", exception)
        }
    }

    private fun onDown(chain: XposedInterface.Chain) {
        resetGesture()
        controller = chain.thisObject
        motionMethod = chain.executable as? Method
        downX = (chain.getArg(0) as? Number)?.toFloat() ?: 0f
        downY = (chain.getArg(1) as? Number)?.toFloat() ?: 0f
        lastX = downX
        lastY = downY
        lastIndex = (chain.getArg(3) as? Number)?.toInt() ?: 0
        lastDisplay = (chain.getArg(4) as? Number)?.toInt() ?: 0
        leftEdge = downX < screenWidth / 2f
        val action = configuration.snapshot.predictiveBackActionEffective
        if (action == ModulePreferences.PREDICTIVE_BACK_ACTION_DISABLED) return
        armed = true
        handler.postDelayed(timeout, ModulePreferences.PREDICTIVE_BACK_HOLD_MS)
        ModuleLog.debug("Free") { "PREDICTIVE_BACK_HOLD_ARMED_" + action }
    }

    /** 计时到期：通知 system_server 执行动作，并结束这次返回手势。 */
    private fun fire() {
        if (!armed || fired) return
        val action = configuration.snapshot.predictiveBackActionEffective
        if (action == ModulePreferences.PREDICTIVE_BACK_ACTION_DISABLED) {
            armed = false
            return
        }
        fired = true
        armed = false
        if (action == ModulePreferences.PREDICTIVE_BACK_ACTION_MORE_PANEL) {
            sendBroadcast(ModulePreferences.ACTION_PREDICTIVE_BACK_MORE_PANEL) { intent ->
                intent.putExtra(ModulePreferences.EXTRA_GESTURE_SIDE, leftEdge)
                intent.putExtra(ModulePreferences.EXTRA_GESTURE_X, downX)
                intent.putExtra(ModulePreferences.EXTRA_GESTURE_Y, downY)
            }
            ModuleLog.info("PREDICTIVE_BACK_MORE_PANEL")
        } else {
            sendBroadcast(ModulePreferences.ACTION_PREDICTIVE_BACK_HOLD)
            ModuleLog.info("PREDICTIVE_BACK_HOLD_FIRED")
        }
        injectCancel()
    }

    private fun sendBroadcast(action: String, buildExtras: (Intent) -> Unit = {}) {
        try {
            // 不指定包名：这是一条隐式广播，只有 system_server 里注册了该 action 的
            // 动态接收器会收到；写死 "android" 在个别版本上会被包过滤丢掉。
            //
            // 必须开 setShareIdentityEnabled：Android 14 起跨进程广播默认不带发起方 uid，
            // 接收侧看到的是 -1，身份校验会直接把这条消息判成陌生来源丢掉。
            val options =
                android.app.BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle()
            val intent = Intent(action).apply(buildExtras)
            context.sendBroadcast(intent, null, options)
            ModuleLog.debug("Free") {
                "PREDICTIVE_BACK_BROADCAST_SENT_" + action.substringAfterLast('.')
            }
        } catch (exception: RuntimeException) {
            ModuleLog.warn("PREDICTIVE_BACK_BROADCAST_FAILED", exception)
        }
    }

    /**
     * 直接把一次 `ACTION_CANCEL` 交给控制器。
     *
     * 不这么做的话，用户松手时控制器仍会按「手势完成」提交返回；注入取消后控制器走的是
     * 取消分支，预览动画收回，返回不会发生。
     */
    private fun injectCancel() {
        val target = controller ?: return
        val method = motionMethod ?: return
        injecting = true
        try {
            method.invoke(target, lastX, lastY, ACTION_CANCEL, lastIndex, lastDisplay)
            ModuleLog.debug("Free") { "PREDICTIVE_BACK_HOLD_CANCEL_INJECTED" }
        } catch (exception: ReflectiveOperationException) {
            ModuleLog.warn("PREDICTIVE_BACK_HOLD_CANCEL_FAILED", exception)
        } catch (exception: RuntimeException) {
            ModuleLog.warn("PREDICTIVE_BACK_HOLD_CANCEL_FAILED", exception)
        } finally {
            injecting = false
        }
    }

    private fun cancelTimer() {
        armed = false
        handler.removeCallbacks(timeout)
    }

    private fun resetGesture() {
        cancelTimer()
        fired = false
    }

    private companion object {
        const val CONTROLLER_CLASS = "com.android.wm.shell.back.BackAnimationController"
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
    }
}
