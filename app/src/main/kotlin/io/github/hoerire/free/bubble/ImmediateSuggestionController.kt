package io.github.hoerire.free.bubble

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** ColorOS 侧的规则引擎：产出 Flyme 风格的即时建议。 */
internal class ImmediateSuggestionController(
    private val context: Context,
    private val host: Host,
) {
    interface Host {
        fun isSuggestionEnabled(type: String): Boolean

        fun isNativeClipboardSuggestionAvailable(): Boolean

        fun getForegroundPackage(): String?

        fun showSuggestion(suggestion: Suggestion)

        fun dismissSuggestion(id: String)

        fun logSuggestion(message: String, error: Throwable?)
    }

    data class Suggestion(
        val id: String,
        val sourcePackage: String,
        val icon: Drawable?,
        val appIcon: Drawable?,
        val title: String,
        val body: String,
        val action: (() -> Unit)?,
        val timeoutSeconds: Int,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val cameraManager: CameraManager? = context.getSystemService(CameraManager::class.java)
    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)
    private val clipboardManager: ClipboardManager? =
        context.getSystemService(ClipboardManager::class.java)
    private val packageManager: PackageManager = context.packageManager
    /** 浏览器包名集合：进程内缓存，链接解析时用来区分「应用」与「浏览器」。 */
    private var browsers: Set<String>? = null
    private val activeTorchIds = HashSet<String>()
    private var lastClipboardText: String? = null
    /** 上一次成功展示的剪贴板内容与时刻：两条来路共用的去重窗。 */
    private var lastClipboardValue: String? = null
    private var lastClipboardAt = 0L
    private var pendingClipboardFallback: Runnable? = null
    private var started = false

    /** 稳定对象：手电筒延时回调要按身份 removeCallbacks。 */
    private val torchSuggestionAction = Runnable { showTorchSuggestionIfNeeded() }

    /**
     * 常亮建议：半暗检测与「点气泡后加锁保持」由 [PreSleepKeepAwakeController] 负责，
     * 这里只把它的结果接到同一条建议通路上，弹出与其它建议一样的气泡。
     */
    private val keepAwakeController =
        PreSleepKeepAwakeController(
            context,
            object : PreSleepKeepAwakeController.Host {
                override fun isEnabled(): Boolean = host.isSuggestionEnabled(TYPE_KEEP_AWAKE)

                override fun foregroundPackage(): String? = host.getForegroundPackage()

                override fun showKeepAwakeSuggestion(engage: () -> Unit) {
                    host.showSuggestion(
                        Suggestion(
                            TYPE_KEEP_AWAKE,
                            context.packageName,
                            MaterialSuggestionIcon(MaterialSuggestionIcon.KEEP_AWAKE),
                            null,
                            "保持常亮",
                            "屏幕即将变暗，点击保持常亮",
                            engage,
                            // 0 = 跟随「气泡停留」设置：停留多久由用户定，到时自己消失。
                            USE_BUBBLE_TIMEOUT,
                        ),
                    )
                }

                override fun dismissSuggestion(id: String) {
                    host.dismissSuggestion(id)
                }

                override fun log(code: String, error: Throwable?) {
                    host.logSuggestion(code, error)
                }
            },
        )

    private val torchCallback: CameraManager.TorchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (enabled) {
                activeTorchIds.add(cameraId)
                handler.removeCallbacks(torchSuggestionAction)
                handler.postDelayed(torchSuggestionAction, TORCH_DELAY_MS)
            } else {
                activeTorchIds.remove(cameraId)
                if (activeTorchIds.isEmpty()) {
                    handler.removeCallbacks(torchSuggestionAction)
                    host.dismissSuggestion(TYPE_FLASHLIGHT)
                }
            }
        }
    }

    private val audioDeviceCallback: AudioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            for (device in addedDevices) {
                if (HEADSET_TYPES.contains(device.type)) {
                    handler.postDelayed({ suggestMusicApp() }, 600L)
                    break
                }
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            if (!hasConnectedHeadset()) host.dismissSuggestion(TYPE_MUSIC)
        }
    }

    private val packageReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            if (Intent.ACTION_PACKAGE_ADDED != intent.action) return
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            val data = intent.data ?: return
            val packageName = data.schemeSpecificPart
            if (packageName != null && packageName.isNotEmpty()) {
                val installedPackage: String = packageName
                handler.postDelayed({ suggestInstalledApp(installedPackage) }, 1_500L)
            }
        }
    }

    private val clipboardListener: ClipboardManager.OnPrimaryClipChangedListener =
        ClipboardManager.OnPrimaryClipChangedListener { inspectClipboard() }

    fun start() {
        if (started) return
        started = true
        try {
            cameraManager?.registerTorchCallback(torchCallback, handler)
        } catch (error: Throwable) {
            host.logSuggestion("torch callback registration failed", error)
        }
        try {
            audioManager?.registerAudioDeviceCallback(audioDeviceCallback, handler)
        } catch (error: Throwable) {
            host.logSuggestion("audio device callback registration failed", error)
        }
        try {
            val packageFilter = IntentFilter(Intent.ACTION_PACKAGE_ADDED)
            packageFilter.addDataScheme("package")
            context.registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_EXPORTED)
        } catch (error: Throwable) {
            host.logSuggestion("package receiver registration failed", error)
        }
        try {
            clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
        } catch (error: Throwable) {
            host.logSuggestion("clipboard listener registration failed", error)
        }
        keepAwakeController.setEnabled(host.isSuggestionEnabled(TYPE_KEEP_AWAKE))
    }

    /** 设置变动：只让常亮建议跟随开关，其它规则按需即时读 `Host`，无需重启监听。 */
    fun onSettingsChanged() {
        keepAwakeController.setEnabled(host.isSuggestionEnabled(TYPE_KEEP_AWAKE))
    }

    private fun showTorchSuggestionIfNeeded() {
        if (!host.isSuggestionEnabled(TYPE_FLASHLIGHT) || activeTorchIds.isEmpty()) return
        val foreground = host.getForegroundPackage()
        if (foreground != null && foreground.lowercase(Locale.ROOT).contains("camera")) return
        val icon: Drawable = MaterialSuggestionIcon(MaterialSuggestionIcon.FLASHLIGHT)
        host.showSuggestion(
            Suggestion(
                TYPE_FLASHLIGHT, context.packageName, icon, null,
                "关闭手电筒", "手电筒已持续开启，点击立即关闭",
                { turnOffTorch() }, 0,
            ),
        )
    }

    private fun turnOffTorch() {
        val cameras = cameraManager ?: return
        for (cameraId in HashSet(activeTorchIds)) {
            try {
                cameras.setTorchMode(cameraId, false)
            } catch (error: Throwable) {
                host.logSuggestion("unable to turn off torch " + cameraId, error)
            }
        }
    }

    private fun suggestMusicApp() {
        if (!host.isSuggestionEnabled(TYPE_MUSIC)) return
        val audio = audioManager ?: return
        if (audio.isMusicActive || !hasConnectedHeadset()) return
        val musicIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
        val target = firstLaunchTarget(musicIntent, host.getForegroundPackage())
        val targetInfo = target?.activityInfo
        val launch: Intent
        val packageName: String
        val label: String
        val icon: Drawable?
        if (target != null && targetInfo != null) {
            packageName = targetInfo.packageName
            launch = Intent(musicIntent)
                .setClassName(packageName, targetInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            label = "${target.loadLabel(packageManager)}"
            icon = target.loadIcon(packageManager)
        } else {
            packageName = firstInstalledPackage(MUSIC_PACKAGES, host.getForegroundPackage()) ?: return
            launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            var resolvedLabel: String? = null
            var resolvedIcon: Drawable? = null
            try {
                val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
                val applicationLabel = "${packageManager.getApplicationLabel(applicationInfo)}"
                val applicationIcon = packageManager.getApplicationIcon(packageName)
                resolvedLabel = applicationLabel
                resolvedIcon = applicationIcon
            } catch (ignored: Throwable) {
                // 读不到包信息时退回通用文案与系统播放图标。
            }
            label = resolvedLabel ?: "音乐"
            icon = resolvedIcon ?: context.getDrawable(android.R.drawable.ic_media_play)
        }
        host.showSuggestion(
            Suggestion(
                TYPE_MUSIC, packageName,
                MaterialSuggestionIcon(MaterialSuggestionIcon.MUSIC), icon,
                "打开" + label, "已连接耳机，点击开始播放音乐",
                { startActivity(launch) }, 10,
            ),
        )
    }

    private fun hasConnectedHeadset(): Boolean {
        val audio = audioManager ?: return false
        try {
            for (device in audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (HEADSET_TYPES.contains(device.type)) return true
            }
        } catch (ignored: Throwable) {
        }
        return false
    }

    private fun suggestInstalledApp(packageName: String) {
        if (!host.isSuggestionEnabled(TYPE_APP_INSTALL)) return
        if (packageName == host.getForegroundPackage()) return
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            val label = packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0),
            )
            val icon = packageManager.getApplicationIcon(packageName)
            host.showSuggestion(
                Suggestion(
                    TYPE_APP_INSTALL, packageName,
                    MaterialSuggestionIcon(MaterialSuggestionIcon.INSTALL), icon,
                    "打开新应用", "“" + label + "”已安装完成",
                    { startActivity(launch) }, 10,
                ),
            )
        } catch (error: Throwable) {
            host.logSuggestion("installed app suggestion failed for " + packageName, error)
        }
    }

    private fun inspectClipboard() {
        if (!host.isSuggestionEnabled(TYPE_CLIPBOARD)) return
        val clipboard = clipboardManager ?: return
        try {
            val description: ClipDescription? = clipboard.primaryClipDescription
            val extras = description?.extras
            if (extras != null && extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)) {
                return
            }
            val clip: ClipData? = clipboard.primaryClip
            if (clip == null || clip.itemCount == 0) return
            val value: CharSequence? = clip.getItemAt(0).coerceToText(context)
            val text = value?.toString()?.trim().orEmpty()
            if (text.isEmpty() || text.length > 4096 || text == lastClipboardText) return
            lastClipboardText = text
            classifyClipboard(text)
        } catch (error: Throwable) {
            host.logSuggestion("clipboard inspection failed", error)
        }
    }

    private fun classifyClipboard(text: String) {
        val result = ClipboardClassifier.classifyAll(text).firstOrNull()
        if (result == null) {
            host.logSuggestion("clipboard: nothing actionable", null)
            return
        }
        // 与 system_server 侧广播路径共用同一套 id，避免同一次复制出两颗气泡。
        showClassifiedSuggestion(clipboardSuggestionId(result), result)
    }

    private fun clipboardSuggestionId(result: ClipboardClassifier.Result): String =
        TYPE_CLIPBOARD + ":" + ClipboardClassifier.kindTag(result.kind)

    /** 这段内容是不是刚刚已经弹过（两条来路共用同一时间窗）。 */
    fun isDuplicateClipboard(value: String): Boolean =
        value == lastClipboardValue &&
            SystemClock.elapsedRealtime() - lastClipboardAt < CLIPBOARD_DEDUPE_MS

    /**
     * 为一条已分类的剪贴板内容构建气泡。进程内（很少送达的）剪贴板监听与
     * system_server 侧的剪贴板钩子都走这里，保证两条路径行为一致。
     */
    fun showClassifiedSuggestion(result: ClipboardClassifier.Result) {
        showClassifiedSuggestion(TYPE_CLIPBOARD, result)
    }

    /**
     * 同 [showClassifiedSuggestion]，但可以指定建议 id，这样同一次复制的多条
     * 剪贴板结果能在气泡队列里并排存在，而不是互相顶掉。
     */
    fun showClassifiedSuggestion(suggestionId: String, result: ClipboardClassifier.Result) {
        if (result.value.isEmpty()) return
        if (!host.isSuggestionEnabled(TYPE_CLIPBOARD)) return
        // 同一次复制会同时从两条路进来（system_server 广播 + 本进程监听），
        // 按内容 + 时间窗去重，避免一次复制弹出两颗一样的气泡。
        if (isDuplicateClipboard(result.value)) {
            host.logSuggestion("clipboard: duplicate ignored", null)
            return
        }
        lastClipboardValue = result.value
        lastClipboardAt = SystemClock.elapsedRealtime()
        val id = if (suggestionId.isEmpty()) TYPE_CLIPBOARD else suggestionId
        host.logSuggestion("clipboard suggestion: " + ClipboardClassifier.kindTag(result.kind), null)
        when (result.kind) {
            ClipboardClassifier.KIND_PHONE -> {
                val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + result.value))
                showClipboardIntentSuggestion(
                    id, dial, "拨打电话", result.value, MaterialSuggestionIcon.PHONE,
                )
                return
            }

            ClipboardClassifier.KIND_EXPRESS -> {
                val encoded = URLEncoder.encode(result.value, StandardCharsets.UTF_8)
                showIntentSuggestion(
                    id,
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://m.kuaidi100.com/result.jsp?nu=" + encoded),
                    ),
                    "查询快递", "快递单号 " + result.value, 10,
                    MaterialSuggestionIcon.PACKAGE,
                )
                return
            }

            ClipboardClassifier.KIND_ADDRESS -> {
                val geo = Uri.parse("geo:0,0?q=" + Uri.encode(result.value))
                showIntentSuggestion(
                    id, Intent(Intent.ACTION_VIEW, geo),
                    "查看地址", result.value, 10, MaterialSuggestionIcon.LOCATION,
                )
                return
            }

            ClipboardClassifier.KIND_COORDINATES -> {
                // "lat,lng" 直接交给地图应用，不需要逆地理编码。
                val geo = Uri.parse("geo:" + result.value + "?q=" + result.value)
                showIntentSuggestion(
                    id, Intent(Intent.ACTION_VIEW, geo),
                    "在地图中查看坐标", result.value, 10, MaterialSuggestionIcon.LOCATION,
                )
                return
            }

            ClipboardClassifier.KIND_SHOPPING,
            ClipboardClassifier.KIND_LINK -> {
                val shopping = result.kind == ClipboardClassifier.KIND_SHOPPING
                val uri = Uri.parse(result.value)
                val hostName = ClipboardClassifier.hostOf(result.value)
                val body = hostName?.takeIf { it.isNotEmpty() } ?: result.value
                val target = appTargetFor(uri, result.appPackage)
                val title: String
                val intent: Intent
                if (target != null) {
                    title = "用" + target.info.loadLabel(packageManager) + "打开"
                    intent = Intent(Intent.ACTION_VIEW, target.uri).setClassName(
                        target.info.activityInfo.packageName,
                        target.info.activityInfo.name,
                    )
                } else {
                    // 认不出具体应用时交给浏览器：优先指名默认浏览器，气泡标题与其它建议一致。
                    val browser = browserTargetFor(uri)
                    title = if (browser != null) {
                        "用" + browser.loadLabel(packageManager) + "打开"
                    } else if (shopping) {
                        "打开购物链接"
                    } else {
                        "打开链接"
                    }
                    intent = if (browser != null) {
                        Intent(Intent.ACTION_VIEW, uri).setClassName(
                            browser.activityInfo.packageName,
                            browser.activityInfo.name,
                        )
                    } else {
                        Intent(Intent.ACTION_VIEW, uri)
                    }
                }
                host.logSuggestion(
                    "clipboard link " + (hostName ?: "") + " -> " +
                        (target?.info?.activityInfo?.packageName ?: "browser"),
                    null,
                )
                showClipboardIntentSuggestion(
                    id, intent, title, body,
                    if (shopping) MaterialSuggestionIcon.PACKAGE else MaterialSuggestionIcon.LINK,
                )
                return
            }

            ClipboardClassifier.KIND_APP_LINK -> {
                val uri = Uri.parse(result.value)
                val intent = Intent(Intent.ACTION_VIEW, uri)
                val appPackage = result.appPackage
                if (appPackage != null && isInstalled(appPackage)) intent.setPackage(appPackage)
                val target: ResolveInfo? =
                    packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                val targetInfo = target?.activityInfo
                if (target == null || targetInfo == null) return
                showClipboardIntentSuggestion(
                    id, intent,
                    "用" + target.loadLabel(packageManager) + "打开",
                    result.value, MaterialSuggestionIcon.LINK,
                )
                return
            }
        }
    }

    private fun isInstalled(packageName: String?): Boolean {
        val name = packageName ?: return false
        if (name.isEmpty()) return false
        return try {
            packageManager.getApplicationInfo(name, 0)
            true
        } catch (ignored: Throwable) {
            false
        }
    }

    private fun showClipboardIntentSuggestion(
        suggestionId: String,
        intent: Intent,
        title: String,
        body: String,
        iconKind: Int,
    ) {
        val id = if (suggestionId.isEmpty()) TYPE_CLIPBOARD else suggestionId
        val fallback = Runnable { showIntentSuggestion(id, intent, title, body, 10, iconKind) }
        pendingClipboardFallback?.let { handler.removeCallbacks(it) }
        pendingClipboardFallback = null
        // 只有主剪贴板条目让位给 ColorOS 原生剪贴板浮层；同一份内容的其它结果应立即出现。
        if (TYPE_CLIPBOARD != id || !host.isNativeClipboardSuggestionAvailable()) {
            fallback.run()
            return
        }
        pendingClipboardFallback = fallback
        handler.postDelayed(fallback, NATIVE_CLIPBOARD_GRACE_MS)
    }

    fun onNativeClipboardSuggestionObserved() {
        val fallback = pendingClipboardFallback ?: return
        handler.removeCallbacks(fallback)
        pendingClipboardFallback = null
        host.dismissSuggestion(TYPE_CLIPBOARD)
    }

    private fun showIntentSuggestion(
        id: String,
        intent: Intent,
        title: String,
        body: String,
        timeoutSeconds: Int,
        iconKind: Int,
    ) {
        val target: ResolveInfo? =
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val targetInfo = target?.activityInfo
        var appIcon: Drawable? = null
        var sourcePackage = context.packageName
        if (target != null && targetInfo != null) {
            sourcePackage = targetInfo.packageName
            appIcon = target.loadIcon(packageManager)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        host.showSuggestion(
            Suggestion(
                id, sourcePackage,
                MaterialSuggestionIcon(iconKind), appIcon,
                title, body, { startActivity(intent) }, timeoutSeconds,
            ),
        )
    }

    private fun firstLaunchTarget(intent: Intent, excludedPackage: String?): ResolveInfo? {
        val targets = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        for (target in targets) {
            val activityInfo = target.activityInfo ?: continue
            if (activityInfo.packageName != excludedPackage) return target
        }
        return null
    }

    private fun firstInstalledPackage(packages: Array<String>, excludedPackage: String?): String? {
        for (packageName in packages) {
            if (packageName == excludedPackage) continue
            try {
                if (packageManager.getLaunchIntentForPackage(packageName) != null) {
                    return packageName
                }
            } catch (ignored: Throwable) {
            }
        }
        return null
    }

    /** 一条链接最终用哪个应用、哪个 URI 打开（URI 可能被改写成该应用声明的域名）。 */
    private data class LinkTarget(val info: ResolveInfo, val uri: Uri)

    /**
     * 用哪个应用打开这条链接。三层判断，优先认出具体应用：
     *
     * 1. 域名表给出包名，且该包确实能处理这个链接；
     * 2. 包处理不了原域名时，试一下它常用的 App Link 前缀域名（applink.<host>）——
     *    国内分享短链经常是网页域名，App 只在自己的 applink 域名上注册了入口；
     * 3. 让系统列出所有能打开它的应用，取第一个非浏览器。
     *
     * 都只命中浏览器时返回 null，由调用方退化成浏览器兜底。
     */
    private fun appTargetFor(uri: Uri, appPackage: String?): LinkTarget? {
        if (appPackage != null && isInstalled(appPackage)) {
            resolveInPackage(uri, appPackage)?.let { return LinkTarget(it, uri) }
            appLinkUri(uri)?.let { rewritten ->
                resolveInPackage(rewritten, appPackage)?.let { return LinkTarget(it, rewritten) }
            }
        }
        val targets = runCatching {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_VIEW, uri),
                PackageManager.MATCH_DEFAULT_ONLY,
            )
        }.getOrNull().orEmpty()
        val target = targets.firstOrNull {
            val info = it.activityInfo ?: return@firstOrNull false
            !isBrowserPackage(info.packageName)
        } ?: return null
        return LinkTarget(target, uri)
    }

    private fun resolveInPackage(uri: Uri, packageName: String): ResolveInfo? = runCatching {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_VIEW, uri).setPackage(packageName),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
    }.getOrNull()?.takeIf { !isBrowserPackage(it.activityInfo?.packageName) }

    /** 网页分享域名 → 应用注册的 applink 域名；已经带前缀或没有域名时返回 null。 */
    private fun appLinkUri(uri: Uri): Uri? {
        val host = uri.host ?: return null
        if (host.startsWith("applink.")) return null
        return runCatching { uri.buildUpon().authority("applink." + host).build() }.getOrNull()
    }

    /** 默认浏览器：认不出应用时用它兜底。 */
    private fun browserTargetFor(uri: Uri): ResolveInfo? =
        runCatching {
            packageManager.resolveActivity(
                Intent(Intent.ACTION_VIEW, uri),
                PackageManager.MATCH_DEFAULT_ONLY,
            )
        }.getOrNull()?.takeIf { !it.activityInfo?.packageName.isNullOrEmpty() }

    /**
     * 浏览器包名集合：按 Android 的「浏览器」分类查，而不是猜包名。
     * 名字判断只作为兜底——国内浏览器（Via、夸克、UC 等）不一定带 browser 字样。
     */
    private fun browserPackages(): Set<String> {
        browsers?.let { return it }
        val found = HashSet<String>()
        runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
            packageManager.queryIntentActivities(intent, 0).forEach { target ->
                target.activityInfo?.packageName?.let(found::add)
            }
        }
        browsers = found
        return found
    }

    private fun isBrowserPackage(packageName: String?): Boolean {
        val name = packageName ?: return false
        if (name in browserPackages()) return true
        val normalized = name.lowercase(Locale.ROOT)
        return normalized.contains("browser") || normalized.contains("chrome") ||
            normalized.contains("edge") || normalized.contains("firefox") ||
            normalized.contains("opera") || normalized.contains("quark") ||
            normalized == "mark.via" || normalized.contains("ucmobile") ||
            normalized.contains("sogou") || normalized.contains("baidu.searchbox") ||
            normalized.contains("qihoo") || normalized.contains("vivo.browser") ||
            normalized.contains("mi.globalbrowser") || normalized.contains("miui.browser")
    }

    private fun startActivity(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: Throwable) {
            host.logSuggestion("suggestion activity launch failed: " + intent, error)
        }
    }

    companion object {
        const val TYPE_FLASHLIGHT = "flashlight"
        const val TYPE_MUSIC = "music"
        const val TYPE_APP_INSTALL = "app_install"
        const val TYPE_CLIPBOARD = "clipboard"
        const val TYPE_KEEP_AWAKE = "keep_awake"

        /** 建议自带停留时间：0 = 跟随「气泡停留」设置，负数 = 不自动消失。 */
        const val USE_BUBBLE_TIMEOUT = 0

        private const val TORCH_DELAY_MS = 5_000L
        private const val NATIVE_CLIPBOARD_GRACE_MS = 1_800L
        private const val CLIPBOARD_DEDUPE_MS = 2_500L

        private val MUSIC_PACKAGES = arrayOf(
            "com.heytap.music", "com.oplus.music", "com.netease.cloudmusic",
            "com.tencent.qqmusic", "com.kugou.android",
            "com.google.android.apps.youtube.music", "com.spotify.music",
        )

        private val HEADSET_TYPES: Set<Int> = HashSet(
            listOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
            ),
        )
    }
}
