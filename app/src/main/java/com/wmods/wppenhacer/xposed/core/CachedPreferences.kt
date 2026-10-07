package com.wmods.wppenhacer.xposed.core

import android.content.SharedPreferences
import com.wmods.wppenhacer.xposed.utils.Utils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * High-performance in-memory caching wrapper for SharedPreferences (such as RemotePreferences).
 *
 * Eliminates cross-process ContentProvider IPC overhead during UI rendering and scroll operations.
 * Reads are served directly from RAM (O(1) ConcurrentHashMap), and changes are synchronized
 * via OnSharedPreferenceChangeListener.
 */
class CachedPreferences(
    private val delegate: SharedPreferences
) : SharedPreferences {

    private val cache = ConcurrentHashMap<String, Any>()
    private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    init {
        // Pre-populate cache in background to avoid any blocking on main thread
        Utils.executor.execute {
            try {
                val all = delegate.all
                if (all != null) {
                    for ((k, v) in all) {
                        if (v != null) cache[k] = v
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // Listen for changes from remote process
        try {
            delegate.registerOnSharedPreferenceChangeListener { _, key ->
                if (key != null) {
                    refreshKey(key)
                    for (listener in listeners) {
                        try {
                            listener.onSharedPreferenceChanged(this, key)
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun refreshKey(key: String) {
        try {
            val all = delegate.all
            if (all != null && all.containsKey(key)) {
                val value = all[key]
                if (value != null) cache[key] = value else cache.remove(key)
            } else {
                cache.remove(key)
            }
        } catch (_: Throwable) {
        }
    }

    override fun getAll(): MutableMap<String, *> {
        return HashMap(cache)
    }

    override fun getString(key: String, defValue: String?): String? {
        val v = cache[key]
        if (v != null) return v as? String ?: defValue
        return try {
            val fetched = delegate.getString(key, defValue)
            if (fetched != null) cache[key] = fetched
            fetched
        } catch (_: Throwable) {
            defValue
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        val v = cache[key]
        if (v != null) return v as? Set<String> ?: defValues
        return try {
            val fetched = delegate.getStringSet(key, defValues)
            if (fetched != null) cache[key] = fetched
            fetched
        } catch (_: Throwable) {
            defValues
        }
    }

    override fun getInt(key: String, defValue: Int): Int {
        val v = cache[key]
        if (v is Number) return v.toInt()
        if (v is String) return v.toIntOrNull() ?: defValue
        return try {
            val fetched = delegate.getInt(key, defValue)
            cache[key] = fetched
            fetched
        } catch (_: Throwable) {
            defValue
        }
    }

    override fun getLong(key: String, defValue: Long): Long {
        val v = cache[key]
        if (v is Number) return v.toLong()
        if (v is String) return v.toLongOrNull() ?: defValue
        return try {
            val fetched = delegate.getLong(key, defValue)
            cache[key] = fetched
            fetched
        } catch (_: Throwable) {
            defValue
        }
    }

    override fun getFloat(key: String, defValue: Float): Float {
        val v = cache[key]
        if (v is Number) return v.toFloat()
        if (v is String) return v.toFloatOrNull() ?: defValue
        return try {
            val fetched = delegate.getFloat(key, defValue)
            cache[key] = fetched
            fetched
        } catch (_: Throwable) {
            defValue
        }
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        val v = cache[key]
        if (v is Boolean) return v
        if (v is String) return v.toBoolean()
        return try {
            val fetched = delegate.getBoolean(key, defValue)
            cache[key] = fetched
            fetched
        } catch (_: Throwable) {
            defValue
        }
    }

    override fun contains(key: String): Boolean {
        return cache.containsKey(key) || try { delegate.contains(key) } catch (_: Throwable) { false }
    }

    override fun edit(): SharedPreferences.Editor {
        return Editor(delegate.edit())
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listeners.remove(listener)
    }

    private inner class Editor(private val delegateEditor: SharedPreferences.Editor) : SharedPreferences.Editor {
        private val localChanges = HashMap<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            localChanges[key] = value
            delegateEditor.putString(key, value)
            return this
        }

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
            localChanges[key] = values
            delegateEditor.putStringSet(key, values)
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            localChanges[key] = value
            delegateEditor.putInt(key, value)
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            localChanges[key] = value
            delegateEditor.putLong(key, value)
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            localChanges[key] = value
            delegateEditor.putFloat(key, value)
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            localChanges[key] = value
            delegateEditor.putBoolean(key, value)
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            localChanges[key] = null
            delegateEditor.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            delegateEditor.clear()
            return this
        }

        override fun commit(): Boolean {
            applyLocal()
            return delegateEditor.commit()
        }

        override fun apply() {
            applyLocal()
            delegateEditor.apply()
        }

        private fun applyLocal() {
            if (clearRequested) {
                cache.clear()
            }
            localChanges.forEach { (k, v) ->
                if (v == null) cache.remove(k) else cache[k] = v
                for (listener in listeners) {
                    try { listener.onSharedPreferenceChanged(this@CachedPreferences, k) } catch (_: Throwable) {}
                }
            }
        }
    }
}
