package io.github.hoerire.free.hook

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.config.PredictiveBackMode
import io.github.hoerire.free.log.ModuleLog
import io.github.hoerire.free.log.VerboseLog
import io.github.hoerire.free.logic.PredictiveBackAngle
import java.lang.reflect.Method

/**
 * 侧滑扩展：边缘内滑开始返回手势后，手指**上滑或下拉**超过设定距离执行对应动作。
 *
 * 方向即动作，具体由 [io.github.hoerire.free.config.PredictiveBackMode] 决定：
 * 默认「上滑切窗 · 下拉更多」，也可以选「上滑更多 · 下拉切窗」。
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

    /**
     * 竖向行程与角度的量测锚点。
     *
     * 起手前的边缘内滑、以及斜划里横向占优的那几段，都是「横向位移」：它们不该算进
     * 竖向行程，更不该把角度带斜。遇到这样的采样段就把锚点挪到当前位置，只量真正的竖滑。
     */
    private var anchorX = 0f
    private var anchorY = 0f
    private var lastX = 0f
    private var lastY = 0f

    /**
     * 最近一小段（约 [SAMPLE_MS] 内）的位移方向。
     *
     * 只看总位移不够：斜着起手、后段转正，累计角度会一路衰减到阈值以内，照样触发。
     * 最近的走向才代表「手指现在正往哪儿滑」。
     */
    private var recentDx = 0f
    private var recentDy = 0f
    private var sampleX = 0f
    private var sampleY = 0f
    private var sampleAt = 0L

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
        anchorX = downX
        anchorY = downY
        recentDx = 0f
        recentDy = 0f
        sampleX = downX
        sampleY = downY
        sampleAt = android.os.SystemClock.uptimeMillis()
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
     * 只认纵向位移 dy；上滑额外乘一个系数 [UPWARD_FACTOR]，下拉直接用设置值。
     *
     * 为什么不能用合位移 sqrt(dx²+dy²)：onMotionEvent 的 DOWN 是在返回手势真正开始之后
     * 才输入的，那时手指已经完成内滑，downX 本身就落在内滑后的位置。于是 dx 衡量的是
     * 「内滑了多少」，不是「手指竖向运动了多少」——上滑抬手时 dx 还会一路回收
     *（实测 -149 → -115），把它算进距离只会稀释真实行程。两个方向的内滑量相同，
     * 用 dx 也不可能改善方向之间的不对称。
     *
     * 实际差异来自生理：内滑之后手腕已内旋，再上抬要先克服这个角度，同样行程体感更远。
     * 所以上滑单独给一个更小的系数。
     *
     * 不看时间：停住不动永远不会触发，滑回原位也不会；
     * 阈值每次现场取，设置页改完不用重装钩子。
     */
    private fun onMove(chain: XposedInterface.Chain) {
        val x = (chain.getArg(0) as? Number)?.toFloat() ?: return
        val y = (chain.getArg(1) as? Number)?.toFloat() ?: return
        lastX = x
        lastY = y
        // 诊断必须在 armed/fired 之前就挡掉，否则会把"没在跟踪的手势"也记进来，
        // 日志里会出现一条早已越过阈值却始终不触发的假轨迹。
        if (fired || !armed) return
        val snapshot = configuration.snapshot
        val threshold = snapshot.predictiveBackSwipeDistanceDpEffective * density
        // 每 [SAMPLE_MS] 记一段走向，并处理横向占优的段：
        // 边缘内滑的尾巴（以及斜划的横向部分）是横向的，既不该算进竖向行程，也不该把角度带斜。
        // 原先从 downX/downY 一直量到底，拉得越长锚点越靠边，就必须拿更长的竖向行程去「稀释」
        // 那段横向位移才能过角度闸——表现就是 50dp 要拉出三四倍的竖直距离才触发。
        val now = android.os.SystemClock.uptimeMillis()
        if (now - sampleAt >= SAMPLE_MS) {
            val sx = x - sampleX
            val sy = y - sampleY
            if (kotlin.math.abs(sx) >= MIN_SAMPLE_DP * density &&
                kotlin.math.abs(sx) > HORIZONTAL_LEAD_FACTOR * kotlin.math.abs(sy)
            ) {
                anchorX = x
                anchorY = y
            }
            if (kotlin.math.abs(sy) >= MIN_SAMPLE_DP * density) {
                recentDx = sx
                recentDy = sy
            }
            sampleX = x
            sampleY = y
            sampleAt = now
        }
        // 竖向行程只从锚点算：横滑段已经被移出。
        val delta = y - anchorY
        // 上滑单独用更短的阈值：内滑后手腕已内旋，再上抬要先克服这个角度，
        // 同样的物理行程体感更远。下拉沿用设置值。
        val up = threshold * UPWARD_FACTOR
        // 方向即动作。两种模式都是双方向，互换了"哪个方向做哪件事"。
        val action =
            when (snapshot.predictiveBackMode) {
                PredictiveBackMode.ZoomUpMoreDown ->
                    when {
                        // 上滑（y 变小）：切窗口形态。
                        delta <= -up -> ModulePreferences.ACTION_PREDICTIVE_BACK_ZOOM
                        // 下拉（y 变大）：打开「更多」面板。
                        delta >= threshold -> ModulePreferences.ACTION_PREDICTIVE_BACK_MORE_PANEL
                        else -> null
                    }
                PredictiveBackMode.ZoomDownMoreUp ->
                    when {
                        // 上滑：打开「更多」面板。
                        delta <= -up -> ModulePreferences.ACTION_PREDICTIVE_BACK_MORE_PANEL
                        // 下拉：切窗口形态。
                        delta >= threshold -> ModulePreferences.ACTION_PREDICTIVE_BACK_ZOOM
                        else -> null
                    }
            } ?: return
        // 角度约束只加在下拉上：下拉是顺着手指自然回落，方向一斜就会累计纵向位移、
        // 把「更多」面板误触出来；上滑本来就要先克服手腕内旋、不容易触发，不需要再限制。
        //
        // 两条都要过：整段位移的方向（起点 → 当前）与**最近一小段**的方向。
        // 只看整段时，斜着起手、后段转正的轨迹会被累计角度「洗白」，实测就是这么误触的。
        if (delta >= threshold) {
            val totalDx = x - anchorX
            val totalDy = y - anchorY
            val totalOk = PredictiveBackAngle.isVertical(totalDx, totalDy)
            // 必须有真实的走向采样才放行：原先「recentDy 还是 0 就跳过最近走向」的写法，
            // 会让一次快速斜划在第一个越阈值的 MOVE 上直接过闸——这正是 45° 斜滑能触发的原因。
            val recentOk =
                recentDy != 0f && PredictiveBackAngle.isRecentVertical(recentDx, recentDy)
            if (!totalOk || !recentOk) {
                if (VerboseLog.enabled) {
                    ModuleLog.debug("Free") {
                        "PREDICTIVE_BACK_ANGLE_REJECT_t" +
                            PredictiveBackAngle.degreesFromVertical(totalDx, totalDy) +
                            "_r" + PredictiveBackAngle.degreesFromVertical(recentDx, recentDy)
                    }
                }
                return
            }
        }
        if (VerboseLog.enabled) {
            ModuleLog.debug("Free") {
                "PREDICTIVE_BACK_FIRE_dy" + (y - downY).toInt() + "_thr" + threshold.toInt()
            }
        }
        fire(action)
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
        recentDx = 0f
        recentDy = 0f
    }

    private companion object {
        const val CONTROLLER_CLASS = "com.android.wm.shell.back.BackAnimationController"

        /**
         * 上滑方向的阈值系数：100 dp 实际按 55 dp 触发。
         *
         * 依据来自实测：日志打印门槛是 0.4 × 245 = 98px，下拉首个采样点 103（吻合），
         * 上滑首个采样点却是 -236——说明**上滑前面约 140px 行程模块没收到**。
         * 你实际手滑约 248 + 140 = 388px，而下拉只有 350px，所以上滑确实更长。
         *
         * 补偿量约 140px，因此上滑阈值取 350 - 140 ≈ 210，即系数 0.55。
         * 若仍偏长调到 0.45；若太灵敏回到 0.65。这只是补偿，不改变判定逻辑。
         */
        const val UPWARD_FACTOR = 0.4f

        /** 走向采样间隔：只看最近这一小段的方向，避免斜起手、后段转正被「洗白」。 */
        const val SAMPLE_MS = 32L

        /** 采样段的最小纵向位移（dp）：太小的抖动不参与方向判定。 */
        const val MIN_SAMPLE_DP = 6f

        /**
         * 横向占优的判据：最近一段的 |dx| 超过 |dy| 的这个倍数，就认为这一段是横滑
         *（边缘内滑的尾巴 / 斜划的横向部分），把量测锚点挪过去、不计入竖向行程。
         * 取 0.8：只要横向占比接近竖向（≥0.8，约 39° 以外）就仍算「还在往外拉」，
         * 把这一段从竖向行程里剔除。45° 斜划的比值是 1.0，每一段都会被剔除——竖向永远攒不够，
         * 所以斜划依旧触发不了；而正常「拉出来再竖直下拉」会从拉完那一刻起重新计距，
         * 50dp 就是 50dp，不会再被前面那段横向位移「稀释」。
         */
        const val HORIZONTAL_LEAD_FACTOR = 0.8f

        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
    }
}
