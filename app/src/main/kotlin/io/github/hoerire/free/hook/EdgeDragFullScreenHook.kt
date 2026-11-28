package io.github.hoerire.free.hook

import android.graphics.Point
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.free.log.ModuleLog

/**
 * 「边缘拖动」：把小窗拖到屏幕左右边缘时变成全屏。
 *
 * 系统现状（反编译确认，ColorOS 17）：
 * - 窗口底部有缩放区，下拉到底部「最大化区域」会变全屏（`FlexibleTaskScaleManager#isInMaxingRegion`）；
 * - 左右两侧的缩放区只有在全景工作台 / 小屏多比例机型上才生成
 *   （`FlexibleCaptionViewTouchRegionMgr#updateTouchRegionInFlexible` 的两个分支），
 *   本机为空 —— 所以「左右两侧是空的、拖不动」。
 *
 * 这里补三处，都受「系统增强 → 边缘拖动」控制：
 * 1. `updateTouchRegion`：把左右各 [EDGE_DP] 的一条并进 caption 的可触摸区域（否则触摸到不了）；
 * 2. `getFlexibleTaskActionFlag`：原始判定为「没碰到任何区」时，把左右这两条改判为 5 / 7
 *    （左右边缘缩放），侧边因此可以拖动缩放；
 * 3. `isInMaxingRegion`：**只要在往外拉**（手指离屏幕中心的距离变大）就判定为最大化，
 *    松手即走系统自己的 `fullScreenByScale`。往内拉不触发任何动作。
 *
 * 不改窗口尺寸、不自己执行全屏：只把系统原本就有的判定放开，动作仍由系统完成。
 */
internal class EdgeDragFullScreenHook(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val density = android.content.res.Resources.getSystem().displayMetrics.density

    /** 本次拖动的起点横坐标（按任务记）：用来算「向外拉了多少」。 */
    private val startPoints = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    /** 每个任务最后一次被询问的时刻：超过 GESTURE_GAP_MS 视为新一次拖动。 */
    private val lastSeen = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** 本次拖动的是哪一侧：5 = 左边缘，7 = 右边缘（由动作判定钩子写入）。 */
    private val sides = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    /** 每个 caption 区域管理器当前的窗口缩放：分区域的尺寸必须除以它（见系统同名算法）。 */
    private val regionScales = java.util.concurrent.ConcurrentHashMap<Any, Float>()

    fun install(classLoader: ClassLoader) {
        val regionClass = runCatching { classLoader.loadClass(TOUCH_REGION_MGR) }.getOrNull()
        val scaleClass = runCatching { classLoader.loadClass(SCALE_MANAGER) }.getOrNull()
        if (regionClass == null || scaleClass == null) {
            ModuleLog.warn("EDGE_DRAG_NO_CLASS", null)
            return
        }
        val installed = hookActionFlag(regionClass) + hookMaxingRegion(scaleClass) + hookTouchRegion(regionClass)
        if (installed == 0) ModuleLog.warn("EDGE_DRAG_NO_METHOD", null) else ModuleLog.install("EDGE_DRAG_INSTALLED_" + installed)
    }

    /** 侧边空白区改判为「左/右边缘缩放」。 */
    private fun hookActionFlag(regionClass: Class<*>): Int {
        val method = OemReflection.findMethodByArity(regionClass, "getFlexibleTaskActionFlag", 3) ?: return 0
        return runCatching {
            method.isAccessible = true
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.system.edge_drag.flag")
                .intercept { chain ->
                    val original = chain.proceed() as? Int ?: 0
                    if (!configuration.snapshot.edgeDragFullScreenActive) {
                        return@intercept original
                    }
                    val x = chain.getArg(0) as? Int ?: return@intercept original
                    val y = chain.getArg(1) as? Int ?: return@intercept original
                    val caption = chain.getArg(2) ?: return@intercept original
                    val width = (OemReflection.callNoArg(caption, "getWidth") as? Int) ?: 0
                    val height = (OemReflection.callNoArg(caption, "getHeight") as? Int) ?: 0
                    // 记住这次拖的是哪一侧：4/5 = 左，6/7 = 右。
                    // 横屏窗口小，实际抓到的是角上的 4/6 而不是侧边 5/7——不记的话
                    // 方向判断只能退回「离屏幕中心」，横屏就又变得很迟钝（实测）。
                    val captionTask = OemReflection.readField(caption, "mTask")
                    val sideTaskId =
                        captionTask?.let { OemReflection.readField(it, "mTaskId") as? Int } ?: -1
                    when (original) {
                        4, 5 -> if (sideTaskId >= 0) sides[sideTaskId] = 5
                        6, 7 -> if (sideTaskId >= 0) sides[sideTaskId] = 7
                    }
                    if (original != 0) return@intercept original
                    if (width <= 0 || height <= 0 || x < 0 || y < 0) return@intercept original
                    // 顶部留给横条/按钮（固定 dp，按缩放折算）。
                    // 不用比例：横屏窗口矮，按比例会把侧边可拖区吃掉一大块。
                    val scaleForTop = regionScales[chain.thisObject] ?: 1f
                    val topBar = ((TOP_BAR_DP * density) / scaleForTop).toInt()
                    if (y < topBar) return@intercept original
                    // 侧边宽度必须按窗口缩放折算：系统自己的区域也是 `dp * density / scale`。
                    // 不折算的话，横屏（scale 小）实际只有一半宽，横竖屏手感不一致（实测）。
                    val scale = regionScales[chain.thisObject] ?: 1f
                    val edge = ((EDGE_DP * density) / scale).toInt()
                    if (x <= edge || x >= width - edge) {
                        if (io.github.hoerire.free.log.VerboseLog.enabled) {
                            ModuleLog.debug("Free") {
                                "EDGE_DRAG_FLAG_x" + x + "_y" + y + "_w" + width + "_h" + height
                            }
                        }
                        val side = if (x <= edge) 5 else 7
                        return@intercept side
                    }
                    original
                }
            1
        }.getOrElse {
            ModuleLog.warn("EDGE_DRAG_FLAG_HOOK_FAILED", it)
            0
        }
    }

    /** 左右边缘也算「最大化区域」，不必先滑到屏幕下方。 */
    private fun hookMaxingRegion(scaleClass: Class<*>): Int {
        val method =
            OemReflection.findMethodByArity(scaleClass, "isInMaxingRegion", 3) ?: return 0
        return runCatching {
            method.isAccessible = true
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.system.edge_drag.maxing")
                .intercept { chain ->
                    val original = chain.proceed() as? Boolean ?: false
                    if (original || !configuration.snapshot.edgeDragFullScreenActive) {
                        return@intercept original
                    }
                    val width = chain.getArg(0) as? Int ?: return@intercept original
                    val point = chain.getArg(2) as? Point ?: return@intercept original
                    val task = OemReflection.readField(chain.thisObject, "mTask")
                    if (width <= 0) return@intercept original
                    val taskId = task?.let { OemReflection.readField(it, "mTaskId") as? Int } ?: -1
                    val center = width / 2f
                    // 一次拖动 = 连续的一串询问；间隔超过 GESTURE_GAP_MS 视为重新起手。
                    val now = android.os.SystemClock.uptimeMillis()
                    val previousAt = lastSeen[taskId] ?: 0L
                    if (now - previousAt > GESTURE_GAP_MS) startPoints[taskId] = point.x
                    lastSeen[taskId] = now
                    val startX = startPoints[taskId] ?: point.x
                    // 只看「被拖的那一侧」是否朝屏幕外走：横向屏幕左右两端到中心距离差很大，
                    // 用「离屏幕中心远了没有」判会一边灵一边不灵（实测横屏）。
                    val side = sides[taskId] ?: 0
                    val movedOut: Float = when (side) {
                        5 -> (startX - point.x).toFloat()
                        7 -> (point.x - startX).toFloat()
                        else ->
                            kotlin.math.abs(point.x - center) -
                                kotlin.math.abs(startX - center)
                    }
                    // 口径：随便向外拉一点点就算（默认 12dp），松手即全屏。
                    var hit = movedOut >= OUTWARD_DP * density
                    // 兜底：已经贴到屏幕左右边缘也算（旧行为，竖屏窗口本来就靠边）。
                    val margin =
                        (OemReflection.readField(chain.thisObject, "mMaxingHotRegionMargin") as? Int)
                            ?: (EDGE_DP * density).toInt()
                    if (!hit && margin > 0 && width > margin &&
                        point.x >= 0 && (point.x <= margin || point.x >= width - margin)
                    ) {
                        hit = true
                    }
                    if (io.github.hoerire.free.log.VerboseLog.enabled) {
                        ModuleLog.debug("Free") {
                            "EDGE_DRAG_EVAL_x" + point.x + "_sx" + startX + "_w" + width +
                                "_m" + movedOut.toInt() + "_hit" + (if (hit) 1 else 0)
                        }
                    }
                    hit
                }
            1
        }.getOrElse {
            ModuleLog.warn("EDGE_DRAG_MAXING_HOOK_FAILED", it)
            0
        }
    }

    /**
     * 把左右两条边加进 caption 的可触摸区域。
     *
     * 这一步不能省：系统只把「已注册的缩放区」并进 mRegion，左右边在本机为空，
     * 触摸根本到不了 caption，后面改判 action flag 也没机会执行。
     */
    private fun hookTouchRegion(regionClass: Class<*>): Int {
        val method = OemReflection.findMethodByArity(regionClass, "updateTouchRegion", 7) ?: return 0
        return runCatching {
            method.isAccessible = true
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.system.edge_drag.region")
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!configuration.snapshot.edgeDragFullScreenActive) return@intercept result
                    runCatching {
                        val height = chain.getArg(0) as? Int ?: return@intercept result
                        val width = chain.getArg(1) as? Int ?: return@intercept result
                        val caption = chain.getArg(5) ?: return@intercept result
                        if (width <= 0 || height <= 0) return@intercept result
                        val region =
                            OemReflection.readField(chain.thisObject, "mRegion") as? android.graphics.Region
                                ?: return@intercept result
                        val scale = (chain.getArg(4) as? Float)?.takeIf { it > 0f } ?: 1f
                        regionScales[chain.thisObject] = scale
                        val band = ((EDGE_DP * density) / scale).toInt()
                        val top = ((TOP_BAR_DP * density) / scale).toInt()
                        if (band <= 0 || top >= height) return@intercept result
                        val merged = android.graphics.Region(region)
                        merged.op(android.graphics.Region(0, top, band, height), android.graphics.Region.Op.UNION)
                        merged.op(
                            android.graphics.Region(width - band, top, width, height),
                            android.graphics.Region.Op.UNION,
                        )
                        if (io.github.hoerire.free.log.VerboseLog.enabled) {
                            ModuleLog.debug("Free") {
                                "EDGE_DRAG_REGION_w" + width + "_h" + height + "_band" + band
                            }
                        }
                        OemReflection.call(caption, "updateTouchableRegion", merged)
                    }
                    result
                }
            1
        }.getOrElse {
            ModuleLog.warn("EDGE_DRAG_REGION_HOOK_FAILED", it)
            0
        }
    }

    private companion object {
        const val TOUCH_REGION_MGR = "com.android.server.wm.FlexibleCaptionViewTouchRegionMgr"
        const val SCALE_MANAGER = "com.android.server.wm.FlexibleTaskScaleManager"

        /** 侧边可拖区域宽度（dp）：太宽会抢走应用内容边缘的点击。 */
        const val EDGE_DP = 18f

        /** 顶部留给横条与按钮的高度（dp）：固定值，按窗口缩放折算，别用比例。 */
        const val TOP_BAR_DP = 40f

        /** 向外拉这么多 dp 就判定为「要全屏」：随便拉一点点即可，不用拖到屏幕边缘。 */
        const val OUTWARD_DP = 12f

        /** 两次询问间隔超过这么多毫秒，视为新的一次拖动。 */
        const val GESTURE_GAP_MS = 400L

    }
}