package io.github.hoerire.free.platform.coloros

import android.content.ComponentName
import android.content.Context
import android.app.ActivityOptions
import android.content.pm.LauncherApps
import android.os.Bundle
import android.content.Intent
import androidx.core.graphics.drawable.toBitmap
import io.github.hoerire.free.bubble.CloneCatalogScanner
import android.os.Handler
import android.os.UserHandle
import android.os.SystemClock
import android.util.Log
import io.github.hoerire.free.apps.SelectableEntry
import io.github.hoerire.free.apps.SidebarLocalTools
import io.github.hoerire.free.config.PinnedTarget
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 扇形用的侧边栏目录：**本进程直读**，没有广播、没有 system_server 转发、没有 bindService。
 *
 * 之前这里是一个跨进程代理（广播 → system_server → 绑侧边栏 :ui → 回调），
 * 在部分机型上绑定必失败且不可自愈。现在 system_server 自己打开侧边栏 APK 读同一份
 * 工具表（见 [SidebarLocalTools]），执行也在本进程完成——各进程各读各的，不存在跨进程通道。
 */
internal class ColorOsEntryCatalog(
    private val context: Context,
    private val handler: Handler,
    private val log: (Int, String, Throwable?) -> Unit,
    private val onCatalog: (List<SelectableEntry>) -> Unit,
) {
    private val local = SidebarLocalTools(context)
    private val worker = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, ArrayBlockingQueue(4),
        { task -> Thread(task, "Free-LocalCatalog") }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    private val callbacks = mutableListOf<(List<SelectableEntry>?) -> Unit>()
    private var refreshing = false
    private var lastRequestAt = -1_000L

    fun start() = refresh()

    fun refresh(onComplete: ((List<SelectableEntry>?) -> Unit)? = null) {
        if (onComplete != null) callbacks += onComplete
        if (refreshing) return
        val now = SystemClock.uptimeMillis()
        if (now - lastRequestAt < 500L) return
        lastRequestAt = now
        refreshing = true
        worker.execute {
            val entries = try {
                local.load() + cloneEntries()
            } catch (exception: Throwable) {
                log(Log.WARN, "ENTRY_CATALOG_LOCAL_FAILED", exception)
                emptyList()
            }
            handler.post {
                refreshing = false
                when {
                    entries.isEmpty() -> log(
                        Log.WARN,
                        "ENTRY_CATALOG_LOCAL_EMPTY_" + (local.lastError ?: "UNKNOWN"),
                        null,
                    )

                    else -> onCatalog(entries)
                }
                val done = callbacks.toList()
                callbacks.clear()
                done.forEach { it(entries.ifEmpty { null }) }
            }
        }
    }

    /**
     * 在本进程执行工具。`beforeLaunch` 是调用方要求的时机校验（浮层已消失、手势环境允许等），
     * 校验通过后才真正调用原生 `handle()`。
     */
    fun launch(target: PinnedTarget.Sidebar, beforeLaunch: () -> Boolean) {
        debug("ENTRY_CATALOG_LAUNCH_" + target.kind.name + "_" + target.alias)
        if (target.kind == PinnedTarget.Kind.CloneApp) {
            launchClone(target, beforeLaunch)
            return
        }
        // 少数工具启动的是 OEM 前台服务，只能代发（见 ServiceToolStarter 的说明）。
        if (target.kind == PinnedTarget.Kind.Tool && ServiceToolStarter.handles(target.alias)) {
            handler.post {
                val allowed = try {
                    beforeLaunch()
                } catch (exception: RuntimeException) {
                    log(Log.WARN, "ENTRY_LAUNCH_GUARD_FAILED_" + target.alias, exception)
                    false
                }
                if (!allowed) {
                    log(Log.WARN, "ENTRY_LAUNCH_GUARD_BLOCKED_" + target.alias, null)
                    return@post
                }
                val started = ServiceToolStarter.launch(context, target.alias)
                if (started) {
                    debug("ENTRY_HANDLE_OK_" + target.alias + "_service")
                } else {
                    log(Log.WARN, "ENTRY_HANDLE_FAILED_" + target.alias + "_service", null)
                }
            }
            return
        }
        awaitGuard(target, beforeLaunch, GUARD_ATTEMPTS)
    }

    /**
     * 等时机：浮层要等 `removeViewImmediate` 之后才会真正消失，立刻判定必然失败。
     *
     * 旧实现靠"先绑服务再执行"天然晚了上百毫秒，这里显式重试；窗口内一直不满足才放弃。
     */
    private fun awaitGuard(target: PinnedTarget.Sidebar, beforeLaunch: () -> Boolean, remaining: Int) {
        val first = remaining == GUARD_ATTEMPTS
        handler.postDelayed({
            val allowed = try {
                beforeLaunch()
            } catch (exception: RuntimeException) {
                log(Log.WARN, "ENTRY_LAUNCH_GUARD_FAILED_" + target.alias, exception)
                false
            }
            if (allowed) {
                debug("ENTRY_GUARD_PASS_" + target.alias + "_try" + (GUARD_ATTEMPTS - remaining + 1))
                worker.execute {
                    debug("ENTRY_HANDLE_BEGIN_" + target.alias)
                    val launched = local.launch(target.alias) { true }
                    if (launched) {
                        debug("ENTRY_HANDLE_OK_" + target.alias)
                    } else {
                        log(
                            Log.WARN,
                            "ENTRY_HANDLE_FAILED_" + target.alias +
                                "_localErr" + (local.lastError ?: "none"),
                            null,
                        )
                    }
                }
            } else if (remaining > 1) {
                awaitGuard(target, beforeLaunch, remaining - 1)
            } else {
                log(Log.WARN, "ENTRY_LAUNCH_GUARD_BLOCKED_" + target.alias, null)
            }
        }, if (first) 0L else GUARD_RETRY_MS)
    }

    fun invalidate() {
        local.invalidate()
    }

    /**
     * 分身条目：system_server 自己持有 `INTERACT_ACROSS_USERS`，可以直接扫其他用户，
     * 不经过侧边栏、也不经过模块应用。
     *
     * 别名与 App 侧保持一致（`包名#用户id`），否则固定项在这里匹配不上。
     */
    private fun cloneEntries(): List<SelectableEntry> {
        val scanned = runCatching {
            CloneCatalogScanner(context) { code, throwable -> log(Log.WARN, code, throwable) }.scan()
        }.getOrElse {
            log(Log.WARN, "ENTRY_CATALOG_CLONE_FAILED", it)
            emptyList()
        }
        if (scanned.isEmpty()) return emptyList()
        val size = SidebarEntryProtocol.ICON_SIZE
        return scanned.mapNotNull { clone ->
            val icon = clone.icon ?: runCatching {
                context.packageManager.getApplicationIcon(clone.packageName).toBitmap(size, size)
            }.getOrElse { context.packageManager.defaultActivityIcon.toBitmap(size, size) }
            SelectableEntry(
                target = PinnedTarget.Sidebar(
                    PinnedTarget.Kind.CloneApp,
                    clone.packageName + "#" + clone.userId,
                    clone.userId,
                ),
                label = clone.label.take(160),
                packageName = clone.packageName.take(256),
                icon = icon,
                badge = io.github.hoerire.free.apps.CloneBadges.of(context, clone.userId),
            )
        }
    }

    /**
     * 分身：按包名在指定用户里拉起启动入口。
     *
     * 不在别名里写死 Activity：同一个包在不同用户下解析到的入口可能不同，
     * 这里让 `startActivityAsUser` 在目标用户里解析（ACTION_MAIN + LAUNCHER + 包名）。
     *
     * 分身用户的存储没挂载时，框架会抛 `SecurityException: MultiApp external storage is
     * not ready`。这时先用 `startUserInBackground` 把用户拉起来再重试一次。
     */
    private fun launchClone(target: PinnedTarget.Sidebar, beforeLaunch: () -> Boolean) {
        handler.post {
            if (!beforeLaunch()) return@post
            val alias = target.alias
            val separator = alias.indexOf('#')
            if (separator <= 0) {
                log(Log.WARN, "ENTRY_LAUNCH_CLONE_BAD_ALIAS", null)
                return@post
            }
            debug("ENTRY_CLONE_BEGIN_" + alias + "_u" + target.userId)
            val packageName = alias.substring(0, separator)
            val user = UserHandle.getUserHandleForUid(target.userId * USER_OFFSET)
            startClone(target.userId, packageName, user, 0)
        }
    }

    private fun startClone(userId: Int, packageName: String, user: UserHandle, attempt: Int) {
        // 只给包名时，ColorOS 的 MultiApp 服务会把意图改写回 u0（日志里 START ... u=0），
        // 结果就是"点了没反应"。这里先按目标用户解析出明确组件，让它没有改写余地。
        val component = resolveCloneComponent(packageName, user)
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (component != null) intent.component = component
        debug(
            "ENTRY_CLONE_INTENT_u" + userId + "_" + (component?.flattenToShortString() ?: "no_component")
        )
        try {
            // 小窗优先走厂商 zoom 入口（它的第四个参数就是目标用户，正好给分身用）；
            // 不可用时再退回带 zoom extras 的普通启动。
            if (startZoomWindow(intent, userId)) {
                debug("ENTRY_LAUNCH_CLONE_ZOOM_" + packageName + "_u" + userId)
                return
            }
            val userStart = Context::class.java.getMethod(
                "startActivityAsUser",
                Intent::class.java,
                Bundle::class.java,
                UserHandle::class.java,
            )
            userStart.invoke(context, intent, zoomOptions(), user)
            debug("ENTRY_LAUNCH_CLONE_" + packageName + "_u" + userId)
        } catch (exception: Throwable) {
            val cause = causeOf(exception)
            if (attempt == 0) {
                log(Log.WARN, "ENTRY_CLONE_RETRY_START_USER_u" + userId + "_" + cause, null)
                ensureUserStarted(user)
                handler.postDelayed({ startClone(userId, packageName, user, attempt + 1) }, CLONE_USER_RETRY_MS)
            } else {
                log(Log.WARN, "ENTRY_LAUNCH_CLONE_FAILED_" + cause, null)
            }
        }
    }

    /** 小窗启动参数，逐项对齐原生侧边栏（不传 windowingMode，见 ColorOsFreeformLauncher）。 */
    private fun zoomOptions(): Bundle = Bundle().apply {
        runCatching { ActivityOptions.makeBasic().toBundle() }.getOrNull()?.let(::putAll)
        putInt("android:activity.mZoomLaunchFlags", 0)
        putInt("android.activity.launchDisplayId", 0)
        putInt("android.activity.splashScreenStyle", 1)
        putInt("extra_window_mode", 100)
    }

    /** 厂商 zoom 入口：`startZoomWindow(Intent, Bundle, userId, callingPackage)`，返回码 <0 即失败。 */
    private fun startZoomWindow(intent: Intent, userId: Int): Boolean = runCatching {
        val managerClass = Class.forName("com.oplus.zoomwindow.OplusZoomWindowManager")
        val instance = managerClass.getMethod("getInstance").invoke(null) ?: return@runCatching false
        val result = managerClass.getMethod(
            "startZoomWindow",
            Intent::class.java,
            Bundle::class.java,
            Int::class.javaPrimitiveType,
            String::class.java,
        ).invoke(instance, intent, zoomOptions(), userId, context.packageName) as? Int
        result != null && result >= 0
    }.getOrDefault(false)

    /** 按目标用户解析启动组件；取不到时退回「只给包名」。 */
    private fun resolveCloneComponent(packageName: String, user: UserHandle): ComponentName? =
        runCatching {
            context.getSystemService(LauncherApps::class.java)
                ?.getActivityList(packageName, user)
                ?.firstOrNull()?.componentName
        }.getOrNull()

    private fun ensureUserStarted(user: UserHandle) {
        runCatching {
            val service = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
                ?: return@runCatching
            service.javaClass
                .getMethod("startUserInBackground", UserHandle::class.java)
                .invoke(service, user)
            debug("ENTRY_CLONE_USER_STARTED")
        }.onFailure { log(Log.WARN, "ENTRY_CLONE_USER_START_FAILED", it) }
    }

    /** 成功路径/细节诊断：只在「详细日志」打开时输出，避免每次点击刷 WARN。 */
    private fun debug(code: String) {
        if (io.github.hoerire.free.log.VerboseLog.enabled) log(Log.DEBUG, code, null)
    }

    private fun causeOf(throwable: Throwable): Throwable =
        if (throwable is java.lang.reflect.InvocationTargetException) {
            throwable.targetException ?: throwable
        } else throwable

    private companion object {
        const val USER_OFFSET = 100_000

        /** 时机重试：约 400ms 窗口，足够浮层消失。 */
        const val GUARD_ATTEMPTS = 14
        const val GUARD_RETRY_MS = 30L

        /** 拉起分身用户后等存储就绪再重试。 */
        const val CLONE_USER_RETRY_MS = 1_200L
    }
}
