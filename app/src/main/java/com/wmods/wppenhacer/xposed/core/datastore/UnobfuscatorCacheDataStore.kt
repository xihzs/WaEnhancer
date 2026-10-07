package com.wmods.wppenhacer.xposed.core.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.highcapable.yukihookapi.hook.log.YLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

class UnobfuscatorCacheDataStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val sp: SharedPreferences = appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
    private val memoryCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var loaded = false

    init {
        ensureLoaded()
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            // If SharedPreferences is empty, attempt one-time migration from legacy DataStore file
            migrateLegacyDataStoreIfNeeded()

            // Pre-populate in-memory cache from SharedPreferences
            try {
                sp.all.forEach { (k, v) ->
                    if (v is String) memoryCache[k] = v
                }
            } catch (e: Throwable) {
                YLog.error("Error loading SharedPreferences cache into memory", e)
            }
            loaded = true
        }
    }

    private fun migrateLegacyDataStoreIfNeeded() {
        try {
            if (sp.all.isNotEmpty()) return
            val dsFile = appContext.preferencesDataStoreFile(DATASTORE_FILE_NAME)
            if (dsFile.exists() && dsFile.length() > 0) {
                val ds = PreferenceDataStoreFactory.create(produceFile = { dsFile })
                val snapshot = runBlocking { ds.data.first() }
                val editor = sp.edit()
                snapshot.asMap().forEach { (k, v) ->
                    if (v is String) editor.putString(k.name, v)
                }
                editor.commit()
                YLog.info("Migrated ${snapshot.asMap().size} keys from legacy DataStore to SharedPreferences")
            }
        } catch (t: Throwable) {
            YLog.error("Migration from legacy DataStore failed (continuing with fresh cache)", t)
        }
    }

    fun getString(namespace: String, key: String, defaultValue: String?): String? {
        val nKey = namespacedKey(namespace, key)
        return memoryCache[nKey] ?: sp.getString(nKey, defaultValue)
    }

    fun getInt(namespace: String, key: String, defaultValue: Int): Int {
        return getString(namespace, key, null)?.toIntOrNull() ?: defaultValue
    }

    fun getLong(namespace: String, key: String, defaultValue: Long): Long {
        return getString(namespace, key, null)?.toLongOrNull() ?: defaultValue
    }

    fun putString(namespace: String, key: String, value: String?) {
        val nKey = namespacedKey(namespace, key)
        if (value == null) {
            memoryCache.remove(nKey)
            sp.edit().remove(nKey).apply()
        } else {
            memoryCache[nKey] = value
            sp.edit().putString(nKey, value).apply()
        }
    }

    fun putInt(namespace: String, key: String, value: Int) {
        putString(namespace, key, value.toString())
    }

    fun putLong(namespace: String, key: String, value: Long) {
        putString(namespace, key, value.toString())
    }

    fun remove(namespace: String, key: String) {
        putString(namespace, key, null)
    }

    fun flushBlocking() {
        try {
            sp.edit().commit()
        } catch (e: Exception) {
            YLog.error("flushBlocking failed", e)
        }
    }

    fun clearNamespace(namespace: String) {
        val prefix = "$namespace::"
        val editor = sp.edit()
        val toRemove = memoryCache.keys.filter { it.startsWith(prefix) }
        toRemove.forEach { k ->
            memoryCache.remove(k)
            editor.remove(k)
        }
        editor.apply()
    }

    fun clearAll() {
        memoryCache.clear()
        sp.edit().clear().apply()
    }

    private fun namespacedKey(namespace: String, key: String): String {
        return "$namespace::$key"
    }

    companion object {
        private const val SP_NAME = "wae_unobfuscator_cache"
        private const val DATASTORE_FILE_NAME = "unobfuscator_cache"
        const val NAMESPACE_HOOKS = "hooks"
        const val NAMESPACE_STRINGS = "strings"
        const val NAMESPACE_REFLECTION = "reflection"

        @Volatile
        private var instance: UnobfuscatorCacheDataStore? = null

        @JvmStatic
        fun getInstance(context: Context): UnobfuscatorCacheDataStore {
            return instance ?: synchronized(this) {
                instance ?: UnobfuscatorCacheDataStore(context).also { instance = it }
            }
        }
    }
}
