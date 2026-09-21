package com.bishop.healthconnectgate

import android.content.SharedPreferences

/** In-memory SharedPreferences for JVM tests. */
internal class FakePrefs : SharedPreferences {
    val data = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)
    override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = data[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class Editor : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clearAll = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clearAll = true }
        override fun commit(): Boolean {
            if (clearAll) data.clear()
            removed.forEach { data.remove(it) }
            data.putAll(pending)
            return true
        }
        override fun apply() { commit() }
    }
}

internal class MemoryResumeStore : ResumeStore {
    val entries = linkedMapOf<Pair<String, String>, TypeProgress>()
    override fun get(chunkId: String, typeKey: String) = entries[chunkId to typeKey]
    override fun put(chunkId: String, typeKey: String, progress: TypeProgress) { entries[chunkId to typeKey] = progress }
    override fun clear(chunkId: String) { entries.keys.removeAll { it.first == chunkId } }
    override fun clearAll() = entries.clear()
}
