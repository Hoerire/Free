package io.github.hoerire.free.hook

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.free.log.ModuleLog
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 小窗的底部横条与顶部三点（设置项：小窗交互 → 沉浸式隐藏指示条）。
 *
 * ## 行为
 *
 * 与全面屏手势指示条（[NavigationHandleImmersiveHook]）完全一致：
 *
 * - **平时一律隐藏**，不看窗口大小、不看什么形态、不看谁触发的；
 * - **触摸小窗底部就显示**，抬手后 [HIDE_DELAY_MS] 收回；
 * - 隐藏只改可见性，**不动布局、不动 inset**，小窗位置与内容不变。
 *
 * ## 实现
 *
 * 全部复用系统自身的显隐入口，不新建 View、不改布局参数：
 *
 * - `FlexibleCaptionView.updateThreeButtonVisibility(boolean)`：系统的"显示操作区"决策点，
 *   内部同时设置 `mThreeButtonFrame` / `mHandleView` / `mFullView` / `mCloseView` /
 *   `mBottomFrame`。系统每次请求显示都被改写成 `false`，所以平时恒为隐藏。
 * - `FlexibleCaptionView.updateBottomHandleViewVisible(boolean)`：单独的底部手柄入口，
 *   同样按上述规则改写。它只维护 `mHasShowBottomView` 与弹簧状态，不写可见性。
 * - `FlexibleCaptionView.dispatchTouchEvent(MotionEvent)`：触摸信号来源。落在底部条带内
 *   就显示，并重新计时收回。
 * - `FlexibleCaptionView.onLayout(...)`：布局完成后确保处于隐藏态（系统可能在布局链路里
 *   重新把操作区拉起来）。
 *
 * ## 为什么"看不见"却"照样好使"
 *
 * 收起用的是 `setVisibility(4)`（INVISIBLE），三个 View 仍参与布局，只是不绘制。
 * 而命中判定**不看可见性**：`isInHandleButtonRegion()` / `getFlexibleTaskActionFlag()`
 * 只问 `mFlexibleCaptionViewRegionMgr` 的几何区域，那些区域由 `updateTouchRegion()`
 * 在 `onLayout` / `relayout` / 迷你窗切换时按当前宽高重算。
 * 所以点横条关窗、横条上滑转迷你窗、三点区域响应全部照旧。
 *
 * **不要把 `setVisibility(4)` 改成 GONE，也不要摘掉触摸区域**——那会把功能一起废掉。
 */
@SuppressLint("PrivateApi")
internal class ExternalHandleHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val installed = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    /** 当前是否处于「显示」状态：触摸底部之后、收回之前为 true。 */
    @Volatile private var revealed = false

    /** 最后一次被触摸的 caption；延时收回时用它刷新。弱引用，不拖住已销毁的窗口。 */
    private var lastCaption: WeakReference<View>? = null

    private val hideAction =
        Runnable {
            revealed = false
            val caption = lastCaption?.get()
            if (caption != null && configuration.snapshot.externalHandleEffective) {
                hideNow(caption)
            }
        }

    fun install(classLoader: ClassLoader) {
        val captionClass = OemReflection.loadOrNull(classLoader, OemClasses.FLEXIBLE_CAPTION_VIEW)
        if (captionClass == null) {
            ModuleLog.warn("EXTERNAL_HANDLE_CAPTION_CLASS_MISSING", null)
            return
        }
        if (!installed.compareAndSet(false, true)) return

        hookThreeButton(captionClass)
        hookBottomHandle(captionClass)
        hookTouch(captionClass)
        hookLayout(captionClass)

        configuration.observe { snapshot ->
            if (!snapshot.externalHandleEffective) handler.post { resetAll() }
        }
        ModuleLog.info("EXTERNAL_HANDLE_INSTALLED")
    }

    /**
     * 操作区（三点 + 横条）的显示决策。
     *
     * 系统一直在按自己的时机请求显示；只要不在触摸显示期内就一律改传 `false`，
     * 于是横条与三点恒为隐藏，与窗口大小、窗口形态无关。
     */
    private fun hookThreeButton(captionClass: Class<*>) {
        val target =
            captionClass.declaredMethods.firstOrNull {
                it.name == "updateThreeButtonVisibility" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == java.lang.Boolean.TYPE
            }?.apply { isAccessible = true } ?: run {
                ModuleLog.warn("EXTERNAL_HANDLE_THREE_BUTTON_MISSING", null)
                return
            }
        module
            .hook(target)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.system.external_handle_three_button")
            .intercept { chain ->
                val requested = chain.getArg(0) as? Boolean
                val caption = chain.thisObject as? View
                if (requested != true || caption == null ||
                    !configuration.snapshot.externalHandleEffective || revealed
                ) {
                    return@intercept chain.proceed()
                }
                chain.proceed(arrayOf<Any?>(false))
            }
    }

    /** 底部手柄自身的显隐：同一套规则。 */
    private fun hookBottomHandle(captionClass: Class<*>) {
        val target =
            captionClass.declaredMethods.firstOrNull {
                it.name == "updateBottomHandleViewVisible" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == java.lang.Boolean.TYPE
            }?.apply { isAccessible = true } ?: return
        module
            .hook(target)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.system.external_handle_bottom_visible")
            .intercept { chain ->
                val requested = chain.getArg(0) as? Boolean
                val caption = chain.thisObject as? View
                if (requested != true || caption == null ||
                    !configuration.snapshot.externalHandleEffective || revealed
                ) {
                    return@intercept chain.proceed()
                }
                chain.proceed(arrayOf<Any?>(false))
            }
    }

    /**
     * 触摸信号：落在小窗底部条带内就显示横条。
     *
     * 只观察、不消费事件——`chain.proceed()` 照常把事件交给原厂处理，
     * 所以点击关闭、拖拽缩放、底部上滑转迷你窗的行为完全不变。
     */
    private fun hookTouch(captionClass: Class<*>) {
        val target =
            captionClass.declaredMethods.firstOrNull {
                it.name == "dispatchTouchEvent" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == MotionEvent::class.java
            }?.apply { isAccessible = true } ?: return
        module
            .hook(target)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.system.external_handle_touch")
            .intercept { chain ->
                val caption = chain.thisObject as? View
                val event = chain.getArg(0) as? MotionEvent
                if (caption != null && event != null &&
                    configuration.snapshot.externalHandleEffective &&
                    isInBottomBand(caption, event)
                ) {
                    reveal(caption)
                }
                chain.proceed()
            }
    }

    /**
     * 布局入口：布局完成后确保处于隐藏态。
     *
     * 系统的布局链路里可能重新拉起操作区（迷你窗切换、主题变化都会走到）；
     * 这里补一次隐藏，保证"平时一律隐藏"。
     */
    private fun hookLayout(captionClass: Class<*>) {
        val target =
            captionClass.declaredMethods.firstOrNull {
                it.name == "onLayout" &&
                    it.parameterCount == 5 &&
                    it.parameterTypes[0] == java.lang.Boolean.TYPE &&
                    it.parameterTypes.drop(1).all { t -> t == Integer.TYPE }
            }?.apply { isAccessible = true } ?: return
        module
            .hook(target)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.system.external_handle_layout")
            .intercept { chain ->
                val result = chain.proceed()
                val caption = chain.thisObject as? View
                if (caption != null && !revealed &&
                    configuration.snapshot.externalHandleEffective
                ) {
                    hideNow(caption)
                }
                result
            }
    }

    private fun reveal(caption: View) {
        lastCaption = WeakReference(caption)
        if (!revealed) {
            revealed = true
            runCatching { OemReflection.call(caption, "updateThreeButtonVisibility", true) }
            ModuleLog.debug("EXTERNAL_HANDLE_REVEALED") { "revealed" }
        }
        handler.removeCallbacks(hideAction)
        handler.postDelayed(hideAction, HIDE_DELAY_MS)
    }

    private fun hideNow(caption: View) {
        runCatching { OemReflection.call(caption, "updateThreeButtonVisibility", false) }
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

    /** 设置关闭：把已经收起的横条与三点还回去。 */
    private fun resetAll() {
        handler.removeCallbacks(hideAction)
        revealed = false
        val caption = lastCaption?.get()
        if (caption != null) {
            runCatching { OemReflection.call(caption, "updateThreeButtonVisibility", true) }
        }
        lastCaption = null
        ModuleLog.info("EXTERNAL_HANDLE_RESTORED")
    }

    private companion object {
        /**
         * 抬手后停留多久再收回（毫秒）。
         *
         * 太短会在手指刚离开时闪一下；太长则小窗上滑之后横条还挂在那儿。
         * 与全面屏手势指示条取同一个节奏。
         */
        const val HIDE_DELAY_MS = 1200L

        /** 兜底判据：触点落在小窗底部这一段内即视为"摸到底部"。 */
        const val BOTTOM_BAND_RATIO = 0.75f
    }
}
