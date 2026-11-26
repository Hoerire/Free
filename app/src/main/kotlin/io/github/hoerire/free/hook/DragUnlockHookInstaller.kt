package io.github.hoerire.free.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.free.log.ModuleLog
import io.github.hoerire.free.log.VerboseLog

/**
 * 解除限制：解除内容传送门的长按拖拽准入限制。
 *
 * 由「系统增强 → 解除限制」开关控制，默认开启；关掉后立刻退回原厂规则。
 *
 * 反编译结论（oplus-services.jar / oplus-framework.jar）：
 *   应用进程 bind 时 OplusViewDragSystemServer#preBindApplication 向
 *   OplusViewDragRusHelper#getPackageDragConfig(pkg) 取配置，取到 null 就直接 return，
 *   应用侧的 OplusViewDragManager#init 也随之提前结束 —— 长按拖拽于是完全不接管。
 *   配置来自 /system_ext/oplus/sys_view_drag_list.xml（可被 /data 下的 RUS 版本覆盖）：
 *     包名 → Activity（窗口标题 contains 匹配）→ 具体 View 类名（getSimpleName() 精确相等）
 *
 * 做法：给每个包补一份通用配置。
 *   · 通用条目的 activityName 用包名 —— findViewDragList 用 windowTitle.contains(name)，
 *     窗口标题形如「包名/Activity」，包名必然命中，等于对所有页面生效；
 *   · ViewDragInfo 只填 viewName + type，其余字段为空时 isEntryNameMatch / isParentMatch /
 *     isExtraInfoMatch 都直接返回 true，不需要额外条件；
 *   · 原厂已登记的应用，把 web 控件追加进它每个 Activity 的控件列表：原厂是「命中第一条即返回」，
 *     只追加在末尾救不了「原厂写了这个 Activity、却没写 WebView」的页面（浏览器就是这种）；
 *   · 系统按 getSimpleName() 精确匹配，所以名单只能覆盖已知类名。名单**必须保持精简**：
 *     条目随 ApplicationInfo 进 binder，按 provider 数放大，堆长尾类名会让应用
 *     在发布 ContentProvider 时超 1MB 而 abort（见 CORE_VIEWS 注释）。
 *
 * 两道硬限制（都是应用进程内的判定，system_server 改不了）：
 *   · Compose / 自绘渲染的页面没有标准控件，按类名匹配不到，取不到内容；
 *   · 图片必须能从 ImageView 的 drawable 取到，取不到会被静默丢弃。
 *
 * 只改「能不能拖」，不参与「拖给谁」：投递目标仍由系统自己的规则决定。
 * 游戏模式下不补配置；横屏不做限制。
 */
internal class DragUnlockHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
    private val environment: ModuleEnvironmentState,
) {
    private var packageClass: Class<*>? = null
    private var activityClass: Class<*>? = null
    private var viewClass: Class<*>? = null
    private var genericViews: List<Any> = emptyList()
    private var webViews: List<Any> = emptyList()

    fun install(classLoader: ClassLoader) {
        val helper = runCatching { classLoader.loadClass(HELPER_CLASS) }.getOrNull()
        if (helper == null) {
            ModuleLog.warn("DRAG_UNLOCK_NO_HELPER")
            return
        }
        packageClass = loadClass(classLoader, PACKAGE_CLASS)
        activityClass = loadClass(classLoader, ACTIVITY_CLASS)
        viewClass = loadClass(classLoader, VIEW_CLASS)
        if (packageClass == null || activityClass == null || viewClass == null) {
            ModuleLog.warn("DRAG_UNLOCK_NO_BEAN")
            return
        }
        genericViews = buildGenericViews(CORE_VIEWS)
        // 原厂条目里补充的即此类配置：网页控件（浏览器文章页缺的正是它）。
        webViews = buildGenericViews(APPEND_VIEWS)
        if (genericViews.isEmpty()) {
            ModuleLog.warn("DRAG_UNLOCK_NO_VIEW")
            return
        }
        val installed = hookConfigProvider(helper) + hookRawXmlProvider(helper)
        if (installed == 0) {
            ModuleLog.warn("DRAG_UNLOCK_NO_METHOD")
        } else {
            // 唯一一条常开日志：hook 注入成功。详细日志关闭时其余诊断全部静默，
            // 这条走框架日志出口（不经 ModuleLog 的开关），用于确认注入发生过。
            ModuleLog.install("DRAG_UNLOCK_INSTALLED_" + installed)
        }
    }

    /** 配置读取入口：未登记的包补一份通用白名单。 */
    private fun hookConfigProvider(helper: Class<*>): Int {
        val method = OemReflection.findMethod(helper, "getPackageDragConfig", String::class.java) ?: return 0
        return runCatching {
            method.isAccessible = true
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.freedrag.config")
                .intercept { chain ->
                    val original = chain.proceed()
                    if (!configuration.snapshot.dragUnlockActive) return@intercept original
                    // 游戏模式下一律不补配置：游戏里的长按更值钱，横屏不算。
                    if (environment.inGameMode) return@intercept original
                    val packageName = chain.getArg(0) as? String
                    if (!isRewritable(packageName)) return@intercept original
                    runCatching { withGenericEntry(packageName as String, original) }
                        .getOrElse { exception ->
                            // 逐次调用的诊断，只在详细日志开启时输出，避免刷屏。
                            ModuleLog.debug(TAG, exception) { "DRAG_UNLOCK_BUILD_FAILED" }
                            original
                        }
                }
            1
        }.getOrElse { exception ->
            ModuleLog.warn("DRAG_UNLOCK_CONFIG_HOOK_FAILED", exception)
            0
        }
    }

    /**
     * 原始 XML：只负责让应用侧的 init 不要提前 return。
     *
     * 系统只有在拿到原始 XML 时才会初始化拖拽阴影的尺寸（OplusViewDragShadowBuilder.init），
     * 否则阴影静态尺寸为 0。内容与补进去的通用条目保持一致，避免两套口径。
     */
    private fun hookRawXmlProvider(helper: Class<*>): Int {
        val method = OemReflection.findMethod(helper, "getPackageRawConfigXml", String::class.java) ?: return 0
        return runCatching {
            method.isAccessible = true
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.freedrag.rawxml")
                .intercept { chain ->
                    val original = chain.proceed() as? String
                    if (!original.isNullOrEmpty()) return@intercept original
                    if (!configuration.snapshot.dragUnlockActive) return@intercept original
                    if (environment.inGameMode) return@intercept original
                    val packageName = chain.getArg(0) as? String
                    if (!isRewritable(packageName)) return@intercept original
                    buildGenericXml(packageName as String)
                }
            1
        }.getOrElse { exception ->
            ModuleLog.warn("DRAG_UNLOCK_RAWXML_HOOK_FAILED", exception)
            0
        }
    }

    private fun isRewritable(packageName: String?): Boolean =
        !packageName.isNullOrEmpty() && packageName !in EXCLUDED_PACKAGES

    /**
     * 在原有白名单末尾追加通用条目。
     *
     * 应用侧 findViewDragList 命中第一条即返回，所以追加在末尾等于「原厂精确规则优先，
     * 没覆盖到的页面才走通用规则」，不会影响已经按控件调优过的那些应用。
     */
    private fun withGenericEntry(packageName: String, original: Any?): Any? {
        val packageType = packageClass ?: return original
        val activityType = activityClass ?: return original

        val merged = packageType.getDeclaredConstructor().newInstance()
        packageType.getMethod("setPackageName", String::class.java).invoke(merged, packageName)

        val originalWhite =
            original?.let {
                runCatching { it.javaClass.getMethod("getWhiteList").invoke(it) as? List<*> }
                    .getOrNull()
            }
        // 原厂已经把这个包登记得很全（大型应用常有 30 多个 Activity）时不再追加：
        // 追加是「每个 Activity 补一份 web 控件 + 末尾再挂一份通用条目」，条目数按
        // Activity 数线性膨胀，而 bind 载荷跟随 ApplicationInfo 一起下发。载荷达到 MB 级时
        // 应用会在 bindApplication 阶段反复被杀（error during bind）。
        // 这类大包的原厂规则本身已够用，追加的收益远小于风险。
        if (originalWhite != null && originalWhite.size > MAX_APPEND_ACTIVITIES) {
            if (VerboseLog.enabled) {
                ModuleLog.debug(TAG) {
                    "DRAG_UNLOCK_SKIP_LARGE_" + packageName + "_base" + originalWhite.size
                }
            }
            return original
        }
        val whiteList = ArrayList<Any?>()
        if (originalWhite != null) {
            // 原厂条目光克隆再补 web 控件：findViewDragList 命中第一条就返回，
            // 只靠末尾的通用条目救不了「原厂已登记但没写 WebView」的页面
            // （浏览器为典型场景：文章页的 WebView 被原厂 Activity 规则挡住）。
            for (entry in originalWhite) whiteList.add(withWebViews(entry, activityType))
        }
        whiteList.add(buildGenericActivity(packageName, activityType))
        packageType.getMethod("setWhiteList", List::class.java).invoke(merged, whiteList)

        if (original != null) {
            runCatching {
                val black = original.javaClass.getMethod("getBlackList").invoke(original) as? List<*>
                if (black != null) {
                    packageType.getMethod("setBlackList", List::class.java)
                        .invoke(merged, ArrayList<Any?>(black))
                }
                val global = original.javaClass.getMethod("getConfigDragInfo").invoke(original)
                if (global != null) {
                    packageType.getMethod("setConfigDragInfo", global.javaClass)
                        .invoke(merged, global)
                }
            }
        }
        if (VerboseLog.enabled) {
            ModuleLog.debug(TAG) {
                "DRAG_UNLOCK_CONFIG_" + packageName + "_base" + (originalWhite?.size ?: 0) +
                    "_generic" + genericViews.size +
                    "_entries" + (whiteList.size + (originalWhite?.size ?: 0) * webViews.size)
            }
        }
        return merged
    }

    /**
     * 克隆原厂的 Activity 条目，并在其控件列表末尾补上 web 控件。
     *
     * 只补 web 这一类：原厂条目的属性（parent / brother / type / source）保持原样，
     * 追加项排在最后，已有规则优先级不变，只覆盖它没登记的网页控件。
     */
    private fun withWebViews(entry: Any?, activityType: Class<*>): Any? {
        if (entry == null) return null
        val existing =
            runCatching { entry.javaClass.getMethod("getViewDragInfoList").invoke(entry) as? List<*> }
                .getOrNull() ?: return entry
        val merged = ArrayList<Any?>(existing)
        merged.addAll(webViews)
        val clone = runCatching {
            activityType.getDeclaredConstructor().newInstance().also { copy ->
                val name = entry.javaClass.getMethod("getActivityName").invoke(entry) as? String
                activityType.getMethod("setActivityName", String::class.java).invoke(copy, name)
                activityType.getMethod("setViewDragInfoList", List::class.java).invoke(copy, merged)
            }
        }.getOrNull() ?: return entry
        return clone
    }

    private fun buildGenericActivity(packageName: String, activityType: Class<*>): Any {
        val activity = activityType.getDeclaredConstructor().newInstance()
        // 用包名而不是空串：应用侧是 activityName 是否出现在窗口标题里（WindowManager 的标题
        // 形如 "包名/Activity"），包名一定能命中，且非空字符串不会在解析/传参时退化成 null。
        // 不能写空串：配置走 XML 重解析时，空属性会被解析成 null，
        // findViewDragList 里 title.contains(null) 直接抛 NPE，应用在触摸时崩溃。
        activityType.getMethod("setActivityName", String::class.java).invoke(activity, packageName)
        activityType.getMethod("setViewDragInfoList", List::class.java)
            .invoke(activity, ArrayList<Any?>(genericViews))
        return activity
    }

    /** 按 (类名, 类型) 批量构造 ViewDragInfo；表尽量精简，见 CORE_VIEWS 注释。 */
    private fun buildGenericViews(
        source: List<Pair<String, String>>,
    ): List<Any> {
        val viewType = viewClass ?: return emptyList()
        val constructor = runCatching { viewType.getDeclaredConstructor() }.getOrNull() ?: return emptyList()
        val nameSetter =
            runCatching { viewType.getMethod("setViewName", String::class.java) }.getOrNull()
                ?: return emptyList()
        val typeSetter =
            runCatching { viewType.getMethod("setType", String::class.java) }.getOrNull()
                ?: return emptyList()
        return source.mapNotNull { (name, type) ->
            runCatching {
                constructor.newInstance().also {
                    nameSetter.invoke(it, name)
                    typeSetter.invoke(it, type)
                }
            }.getOrNull()
        }
    }

    /**
     * 合成原始 XML：**只用来让应用侧的 init 跑起来**，不参与匹配。
     *
     * preBindApplication 里有一道分界：原始 XML 长度 ≥ 0x2000 会被压成 gz 下发，
     * 应用侧走「解压 + 重新解析」流程，用解析结果**覆盖**注入侧传入的配置对象；
     * 小于 0x2000 则只当作字符串存着，配置对象是唯一的匹配依据。
     *
     * 所以这里刻意不把控件表写进 XML：写进去必然超过 8KB，等于把匹配交给解析器
     * （属性缺失会被解析成 null，匹配必然失效）。保持短小，配置对象说了算。
     */
    private fun buildGenericXml(packageName: String): String = buildString {
        append("<packageConfig><whiteList><package name=\"")
        append(packageName)
        append("\"><activity name=\"")
        append(packageName)
        append("\"/></package></whiteList></packageConfig>")
    }

    private fun loadClass(loader: ClassLoader, name: String): Class<*>? =
        runCatching { loader.loadClass(name) }.getOrNull()
            ?: runCatching { Class.forName(name, false, loader) }.getOrNull()

    private companion object {
        const val TAG = "DragUnlock"
        const val HELPER_CLASS = "com.android.server.viewdrag.OplusViewDragRusHelper"
        const val PACKAGE_CLASS = "android.view.viewdrag.bean.PackageDragConfigInfo"
        const val ACTIVITY_CLASS = "android.view.viewdrag.bean.ActivityDragInfo"
        const val VIEW_CLASS = "android.view.viewdrag.bean.ViewDragInfo"

        /**
         * 原厂 Activity 条目超过这个数就不再追加。
         *
         * 注入侧的追加是「每个 Activity 补一份 web 控件表 + 末尾再挂一份通用条目」，
         * 条目数随 Activity 数线性膨胀；这些条目随 ApplicationInfo 进 binder，
         * 超过 1MB 时应用会绑定失败。原厂登记了
         * 这么多 Activity 的包，本身覆盖已经很全，兜底收益远小于风险。
         */
        const val MAX_APPEND_ACTIVITIES = 8

        /**
         * 不参与「解除限制」的包。
         *
         * 桌面与系统界面以长按承担排序/编辑操作，其图标大量由 ImageView/TextView 承载；
         * 纳入白名单会与系统自身的长按手势冲突。设置页同理，长按条目易被误触。
         */
        val EXCLUDED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.launcher",
            "com.android.launcher3",
            "com.oplus.launcher",
        )

        /**
         * 核心控件表（类名 → 拖拽类型）。
         *
         * **不要往这里堆长尾类名。** 这份表随 ApplicationInfo 进 binder，而应用发布
         * ContentProvider 时每个 ProviderInfo 都带一份 ApplicationInfo —— 条目数会被
         * 「provider 数」放大。284 条通用控件 × 多个 provider 就会超过 1MB 上限，
         * 应用在 Application.onCreate 阶段直接 abort（梦幻西游 TransactionTooLargeException
         * 就是这么来的）。只保留覆盖面最高的文本/图片/网页控件。
         */
        val CORE_VIEWS = listOf(
            "TextView" to "text",
            "AppCompatTextView" to "text",
            "ImageView" to "image",
            "AppCompatImageView" to "image",
            "WebView" to "web",
            "X5WebView" to "web",
            "SimpleDraweeView" to "image",
            "CircleImageView" to "image",
            "ReactTextView" to "text",
            "ReactImageView" to "image",
        )

        /**
         * 追加到原厂 Activity 条目里的网页控件。
         *
         * 只补网页这一类：浏览器文章页缺的就是它。条目数会乘以 Activity 数，
         * 所以这里同样保持个位数。
         */
        val APPEND_VIEWS = listOf(
            "WebView" to "web",
            "X5WebView" to "web",
            "WebViewAndroid" to "web",
            "BrowserWebViewEx" to "web",
        )
    }
}