package io.github.hoerire.free.config

import android.content.SharedPreferences

/**
 * 设置快照的跨进程编解码。
 *
 * 模块应用改完设置后经 [ModulePreferences.ACTION_PINNED_TARGETS_CHANGED] 把整份快照
 * 直接推给各作用域进程，接收方用本对象还原成 [ModuleSettingsSnapshot] 覆盖内存快照。
 * 载荷只走广播 extras，没有落盘与回执。
 */
internal object SettingsBridge {
    const val EXTRA_SETTINGS_MAP = "settings_map"

    fun encode(snapshot: ModuleSettingsSnapshot): HashMap<String, Any?> {
        val map = HashMap<String, Any?>()
        snapshot.writeTo(MapPreferences(map).edit()).commit()
        return map
    }

    fun decode(map: Map<String, Any?>): ModuleSettingsSnapshot =
        ModuleSettingsSnapshot.readFrom(MapPreferences(HashMap(map)))

    fun putMap(bundle: android.os.Bundle, map: Map<String, Any?>) {
        bundle.putSerializable(EXTRA_SETTINGS_MAP, HashMap(map))
    }

    @Suppress("UNCHECKED_CAST")
    fun readMap(bundle: android.os.Bundle?): Map<String, Any?>? =
        try {
            bundle?.getSerializable(EXTRA_SETTINGS_MAP) as? Map<String, Any?>
        } catch (_: RuntimeException) {
            null
        }
}

/**
 * 只用于搬移快照的 [SharedPreferences]：读写落在内存映射上。
 *
 * 复用 [ModuleSettingsSnapshot.writeTo] / [ModuleSettingsSnapshot.readFrom]，键名与类型
 * 因此只有一处定义，通道两端不需要各自维护字段清单。
 */
internal class MapPreferences(private val map: MutableMap<String, Any?>) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = HashMap(map)

    override fun getString(key: String, defValue: String?): String? =
        map[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        when (val value = map[key]) {
            is Set<*> -> value.filterIsInstance<String>().toMutableSet()
            else -> defValues
        }

    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        map[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = MapEditor(map)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private class MapEditor(private val map: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val removals = ArrayList<String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            apply(key, value)

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply(key, values?.toMutableSet())

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply(key, value)

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply(key, value)

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            apply(key, value)

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            apply(key, value)

        override fun remove(key: String): SharedPreferences.Editor {
            removals += key
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            map.clear()
            return this
        }

        override fun commit(): Boolean {
            removals.forEach { key -> map.remove(key) }
            return true
        }

        override fun apply() = Unit

        private fun apply(key: String, value: Any?): SharedPreferences.Editor {
            removals -= key
            if (value == null) map.remove(key) else map[key] = value
            return this
        }
    }
}
