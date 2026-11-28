package io.github.hoerire.free.logic

/**
 * 「更多」面板列表的两件纯逻辑：过滤隐藏应用、给「最近」去掉收藏区已有的条目。
 *
 * 抽成纯函数而不是散在 [io.github.hoerire.free.platform.coloros.FavoriteGroupInjector]
 * 里，是因为这里的判定只吃 alias / 包名这类字符串，可以在 JVM 单测里直接验证——
 * 面板本身的反射路径没法单测，能搬出来的判定就都搬出来。
 *
 * **别名（alias）是主键**：面板里同一条目在两个分组里的别名一致，
 * 分身是 `包名#userId`，主应用是 `包名/Activity`，两者天然不同、不会互相误删。
 */
internal object PanelListPolicy {

    /**
     * 该条目是否要隐藏。
     *
     * @param packageName 条目所属包名（不能从 alias 推：主应用的 alias 是组件名）
     * @param hidden 隐藏名单（包名）
     */
    fun isHidden(packageName: String?, hidden: Collection<String>): Boolean =
        packageName != null && hidden.any { it == packageName }

    /**
     * 给「最近」去掉已在收藏区出现的条目。
     *
     * 只按别名比对：收藏区放的是用户自己挑的那几个条目，最近里同一个条目再出现一次
     * 就是重复。**扇形固定项不参与**——那是另一份配置，不该影响面板的「最近」。
     *
     * @param recent 最近分组的条目别名，保持原顺序
     * @param favorite 收藏分组的条目别名
     */
    fun dedupeRecent(recent: List<String>, favorite: Collection<String>): List<String> {
        if (favorite.isEmpty()) return recent
        val taken = favorite.toHashSet()
        return recent.filterNot { alias -> alias.isEmpty() || taken.contains(alias) }
    }
}
