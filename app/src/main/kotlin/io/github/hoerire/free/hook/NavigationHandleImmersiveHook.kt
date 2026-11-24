package io.github.hoerire.free.hook

import android.annotation.SuppressLint
import android.graphics.Canvas
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
 * 全面屏手势指示条的「沉浸式隐藏」（设置项：小窗交互 → 横条沉浸）。
 * 与 [ExternalHandleHookInstaller] 共用同一个开关。
 *
 * ## 它解决什么
 *
 * ColorOS 自带的「隐藏手势指示条」是把整条导航栏连同它的 inset 一起撤掉，应用会因此
 * **重新布局、内容整体下沉**。这里不做那件事：inlay 与布局完全不动，
 * 只让那根横条**空闲时不绘制**，手指碰到屏幕底部时再出现。
 *
 * 于是：应用的位置从头到尾不变，横条平时看不见，要用时立刻出来。
 *
 * ## 实现
 *
 * 只碰 `OplusNavigationHandle`（ColorOS 手势导航那根横条的 View）的两个方法：
 *
 * - `onDraw(Canvas)`：空闲时不交给原方法，View 自身透明，等于没画。**不改可见性、
 *   不改布局参数、不碰 inset**，所以不会有任何重新测量或应用位移。
 * - `handleValidTouchEvent(MotionEvent)`：ColorOS 自己把底部手势区的触摸喂给这里
 *   （`SideGestureDetector` 内两条路径都调它），因此它就是"手指碰到底部"的准确信号。
 *   收到就显示并 `invalidate()`，抬手后延时 [HIDE_DELAY_MS] 再收回空闲态。
 *
 * ## 为什么不改 `setAlpha`
 *
 * `OplusNavigationHandle.setAlpha` 被厂商重写过、并且与长按/跟手动画共用同一条属性，
 * 从外部改它会被动画下一次采样覆盖。`onDraw` 不吃这套：它每帧都会被问一次，
 * 判定结果永远是最新的。
 *
 * ## 与官方开关的关系
 *
 * 官方「隐藏手势指示条」开着时，这条 View 通常根本不会创建或绘制，本钩子自然不生效，
 * 也就不会有"又隐藏一次"的副作用。三分键导航下没有这个 View，同样不生效。
 */
@SuppressLint("PrivateApi")
internal class NavigationHandleImmersiveHook(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val installed = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    /** 当前是否处于「显示」状态：触摸期间与触摸后的短暂停留期内为 true。 */
    @Volatile private var revealed = false

    /** 最后一次被触摸的手柄；延时收回时用它重绘。弱引用，不拖住已被销毁的窗口。 */
    private var lastHandle: WeakReference<View>? = null

    private val hideAction =
        Runnable {
            revealed = false
            lastHandle?.get()?.invalidate()
        }

    fun install(classLoader: ClassLoader) {
        val handleClass = OemReflection.loadOrNull(classLoader, HANDLE_CLASS)
        if (handleClass == null) {
            ModuleLog.info("NAV_HANDLE_IMMERSIVE_CLASS_MISSING")
            return
        }
        if (!installed.compareAndSet(false, true)) return

        val draw =
            runCatching {
                handleClass.getDeclaredMethod("onDraw", Canvas::class.java)
                    .apply { isAccessible = true }
            }.getOrNull()
        val touch =
            runCatching {
                handleClass.getDeclaredMethod("handleValidTouchEvent", MotionEvent::class.java)
                    .apply { isAccessible = true }
            }.getOrNull()
        if (draw == null && touch == null) {
            ModuleLog.warn("NAV_HANDLE_IMMERSIVE_TARGET_UNAVAILABLE", null)
            return
        }
        if (draw != null) hookDraw(draw)
        if (touch != null) hookTouch(touch)
        ModuleLog.info("NAV_HANDLE_IMMERSIVE_INSTALLED")
    }

    /**
     * 空闲时不绘制。
     *
     * 返回 null 表示"这一次调用什么都不做"，对返回 void 的 `onDraw` 就是跳过一次绘制；
     * 手上保留的脏区域由 [View.invalidate] 负责刷新，不会留下残影。
     */
    private fun hookDraw(draw: java.lang.reflect.Method) {
        module
            .hook(draw)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.systemui.nav_handle_immersive_draw")
            .intercept { chain ->
                if (!revealed && configuration.snapshot.externalHandleEffective) {
                    return@intercept null
                }
                chain.proceed()
            }
    }

    /** 手指碰到底部手势区就显示，并重新计时收回。 */
    private fun hookTouch(touch: java.lang.reflect.Method) {
        module
            .hook(touch)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.systemui.nav_handle_immersive_touch")
            .intercept { chain ->
                val handle = chain.thisObject as? View
                if (handle != null && configuration.snapshot.externalHandleEffective) {
                    reveal(handle)
                }
                chain.proceed()
            }
    }

    private fun reveal(handle: View) {
        lastHandle = WeakReference(handle)
        if (!revealed) {
            revealed = true
            handle.invalidate()
        }
        handler.removeCallbacks(hideAction)
        handler.postDelayed(hideAction, HIDE_DELAY_MS)
    }

    private companion object {
        /** ColorOS 手势导航的家条 View。 */
        const val HANDLE_CLASS =
            "com.oplus.systemui.navigationbar.gesture.sidegesture.OplusNavigationHandle"

        /**
         * 抬手后停留多久再收回空闲态（毫秒）。
         *
         * 太短会在手指刚离开时闪一下；太长则上滑回桌面之后横条还挂在那儿。
         * 1.2 秒与系统自己的操作提示节奏相当。
         */
        const val HIDE_DELAY_MS = 1200L
    }
}
