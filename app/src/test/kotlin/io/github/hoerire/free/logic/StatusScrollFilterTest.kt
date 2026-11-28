package io.github.hoerire.free.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusScrollFilterTest {

    private val excluded = listOf("com.tencent.mm", "com.example.notes#999")

    @Test
    fun listedMainAppIsFiltered() {
        assertFalse(StatusScrollFilter.accepts(excluded, "com.tencent.mm"))
    }

    @Test
    fun unlistedAppStillScrolls() {
        assertTrue(StatusScrollFilter.accepts(excluded, "com.tencent.mobileqq"))
    }

    @Test
    fun emptyListFiltersNothing() {
        assertTrue(StatusScrollFilter.accepts(emptyList(), "com.tencent.mm"))
    }

    /** 分身靠 `包名#userId` 单独列出：排除分身不影响主应用。 */
    @Test
    fun listedCloneIsFiltered() {
        assertFalse(StatusScrollFilter.accepts(excluded, "com.example.notes#999"))
    }

    @Test
    fun otherCloneOfListedCloneStillScrolls() {
        assertTrue(StatusScrollFilter.accepts(excluded, "com.example.notes#998"))
    }

    /** 主应用与分身同包名：排除主应用不会连带挡掉分身。 */
    @Test
    fun listedMainDoesNotFilterItsClone() {
        assertTrue(StatusScrollFilter.accepts(excluded, "com.tencent.mm#999"))
    }

    @Test
    fun listedCloneDoesNotFilterItsMain() {
        assertTrue(StatusScrollFilter.accepts(excluded, "com.example.notes"))
    }
}
