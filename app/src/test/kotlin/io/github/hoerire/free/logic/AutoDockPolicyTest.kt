package io.github.hoerire.free.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDockPolicyTest {

    private val selected = listOf("com.tencent.mm", "com.miHoYo.Yuanshen")

    @Test
    fun launcherIsHome() {
        assertTrue(AutoDockPolicy.isHome("com.android.launcher"))
    }

    @Test
    fun appIsNotHome() {
        assertFalse(AutoDockPolicy.isHome("com.tencent.mm"))
        assertFalse(AutoDockPolicy.isHome(null))
    }

    @Test
    fun listedAppDocksWhenReturningHome() {
        assertTrue(AutoDockPolicy.shouldDock(true, selected, "com.tencent.mm"))
    }

    @Test
    fun unlistedAppIsNotDocked() {
        assertFalse(AutoDockPolicy.shouldDock(true, selected, "com.tencent.mobileqq"))
    }

    @Test
    fun moduleDisabledDocksNothing() {
        assertFalse(AutoDockPolicy.shouldDock(false, selected, "com.tencent.mm"))
    }

    @Test
    fun emptyListDocksNothing() {
        assertFalse(AutoDockPolicy.shouldDock(true, emptyList(), "com.tencent.mm"))
    }

    @Test
    fun noPreviousForegroundDocksNothing() {
        assertFalse(AutoDockPolicy.shouldDock(true, selected, null))
    }
}
