package io.github.hoerire.free.bubble

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import io.github.hoerire.free.config.ModulePreferences

/**
 * 常亮建议：屏幕进入「即将变暗」阶段时弹一颗气泡，点气泡把屏幕钉住。
 *
 * 半暗状态由 system_server 侧写入 [ModulePreferences.KEY_PRE_SLEEP_ARMED]（见
 * `hook/PreSleepDetectionHook`），这里按固定间隔轮询该标志：
 *
 * 1. 连续变暗且屏幕仍可交互 → 弹一颗「保持常亮」气泡（只提醒，不替用户点亮屏幕）；
 * 2. 点中 → 拿一把屏幕常亮锁 + 挂一个带 `FLAG_KEEP_SCREEN_ON` 的辅助窗口；
 * 3. 只有锁屏/灭屏才结束：这期间切应用、换界面都继续亮着。
 *
 * 没有变暗信号时不铺任何窗口、也不持锁。
 */
internal class PreSleepKeepAwakeController(
    context: Context,
    private val host: Host,
) {
    interface Host {
        fun isEnabled(): Boolean

        fun foregroundPackage(): String?

        /** 弹一颗建议气泡；用户点它时执行 [engage]。 */
        fun showKeepAwakeSuggestion(engage: () -> Unit)

        fun dismissSuggestion(id: String)

        fun log(code: String, error: Throwable?)
    }

    private val appContext = context.applicationContext ?: context
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager? =
        context.getSystemService(WindowManager::class.java)
    private val powerManager: PowerManager? =
        context.getSystemService(PowerManager::class.java)
    private val keyguardManager: KeyguardManager? =
        context.getSystemService(KeyguardManager::class.java)

    /** 上一次因变暗弹出建议的时刻；同一轮变暗只弹一次。 */
    private var lastSuggestionAt = 0L
    private var keepWindow: View? = null
    private var keepLock: PowerManager.WakeLock? = null
    private var keepAwake = false
    private var engagedAt = 0L
    private var lastAliveLogAt = 0L
    private var polling = false
    /** 用户点过一次之后，这一轮亮屏周期内不再自动弹（灭屏后复位）。 */
    private var consumedThisProcess = false

    /** 本次「变暗」信号是从什么时候开始连续保持的；0 表示当前不在变暗。 */
    private var armedSince = 0L
    private var receiverRegistered = false

    private val pollAction = Runnable { poll() }

    private val screenOffReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_SCREEN_OFF) return
                handler.post {
                    clearArmed()
                    release()
                    // 灭屏算这一轮使用结束：下次点亮后再变暗重新给一次建议，
                    // 否则进程跑久了这一项就永远沉默了。
                    consumedThisProcess = false
                    lastSuggestionAt = 0L
                    armedSince = 0L
                }
            }
        }

    /** 设置变化时调用：总开关或常亮建议关掉就立即停手，不留任何残留。 */
    fun setEnabled(enabled: Boolean) {
        if (enabled == polling) return
        if (enabled) {
            registerReceiver()
            clearArmed()
            // 关掉再打开视为重新要这个功能：把「已经用过」的标记清掉。
            consumedThisProcess = false
            lastSuggestionAt = 0L
            polling = true
            handler.post(pollAction)
            host.log("PRE_SLEEP_WATCH_STARTED", null)
        } else {
            polling = false
            handler.removeCallbacks(pollAction)
            release()
            clearArmed()
            host.log("PRE_SLEEP_WATCH_STOPPED", null)
        }
    }

    fun stop() = setEnabled(false)

    private fun poll() {
        if (!polling) return
        runCatching {
            val interactive = powerManager?.isInteractive ?: true
            val armed = interactive && !isLocked() && isArmed()
            if (!armed) armedSince = 0L
            when {
                // 唯一的自然结束点：用户锁屏/息屏。
                !interactive -> release()
                // 常亮期间什么都不做：切应用、换界面都继续亮着，直到锁屏或息屏。
                keepAwake -> logKeepAlive()
                armed -> maybeSuggest()
            }
        }.onFailure { host.log("PRE_SLEEP_POLL_FAILED", it) }
        handler.postDelayed(pollAction, POLL_MS)
    }

    /**
     * 变暗要连续保持 [DIM_CONFIRM_MS] 才给建议。
     *
     * 播放视频时系统会反复把用户活动汇总瞬时置成 DIMMED 再回到明亮（实测单次 0.04~0.6 秒，
     * 几秒里反复好几次），那并不是即将休眠；连续变暗才算成立。
     * 成立之后就弹气泡，停留时间完全交给「气泡停留」设置，模块不主动撤。
     */
    private fun maybeSuggest() {
        val now = SystemClock.elapsedRealtime()
        if (armedSince == 0L) armedSince = now
        if (now - armedSince < DIM_CONFIRM_MS) return
        onDimDetected()
    }

    /**
     * 进入变暗阶段：弹一颗「保持常亮」气泡等用户点。
     *
     * 只提醒，不替用户把屏幕点亮——用户不点就照常熄灭，这是他们自己的选择。
     * 气泡的停留时间跟随「气泡停留」设置（多久消失由用户决定），模块不再主动撤回。
     *
     * 用户点过一次之后，这一轮亮屏周期内不再自动弹：这是「用过了」而不是「每次变暗都提醒」。
     * 灭屏（[screenOffReceiver]）即视为本轮结束，下次点亮后再变暗会重新提醒一次。
     */
    private fun onDimDetected() {
        if (consumedThisProcess) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSuggestionAt < SUGGESTION_COOLDOWN_MS) return
        lastSuggestionAt = now
        // 不要清 armed 标志：它由 system_server 侧按真实状态维护。
        // 早先在这里清一次，下一次轮询就会看到"变暗信号消失"而把刚弹出的气泡撤掉，
        // 表现为气泡闪一下就没了、根本轮不到用户点。
        host.log("PRE_SLEEP_DIM_DETECTED", null)
        if (!host.isEnabled()) return
        runCatching { host.showKeepAwakeSuggestion { engage() } }
            .onFailure { host.log("PRE_SLEEP_SUGGESTION_FAILED", it) }
    }

    private fun engage() {
        if (keepAwake) return
        runCatching {
            keepAwake = true
            consumedThisProcess = true
            engagedAt = SystemClock.elapsedRealtime()
            lastAliveLogAt = 0L
            pokeUserActivity()
            acquireKeepLock()
            attachKeepWindow()
            // 气泡的使命已经完成：常亮由窗口/锁维持，气泡留着只会挡视线。
            host.dismissSuggestion(ImmediateSuggestionController.TYPE_KEEP_AWAKE)
            host.log("PRE_SLEEP_KEEP_AWAKE_ENGAGED", null)
        }.onFailure {
            keepAwake = false
            host.log("PRE_SLEEP_KEEP_AWAKE_FAILED", it)
        }
    }

    /**
     * 屏幕常亮最硬的一层：直接拿一把「屏幕常亮」锁。
     *
     * 窗口那条路要靠 WMS 把带 `FLAG_KEEP_SCREEN_ON` 的窗口选成 hold-screen 窗口，
     * 一个 1×1 的辅助窗口并不总被算数（实测经常不生效）。锁是 PowerManagerService
     * 自己的语义：亮屏期间屏不会自己变暗/休眠；用户按电源键仍然正常灭屏，
     * 灭屏后由 [screenOffReceiver] 与 [poll] 立刻释放。
     */
    @Suppress("DEPRECATION")
    private fun acquireKeepLock() {
        if (keepLock != null) return
        val power = powerManager ?: return
        runCatching {
            val lock = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, KEEP_LOCK_TAG)
            lock.setReferenceCounted(false)
            lock.acquire()
            keepLock = lock
            host.log("PRE_SLEEP_KEEP_LOCK_ACQUIRED", null)
        }.onFailure { host.log("PRE_SLEEP_KEEP_LOCK_FAILED", it) }
    }

    private fun releaseKeepLock() {
        val lock = keepLock ?: return
        keepLock = null
        runCatching { lock.release() }
    }

    /** 1×1 的不可触摸窗口：`FLAG_KEEP_SCREEN_ON` 靠窗口存在维持。 */
    private fun attachKeepWindow() {
        val manager = windowManager ?: return
        if (keepWindow != null) return
        runCatching {
            val view = View(appContext)
            val params =
                WindowManager.LayoutParams(
                    KEEP_WINDOW_SIZE,
                    KEEP_WINDOW_SIZE,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    // 必须落在屏幕内：完全在屏外的窗口不会被 WMS 当成 hold-screen 窗口。
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                    title = KEEP_WINDOW_TITLE
                }
            manager.addView(view, params)
            keepWindow = view
            host.log("PRE_SLEEP_KEEP_WINDOW_ATTACHED", null)
        }.onFailure { host.log("PRE_SLEEP_KEEP_WINDOW_FAILED", it) }
    }

    /**
     * 常亮期间每 [ALIVE_PROBE_MS] 记一行：屏幕到底有没有被钉住。
     *
     * 走 DEBUG 通道（不去重），连续几行的时间戳就是"钉住了多久"的直接证据；
     * 如果屏幕提前熄灭，这一串会在熄灭那一刻断掉，同时收到 SCREEN_OFF 释放。
     */
    private fun logKeepAlive() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAliveLogAt < ALIVE_PROBE_MS) return
        lastAliveLogAt = now
        io.github.hoerire.free.log.ModuleLog.debug("PRE_SLEEP") {
            "PRE_SLEEP_ALIVE_+" + (now - engagedAt) +
                "_fg" + host.foregroundPackage() +
                "_interactive" + (powerManager?.isInteractive == true) +
                "_lock" + (keepLock != null) +
                "_win" + (keepWindow != null)
        }
    }

    private fun releaseKeepWindow() {
        val view = keepWindow ?: return
        keepWindow = null
        runCatching { windowManager?.removeView(view) }
    }

    private fun release() {
        val wasAwake = keepAwake
        keepAwake = false
        engagedAt = 0L
        lastAliveLogAt = 0L
        // 锁无条件回收：engage 中途失败时也可能已经拿到。
        releaseKeepLock()
        if (!wasAwake) {
            // 没点在常亮上：灭屏时把可能还挂着的气泡一并收掉（未灭屏时由「气泡停留」自己走完）。
            host.dismissSuggestion(ImmediateSuggestionController.TYPE_KEEP_AWAKE)
            return
        }
        releaseKeepWindow()
        host.dismissSuggestion(ImmediateSuggestionController.TYPE_KEEP_AWAKE)
        host.log("PRE_SLEEP_KEEP_AWAKE_RELEASED", null)
    }

    private fun isLocked(): Boolean =
        runCatching { keyguardManager?.isKeyguardLocked == true }.getOrDefault(false)

    /** 用户点了气泡才把屏幕点亮：这是用户的选择，模块不替用户决定。 */
    private fun pokeUserActivity() {
        val power = powerManager ?: return
        runCatching {
            val method =
                PowerManager::class.java.getDeclaredMethod(
                    "userActivity",
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            method.isAccessible = true
            method.invoke(power, SystemClock.uptimeMillis(), USER_ACTIVITY_EVENT_TOUCH, 0)
        }.onFailure { host.log("PRE_SLEEP_POKE_FAILED", it) }
    }

    private fun isArmed(): Boolean =
        runCatching {
            Settings.Global.getInt(
                appContext.contentResolver,
                ModulePreferences.KEY_PRE_SLEEP_ARMED,
                0,
            ) == 1
        }.getOrDefault(false)

    private fun clearArmed() {
        runCatching {
            if (isArmed()) {
                Settings.Global.putInt(
                    appContext.contentResolver,
                    ModulePreferences.KEY_PRE_SLEEP_ARMED,
                    0,
                )
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        receiverRegistered = true
        runCatching {
            appContext.registerReceiver(
                screenOffReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                Context.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure {
            receiverRegistered = false
            host.log("PRE_SLEEP_RECEIVER_FAILED", it)
        }
    }

    private companion object {
        const val POLL_MS = 250L

        /**
         * 变暗要连续保持这么久才弹建议（毫秒）。
         *
         * 视频播放期间系统会反复短暂进入 DIMMED（实测 0.04~0.6 秒一次，几秒里好几回），
         * 那是播放器的常亮与系统超时在打架，不是"马上要息屏"；真正的变暗会一直持续到息屏。
         */
        const val DIM_CONFIRM_MS = 1_500L

        const val KEEP_WINDOW_TITLE = "FreeKeepScreenOn"
        const val KEEP_LOCK_TAG = "Free:KeepScreenOn"

        /** 常亮窗口的边长（px）：小到看不见，但必须真的落在屏幕里。 */
        const val KEEP_WINDOW_SIZE = 8

        /** 常亮诊断日志的间隔。 */
        const val ALIVE_PROBE_MS = 10_000L

        /** 两次自动弹出之间的最小间隔：忽略掉的那一次之后，十分钟内不再打扰。 */
        const val SUGGESTION_COOLDOWN_MS = 10 * 60_000L

        /** 触摸事件：PowerManager.USER_ACTIVITY_EVENT_TOUCH */
        const val USER_ACTIVITY_EVENT_TOUCH = 2
    }
}
