package io.github.hoerire.free.apps

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import dalvik.system.DexClassLoader
import io.github.hoerire.free.config.PinnedTarget
import io.github.hoerire.free.platform.coloros.SidebarEntryProtocol
import java.lang.reflect.InvocationTargetException

/**
 * 在本进程内直接加载侧边栏 APK 的 dex，读出工具 / 快捷方式 / 分身应用三类条目，
 * 并在本进程执行工具。
 *
 * 这里**没有任何跨进程调用**：不广播、不经过 system_server 转发、不 bindService。
 * App 进程和 system_server 各自 new 一份，各读各的。
 *
 * 依据：侧边栏自己就是靠 `EntryBeanHelper.init()` 初始化 `ToolEntryHelper` /
 * `ShortcutEntryHelper` / `AppEntryHelper` 这三张表，再通过各自的 Proxy 取数据。
 * 唯一的外部依赖是 `com.coloros.common.App.sContext`（Application 级 Context 静态字段），
 * 这里在加载前把它指向侧边栏包的 Context，之后的调用与侧边栏进程内完全一致。
 */
internal class SidebarLocalTools(private val context: Context) {
    private var state: State? = null

    /** 最近一次加载失败的原因（只为日志定位）。 */
    @Volatile
    var lastError: String? = null
        private set

    private class State(val entries: List<SelectableEntry>)

    fun load(): List<SelectableEntry> = state?.entries ?: synchronized(this) {
        state?.entries ?: read().entries
    }

    fun invalidate() {
        synchronized(this) { state = null }
    }

    /**
     * 在**本进程**执行工具：解析出侧边栏自己的工具实例，直接调它的 `handle()`。
     *
     * 与原生侧边栏一致，`handle()` 里的启动逻辑（Intent / 厂商 API）原样保留，
     * 本模块不复制任何单工具的映射。
     */
    fun launch(alias: String, beforeLaunch: () -> Boolean): Boolean {
        Log.d(TAG, "SIDEBAR_LOCAL_LAUNCH_ENTER_" + alias)
        val instance = try {
            tool(alias)
        } catch (exception: Throwable) {
            Log.w(TAG, "SIDEBAR_LOCAL_LAUNCH_RESOLVE_FAILED_" + alias, unwrap(exception))
            null
        } ?: run {
            Log.w(TAG, "SIDEBAR_LOCAL_LAUNCH_NO_TOOL_" + alias, null)
            return false
        }
        Log.d(TAG, "SIDEBAR_LOCAL_TOOL_FOUND_" + alias + "_" + instance.javaClass.name)
        if (!beforeLaunch()) {
            Log.w(TAG, "SIDEBAR_LOCAL_GUARD_FALSE_" + alias, null)
            return false
        }
        return try {
            instance.javaClass.getMethod("handle").invoke(instance)
            Log.d(TAG, "SIDEBAR_LOCAL_LAUNCHED_" + alias)
            true
        } catch (exception: Throwable) {
            Log.w(TAG, "SIDEBAR_LOCAL_LAUNCH_FAILED_" + alias, unwrap(exception))
            false
        }
    }

    private fun tool(alias: String): Any? {
        // 必须先 load()：OEM 的 ToolEntryHelper 只有 init 过后才会创建单例，
        // 直接 getActiveInstance() 在还没读过目录的进程（如 system_server）里会是 null。
        load()
        val (_, loader) = contextAndLoader() ?: return null
        val helper = loader.loadClass(TOOL_HELPER_CLASS).getMethod("getActiveInstance").invoke(null) ?: return null
        return helper.javaClass.getMethod("getToolByAlias", String::class.java).invoke(helper, alias)
    }

    private fun read(): State {
        val (packageContext, loader) = contextAndLoader() ?: return State(emptyList())
        val beans = try {
            entryBeans(packageContext, loader)
        } catch (exception: Throwable) {
            lastError = "ENTRY_BEANS_FAILED"
            Log.w(TAG, "SIDEBAR_LOCAL_ENTRY_BEANS_FAILED", unwrap(exception))
            emptyList()
        }
        val toolsOnly = if (beans.isEmpty()) toolsOnly(packageContext, loader) else emptyList()
        val source = beans.ifEmpty { toolsOnly }
        val userId = Process.myUid() / USER_OFFSET
        val result = ArrayList<SelectableEntry>(source.size)
        for (bean in source) {
            try {
                val entry = entry(packageContext, loader, bean, userId) ?: continue
                result.add(entry)
            } catch (exception: Throwable) {
                Log.w(TAG, "SIDEBAR_LOCAL_BEAN_FAILED", unwrap(exception))
            }
        }
        val built = State(result)
        synchronized(this) { state = built }
        lastError = if (result.isEmpty()) "EMPTY" else null
        val kinds = result.groupingBy { (it.target as PinnedTarget.Sidebar).kind }.eachCount()
        Log.d(TAG, "SIDEBAR_LOCAL_ENTRIES_" + result.size + "_" + kinds)
        return built
    }

    /** OEM 的完整目录：工具 + 快捷方式/应用 + 分身。 */
    private fun entryBeans(packageContext: Context, loader: ClassLoader): List<Any> {
        seedApplicationContext(loader, packageContext)
        val helperClass = loader.loadClass(ENTRY_HELPER_CLASS)
        val helper = helperClass.getMethod("createAndGetInstance").invoke(null) ?: return emptyList()
        helperClass.getMethod("init", Context::class.java).invoke(helper, packageContext)
        val tools = runCatching {
            helperClass.getMethod("getAllAppListForTool").invoke(helper) as? List<*>
        }.getOrNull().orEmpty()
        val shortcuts = runCatching {
            helperClass.getMethod("getShownShortcuts").invoke(helper) as? List<*>
        }.getOrNull().orEmpty()
        val apps = runCatching {
            helperClass.getMethod("getShownApps").invoke(helper) as? List<*>
        }.getOrNull().orEmpty()
        return (tools + shortcuts + apps).filterNotNull()
    }

    /** 兜底：只读工具表（`EntryBeanHelper` 初始化失败时至少工具还在）。 */
    private fun toolsOnly(packageContext: Context, loader: ClassLoader): List<Any> {
        val helperClass = loader.loadClass(TOOL_HELPER_CLASS)
        val helper = helperClass.getMethod("createAndGetInstance").invoke(null) ?: return emptyList()
        helperClass.getMethod("init", Context::class.java).invoke(helper, packageContext)
        val map = helperClass.getDeclaredField("mTools").apply { isAccessible = true }
            .get(helper) as? Map<*, *> ?: return emptyList()
        return map.values.filterNotNull()
    }

    private fun entry(packageContext: Context, loader: ClassLoader, bean: Any, ownerUserId: Int): SelectableEntry? {
        val type = bean.javaClass
        val nativeType = (type.getMethod("getType").invoke(bean) as? Int) ?: return null
        val category = (type.getMethod("getCategory").invoke(bean) as? Int) ?: return null
        val cloned = type.getMethod("isCloned").invoke(bean) == true
        val entryUserId = (type.getMethod("getMutiUserId").invoke(bean) as? Int) ?: 0
        if (type.getMethod("isHidden").invoke(bean) == true) return null
        val kind = kindOf(nativeType, category, cloned, entryUserId, ownerUserId) ?: return null
        val alias = (type.getMethod("getAlias").invoke(bean) as? String)?.takeIf { it.isNotBlank() } ?: return null
        val packageName = (type.getMethod("getPkg").invoke(bean) as? String).orEmpty()
        val label = (type.getMethod("getLabel").invoke(bean) as? String)?.takeIf { it.isNotBlank() }
            ?: alias
        val cloneInfo = if (kind == PinnedTarget.Kind.CloneApp) cloneActivity(packageName, entryUserId) else null
        val iconRes = (runCatching { type.getMethod("getIconRes").invoke(bean) as? Int }.getOrNull()) ?: 0
        val icon = when (kind) {
            PinnedTarget.Kind.Tool -> drawableIcon(packageContext, iconRes)
            PinnedTarget.Kind.CloneApp -> cloneInfo?.let { launcherIcon(it) } ?: appIcon(packageContext, packageName).toIconBitmap()
            else -> shortcutIcon(loader, alias) ?: appIcon(packageContext, packageName).toIconBitmap()
        }
        return SelectableEntry(
            target = PinnedTarget.Sidebar(kind, alias, ownerUserId),
            label = label.take(160),
            packageName = packageName.take(256),
            icon = icon,
            badge = cloneInfo?.let { cloneBadge(it) },
        )
    }

    /** 与原生侧边栏一致的类别判定（旧实现 SidebarEntryKind 的规则）。 */
    private fun kindOf(type: Int, category: Int, cloned: Boolean, entryUserId: Int, ownerUserId: Int): PinnedTarget.Kind? = when {
        type == 0 && !cloned -> PinnedTarget.Kind.Tool
        type == 1 && !cloned -> PinnedTarget.Kind.Shortcut
        type == 2 && !cloned && category == LAUNCHER_SHORTCUT_CATEGORY -> PinnedTarget.Kind.AppEntry
        type == 2 && cloned && category != LAUNCHER_SHORTCUT_CATEGORY &&
            entryUserId in 1..MAX_USER_ID && entryUserId != ownerUserId -> PinnedTarget.Kind.CloneApp

        else -> null
    }

    /** `com.coloros.common.App.sContext`：OEM 助手全部从这里取 Context。 */
    private fun seedApplicationContext(loader: ClassLoader, packageContext: Context) {
        runCatching {
            val field = loader.loadClass("com.coloros.common.App").getDeclaredField("sContext")
            field.isAccessible = true
            if (field.get(null) == null) field.set(null, packageContext)
        }.onFailure { Log.w(TAG, "SIDEBAR_LOCAL_APP_CONTEXT_FAILED", it) }
    }

    /** 侧边栏包的 Context + 能加载到它代码的类加载器；两条路都失败返回 null。 */
    private fun contextAndLoader(): Pair<Context, ClassLoader>? {
        // 必须复用同一个 ClassLoader：OEM 的工具表是静态单例，第二次调用如果拿到新的
        // ClassLoader（DexClassLoader 每次 new 都是新的一份类），getActiveInstance() 会是 null。
        cached?.let { return it }
        val built = buildContextAndLoader() ?: return null
        cached = built
        installAppPlatformRedirect(built.first, built.second)
        return built
    }

    @Volatile
    private var appPlatformHooked = false

    /**
     * 侧边栏工具最终都走 `AppPlatformUtils.m(Intent)`。
     *
     * 它的原实现是 OPPO 的 AppPlatform/Epona 通道，只有侧边栏自己的进程注册过；
     * 在 system_server 里调用会被静默丢弃（日志 `getCurrentUser` 返回 1005），
     * 表现就是"点了没反应"，而 `handle()` 本身没有抛异常。
     *
     * 这里把这一步在本进程内换成普通 `startActivity`：一次通用替换，不针对任何工具。
     * 只有装了模块的进程才有 XposedModule，应用进程拿到 null 时直接跳过。
     */
    private fun installAppPlatformRedirect(packageContext: Context, loader: ClassLoader) {
        if (appPlatformHooked) return
        val module = runCatching { io.github.hoerire.free.settings.ModuleRuntime.module }.getOrNull()
            ?: return
        val method = runCatching {
            loader.loadClass(APP_PLATFORM_CLASS).getDeclaredMethod("m", Intent::class.java)
        }.getOrNull() ?: return
        runCatching {
            module.hook(method)
                .setId("free.sidebar.appplatform")
                .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val intent = chain.getArg(0) as? Intent
                    if (intent != null) {
                        runCatching {
                            packageContext.startActivity(
                                Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure { Log.w(TAG, "APP_PLATFORM_START_FAILED", it) }
                    }
                    null
                }
            appPlatformHooked = true
            Log.i(TAG, "APP_PLATFORM_HOOKED")
        }.onFailure { Log.w(TAG, "APP_PLATFORM_HOOK_FAILED", it) }
    }

    @Volatile
    private var cached: Pair<Context, ClassLoader>? = null

    private fun buildContextAndLoader(): Pair<Context, ClassLoader>? {
        // 首选：已安装包的 ClassLoader。多 dex 与 AOT 由系统处理；DexClassLoader 直接开 APK
        // 时通常只认 classes.dex，而这三张表都在 classes2。
        val withCode = runCatching {
            context.createPackageContext(SIDEBAR_PACKAGE, Context.CONTEXT_INCLUDE_CODE)
        }.getOrNull()
        if (withCode?.classLoader != null &&
            runCatching { withCode.classLoader.loadClass(ENTRY_HELPER_CLASS) }.isSuccess
        ) {
            return withCode to withCode.classLoader
        }
        // 兜底：自己开 APK。父加载器用 boot，避免 OEM 的 kotlin/androidx 被本模块同名类遮蔽。
        val plain = runCatching {
            context.createPackageContext(SIDEBAR_PACKAGE, 0)
        }.getOrNull() ?: run {
            lastError = "NO_CONTEXT"
            return null
        }
        val apk = runCatching {
            context.packageManager
                .getApplicationInfo(SIDEBAR_PACKAGE, PackageManager.ApplicationInfoFlags.of(0))
                .sourceDir
        }.getOrNull() ?: run {
            lastError = "NO_PACKAGE"
            return null
        }
        val loader = runCatching {
            DexClassLoader(apk, null, null, ClassLoader.getSystemClassLoader())
        }.getOrNull() ?: run {
            lastError = "NO_LOADER"
            return null
        }
        return plain to loader
    }

    private fun drawableIcon(packageContext: Context, resId: Int): Bitmap {
        val drawable = if (resId != 0) {
            runCatching { packageContext.getDrawable(resId) }.getOrNull()
        } else null
        return (drawable ?: appIcon(packageContext, null)).toIconBitmap()
    }

    private fun appIcon(packageContext: Context, packageName: String?): Drawable {
        val drawable = packageName?.takeIf { it.isNotBlank() }?.let { name ->
            runCatching { context.packageManager.getApplicationIcon(name) }.getOrNull()
        }
        return drawable
            ?: runCatching { context.packageManager.getApplicationIcon(SIDEBAR_PACKAGE) }.getOrNull()
            ?: context.packageManager.defaultActivityIcon
    }

    /** 快捷方式图标：`ShortcutEntryHelper.getShortcut(alias).getIcon()` 返回 PNG 字节。 */
    private fun shortcutIcon(loader: ClassLoader, alias: String): Bitmap? {
        val bytes = runCatching {
            val helperClass = loader.loadClass(SHORTCUT_HELPER_CLASS)
            val helper = helperClass.getMethod("getActiveInstance").invoke(null) ?: return null
            val shortcut = helperClass.getMethod("getShortcut", String::class.java).invoke(helper, alias)
            shortcut?.javaClass?.getMethod("getIcon")?.invoke(shortcut) as? ByteArray
        }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_ICON_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > SidebarEntryProtocol.ICON_SIZE * 2) {
                inSampleSize *= 2
            }
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.toIconBitmap()
    }

    private fun cloneActivity(packageName: String, entryUserId: Int): LauncherActivityInfo? {
        if (packageName.isBlank() || entryUserId !in 1..MAX_USER_ID) return null
        return try {
            context.getSystemService(LauncherApps::class.java)
                ?.getActivityList(packageName, UserHandle.getUserHandleForUid(entryUserId * USER_OFFSET))
                ?.firstOrNull()
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun launcherIcon(info: LauncherActivityInfo): Bitmap? = try {
        info.getIcon(context.resources.displayMetrics.densityDpi)?.toIconBitmap()
    } catch (_: RuntimeException) {
        null
    }

    /** 让系统在透明底图上绘制分身角标，位置与比例沿用系统徽标规则。 */
    private fun cloneBadge(info: LauncherActivityInfo): Bitmap? {
        val size = SidebarEntryProtocol.ICON_SIZE
        val blank = createBitmap(size, size, Bitmap.Config.ARGB_8888).toDrawable(context.resources)
        val badged = try {
            context.packageManager.getUserBadgedIcon(blank, info.user)
        } catch (_: RuntimeException) {
            return null
        }
        if (badged === blank) return null
        return badged.toIconBitmap().takeIf { bitmap ->
            IntArray(size * size)
                .also { bitmap.getPixels(it, 0, size, 0, 0, size, size) }
                .any { it ushr 24 != 0 }
        }
    }

    private fun Drawable.toIconBitmap(): Bitmap = createBitmap(
        SidebarEntryProtocol.ICON_SIZE,
        SidebarEntryProtocol.ICON_SIZE,
        Bitmap.Config.ARGB_8888,
    ).also {
        setBounds(0, 0, it.width, it.height)
        draw(Canvas(it))
    }

    private fun Bitmap.toIconBitmap(): Bitmap = this

    private fun unwrap(throwable: Throwable): Throwable =
        if (throwable is InvocationTargetException) throwable.targetException ?: throwable
        else throwable

    private companion object {
        const val TAG = "FreeSidebarLocal"
        const val SIDEBAR_PACKAGE = "com.coloros.smartsidebar"
        const val ENTRY_HELPER_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.EntryBeanHelper"
        const val TOOL_HELPER_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.ToolEntryHelper"
        const val APP_PLATFORM_CLASS = "com.oplus.smartsidebar.utils.AppPlatformUtils"
        const val SHORTCUT_HELPER_CLASS =
            "com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.ShortcutEntryHelper"
        const val LAUNCHER_SHORTCUT_CATEGORY = 11
        const val MAX_USER_ID = 21_474
        const val USER_OFFSET = 100_000
        const val MAX_ICON_BYTES = 1_048_576
    }
}
