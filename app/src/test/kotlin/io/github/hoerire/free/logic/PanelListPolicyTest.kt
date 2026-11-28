package io.github.hoerire.free.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelListPolicyTest {

    private val hidden = listOf("com.tencent.mm", "com.example.notes")

    @Test
    fun hiddenPackageIsFiltered() {
        assertTrue(PanelListPolicy.isHidden("com.tencent.mm", hidden))
    }

    @Test
    fun otherPackageIsKept() {
        assertFalse(PanelListPolicy.isHidden("com.tencent.mobileqq", hidden))
    }

    @Test
    fun nullPackageIsNeverHidden() {
        assertFalse(PanelListPolicy.isHidden(null, hidden))
    }

    @Test
    fun emptyHiddenListHidesNothing() {
        assertFalse(PanelListPolicy.isHidden("com.tencent.mm", emptyList()))
    }

    /** 最近里与收藏重复的条目要被去掉，其余原顺序保留。 */
    @Test
    fun recentDropsFavoriteDuplicates() {
        val recent = listOf("com.a/.A", "com.b/.B", "com.c/.C")
        val favorite = listOf("com.b/.B")
        assertEquals(listOf("com.a/.A", "com.c/.C"), PanelListPolicy.dedupeRecent(recent, favorite))
    }

    @Test
    fun recentKeepsOrderWhenNoOverlap() {
        val recent = listOf("com.a/.A", "com.b/.B")
        assertEquals(recent, PanelListPolicy.dedupeRecent(recent, listOf("com.z/.Z")))
    }

    @Test
    fun emptyFavoriteKeepsEverything() {
        val recent = listOf("com.a/.A")
        assertEquals(recent, PanelListPolicy.dedupeRecent(recent, emptyList()))
    }

    /** 分身与主应用别名不同，不能互相删掉。 */
    @Test
    fun cloneAndHostDoNotDedupeEachOther() {
        val recent = listOf("com.tencent.mm/.ui.LauncherUI", "com.tencent.mm#999")
        val favorite = listOf("com.tencent.mm#999")
        assertEquals(
            listOf("com.tencent.mm/.ui.LauncherUI"),
            PanelListPolicy.dedupeRecent(recent, favorite),
        )
    }

    @Test
    fun blankAliasIsDropped() {
        assertEquals(listOf("com.a/.A"), PanelListPolicy.dedupeRecent(listOf("", "com.a/.A"), listOf("x")))
    }
}
