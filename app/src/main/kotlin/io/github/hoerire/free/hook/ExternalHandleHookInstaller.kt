package io.github.hoerire.free.hook

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.free.log.ModuleLog
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 小窗的底部横条与顶部三点（设置项：小窗交互 → 横条沉浸）。
 *
 * ## 行为
 *
 * - 平时**不绘制**，不看窗口大小、不看形态、不看谁触发的；
 * - 手指碰到底部**立刻显示**，拖动窗口期间全程显示；
 * - **抬手立即收回**（不需要等窗口失焦、不需要点别处）；
 * - 不撤 inset、不改布局参数、不改可见性、不改 alpha —— 小窗位置与内容始终不变。
 *
 * ## 为什么不再去改 vendor 的 alpha / 可见性
 *
 * 之前两版都在 vendor 的状态上做文章（`updateThreeButtonVisibility(false)`、
 * 逐个把 `mControlView` / `mBottomHandleView` / 四个按钮的 alpha 归零），都没成：
 *
 * 1. `updateThreeButtonVisibility` 开头有 `if (mLastVisible == 请求值) return`，
 *    一旦 `mLastVisible` 已被写成 false，收尾那次调用直接 return，什么都没做。
 * 2. 真正画出来的是 alpha 那一套，而系统会**不停**地用
 *    `animateControlView` / `animateToolBarMode` / `animateBottomHandleView`
 *    把 alpha 拉回 1（日志里 `requestVisible = true` 成串出现）。
 *    模块逐个字段去改，永远追不上一帧之后的重绘。
 *
 * 现在改成**只管这一帧画不画**，vendor 状态一律不碰：
 *
 * | 钩子 | 目标 | 作用 |
 * |---|---|---|
 * | `external_handle_draw` | `ViewGroup.dispatchDraw(Canvas)` | 命中 caption 且处于隐藏态时跳过绘制（整棵子树一帧都不画） |
 * | `external_handle_touch` | `FlexibleCaptionView.dispatchTouchEvent(MotionEvent)` | 触摸/拖动信号：落到小窗底部就显示，抬手立即收回 |
 *
 * 这与 [NavigationHandleImmersiveHook] 处理全面屏手势指示条是同一套思路——
 * 那边也是"空闲时 `onDraw` 不交给原方法"，同样不改 inset、不改可见性、不触发重排。
 *
 * ## 为什么"看不见"却"照样好使"
 *
 * 只是不画。View 仍在布局里，命中判定问的是
 * `mFlexibleCaptionViewRegionMgr` 的几何区域（`isInHandleButtonRegion()` /
 * `getFlexibleTaskActionFlag()`），与绘制、可见性、alpha 都无关。
 * 所以点横条关窗、横条上滑转迷你窗、三点菜单照旧。
 *
 * **不要改成 `setVisibility(GONE)`、不要摘触摸区域、不要动 alpha**——
 * 前两者会废掉点击，后者会与 vendor 的动画互相覆盖（前两版就是这么失败的）。
 */
@SuppressLint("PrivateApi")
internal class ExternalHandleHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val installed = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    /** 当前是否处于「显示」状态：手指按住期间为 true，抬手即 false。 */
    @Volatile private var revealed = false

    /** caption 的类型，用于在 `dispatchDraw` 里判断"这一帧画的是不是它"。 */
    @Volatile private var captionType: Class<*>? = null

    /** 最后一次被触摸的 caption。弱引用，不拖住已被销毁的窗口。 */
    private var lastCaption: WeakReference<View>? = null

    /**
     * 兜底收回：正常路径靠 `ACTION_UP` / `ACTION_CANCEL` 立即收回，
     * 这里只负责"抬手事件丢了"的情况（窗口在拖动中被销毁、事件被上层吞掉等）。
     */
    private val safetyHide =
        Runnable {
            if (revealed) {
                revealed = false
                lastCaption?.get()?.invalidate()
                ModuleLog.debug("EXTERNAL_HANDLE_AUTO_HIDE") { "timeout" }
            }
        }

    fun install(classLoader: ClassLoader) {
        val captionClass = OemReflection.loadOrNull(classLoader, OemClasses.FLEXIBLE_CAPTION_VIEW)
        if (captionClass == null) {
            ModuleLog.warn("EXTERNAL_HANDLE_CAPTION_CLASS_MISSING", null)
            return
        }
        if (!installed.compareAndSet(false, true)) return
        captionType = captionClass

        hookDrawGate(classLoader)
        hookTouch(captionClass)

        configuration.observe { snapshot ->
            if (!snapshot.externalHandleEffective) handler.post { resetAll() }
        }
        ModuleLog.info("EXTERNAL_HANDLE_INSTALLED")
    }

    /**
     * 绘制闸门：隐藏态下 caption 的整棵子树一帧都不画。
     *
     * 挂 `ViewGroup.dispatchDraw(Canvas)` 这一个点就够——它覆盖了
     * `mThreeButtonFrame` / `mBottomFrame` / `mControlView` / `mBottomHandleView`
     * 以及工具条模式下那四个按钮的全部绘制，不用逐个跟 vendor 的动画赛跑。
     *
     * 代价控制：`gateActive` 是 volatile 布尔，关闭本功能或处于显示态时
     * 只有一次读 + 一次 `proceed()`，不做类型判断、不做反射。
     */
    private fun hookDrawGate(classLoader: ClassLoader) {
        val target =
            runCatching {
                ViewGroup::class.java.getDeclaredMethod("dispatchDraw", Canvas::class.java)
                    .apply { isAccessible = true }
            }.getOrNull()
                ?: runCatching {
                    classLoader.loadClass("android.view.ViewGroup")
                        .getDeclaredMethod("dispatchDraw", Canvas::class.java)
                        .apply { isAccessible = true }
                }.getOrNull()
        if (target == null) {
            ModuleLog.warn("EXTERNAL_HANDLE_DRAW_TARGET_MISSING", null)
            return
        }
        module
            .hook(target)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.system.external_handle_draw")
            .intercept { chain ->
                if (gateActive() && captionType?.isInstance(chain.thisObject) == true) {
                    return@intercept null
                }
                chain.proceed()
            }
    }

    /**
     * 触摸信号：落到小窗底部就显示，抬手立即收回。
     *
     * 只观察、不消费事件——`chain.proceed()` 照常把事件交给原厂处理，
     * 所以点击关闭、拖拽缩放、底部上滑转迷你窗的行为完全不变。
     *
     * 续时覆盖整次触摸：拖动时手指很快离开底部条带，只在"还在条带内"的事件上续时
     * 会让横条在拖动中途消失。显示期内任何 DOWN/MOVE 都算数，抬手就收。
     */
    private fun hookTouch(captionClass: Class<*>) {
        val target =
            captionClass.declaredMethods.firstOrNull {
                it.name == "dispatchTouchEvent" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == MotionEvent::class.java
            }?.apply { isAccessible = true } ?: run {
                ModuleLog.warn("EXTERNAL_HANDLE_TOUCH_TARGET_MISSING", null)
                return
            }
        module
            .hook(target)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.system.external_handle_touch")
            .intercept { chain ->
                val caption = chain.thisObject as? View
                val event = chain.getArg(0) as? MotionEvent
                if (caption != null && event != null && configuration.snapshot.externalHandleEffective) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.ACTION_MOVE,
                        -> if (revealed || isInBottomBand(caption, event)) reveal(caption)

                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL,
                        -> if (revealed) hideNow()
                    }
                }
                chain.proceed()
            }
    }

    /** 闸门是否生效：功能开启且当前不处于显示态。 */
    private fun gateActive(): Boolean =
        !revealed && configuration.snapshot.externalHandleEffective

    private fun reveal(caption: View) {
        lastCaption = WeakReference(caption)
        if (!revealed) {
            revealed = true
            // 从"不画"切到"画"，必须请求重绘，否则要等下一帧别的原因触发。
            runCatching { caption.invalidate() }
            ModuleLog.debug("EXTERNAL_HANDLE_REVEALED") { "shown" }
        }
        // 兜底：真丢了抬手事件时 3 秒后自己收，不让横条挂在那儿。
        handler.removeCallbacks(safetyHide)
        handler.postDelayed(safetyHide, SAFETY_HIDE_MS)
    }

    private fun hideNow() {
        handler.removeCallbacks(safetyHide)
        if (!revealed) return
        revealed = false
        // 同理：从"画"切回"不画"，主动请求一帧把残影刷掉。
        lastCaption?.get()?.let { caption -> runCatching { caption.invalidate() } }
        ModuleLog.debug("EXTERNAL_HANDLE_HIDDEN") { "released" }
    }

    /**
     * 触点是否落在小窗底部条带内。
     *
     * 优先用 caption 自己的手柄区域判定（`isInHandleButtonRegion`，就是那根横条的可点范围）；
     * 取不到时退回"底部四分之一"这条粗判据，保证触摸总能唤出。
     */
    private fun isInBottomBand(caption: View, event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN &&
            event.actionMasked != MotionEvent.ACTION_MOVE
        ) {
            return false
        }
        val y = event.y
        if (y <= 0f || y > caption.height) return false
        val precise =
            runCatching {
                OemReflection.call(caption, "isInHandleButtonRegion", event.x.toInt(), y.toInt()) as? Boolean
            }.getOrNull()
        if (precise == true) return true
        return y >= caption.height * BOTTOM_BAND_RATIO
    }

    /** 设置关闭：解除闸门，绘制立即回到系统原本的样子。 */
    private fun resetAll() {
        handler.removeCallbacks(safetyHide)
        revealed = false
        lastCaption = null
        ModuleLog.info("EXTERNAL_HANDLE_RESTORED")
    }

    private companion object {
        /**
         * 兜底收回的超时（毫秒）。
         *
         * 正常路径抬手即收，这个值只在"抬手事件丢了"时生效，
         * 取一个明显长于一次拖拽的时长即可，不参与常规节奏。
         */
        const val SAFETY_HIDE_MS = 3000L

        /** 兜底判据：触点落在小窗底部这一段内即视为"摸到底部"。 */
        const val BOTTOM_BAND_RATIO = 0.75f
    }
}
