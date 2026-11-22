package io.github.hoerire.free.apps

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class SidebarCatalogStatus { Idle, Loading, Ready, Unavailable }
internal data class SidebarCatalogState(
    val entries: List<SelectableEntry> = emptyList(),
    val status: SidebarCatalogStatus = SidebarCatalogStatus.Idle,
)

/**
 * 侧边栏条目目录，**只在本进程读**。
 *
 * 旧实现通过广播把请求发给 system_server，再由 system_server 去 bind 侧边栏的 `:ui`
 * 服务取数据；那条链路在部分机型上绑定必失败、且死 binder 不会自愈。现在改为：
 * 本进程用 DexClassLoader/已安装包的 ClassLoader 打开侧边栏 APK，直接读它的工具表
 * （见 [SidebarLocalTools]）。页面可见时刷新一次即可，没有超时重试链路。
 */
internal class SidebarEntryRepository(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val cache = SidebarCatalogCache(context)
    // 先用上次成功读到的目录顶上：首帧就不会把已固定的工具显示成"条目已移除"。
    private val mutableState = MutableStateFlow(
        cache.load().let { cached ->
            if (cached.isEmpty()) {
                SidebarCatalogState()
            } else {
                SidebarCatalogState(cached, SidebarCatalogStatus.Ready)
            }
        },
    )
    val state = mutableState.asStateFlow()
    private val local = SidebarLocalTools(context)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Free-LocalCatalog")
    }

    /** 每次刷新递增；迟到的结果直接丢弃，避免旧结果覆盖新状态。 */
    private var generation = 0
    private val timeout = Runnable { fail() }

    fun refresh() {
        val current = ++generation
        enterLoading()
        handler.postDelayed(timeout, LOAD_TIMEOUT_MS)
        worker.execute {
            val entries = try {
                local.load()
            } catch (_: Throwable) {
                // 加载 OEM dex 可能抛 LinkageError/NoClassDefFoundError，按失败处理。
                emptyList()
            }
            if (entries.isNotEmpty()) cache.save(entries)
            handler.post {
                if (current != generation) return@post
                handler.removeCallbacks(timeout)
                mutableState.value = if (entries.isEmpty()) {
                    SidebarCatalogState(mutableState.value.entries, SidebarCatalogStatus.Unavailable)
                } else {
                    SidebarCatalogState(entries, SidebarCatalogStatus.Ready)
                }
            }
        }
    }

    /** 页面离开：丢掉本轮结果，下次进入重新读。 */
    fun cancel() {
        generation++
        handler.removeCallbacks(timeout)
        if (mutableState.value.status == SidebarCatalogStatus.Loading) {
            mutableState.value = mutableState.value.copy(status = SidebarCatalogStatus.Idle)
        }
    }

    private fun enterLoading() {
        mutableState.value = mutableState.value.copy(status = SidebarCatalogStatus.Loading)
    }

    private fun fail() {
        // 读不到时保留上一次成功的结果，避免固定位的工具/快捷项变成空格。
        if (mutableState.value.entries.isNotEmpty()) {
            mutableState.value = mutableState.value.copy(status = SidebarCatalogStatus.Ready)
            return
        }
        mutableState.value = mutableState.value.copy(status = SidebarCatalogStatus.Unavailable)
    }

    private companion object {
        /** 首次加载侧边栏 dex 需要一两百毫秒；超过这个时间按失败处理，不让界面干等。 */
        const val LOAD_TIMEOUT_MS = 15_000L
    }
}
