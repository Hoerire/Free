package io.github.hoerire.free.platform.coloros

/**
 * 观察式目录：不再主动向 OEM 索取条目，而是"它自己算的时候我们记一份"。
 *
 * 起因：老链路要自己唤醒侧边栏 Provider、等面板 ready，再去调 OEM 的单例把条目取出来；
 * 开机后或面板没 inflate 时这条路必挂（实测 `SIDEBAR_ENTRY_PROVIDER_UNAVAILABLE`）。
 *
 * 现在改成纯观察：钩住侧边栏自己枚举条目的那几个方法（[observe]），把**它算出来的对象**
 * 留一份在本进程里。之后目录请求直接用这份观测结果，不再依赖 Provider、不再等 ready，
 * 也不会因为拿不到 OEM 单例而整表失败。
 *
 * 生命周期与所在进程一致：进程重启后重新观察一次（用户打开一次侧边栏/「更多」即可）。
 * 观测为空时调用方仍回退到老链路，因此这是纯增量，不影响既有行为。
 */
internal object SidebarCatalogObservation {
    /**
     * 三个来源分开存，因为它们的语义不同：
     * - [Role.Tools] 是全部可用工具；
     * - [Role.Shortcuts] 是侧边栏里展示的快捷方式（含应用）；
     * - [Role.ShownApps] 是侧边栏里的应用列表，**只有分身**才进目录（沿用老链路规则）。
     *
     * 混在一起存会把侧边栏的"全部应用"当成目录项，既拖慢传输也不是用户想要的列表。
     */
    enum class Role { Tools, Shortcuts, ShownApps }

    private val buckets = java.util.Collections.synchronizedMap(
        HashMap<Role, MutableList<Any>>(),
    )

    @Volatile
    private var lastObservedAt = 0L

    /** 观测是否已经发生过（哪怕一次）。 */
    fun hasData(): Boolean = buckets.values.any { it.isNotEmpty() }

    /** 指定来源观测到的条目快照；没有就是空表。 */
    fun beans(role: Role): List<Any> =
        synchronized(buckets) { ArrayList(buckets[role].orEmpty()) }

    fun lastObservedAt(): Long = lastObservedAt

    /**
     * 记下 OEM 自己算出来的一批条目。
     *
     * 三个枚举方法给的子集不同（工具 / 已显示的应用 / 分身），这里按对象身份合并去重，
     * 顺序沿用首次出现的顺序；超过上限就丢弃新增，避免异常 ROM 无限堆积。
     */
    fun observe(role: Role, result: Any?) {
        val list = (result as? List<*>)?.filterNotNull() ?: return
        if (list.isEmpty()) return
        synchronized(buckets) {
            val bucket = buckets.getOrPut(role) { ArrayList(64) }
            for (bean in list) {
                if (bucket.size >= MAX_BEANS) break
                if (bucket.none { it === bean }) bucket.add(bean)
            }
        }
        lastObservedAt = android.os.SystemClock.uptimeMillis()
    }

    /** 在所有来源里找一个满足条件的对象（启动固定工具时复用，免得再去问 OEM 单例）。 */
    fun find(predicate: (Any) -> Boolean): Any? = synchronized(buckets) {
        for (role in Role.values()) {
            buckets[role]?.firstOrNull(predicate)?.let { return it }
        }
        null
    }

    private const val MAX_BEANS = 512
}
