package io.github.hoerire.free.config

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleSettingsSnapshotTest {
    @Test
    fun legacyPinsMigrateOnWriteAndKeepOrder() {
        val preferences = InMemoryPreferences(ModulePreferences.KEY_CORNER_PINS to "com.first/.A\ncom.second/.B")
        val migrated = ModuleSettingsSnapshot.readFrom(preferences)
        assertTrue(migrated.pinsSaved)
        assertEquals(listOf(PinnedTarget.App("com.first", "com.first.A"), PinnedTarget.App("com.second", "com.second.B")), migrated.pinnedTargets)
        migrated.writeTo(preferences.edit()).commit()
        assertFalse(preferences.contains(ModulePreferences.KEY_CORNER_PINS))
        assertTrue(preferences.contains(ModulePreferences.KEY_PINNED_TARGETS))
        assertEquals(migrated, ModuleSettingsSnapshot.readFrom(preferences))
    }

    @Test
    fun explicitEmptyNewPinsOverrideLegacyAndRemainEmptyAfterSaving() {
        val preferences = InMemoryPreferences(
            ModulePreferences.KEY_PINNED_TARGETS to "",
            ModulePreferences.KEY_CORNER_PINS to "com.old/.Main",
        )
        val empty = ModuleSettingsSnapshot.readFrom(preferences)
        assertTrue(empty.pinsSaved)
        assertTrue(empty.pinnedTargets.isEmpty())
        empty.writeTo(preferences.edit()).commit()
        assertEquals(empty, ModuleSettingsSnapshot.readFrom(preferences))
    }

    @Test
    fun mixedUnavailableTargetsArePreservedByUnrelatedSettingsWrites() {
        val targets = listOf(
            PinnedTarget.Sidebar(PinnedTarget.Kind.Tool, "capture", 0),
            PinnedTarget.App("com.example", "com.example.Main"),
            PinnedTarget.Sidebar(PinnedTarget.Kind.Shortcut, "com.example_note", 0),
        )
        val preferences = InMemoryPreferences()
        ModuleSettingsSnapshot(pinsSaved = true, pinnedTargets = targets).writeTo(preferences.edit()).commit()
        ModuleSettingsSnapshot.readFrom(preferences).copy(pauseInLandscape = false).writeTo(preferences.edit()).commit()
        assertEquals(targets, ModuleSettingsSnapshot.readFrom(preferences).pinnedTargets)
    }

    @Test
    fun radialAnimationDurationsRoundTripAndClampToRange() {
        val preferences =
            InMemoryPreferences(
                ModulePreferences.KEY_RADIAL_EXPAND_DURATION_MS to 5000,
                ModulePreferences.KEY_RADIAL_RETRACT_DURATION_MS to 20,
            )
        val snapshot = ModuleSettingsSnapshot.readFrom(preferences)
        // 越界值读进来就被钳到区间：上限 1000，下限 100。
        assertEquals(ModulePreferences.MAX_RADIAL_DURATION_MS, snapshot.radialExpandDurationMs)
        assertEquals(ModulePreferences.MIN_RADIAL_DURATION_MS, snapshot.radialRetractDurationMs)
        snapshot.writeTo(preferences.edit()).commit()
        assertEquals(snapshot, ModuleSettingsSnapshot.readFrom(preferences))
    }

    @Test
    fun missingKeysUseProductDefaults() {
        val snapshot = ModuleSettingsSnapshot.readFrom(InMemoryPreferences())

        assertEquals(84, snapshot.cornerTriggerRangeDp)
        assertEquals(ModulePreferences.DEFAULT_RADIAL_EXPAND_DURATION_MS, snapshot.radialExpandDurationMs)
        assertEquals(ModulePreferences.DEFAULT_RADIAL_RETRACT_DURATION_MS, snapshot.radialRetractDurationMs)
        assertEquals(OutsideTapCloseMode.Disabled, snapshot.outsideTapCloseMode)
        assertFalse(snapshot.handleSwipeUpToMiniEnabled)
        assertTrue(snapshot.pauseInLandscape)
        assertTrue(snapshot.pauseInGameMode)
        // 呼吸特效默认开启（幅度只有 ±2%），关掉后不应被默认值覆盖回去。
        assertTrue(snapshot.radialBreathingEnabled)
    }

    @Test
    fun radialBreathingRoundTripsBothStates() {
        for (enabled in listOf(false, true)) {
            val preferences = InMemoryPreferences()
            ModuleSettingsSnapshot(radialBreathingEnabled = enabled).writeTo(preferences.edit()).commit()
            assertEquals(enabled, ModuleSettingsSnapshot.readFrom(preferences).radialBreathingEnabled)
        }
    }

    @Test
    fun retiredAppearanceSettingsDoNotAffectCurrentSettings() {
        val preferences = InMemoryPreferences(
            "radial_icon_mask_scale_percent" to "retired",
            "radial_icon_content_scale_percent" to "retired",
            "radial_circular_icons_enabled" to "retired",
            "radial_icon_size_percent_v1" to "retired",
            "radial_icon_roundness_percent_v1" to "retired",
        )
        val expected = ModuleSettingsSnapshot.readFrom(InMemoryPreferences())
        val actual = ModuleSettingsSnapshot.readFrom(preferences)
        assertEquals(expected, actual)
        actual.writeTo(preferences.edit()).commit()
        assertEquals(expected, ModuleSettingsSnapshot.readFrom(preferences))
        assertEquals("retired", preferences.getString("radial_icon_size_percent_v1", null))
        assertEquals("retired", preferences.getString("radial_icon_roundness_percent_v1", null))
    }

    @Test
    fun interactionSettingsRoundTrip() {
        val preferences = InMemoryPreferences()
        ModuleSettingsSnapshot(
            outsideTapCloseMode = OutsideTapCloseMode.DoubleTap,
            handleSwipeUpToMiniEnabled = false,
        ).writeTo(preferences.edit()).commit()

        val restored = ModuleSettingsSnapshot.readFrom(preferences)

        assertEquals(OutsideTapCloseMode.DoubleTap, restored.outsideTapCloseMode)
        assertFalse(restored.handleSwipeUpToMiniEnabled)
    }

    @Test
    fun pauseSwitchesRoundTripIndependently() {
        for (landscape in listOf(false, true)) {
            for (game in listOf(false, true)) {
                val preferences = InMemoryPreferences()
                val expected = ModuleSettingsSnapshot(
                    enabled = true,
                    pauseInLandscape = landscape,
                    pauseInGameMode = game,
                )
                expected.writeTo(preferences.edit()).commit()
                assertEquals(expected, ModuleSettingsSnapshot.readFrom(preferences))
            }
        }
    }

    @Test
    fun eitherEnabledPauseRuleBlocksItsEnvironment() {
        val defaults = ModuleSettingsSnapshot()
        assertFalse(defaults.isPausedByEnvironment(landscape = false, gameMode = false))
        assertTrue(defaults.isPausedByEnvironment(landscape = true, gameMode = false))
        assertTrue(defaults.isPausedByEnvironment(landscape = false, gameMode = true))

        val allowLandscape = defaults.copy(pauseInLandscape = false)
        assertFalse(allowLandscape.isPausedByEnvironment(landscape = true, gameMode = false))
        assertTrue(allowLandscape.isPausedByEnvironment(landscape = true, gameMode = true))

        val allowGames = defaults.copy(pauseInGameMode = false)
        assertFalse(allowGames.isPausedByEnvironment(landscape = false, gameMode = true))
        assertTrue(allowGames.isPausedByEnvironment(landscape = true, gameMode = true))

        val allowBoth = defaults.copy(pauseInLandscape = false, pauseInGameMode = false)
        assertFalse(allowBoth.isPausedByEnvironment(landscape = true, gameMode = true))
    }

    @Test
    fun unknownOutsideTapModeFailsClosed() {
        val preferences =
            InMemoryPreferences(ModulePreferences.KEY_OUTSIDE_TAP_CLOSE_MODE to 99)

        assertEquals(
            OutsideTapCloseMode.Disabled,
            ModuleSettingsSnapshot.readFrom(preferences).outsideTapCloseMode,
        )
    }

    @Test
    fun cornerRangeIsClampedWhenRead() {
        val preferences =
            InMemoryPreferences(
                ModulePreferences.KEY_CORNER_TRIGGER_RANGE_DP to 8,
            )

        val snapshot = ModuleSettingsSnapshot.readFrom(preferences)

        assertEquals(24, snapshot.cornerTriggerRangeDp)
    }

    @Test
    fun bothCornersDisabledInStoredPreferencesNormalizeToBothEnabled() {
        val preferences =
            InMemoryPreferences(
                ModulePreferences.KEY_LEFT_CORNER_ENABLED to false,
                ModulePreferences.KEY_RIGHT_CORNER_ENABLED to false,
            )

        val snapshot = ModuleSettingsSnapshot.readFrom(preferences)

        assertTrue(snapshot.leftCornerEnabled)
        assertTrue(snapshot.rightCornerEnabled)
    }

    @Test
    fun cornerRangeIsClampedAndRoundTripsWhenWritten() {
        val preferences = InMemoryPreferences()
        ModuleSettingsSnapshot(
            cornerTriggerRangeDp = 200,
        ).writeTo(preferences.edit()).commit()

        assertEquals(
            160,
            preferences.getInt(ModulePreferences.KEY_CORNER_TRIGGER_RANGE_DP, 0),
        )
        val restored = ModuleSettingsSnapshot.readFrom(preferences)
        assertEquals(160, restored.cornerTriggerRangeDp)
    }

    @Test
    fun alternateGroupAndEffectiveSideRoundTripIndependently() {
        val alternate =
            listOf(
                PinnedTarget.App("com.alt", "com.alt.Main"),
                PinnedTarget.Sidebar(PinnedTarget.Kind.Tool, "capture", 0),
            )
        val expected =
            ModuleSettingsSnapshot(
                pinsSaved = true,
                pinnedTargets = listOf(PinnedTarget.App("com.main", "com.main.Main")),
                alternatePinnedTargets = alternate,
                secondaryGroupSide = RadialGroupSide.Right,
            )
        val preferences = InMemoryPreferences()
        expected.writeTo(preferences.edit()).commit()

        val restored = ModuleSettingsSnapshot.readFrom(preferences)
        assertEquals(expected, restored)
        assertEquals(expected.pinnedTargets, restored.pinsFor(RadialGroup.Primary))
        assertEquals(alternate, restored.pinsFor(RadialGroup.Alternate))
        assertEquals(RadialGroupSide.Right, restored.secondaryGroupSide)
        // 右侧换成第二组，左侧仍是默认组。
        assertEquals(RadialGroup.Primary, restored.groupForSide(left = true))
        assertEquals(RadialGroup.Alternate, restored.groupForSide(left = false))
    }

    @Test
    fun missingAlternateGroupLeavesPrimaryPinsUntouched() {
        val preferences =
            InMemoryPreferences(ModulePreferences.KEY_PINNED_TARGETS to "A|com.a/com.a.A")

        val snapshot = ModuleSettingsSnapshot.readFrom(preferences)

        assertTrue(snapshot.alternatePinnedTargets.isEmpty())
        assertEquals(
            listOf(PinnedTarget.App("com.a", "com.a.A")),
            snapshot.pinnedTargets,
        )
        // 默认不启用：两侧都用默认组。
        assertEquals(RadialGroupSide.Disabled, snapshot.secondaryGroupSide)
        assertEquals(RadialGroup.Primary, snapshot.groupForSide(left = true))
        assertEquals(RadialGroup.Primary, snapshot.groupForSide(left = false))
    }

    @Test
    fun unknownStoredEffectiveSideFallsBackToDisabled() {
        val preferences =
            InMemoryPreferences(ModulePreferences.KEY_SECONDARY_GROUP_SIDE to 99)

        val snapshot = ModuleSettingsSnapshot.readFrom(preferences)

        assertEquals(RadialGroupSide.Disabled, snapshot.secondaryGroupSide)
        assertEquals(RadialGroup.Primary, snapshot.groupForSide(left = true))
        assertEquals(RadialGroup.Primary, snapshot.groupForSide(left = false))
    }

    @Test
    fun pinnedMembershipCoversBothGroups() {
        val primaryPin = PinnedTarget.App("com.main", "com.main.Main")
        // 第二组里的工具以前会被启动前的准入校验当成「不在固定项」，导致点了没反应。
        val alternatePin = PinnedTarget.Sidebar(PinnedTarget.Kind.Tool, "capture", 0)
        val snapshot =
            ModuleSettingsSnapshot(
                pinsSaved = true,
                pinnedTargets = listOf(primaryPin),
                alternatePinnedTargets = listOf(alternatePin),
            )

        assertTrue(snapshot.isPinned(primaryPin))
        assertTrue(snapshot.isPinned(alternatePin))
        assertFalse(snapshot.isPinned(PinnedTarget.App("com.other", "com.other.Main")))
    }

    @Test
    fun leftSelectedSecondaryGroupLeavesRightSideOnPrimary() {
        val snapshot =
            ModuleSettingsSnapshot(secondaryGroupSide = RadialGroupSide.Left)

        assertEquals(RadialGroup.Alternate, snapshot.groupForSide(left = true))
        assertEquals(RadialGroup.Primary, snapshot.groupForSide(left = false))
    }

    @Test
    fun zoomMasterSwitchGatesEveryZoomRewrite() {
        // 子开关默认多为关闭，这里全部显式打开，才看得出总闸管不管得到它们。
        val on =
            ModuleSettingsSnapshot(
                enabled = true,
                externalHandleEnabled = true,
                longPressMuteEnabled = true,
                handleSwipeUpToMiniEnabled = true,
                hideZoomInRecents = true,
                floatKeepAlive = true,
                autoCloseWindow = true,
                zoomLaunchNotice = true,
                zoomMaxWindowCount = 3,
                // 内容拖拽按应用生效：给一个名单，才能验证它不受小窗总闸影响。
                dragUnlockPackages = listOf("com.tencent.mm"),
            )
        assertTrue(on.zoomInteractionActive)
        assertTrue(on.longPressMuteEffective)
        assertTrue(on.handleSwipeUpToMiniEffective)

        // 关掉总闸：整组小窗改写一起退出，不是只把界面变灰。
        val off = on.copy(zoomInteractionEnabled = false)
        assertFalse(off.zoomInteractionActive)
        assertFalse(off.autoCloseWindowEffective)
        assertFalse(off.zoomLaunchNoticeEffective)
        // 数量本身是数值，仍保留设置值；真正裁决它的钩子会先看总闸。
        assertEquals(3, off.zoomMaxWindowCountEffective)
        assertTrue(off.externalHandleEffective)
        assertTrue(off.dragUnlockActive)
        // 贴边挂机、隐藏卡片已搬进「小窗交互」：随总闸一起停。
        assertFalse(off.floatKeepAliveEffective)
        assertFalse(off.hideZoomInRecentsEffective)

        // 往返：开关独立存盘，互不覆盖。
        val preferences = InMemoryPreferences()
        off.writeTo(preferences.edit()).commit()
        assertFalse(ModuleSettingsSnapshot.readFrom(preferences).zoomInteractionEnabled)
    }

    @Test
    fun dragUnlockIsOffUntilAppsAreSelected() {
        // 默认关闭：全局补配置会让部分机型/应用在 bind 阶段崩溃，改成按应用生效。
        val empty = ModuleSettingsSnapshot(enabled = true, externalHandleEnabled = true)
        assertFalse(empty.dragUnlockActive)

        val on = empty.copy(dragUnlockPackages = listOf("com.tencent.mm", "com.android.chrome"))
        assertTrue(on.dragUnlockActive)

        // 清空名单即关闭，其它能力照旧。
        val unlockOff = on.copy(dragUnlockPackages = emptyList())
        assertFalse(unlockOff.dragUnlockActive)
        assertTrue(unlockOff.zoomInteractionActive)
        assertTrue(unlockOff.externalHandleEffective)

        // 模块总开关关掉时一律不生效。
        assertFalse(on.copy(enabled = false).dragUnlockActive)

        val preferences = InMemoryPreferences()
        on.writeTo(preferences.edit()).commit()
        assertEquals(
            listOf("com.tencent.mm", "com.android.chrome"),
            ModuleSettingsSnapshot.readFrom(preferences).dragUnlockPackages,
        )
    }

    @Test
    fun zoomWindowCountClampsToProductRange() {
        // 上限 4.1 起放宽到 12（原 10）：断言跟着产品值走，别再写死旧上限。
        assertEquals(12, ModulePreferences.coerceZoomMaxWindowCount(13))
        assertEquals(12, ModulePreferences.coerceZoomMaxWindowCount(12))
        assertEquals(1, ModulePreferences.coerceZoomMaxWindowCount(0))
        // 数量与「启用交互」无关，只跟模块总开关走。
        val settings = ModuleSettingsSnapshot(enabled = true, zoomMaxWindowCount = 7)
        assertEquals(7, settings.copy(zoomInteractionEnabled = false).zoomMaxWindowCountEffective)
        assertEquals(
            ModulePreferences.DEFAULT_ZOOM_MAX_WINDOW_COUNT,
            settings.copy(enabled = false).zoomMaxWindowCountEffective,
        )
    }


    @Test
    fun predictiveBackModeRoundTripsAndDefaultsToBothDirections() {
        assertEquals(
            PredictiveBackMode.ZoomUpMoreDown,
            ModuleSettingsSnapshot().predictiveBackMode,
        )
        val preferences = InMemoryPreferences()
        ModuleSettingsSnapshot(predictiveBackMode = PredictiveBackMode.ZoomDownMoreUp)
            .writeTo(preferences.edit())
            .commit()
        assertEquals(
            PredictiveBackMode.ZoomDownMoreUp,
            ModuleSettingsSnapshot.readFrom(preferences).predictiveBackMode,
        )
        // 已删除的单向模式（旧值 1 / 2）回落默认，旧配置不会错乱。
        for (stale in listOf(1, 2)) {
            val legacy = InMemoryPreferences(ModulePreferences.KEY_PREDICTIVE_BACK_MODE to stale)
            assertEquals(
                PredictiveBackMode.ZoomUpMoreDown,
                ModuleSettingsSnapshot.readFrom(legacy).predictiveBackMode,
            )
        }
        // 未知存储值同样退回默认，不用崩溃换一次可读性。
        val unknown = InMemoryPreferences(ModulePreferences.KEY_PREDICTIVE_BACK_MODE to 99)
        assertEquals(
            PredictiveBackMode.ZoomUpMoreDown,
            ModuleSettingsSnapshot.readFrom(unknown).predictiveBackMode,
        )
    }

}

private class InMemoryPreferences(vararg initialValues: Pair<String, Any>) : SharedPreferences {
    private val values = linkedMapOf(*initialValues)

    override fun getAll(): Map<String, *> = values.toMap()

    override fun getString(key: String, defValue: String?): String? =
        values[key]?.let { it as String } ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        values[key]?.let { it as Set<String> } ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key]?.let { it as Int } ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key]?.let { it as Long } ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key]?.let { it as Float } ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key]?.let { it as Boolean } ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val updates = linkedMapOf<String, Any>()
        private val removals = linkedSetOf<String>()
        private var clear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            updateNullable(key, value)

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor =
            updateNullable(key, values?.toSet())

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = update(key, value)

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = update(key, value)

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = update(key, value)

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = update(key, value)

        override fun remove(key: String): SharedPreferences.Editor = apply {
            updates.remove(key)
            removals += key
        }

        override fun clear(): SharedPreferences.Editor = apply {
            clear = true
            updates.clear()
            removals.clear()
        }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() = applyChanges()

        private fun update(key: String, value: Any): SharedPreferences.Editor = apply {
            removals -= key
            updates[key] = value
        }

        private fun updateNullable(key: String, value: Any?): SharedPreferences.Editor =
            if (value == null) remove(key) else update(key, value)

        private fun applyChanges() {
            if (clear) values.clear()
            removals.forEach(values::remove)
            values.putAll(updates)
        }
    }


}