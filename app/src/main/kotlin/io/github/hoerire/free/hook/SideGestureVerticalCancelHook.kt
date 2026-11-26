package io.github.hoerire.free.hook

import android.annotation.SuppressLint
import android.graphics.PointF
import android.view.MotionEvent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.free.log.ModuleLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.abs

/**
 * 侧滑扩展：放行被厂商提前取消的竖向派生手势。
 *
 * 厂商 SideGestureDetector 对竖向位移用了**不对称**判据：
 *
 *   f = 起手点在当前点下方（上滑）? 1.0 : 2.5
 *   if (|dx| * f < |dy| && |dx| > 0 && |dy| > mTouchSlop) cancelGesture(...)
 *
 * 上滑只要纵向超过横向就取消，下拉要纵向超过横向 2.5 倍才取消。取消之后
 * mAllowGesture 归零，MOVE 不再投给 BackAnimationController，模块的距离判据
 * 永远走不到——表现为「上滑比下拉不灵敏」，调阈值没用。
 *
 * 这里只做两件事：
 * 1. 命中同一条竖向分支时跳过那次取消，让 MOVE 继续流向返回手势控制器；
 * 2. 抬手时补一次厂商自己的取消，保持「竖向派生手势不触发返回」的原语义。
 *
 * 不碰横向内滑（正常返回），也不碰长按取消（那条仍是厂商判据，原样放行）。
 */
@SuppressLint("PrivateApi")
internal class SideGestureVerticalCancelHook(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    /** 本次手势是否跳过一次竖向取消：抬手时要据此收场。 */
    @Volatile private var skipped = false

    fun install(classLoader: ClassLoader) {
        try {
            val type = classLoader.loadClass(DETECTOR_CLASS)
            val cancel =
                type.getDeclaredMethod(
                    "cancelGesture",
                    MotionEvent::class.java,
                    java.lang.Boolean::class.java,
                    java.lang.Boolean::class.java,
                ).apply { isAccessible = true }
            val onMotion =
                type.getDeclaredMethod("onMotionEventImpl", MotionEvent::class.java, Map::class.java)
                    .apply { isAccessible = true }
            val downPoint = field(type, "mDownPoint")
            val touchSlop = field(type, "mTouchSlop")
            val longPressTimeout = field(type, "mLongPressTimeout")

            // 1) 命中竖向分支时跳过取消。
            module
                .hook(cancel)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.systemui.side_gesture_vertical_pass")
                .intercept { chain ->
                    val event = chain.getArg(0) as? MotionEvent
                    if (!effective() || event == null ||
                        !isVerticalBranch(chain.thisObject, event, downPoint, touchSlop, longPressTimeout)
                    ) return@intercept chain.proceed()
                    if (!skipped) {
                        skipped = true
                        ModuleLog.debug("Free") { "SIDEBAR_VERTICAL_CANCEL_SKIPPED" }
                    }
                    null
                }

            // 2) 抬手收场：用厂商自己的取消入口收场，避免它在 UP 分支里发出一次返回。
            module
                .hook(onMotion)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.systemui.side_gesture_up")
                .intercept { chain ->
                    val event = chain.getArg(0) as? MotionEvent
                    if (event == null) return@intercept chain.proceed()
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> skipped = false
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (!skipped) return@intercept chain.proceed()
                            skipped = false
                            // 用厂商自己的取消入口收场：它会把 mAllowGesture 归零、cancelBack 并复位状态。
                            // 之后照常 proceed：厂商的 UP 分支看到 mAllowGesture=false 会走早退路径，
                            // 不会触发 triggerBack，同时仍保留它原有的抬手收尾。
                            runCatching {
                                cancel.invoke(
                                    chain.thisObject,
                                    event,
                                    java.lang.Boolean.FALSE,
                                    java.lang.Boolean.TRUE,
                                )
                            }.onFailure { ModuleLog.warn("SIDEBAR_VERTICAL_CANCEL_FAILED", it) }
                            ModuleLog.debug("Free") { "SIDEBAR_VERTICAL_UP_SETTLED" }
                        }
                    }
                    chain.proceed()
                }

            ModuleLog.info("SIDEBAR_VERTICAL_PASS_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            ModuleLog.warn("SIDEBAR_VERTICAL_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            ModuleLog.warn("SIDEBAR_VERTICAL_LINKAGE_FAILED", exception)
        }
    }

    private fun effective(): Boolean = configuration.snapshot.predictiveBackEffective

    /** 与厂商同一条判据：MOVE、未到长按、纵向压过横向（上滑 1.0 / 下拉 2.5）。 */
    private fun isVerticalBranch(
        instance: Any?,
        event: MotionEvent,
        downPoint: Field,
        touchSlop: Field,
        longPressTimeout: Field,
    ): Boolean {
        if (instance == null || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        val down = runCatching { downPoint.get(instance) as? PointF }.getOrNull() ?: return false
        val slop = runCatching { touchSlop.getFloat(instance) }.getOrDefault(0f)
        val timeout = runCatching { longPressTimeout.getInt(instance) }.getOrDefault(Int.MAX_VALUE)
        // 长按取消由厂商先判定，这里不接管。
        if (event.eventTime - event.downTime > timeout) return false
        val dx = abs(event.x - down.x)
        val dy = abs(event.y - down.y)
        if (dx <= 0f || dy <= slop) return false
        val scale = if (down.y > event.y) 1.0f else 2.5f
        return dx * scale < dy
    }

    private fun field(type: Class<*>, name: String): Field =
        type.getDeclaredField(name).apply { isAccessible = true }

    private companion object {
        const val DETECTOR_CLASS =
            "com.oplus.systemui.navigationbar.gesture.sidegesture.SideGestureDetector"
    }
}
