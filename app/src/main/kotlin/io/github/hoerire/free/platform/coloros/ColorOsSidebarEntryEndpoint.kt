package io.github.hoerire.free.platform.coloros

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.config.PinnedTarget
import io.github.hoerire.free.config.PinnedTargetCodec
import io.github.hoerire.free.hook.ProcessConfiguration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 目录与单项执行各占用一次有时限的绑定；任一调用都只接受 system UID。 */
internal class ColorOsSidebarEntryEndpoint(
    val service: Service,
    private val loader: ClassLoader,
    private val configuration: ProcessConfiguration,
    private val log: (Int, String, Throwable?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, ArrayBlockingQueue(4),
        { task -> Thread(task, "Free-Entries") }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    private val messenger = Messenger(Handler(handler.looper, ::receive))
    private val history = EntryRequestHistory()
    private var current: Request? = null
    private var disposed = false
    private var lastFailure = -5_000L
    private val invalidation = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { current?.let(::finish) }
    }
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ModulePreferences.ACTION_SETTINGS_PUSHED) {
                if (sentFromUid != moduleUid()) return
                val map = io.github.hoerire.free.config.SettingsBridge.readMap(intent.extras)
                    ?: return
                val next = try {
                    io.github.hoerire.free.config.SettingsBridge.decode(map)
                } catch (_: RuntimeException) {
                    return
                }
                configuration.override(next)
                return
            }
            if (intent?.action != ModulePreferences.ACTION_PINNED_TARGETS_CHANGED ||
                sentFromUid != moduleUid()
            ) return
            val encoded = intent.getStringExtra(ModulePreferences.EXTRA_PINNED_TARGETS) ?: return
            // 广播现在覆盖全部设置变更：先重读磁盘快照，再叠上本次推送的固定项。
            configuration.forceReload()
            configuration.override(
                configuration.snapshot.copy(
                    enabled = intent.getBooleanExtra(
                        ModulePreferences.EXTRA_MODULE_ENABLED,
                        configuration.snapshot.enabled,
                    ),
                    pinsSaved = intent.getBooleanExtra(
                        ModulePreferences.EXTRA_PINS_SAVED,
                        configuration.snapshot.pinsSaved,
                    ),
                    pinnedTargets = PinnedTargetCodec.decodeList(encoded),
                ),
            )
        }
    }

    val binder: IBinder get() = messenger.binder

    init {
        service.registerReceiver(invalidation, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(SidebarProtocol.ACTION_USER_SWITCHED)
        }, Context.RECEIVER_NOT_EXPORTED)
        service.registerReceiver(
            settingsReceiver,
            IntentFilter(ModulePreferences.ACTION_PINNED_TARGETS_CHANGED).apply {
                addAction(ModulePreferences.ACTION_SETTINGS_PUSHED)
            },
            Context.RECEIVER_EXPORTED,
        )
    }

    fun onUnbound(id: String?) { current?.takeIf { it.id == id }?.let(::finish) }

    fun dispose() {
        if (disposed) return
        disposed = true
        current?.let(::finish)
        try { service.unregisterReceiver(invalidation) }
        catch (exception: RuntimeException) { report(exception) }
        try { service.unregisterReceiver(settingsReceiver) }
        catch (exception: RuntimeException) { report(exception) }
        finally {
            worker.shutdownNow()
            handler.removeCallbacksAndMessages(null)
        }
    }

    private fun receive(message: Message): Boolean {
        if (disposed || message.sendingUid != Process.SYSTEM_UID ||
            message.arg1 != SidebarEntryProtocol.VERSION
        ) {
            log(
                Log.WARN,
                "SIDEBAR_ENTRY_RECV_REJECT_d" + disposed +
                    "_uid" + message.sendingUid + "_v" + message.arg1 +
                    "_expect" + SidebarEntryProtocol.VERSION,
                null,
            )
            return true
        }
        try {
            val data = message.peekData() ?: return true
            val id = data.getString(SidebarEntryProtocol.ID) ?: return true
            val reply = message.replyTo ?: return true
            if (!SidebarProtocol.isValidRequestId(id)) return true
            if (message.what == SidebarEntryProtocol.CANCEL) {
                current?.takeIf { it.id == id && it.reply.binder == reply.binder }?.let(::finish)
                return true
            }
            if (message.what == SidebarEntryProtocol.EXECUTE) {
                val request = current?.takeIf { it.id == id && it.reply.binder == reply.binder } ?: return true
                val entries = request.prepared ?: return true
                request.prepared = null
                val target = request.target ?: return true
                if (SystemClock.uptimeMillis() >= request.deadline || !launchAllowed(target) || !entries.sidebarHidden()) {
                    finish(request)
                    return true
                }
                execute(request) {
                    val bean = entries.resolve(target)
                    handler.post {
                        if (current !== request || !request.active.get()) return@post
                        try {
                            val dispatched = bean != null && SystemClock.uptimeMillis() < request.deadline &&
                                launchAllowed(target) && entries.sidebarHidden() && entries.launch(target, bean)
                            execute(request) {
                                send(request, SidebarEntryProtocol.message(
                                    if (dispatched) SidebarEntryProtocol.DISPATCHED else SidebarEntryProtocol.FAILED, request.id,
                                ))
                                handler.post { finish(request) }
                            }
                        } catch (exception: Exception) { report(exception); finish(request) }
                    }
                }
                return true
            }
            if (message.what != SidebarEntryProtocol.LIST && message.what != SidebarEntryProtocol.LAUNCH) return true
            if (current != null) return true
            val deadline = data.getLong(SidebarEntryProtocol.DEADLINE)
            if (!SidebarProtocol.isValidDeadline(deadline, SystemClock.uptimeMillis(), SidebarEntryProtocol.TIMEOUT_MS)) {
                log(Log.WARN, "SIDEBAR_ENTRY_RECV_DEADLINE", null)
                return true
            }
            if (!allowed()) {
                log(Log.WARN, "SIDEBAR_ENTRY_RECV_NOT_ALLOWED_" + allowedDetail(), null)
                return true
            }
            val target = if (message.what == SidebarEntryProtocol.LAUNCH) {
                data.getString(SidebarEntryProtocol.TARGET)?.let(PinnedTargetCodec::decode) as? PinnedTarget.Sidebar ?: return true
            } else null
            if (target != null && !launchAllowed(target)) {
                log(
                    Log.WARN,
                    "SIDEBAR_ENTRY_RECV_TARGET_REJECT_" + target.alias +
                        "_pinned" + configuration.snapshot.isPinned(target) +
                        "_en" + configuration.snapshot.enabled,
                    null,
                )
                return true
            }
            if (!history.claim(id, deadline, SystemClock.uptimeMillis())) return true
            val request = Request(id, deadline, reply, target)
            current = request
            reply.binder.linkToDeath(request.death, 0)
            request.linked = true
            handler.postAtTime(request.timeout, deadline)
            execute(request) {
                val entries = ColorOsSidebarEntries(service, loader)
                handler.post { prepare(request, entries) }
            }
        } catch (exception: Exception) {
            report(exception)
            current?.let(::finish)
        }
        return true
    }

    private fun prepare(request: Request, entries: ColorOsSidebarEntries) {
        if (current !== request || !request.active.get()) return
        try {
            if (!allowed()) { finish(request); return }
            val target = request.target
            if (target == null) {
                // 目录只有一个来源：观察结果。老探针链路已经整段删除，
                // 因此这里既不看 ready、也不唤醒任何 OEM 单例。
                execute(request) {
                    val userId = Process.myUid() / 100_000
                    var loaded =
                        runCatching { entries.mapObserved(userId) }.getOrNull().orEmpty()
                    // 侧边栏进程刚起来时，OEM 还没自己枚举过一次，观察表是空的。
                    // 与其让 App 干等满 7 秒再超时，不如在这里等一小段（面板通常几秒内就构建），
                    // 仍然没有就立刻回 FAILED，让 App 按退避重试。
                    if (loaded.isEmpty()) {
                        val until = minOf(
                            SystemClock.uptimeMillis() + OBSERVATION_WAIT_MS,
                            request.deadline - OBSERVATION_WAIT_GUARD_MS,
                        )
                        while (loaded.isEmpty() && request.active.get() &&
                            SystemClock.uptimeMillis() < until
                        ) {
                            try {
                                Thread.sleep(OBSERVATION_POLL_MS)
                            } catch (_: InterruptedException) {
                                break
                            }
                            loaded = runCatching { entries.mapObserved(userId) }.getOrNull().orEmpty()
                        }
                    }
                    log(Log.DEBUG, "SIDEBAR_CATALOG_OBSERVED_ENTRIES_" + loaded.size, null)
                    if (loaded.isEmpty()) {
                        // 面板从未构建过：目录为空是预期行为（模块依赖「更多」面板），
                        // 立刻回 FAILED，别让 App 卡在 Loading 里等到超时。
                        log(Log.DEBUG, "SIDEBAR_CATALOG_NOT_OBSERVED", null)
                        send(request, SidebarEntryProtocol.message(SidebarEntryProtocol.FAILED, request.id))
                        handler.post { finish(request) }
                        return@execute
                    }
                    for ((index, entry) in loaded.withIndex()) {
                        if (!request.active.get() || SystemClock.uptimeMillis() >= request.deadline || !allowed()) return@execute
                        request.reply.send(SidebarEntryProtocol.item(request.id, index, entry))
                    }
                    send(request, SidebarEntryProtocol.message(SidebarEntryProtocol.COMPLETE, request.id).apply {
                        data.putInt(SidebarEntryProtocol.COUNT, loaded.size)
                    })
                    handler.post { finish(request) }
                }
            } else {
                // 启动固定条目仍走原生路由，这里才需要等面板就绪。
                val ready = entries.ready()
                if (!ready) {
                    request.readyRetries++
                    if (request.readyRetries < READY_RETRY_LIMIT) {
                        handler.postDelayed({ prepare(request, entries) }, 50L)
                        return
                    }
                    log(Log.WARN, "SIDEBAR_ENTRY_READY_FALLBACK", null)
                }
                if (!launchAllowed(target) || (ready && !entries.sidebarHidden())) { finish(request); return }
                request.prepared = entries
                execute(request) { send(request, SidebarEntryProtocol.message(SidebarEntryProtocol.PREPARED, request.id)) }
            }
        } catch (exception: Exception) { report(exception); finish(request) }
    }

    private fun allowed(): Boolean = configuration.isAvailable &&
        service.getSystemService(UserManager::class.java)?.isUserForeground == true &&
        service.getSystemService(UserManager::class.java)?.isUserUnlocked == true &&
        service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false &&
        Settings.Secure.getInt(service.contentResolver, "edge_panel_toggle", -1) == 1

    /** 逐项输出 allowed() 的判定结果，定位配置没读到的具体原因。 */
    private fun allowedDetail(): String = "avail" + configuration.isAvailable +
        "_fg" + (service.getSystemService(UserManager::class.java)?.isUserForeground == true) +
        "_unlock" + (service.getSystemService(UserManager::class.java)?.isUserUnlocked == true) +
        "_lock" + (service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false) +
        "_toggle" + Settings.Secure.getInt(service.contentResolver, "edge_panel_toggle", -1)

    /**
     * 条目启动许可。
     *
     * 固定项列表不参与判定：目标由 system_server 侧从扇形已提交的条目下发，
     * 这里再要求一次"在当前固定列表里"，会让跨进程快照没同步到的条目被静默丢弃。
     * 仍然要求目标属于当前用户、模块开启、侧边栏可用。
     */
    private fun launchAllowed(target: PinnedTarget.Sidebar): Boolean = allowed() &&
        target.userId == (Process.myUid() / 100_000) && configuration.snapshot.enabled

    private fun execute(request: Request, action: () -> Unit) {
        try {
            worker.execute {
                if (!request.active.get()) return@execute
                try { action() } catch (exception: Exception) {
                    handler.post { report(exception); finish(request) }
                }
            }
        } catch (exception: RuntimeException) { report(exception); finish(request) }
    }

    private fun send(request: Request, message: Message) {
        if (request.active.get() && SystemClock.uptimeMillis() < request.deadline) request.reply.send(message)
    }

    private fun finish(request: Request) {
        if (current !== request) return
        current = null
        request.active.set(false)
        request.prepared = null
        handler.removeCallbacks(request.timeout)
        if (request.linked) {
            request.linked = false
            try { request.reply.binder.unlinkToDeath(request.death, 0) }
            catch (exception: RuntimeException) { report(exception) }
        }
    }

    private fun report(exception: Exception) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFailure >= 5_000L) {
            lastFailure = now
            log(Log.WARN, "SIDEBAR_ENTRIES_FAILED", exception)
        }
    }

    private fun moduleUid(): Int = try {
        service.packageManager.getApplicationInfo(
            SidebarEntryProtocol.MODULE_PACKAGE,
            android.content.pm.PackageManager.ApplicationInfoFlags.of(0),
        ).uid
    } catch (_: RuntimeException) {
        -1
    }

    private inner class Request(val id: String, val deadline: Long, val reply: Messenger, val target: PinnedTarget.Sidebar?) {
        val active = AtomicBoolean(true)
        var linked = false
        /** ready() 的重试次数；到上限后不再等，按不可用兜底继续。 */
        var readyRetries = 0
        var prepared: ColorOsSidebarEntries? = null
        val timeout = Runnable { finish(this) }
        val death = IBinder.DeathRecipient { handler.post { finish(this) } }
    }

    private companion object {
        /** ready() 最多等 800 毫秒（50ms × 16），之后不再等，按不可用兜底继续。 */
        const val READY_RETRY_LIMIT = 16

        /** 等 OEM 自己枚举出条目：最多等这么久，并且给请求截止时间留出余量。 */
        const val OBSERVATION_WAIT_MS = 2_500L
        const val OBSERVATION_WAIT_GUARD_MS = 600L
        const val OBSERVATION_POLL_MS = 100L
    }
}
