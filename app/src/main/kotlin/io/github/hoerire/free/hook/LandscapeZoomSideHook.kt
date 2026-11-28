package io.github.hoerire.free.hook

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import io.github.hoerire.free.config.LandscapeZoomSide
import io.github.hoerire.free.log.ModuleLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 横屏小窗位置：把系统算出来的落点固定到屏幕左侧或右侧。
 *
 * 落点由 OplusFlexibleTaskLayoutPolicy 算（calcLaunchBounds / layoutLaunchBounds /
 * layoutLaunchBoundsWhenDisplayChanged），这里只改算完之后那个 Rect 的横向位置，
 * 宽高、比例、动画一律不动。
 *
 * 屏幕范围取策略对象自己的 mScreenWidth / mScreenHeight / mWindowBounds：
 * 候选区列表经常是空的（没有别的窗口时系统直接走默认落点），靠它判横屏和边界不可靠。
 * 只在横屏（宽 > 高）接管；默认「跟随系统」时整段逻辑不执行。每条早退都留一行诊断。
 */
internal class LandscapeZoomSideHook(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val reported = HashSet<String>()
    private val density = android.content.res.Resources.getSystem().displayMetrics.density

    fun install(classLoader: ClassLoader) {
        val cls = runCatching { classLoader.loadClass(LAYOUT_POLICY_CLASS) }.getOrNull()
        if (cls == null) {
            ModuleLog.warn("ZOOM_SIDE_NO_CLASS", null)
            return
        }
        val methods =
            cls.declaredMethods.filter { method ->
                PLACEMENT_METHODS.contains(method.name) &&
                    method.parameterTypes.any { it == Rect::class.java }
            }
        if (methods.isEmpty()) {
            ModuleLog.warn("ZOOM_SIDE_NO_METHOD", null)
            return
        }
        methods.forEach { method ->
            runCatching {
                method.isAccessible = true
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("free.zoom_side." + method.name + "." + method.parameterCount)
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching { apply(chain.thisObject, chain.args, method.name) }
                        result
                    }
            }.onFailure { ModuleLog.warn("ZOOM_SIDE_HOOK_FAILED_" + method.name, it) }
        }
        ModuleLog.info("ZOOM_SIDE_HOOK_INSTALLED_" + methods.size)
    }

    /** 窗口的显示缩放：布局尺寸 × 它才是屏幕上看得见的宽高。取不到时按 1 处理。 */
    private fun taskScale(args: List<Any?>?): Float {
        args.orEmpty().forEach { arg ->
            if (arg == null) return@forEach
            val direct = OemReflection.callNoArg(arg, "getScale") as? Float
            if (direct != null && direct > 0.05f && direct <= 1.05f) return direct
            val wrapper = OemReflection.callNoArg(arg, "getWrapper")
            val ext = wrapper?.let { OemReflection.callNoArg(it, "getExtImpl") }
            val scale = ext?.let { OemReflection.callNoArg(it, "getScale") } as? Float
            if (scale != null && scale > 0.05f && scale <= 1.05f) return scale
        }
        return 1f
    }

    /** 把目标矩形摆到选定的那一侧；只在横屏、且屏幕参数可用时动手。 */
    private fun apply(policy: Any?, args: List<Any?>?, methodName: String) {
        val snapshot = configuration.snapshot
        if (!snapshot.zoomLandscapeSideActive) {
            report("ZOOM_SIDE_SKIP_INACTIVE_" + snapshot.zoomLandscapeSide.storedValue)
            return
        }
        val side = snapshot.zoomLandscapeSide
        val target = args.orEmpty().firstOrNull { it is Rect } as? Rect
        if (target == null) {
            report("ZOOM_SIDE_SKIP_NO_RECT_" + methodName)
            return
        }
        if (target.width() <= 0) {
            report("ZOOM_SIDE_SKIP_EMPTY_" + methodName)
            return
        }
        val screenW = OemReflection.readField(policy, "mScreenWidth") as? Int ?: 0
        val screenH = OemReflection.readField(policy, "mScreenHeight") as? Int ?: 0
        if (screenW <= 0 || screenH <= 0) {
            report("ZOOM_SIDE_SKIP_NO_SCREEN_" + methodName)
            return
        }
        if (screenW <= screenH) {
            report("ZOOM_SIDE_SKIP_PORTRAIT_" + screenW + "x" + screenH)
            return
        }
        val windowBounds = OemReflection.readField(policy, "mWindowBounds") as? Rect
        val left = windowBounds?.left?.takeIf { it >= 0 && it < screenW } ?: 0
        val right =
            windowBounds?.right?.takeIf { it > left && it <= screenW } ?: screenW
        // 离屏幕边缘留一点缝：物理边缘贴上去，圆角与阴影会被屏幕边裁掉。
        val margin = (SIDE_MARGIN_DP * density).toInt()
        // Rect 是**布局尺寸**，窗口还会按 scale 缩小显示；右侧落点必须用可见宽度，
        // 否则会按布局宽度把窗口推到内侧，看起来离屏幕边缘很远（实测差 500+px）。
        val scale = taskScale(args)
        val visibleWidth = (target.width() * scale).toInt().coerceAtLeast(1)
        val x =
            when (side) {
                LandscapeZoomSide.Left -> (left + margin)
                LandscapeZoomSide.Right ->
                    (right - visibleWidth - margin).coerceAtLeast(left)
                LandscapeZoomSide.FollowSystem -> return
            }
        if (target.left == x) {
            report("ZOOM_SIDE_ALREADY_" + side.name + "_x" + x)
            return
        }
        target.offsetTo(x, target.top)
        report("ZOOM_SIDE_APPLIED_" + side.name + "_x" + x + "_w" + screenW + "x" + screenH)
    }

    private fun report(code: String) {
        if (!reported.add(code)) return
        handler.post { ModuleLog.warn(code, null) }
    }

    private companion object {
        const val LAYOUT_POLICY_CLASS = "com.android.server.wm.OplusFlexibleTaskLayoutPolicy"

        /** 贴边时离物理屏幕边缘留的缝（dp）。 */
        const val SIDE_MARGIN_DP = 36f

        val PLACEMENT_METHODS =
            setOf("calcLaunchBounds", "layoutLaunchBounds", "layoutLaunchBoundsWhenDisplayChanged")
    }
}
