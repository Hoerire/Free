package io.github.hoerire.free.platform.coloros

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.hoerire.free.hook.OemClasses
import io.github.hoerire.free.hook.OemReflection

/**
 * 「自动贴边」的系统侧执行器。
 *
 * 语义：名单里的应用**全屏**时**上滑返回桌面**，自动收成贴边浮标。
 * 已经是小窗 / 迷你 / 贴边的不接管（用户自己拖到边缘就能收）。所有应用一视同仁，不区分游戏。
 *
 * 入口只有「桌面成为前台」这一次前台切换（由 AutoDockHook 判定），切到别的应用、锁屏、
 * 应用互跳（登录/授权/分享）一律不接管。这一刻应用任务还在任务树里、bounds 仍是全屏，
 * 正好可以拿它当收窗动画的起点。
 *
 * 转换分两段：先把任务改成小窗形态，形态一落地立刻收成贴边浮标。转换有两个系统入口，
 * 按轮次交替尝试：
 * - switchFocusedTaskToZoom(Task)：把**仍聚焦**的全屏任务原地变小窗（模块的侧滑切窗一直用它）；
 * - fullscreenToZoom(Task)：按任务号转，不要求焦点，用于回桌面那一刻任务已经失去焦点的情况。
 * **不要**给转换加「系统正在播动画就放弃」的硬闸门：回桌面转场本身就会让某些动画标志为真，
 * 那会把整条链路直接掐死（2026112813 就是这么失效的）。
 *
 * 每一步的早退都留诊断码，排查「设了没反应」只看日志即可。
 */
internal class ColorOsAutoDock(
    private val controller: Any,
    private val logger: (String, Throwable?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val reported = HashSet<String>()

    /** 正在收的任务：同一任务短时间内只接管一次，连按两次返回桌面不会收两遍。 */
    private var pendingTaskId = -1
    private var pendingAt = 0L

    /** 回到桌面后调用（主线程）：按包名找到那个应用的全屏任务并收成浮标。 */
    fun dockOnReturnHome(packageName: String) {
        val tasks = tasksOfFocusedDisplay()
        if (tasks == null) {
            logOnce("AUTO_DOCK_NO_TASKS")
            return
        }
        val mine = tasks.filter { packageOfTask(it) == packageName }
        if (mine.isEmpty()) {
            logOnce("AUTO_DOCK_NO_TASK_" + packageName + "_" + describe(tasks))
            return
        }
        val task =
            mine.firstOrNull { !isFlexibleScenario(it) }
                ?: run {
                    logger("AUTO_DOCK_SKIP_WINDOW_" + packageName, null)
                    null
                }
        if (task == null) return
        val taskId = taskIdOf(task)
        if (isPending(taskId)) {
            logger("AUTO_DOCK_PENDING_" + packageName, null)
            return
        }
        markPending(taskId)
        // 此刻窗口仍是全屏，bounds 就是收窗动画的起点。
        val startBounds =
            runCatching {
                OemReflection.requireMethod(task.javaClass, "getBounds", 0).invoke(task) as? Rect
            }.getOrNull()
        dock(task, packageName, startBounds)
    }

    private fun dock(task: Any, packageName: String, startBounds: Rect?) {
        val state = ColorOsFlexibleWindowState.zoomStateOf(task)
        if (state == ColorOsFlexibleWindowState.MINI || state == ColorOsFlexibleWindowState.READY) {
            logger("AUTO_DOCK_ALREADY_" + state + "_" + packageName, null)
            return
        }
        if (isFlexibleScenario(task)) {
            logger("AUTO_DOCK_SKIP_WINDOW_" + packageName, null)
            return
        }
        logger("AUTO_DOCK_DOCKING_" + packageName, null)
        convert(task, packageName, startBounds, waited = 0L, invoked = 0)
    }

    /**
     * 转成小窗并盯住形态。每 40ms 轮一次，转换入口按 [INVOKE_AT] 的节奏重试：
     * 回桌面转场期间系统会拒掉或吞掉这次转换，只调一次就会漏。
     */
    private fun convert(
        task: Any,
        packageName: String,
        startBounds: Rect?,
        waited: Long,
        invoked: Int,
    ) {
        val state = ColorOsFlexibleWindowState.zoomStateOf(task)
        if (state == ColorOsFlexibleWindowState.ORDINARY ||
            state == ColorOsFlexibleWindowState.MINI ||
            state == ColorOsFlexibleWindowState.READY
        ) {
            logger("AUTO_DOCK_ZOOMED_" + packageName + "_w" + waited, null)
            exitToFloat(task, packageName, startBounds)
            return
        }
        if (waited >= MAX_WAIT_MS || invoked >= MAX_INVOKES) {
            logger("AUTO_DOCK_ZOOM_TIMEOUT_" + packageName + "_z" + state + "_i" + invoked, null)
            return
        }
        if (invoked < INVOKE_AT.size && waited >= INVOKE_AT[invoked]) {
            val focused = isTaskFocused(task) && isTaskVisible(task)
            val how = if (focused && invoked % 2 == 0) "switch" else "task"
            val accepted = if (how == "switch") switchFocusedToZoom(task) else fullscreenToZoom(task)
            logger(
                "AUTO_DOCK_CONVERT_" + how + "_" + (if (accepted) 1 else 0) + "_" + packageName +
                    "_z" + state + "_w" + waited,
                null,
            )
            handler.postDelayed(
                { convert(task, packageName, startBounds, waited + STEP_MS, invoked + 1) },
                STEP_MS,
            )
            return
        }
        handler.postDelayed(
            { convert(task, packageName, startBounds, waited + STEP_MS, invoked) },
            STEP_MS,
        )
    }

    /** 「全屏 → 小窗」：目标此刻仍是聚焦的全屏任务时，用系统自己的就地切换。 */
    private fun switchFocusedToZoom(task: Any): Boolean {
        val result =
            runCatching {
                val service = flexibleService() ?: return@runCatching "no_service"
                val method =
                    OemReflection.findMethodByArity(service.javaClass, "switchFocusedTaskToZoom", 1)
                        ?: return@runCatching "no_method"
                method.isAccessible = true
                method.invoke(service, task)
                "ok"
            }
        result.exceptionOrNull()?.let { logger("AUTO_DOCK_SWITCH_ERROR", it as? RuntimeException) }
        return result.getOrNull() == "ok"
    }

    /** 「全屏 → 小窗」：按 Task 转，不要求焦点（已经回到桌面时走这条）。 */
    private fun fullscreenToZoom(task: Any): Boolean {
        val result =
            runCatching {
                val service = flexibleService() ?: return@runCatching "no_service"
                val method =
                    OemReflection.findMethod(service.javaClass, "fullscreenToZoom", task.javaClass)
                        ?: OemReflection.findMethodByArity(service.javaClass, "fullscreenToZoom", 1)
                        ?: return@runCatching "no_method"
                method.isAccessible = true
                method.invoke(service, task)
                "ok"
            }
        result.exceptionOrNull()?.let { logger("AUTO_DOCK_ZOOM_ERROR", it as? RuntimeException) }
        val reason = result.getOrNull() as? String
        if (reason != "ok") {
            logOnce("AUTO_DOCK_ZOOM_REJECT_" + (reason ?: "exception"))
            return false
        }
        return true
    }

    private fun flexibleService(): Any? =
        OemReflection.readField(controller, "mFlexibleWindowManagerService")

    private fun exitToFloat(task: Any, packageName: String, startBounds: Rect?) {
        if (runCatching { OemReflection.call(controller, "isFlexibleTaskAndHasCaption", task) as? Boolean == true }
                .getOrDefault(false) == false
        ) {
            logger("AUTO_DOCK_NO_CAPTION_" + packageName, null)
        }
        val result = runCatching { submitFloatExit(task, startBounds) }
        result.exceptionOrNull()?.let { logger("AUTO_DOCK_EXIT_ERROR", it as? RuntimeException) }
        logger(
            (if (result.getOrDefault(false)) "AUTO_DOCKED_" else "AUTO_DOCK_FAILED_") + packageName,
            null,
        )
    }

    private fun submitFloatExit(task: Any, startBounds: Rect?): Boolean {
        val loader = controller.javaClass.classLoader
        val strategyClass =
            Class.forName(OemClasses.EXIT_FLEXIBLE_TASK_TO_FLOAT_STRATEGY, false, loader)
        val taskClass = Class.forName(OemClasses.TASK, false, loader)
        val constructor =
            strategyClass.declaredConstructors.firstOrNull { candidate ->
                candidate.parameterTypes.size == 3 &&
                    candidate.parameterTypes[0] == taskClass &&
                    candidate.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                    candidate.parameterTypes[2] == Rect::class.java
            } ?: return false
        constructor.isAccessible = true
        // 恢复尺寸取窗口此刻的 bounds：用户点浮标展开时按它还原。
        val bounds =
            runCatching {
                OemReflection.requireMethod(task.javaClass, "getBounds", 0).invoke(task) as? Rect
            }.getOrNull() ?: Rect()
        val strategy = constructor.newInstance(task, false, bounds)
        // 动画起点：Home 那一刻窗口还在全屏，从这里收才是一段连续的收缩。
        if (startBounds != null && !startBounds.isEmpty) {
            strategyClass.methods
                .firstOrNull { it.name == "setStartBounds" && it.parameterTypes.size == 1 }
                ?.let { setter ->
                    setter.isAccessible = true
                    runCatching { setter.invoke(strategy, Rect(startBounds)) }
                }
        }
        strategyClass.methods
            .firstOrNull { it.name == "setSourceFrom" && it.parameterTypes.size == 1 }
            ?.let { setter ->
                setter.isAccessible = true
                runCatching { setter.invoke(strategy, 1) }
            }
        // **不要** setNoAnimation(true)：无动画分支在任务还没进浮动列表时走的是
        // resetFlexibleTaskToFullScreen（FlexibleTaskController#exitFlexibleTask 的
        // flexibleCustomTransition == 16 分支），根本不会生成浮标；真正建浮标的是
        // 动画分支里的 FloatHandleController.addFloatHandle。2026112813 失效的第二个原因。
        val baseClass = Class.forName(OemClasses.ABS_FLEXIBLE_TASK_EXIT_STRATEGY, false, loader)
        val inner = controller.javaClass.getMethod("exitFlexibleTaskWindowInnerLocked", baseClass)
        inner.isAccessible = true
        inner.invoke(controller, strategy)
        return true
    }

    private fun isPending(taskId: Int): Boolean {
        if (taskId < 0) return false
        return taskId == pendingTaskId &&
            SystemClock.uptimeMillis() - pendingAt < PENDING_WINDOW_MS
    }

    private fun markPending(taskId: Int) {
        pendingTaskId = taskId
        pendingAt = SystemClock.uptimeMillis()
    }

    /** 任务清单快照（诊断用）：包名|f焦点v可见z形态。 */
    private fun describe(tasks: List<Any>): String =
        tasks.take(6).joinToString(",") { task ->
            val packageName = packageOfTask(task) ?: "?"
            val focus = if (isTaskFocused(task)) 1 else 0
            val visible = if (isTaskVisible(task)) 1 else 0
            packageName + "|f" + focus + "v" + visible + "z" +
                ColorOsFlexibleWindowState.zoomStateOf(task)
        }

    private fun logOnce(code: String) {
        if (!reported.add(code)) return
        logger(code, null)
    }

    private fun isFlexibleScenario(task: Any): Boolean =
        runCatching {
            OemReflection.call(controller, "isTaskInFlexibleScenario", task) as? Boolean ?: false
        }.getOrDefault(false)

    private fun isTaskFocused(task: Any): Boolean =
        runCatching {
            val method = OemReflection.findMethod(task.javaClass, "isFocused")
            method?.isAccessible = true
            method?.invoke(task) as? Boolean ?: false
        }.getOrDefault(false)

    private fun isTaskVisible(task: Any): Boolean =
        runCatching { OemReflection.call(task, "isVisible") as? Boolean ?: false }.getOrDefault(false)

    private fun taskIdOf(task: Any): Int =
        OemReflection.readField(task, "mTaskId") as? Int ?: -1

    private fun tasksOfFocusedDisplay(): List<Any>? =
        try {
            val atms = OemReflection.readField(controller, "mAtms") ?: return null
            val root = OemReflection.readField(atms, "mRootWindowContainer") ?: return null
            val display =
                OemReflection.requireMethod(root.javaClass, "getTopFocusedDisplayContent", 0)
                    .invoke(root) ?: return null
            val collected = ArrayList<Any>(8)
            collectTasks(display, collected, 0)
            collected
        } catch (exception: ReflectiveOperationException) {
            logger("AUTO_DOCK_TASK_SCAN_FAILED", exception)
            null
        } catch (exception: RuntimeException) {
            logger("AUTO_DOCK_TASK_SCAN_FAILED", exception)
            null
        }

    /** 只沿 DisplayArea / DisplayContent 下钻；任务节点一命中就收。 */
    private fun collectTasks(container: Any, out: MutableList<Any>, depth: Int) {
        if (depth > MAX_TASK_WALK_DEPTH) return
        val self = OemReflection.requireMethod(container.javaClass, "asTask", 0).invoke(container)
        if (self != null) {
            out.add(self)
            return
        }
        val typeName = container.javaClass.name
        if (!typeName.contains("DisplayArea") &&
            !typeName.contains("DisplayContent") &&
            !typeName.contains("RootWindowContainer")
        ) {
            return
        }
        val count =
            OemReflection.requireMethod(container.javaClass, "getChildCount", 0).invoke(container)
                as? Int ?: 0
        if (count <= 0) return
        val childAt = OemReflection.requireMethod(container.javaClass, "getChildAt", 1)
        for (index in 0 until count) {
            val child = childAt.invoke(container, index) ?: continue
            collectTasks(child, out, depth + 1)
        }
    }

    private fun packageOfTask(task: Any): String? {
        runCatching {
            val intent = OemReflection.requireMethod(task.javaClass, "getBaseIntent", 0)
                .invoke(task) as? android.content.Intent
            intent?.component?.packageName
        }.getOrNull()?.let { return it }
        runCatching {
            (OemReflection.requireMethod(task.javaClass, "getRealActivity", 0).invoke(task)
                as? android.content.ComponentName)?.packageName
        }.getOrNull()?.let { return it }
        listOf("topRunningActivity", "getRootActivity", "getTopNonFinishingActivity").forEach { name ->
            runCatching {
                val activity = OemReflection.requireMethod(task.javaClass, name, 0).invoke(task)
                    ?: return@runCatching null
                OemReflection.readField(activity, "packageName") as? String
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private companion object {
        const val STEP_MS = 40L
        const val MAX_WAIT_MS = 1800L
        const val MAX_INVOKES = 4
        const val PENDING_WINDOW_MS = 2500L
        const val MAX_TASK_WALK_DEPTH = 8

        /** 第 n 次转换调用最早在「开始后多少毫秒」发起：回桌面转场期间被拒时要留重试余量。 */
        val INVOKE_AT = longArrayOf(0L, 300L, 700L, 1100L)
    }
}