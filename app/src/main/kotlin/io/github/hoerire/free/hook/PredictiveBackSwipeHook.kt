package io.github.hoerire.free.hook

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.config.PredictiveBackMode
import io.github.hoerire.free.log.ModuleLog
import java.lang.reflect.Method

/**
 * 侧滑扩展：边缘内滑开始返回手势后，手指**上滑或下拉**超过设定距离执行对应动作。
 *
 * 上滑方向还有一道厂商拦截要一起处理，见 [SideGestureVerticalCancelHook]：
 * 那边不放开的话，上滑的 MOVE 到不了这里。
 *
 * 方向即动作：上滑把焦点应用在「全屏 ↔ 小窗」之间切换，下拉打开「更多」面板。
 *
 * 原厂行为是「松手才返回」：手势过程中只是预览，`ACTION_UP` 才真正提交返回。
 * 这里接在侧滑扩展手势的入口 `BackAnimationController.onMotionEvent(x, y, action, index,
 * displayId)` 上，只观察、不改写任何事件——没滑够距离就松手时走的是原厂路径，行为完全不变。
 *
 * 判据是**方向 + 距离**而不是停留时长：手指在屏幕上停多久都不会触发，停住期间把手指滑回
 * 原位或直接松手都等于取消这次返回。按时间触发做不到这一点——时间一到就必须执行，
 * 想反悔已经晚了。
 *
 * 滑够距离时做两件事：
 *   1. 给 system_server 发一条广播，由它执行动作（小窗切换 / 打开面板，链路与扇形菜单一致）；
 *   2. 立刻向控制器注入一次 `ACTION_CANCEL`，结束这次返回手势，之后松手不会再返回。
 */
@SuppressLint("PrivateApi")
internal class PredictiveBackSwipeHook(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
    private val context: Context,
) {
    /** 本次手势是否在跟踪（侧滑扩展已开启且起手落在生效边缘）。 */
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
    private val density = context.resources.displayMetrics.density

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
                .setId("free.systemui.predictive_back_swipe")
                .intercept { chain ->
                    if (injecting) return@intercept chain.proceed()
                    val action = (chain.getArg(2) as? Number)?.toInt() ?: -1
                    when (action) {
                        ACTION_DOWN -> onDown(chain)
                        ACTION_MOVE -> {
                            // 触发之后手势已经结束，后续移动不再交给原厂。
                            if (fired) return@intercept null
                            onMove(chain)
                        }
                        ACTION_UP, ACTION_CANCEL -> {
                            if (fired) {
                                // 触发后松手：抬手事件不再交给原厂，否则会提交一次返回。
                                resetGesture()
                                return@intercept null
                            }
                            resetGesture()
                        }
                    }
                    chain.proceed()
                }
            ModuleLog.info("PREDICTIVE_BACK_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            ModuleLog.warn("PREDICTIVE_BACK_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            ModuleLog.warn("PREDICTIVE_BACK_TARGET_LINKAGE_FAILED", exception)
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
        if (!configuration.snapshot.predictiveBackEffective) return
        armed = true
        ModuleLog.debug("Free") { "PREDICTIVE_BACK_ARMED" }
    }

    /**
     * 跟踪手指：上滑或下拉超过设定距离就触发，其余情况一律放行。
     *
     * 只看纵向位移，不看时间：停住不动永远不会触发，滑回原位也不会；
     * 阈值每次现场取，设置页改完不用重装钩子。
     */
    private fun onMove(chain: XposedInterface.Chain) {
        val x = (chain.getArg(0) as? Number)?.toFloat() ?: return
        val y = (chain.getArg(1) as? Number)?.toFloat() ?: return
        lastX = x
        lastY = y
        if (fired) return
        if (!armed) return
        val snapshot = configuration.snapshot
        val threshold = snapshot.predictiveBackSwipeDistanceDpEffective * density
        val delta = y - downY
        // 方向即动作，具体由模式决定：有的模式只认下滑，有的两个方向都用。
        when (snapshot.predictiveBackMode) {
            PredictiveBackMode.ZoomUpMoreDown ->
                when {
                    // 上滑：y 变小，切窗口形态。
                    delta <= -threshold -> fire(ModulePreferences.ACTION_PREDICTIVE_BACK_ZOOM)
                    // 下拉：y 变大，打开「更多」面板。
                    delta >= threshold -> fire(ModulePreferences.ACTION_PREDICTIVE_BACK_MORE_PANEL)
                }
            PredictiveBackMode.ZoomDown ->
                if (delta >= threshold) fire(ModulePreferences.ACTION_PREDICTIVE_BACK_ZOOM)
            PredictiveBackMode.MoreDown ->
                if (delta >= threshold) fire(ModulePreferences.ACTION_PREDICTIVE_BACK_MORE_PANEL)
        }
    }

    /** 滑够距离：通知 system_server 执行动作，并结束这次返回手势。 */
    private fun fire(action: String) {
        if (!armed || fired) return
        if (!configuration.snapshot.predictiveBackEffective) {
            armed = false
            return
        }
        fired = true
        armed = false
        sendBroadcast(action) { intent ->
            if (action == ModulePreferences.ACTION_PREDICTIVE_BACK_MORE_PANEL) {
                intent.putExtra(ModulePreferences.EXTRA_GESTURE_SIDE, leftEdge)
                intent.putExtra(ModulePreferences.EXTRA_GESTURE_X, downX)
                intent.putExtra(ModulePreferences.EXTRA_GESTURE_Y, downY)
            }
        }
        ModuleLog.info(
            if (action == ModulePreferences.ACTION_PREDICTIVE_BACK_MORE_PANEL) {
                "PREDICTIVE_BACK_MORE_PANEL"
            } else {
                "PREDICTIVE_BACK_ZOOM"
            },
        )
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
            ModuleLog.debug("Free") { "PREDICTIVE_BACK_CANCEL_INJECTED" }
        } catch (exception: ReflectiveOperationException) {
            ModuleLog.warn("PREDICTIVE_BACK_CANCEL_FAILED", exception)
        } catch (exception: RuntimeException) {
            ModuleLog.warn("PREDICTIVE_BACK_CANCEL_FAILED", exception)
        } finally {
            injecting = false
        }
    }

    private fun resetGesture() {
        armed = false
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
