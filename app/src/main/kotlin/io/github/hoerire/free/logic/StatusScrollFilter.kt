package io.github.hoerire.free.logic

/**
 * 滚动通知的过滤列表判定。
 *
 * 键与气泡白名单同一套：主应用是 `包名`，分身是 `包名#userId`，由调用方用 BubbleKey 拼好。
 * 一条键只排除它自己那一份——排除主应用不会连带排除同包名的分身，反之亦然。
 */
internal object StatusScrollFilter {
    /** [key] 在表里就不接管；表为空时一律放行。 */
    fun accepts(excluded: Collection<String>, key: String): Boolean =
        excluded.isEmpty() || key !in excluded
}
