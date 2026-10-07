package com.wmods.wppenhacer.xposed.features.media

import android.content.SharedPreferences
import android.util.Log
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.utils.Utils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Modifier

class MusicRegionSpoof(classLoader: ClassLoader, preferences: SharedPreferences) :
    Feature(classLoader, preferences) {

    companion object {
        private const val TAG = "WAE_MUSIC"
    }

    private fun getTargetCountry(): String {
        val custom = xprefs.getString("music_region_custom_country", null)
        val selected = xprefs.getString("music_region_country", null)
        return (custom?.trim()?.uppercase()?.takeIf { it.length == 2 && it.all { c -> c.isLetter() } }
            ?: selected?.trim()?.uppercase()?.takeIf { it.length == 2 && it.all { c -> c.isLetter() } }
            ?: "US")
    }

    override fun doHook() {
        val isEnabled = xprefs.getBoolean("music_region_spoof", true)
        if (!isEnabled) {
            Log.i(TAG, "MusicRegionSpoof is disabled by user preference")
            return
        }

        val targetCountry = getTargetCountry()

        Log.i(TAG, "==================================================")
        Log.i(TAG, "Initializing MusicRegionSpoof for country: $targetCountry")
        Log.i(TAG, "==================================================")

        // Listen for country changes at runtime
        try {
            xprefs.registerOnSharedPreferenceChangeListener { _, key ->
                if (key == "music_region_country" || key == "music_region_custom_country") {
                    val updated = getTargetCountry()
                    Log.i(TAG, "Music region preference changed dynamically to: $updated")
                    clearStaleMusicDiskCache(updated)
                }
            }
        } catch (_: Throwable) {}

        // 1. Purge stale disk cache with different country code
        clearStaleMusicDiskCache(targetCountry)

        // 2. Hook Music Country Resolvers (X.7mP & X.13r)
        hookMusicCountryResolvers()

        // 3. Hook GraphQL Parameter Builders (X.RDU & JSONObject)
        hookGraphQLParameters()

        // 4. Hook Music Disk Cache Validator (X.Q1a)
        hookDiskCacheValidator()

        // 5. Hook Music Gating / License Check (MusicGating)
        hookMusicGating()

        // 6. Hook Music Repository Eligible Countries (MusicRepository)
        hookMusicRepository()
    }

    private fun clearStaleMusicDiskCache(targetCountry: String) {
        try {
            val app = Utils.application
            val filesDir = app.filesDir
            val candidates = listOf(
                File(filesDir, "music_catalog_disk_cache.json"),
                File(filesDir.parentFile, "accounts/1001/files/music_catalog_disk_cache.json")
            )
            for (file in candidates) {
                if (file.exists()) {
                    val content = file.readText()
                    if (!content.contains("\"countryCode\":\"$targetCountry\"")) {
                        file.delete()
                        Log.i(TAG, "Purged stale music cache from: ${file.absolutePath}")
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Cache purge check error: ${e.message}")
        }
    }

    private fun hookMusicCountryResolvers() {
        // Direct hook for WhatsApp 2.26.x music country provider (X.7mP)
        try {
            val countryProviderClass = runCatching { classLoader.loadClass("X.7mP") }.getOrNull()
            if (countryProviderClass != null) {
                for (method in countryProviderClass.declaredMethods) {
                    if (method.returnType == String::class.java) {
                        method.hook {
                            before {
                                val country = getTargetCountry()
                                Log.i(TAG, "Hooked X.7mP.${this.method.name}() -> returning $country")
                                result = country
                            }
                        }
                    }
                }
                Log.i(TAG, "Successfully installed X.7mP country resolver hook")
            } else {
                Log.w(TAG, "Class X.7mP not found directly in classLoader")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error hooking X.7mP", e)
        }

        // Direct hook for WhatsApp general ISO country provider (X.13r)
        try {
            val generalCountryClass = runCatching { classLoader.loadClass("X.13r") }.getOrNull()
            if (generalCountryClass != null) {
                for (method in generalCountryClass.declaredMethods) {
                    if (method.returnType == String::class.java && method.parameterTypes.isEmpty()) {
                        method.hook {
                            before {
                                val country = getTargetCountry()
                                Log.i(TAG, "Hooked X.13r.${this.method.name}() -> returning $country")
                                result = country
                            }
                        }
                    }
                }
                Log.i(TAG, "Successfully installed X.13r country resolver hook")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error hooking X.13r", e)
        }

        // Dynamic fallback via MusicApi DI provider
        try {
            val musicApiClass = runCatching {
                classLoader.loadClass("com.whatsapp.music.productinfra.api.MusicApi")
            }.getOrNull()

            if (musicApiClass != null) {
                musicApiClass.resolve().constructor { }.hookAll {
                    after {
                        val apiInstance = instance ?: return@after
                        for (field in musicApiClass.declaredFields) {
                            field.isAccessible = true
                            val provider = field.get(apiInstance) ?: continue
                            for (m in provider.javaClass.methods) {
                                if (m.parameterTypes.isEmpty() && m.returnType != Void.TYPE && m.returnType != java.lang.Object::class.java) {
                                    val candidate = runCatching { m.invoke(provider) }.getOrNull() ?: continue
                                    val stringMethods = candidate.javaClass.declaredMethods.filter {
                                        it.returnType == String::class.java && it.parameterTypes.isEmpty()
                                    }
                                    for (sm in stringMethods) {
                                        sm.isAccessible = true
                                        val testVal = runCatching { sm.invoke(candidate) as? String }.getOrNull()
                                        if (testVal != null && testVal.length == 2 && testVal.all { it.isLetter() }) {
                                            for (targetMethod in candidate.javaClass.declaredMethods) {
                                                if (targetMethod.returnType == String::class.java) {
                                                    targetMethod.hook {
                                                        before {
                                                            val country = getTargetCountry()
                                                            Log.i(TAG, "Dynamic provider ${candidate.javaClass.name}.${this.method.name}() -> returning $country")
                                                            result = country
                                                        }
                                                    }
                                                }
                                            }
                                            Log.i(TAG, "Dynamically hooked country provider ${candidate.javaClass.name}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in dynamic MusicApi hook", e)
        }
    }

    private fun hookGraphQLParameters() {
        // Hook Meta ACS GraphQL parameter builder (X.RDU)
        try {
            val rduClass = runCatching { classLoader.loadClass("X.RDU") }.getOrNull()
            if (rduClass != null) {
                for (method in rduClass.declaredMethods) {
                    val pTypes = method.parameterTypes
                    // A02(String, List)
                    if (pTypes.size == 2 && pTypes[0] == String::class.java && List::class.java.isAssignableFrom(pTypes[1])) {
                        method.hook {
                            before {
                                val key = args.getOrNull(0) as? String
                                if (key == "available_countries") {
                                    val country = getTargetCountry()
                                    args[1] = listOf(country)
                                    Log.i(TAG, "X.RDU.A02 available_countries replaced with [$country]")
                                }
                            }
                        }
                    }
                    // A01(String, Object)
                    if (pTypes.size == 2 && pTypes[0] == String::class.java && pTypes[1] == java.lang.Object::class.java) {
                        method.hook {
                            before {
                                val key = args.getOrNull(0) as? String
                                if (key == "country") {
                                    val country = getTargetCountry()
                                    args[1] = country
                                    Log.i(TAG, "X.RDU.A01 country replaced with $country")
                                }
                            }
                        }
                    }
                }
                Log.i(TAG, "Successfully installed X.RDU parameter hooks")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error hooking X.RDU", e)
        }

        // Global JSONObject.put interception for available_countries and country
        try {
            for (method in JSONObject::class.java.declaredMethods) {
                if (method.name == "put" && method.parameterTypes.size == 2 && method.parameterTypes[0] == String::class.java) {
                    method.hook {
                        before {
                            val key = args.getOrNull(0) as? String
                            if (key == "available_countries") {
                                val country = getTargetCountry()
                                args[1] = JSONArray().put(country)
                                Log.i(TAG, "JSONObject.put available_countries -> [$country]")
                            } else if (key == "country" && instance.javaClass.name.contains("RDU")) {
                                val country = getTargetCountry()
                                args[1] = country
                                Log.i(TAG, "JSONObject.put country -> $country")
                            }
                        }
                    }
                }
            }
            Log.i(TAG, "Successfully installed JSONObject.put hooks")
        } catch (e: Throwable) {
            Log.e(TAG, "Error hooking JSONObject.put", e)
        }
    }

    private fun hookDiskCacheValidator() {
        try {
            val cacheManagerClass = runCatching { classLoader.loadClass("X.Q1a") }.getOrNull()
            if (cacheManagerClass != null) {
                for (method in cacheManagerClass.declaredMethods) {
                    // A01 validates if disk cache matches current country
                    if (method.name == "A01" && method.returnType == Boolean::class.javaPrimitiveType) {
                        method.hook {
                            before {
                                val entry = args.getOrNull(1)
                                if (entry != null) {
                                    val countryField = entry.javaClass.declaredFields.firstOrNull { it.name == "A02" }
                                    countryField?.isAccessible = true
                                    val cachedCountry = countryField?.get(entry) as? String
                                    val targetCountry = getTargetCountry()
                                    if (cachedCountry != null && !cachedCountry.equals(targetCountry, ignoreCase = true)) {
                                        Log.i(TAG, "Rejecting stale disk cache (cached=$cachedCountry, target=$targetCountry)")
                                        result = false // Force cache invalidation!
                                    }
                                }
                            }
                        }
                    }
                }
                Log.i(TAG, "Successfully installed X.Q1a disk cache validation hook")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error hooking X.Q1a", e)
        }
    }

    private fun hookMusicGating() {
        try {
            val gatingClass = runCatching {
                classLoader.loadClass("com.whatsapp.music.productinfra.gating.MusicGating")
            }.getOrNull() ?: return

            val c7e1Class = runCatching { classLoader.loadClass("X.7E1") }.getOrNull()
            val notBlockedConstant = c7e1Class?.enumConstants?.firstOrNull { it.toString() == "NOT_BLOCKED" }

            for (method in gatingClass.declaredMethods) {
                if (!Modifier.isStatic(method.modifiers)) {
                    method.hook {
                        after {
                            // A05 returns Boolean (isBlocked) -> force false
                            if (method.name == "A05" && result is Boolean) {
                                result = false
                            }
                            // If method returns C7E1 enum, force NOT_BLOCKED
                            if (notBlockedConstant != null && result != null && result?.javaClass == c7e1Class) {
                                result = notBlockedConstant
                            }
                        }
                    }
                }
            }
            Log.i(TAG, "Successfully installed MusicGating licensing hooks")
        } catch (e: Throwable) {
            Log.e(TAG, "Error hooking MusicGating", e)
        }
    }

    private fun hookMusicRepository() {
        try {
            val repoClass = runCatching {
                classLoader.loadClass("com.whatsapp.music.productinfra.api.MusicRepository")
            }.getOrNull() ?: return

            // Ensure in-memory cache is wiped on construction
            repoClass.resolve().constructor { }.hookAll {
                after {
                    for (f in repoClass.declaredFields) {
                        if (Map::class.java.isAssignableFrom(f.type)) {
                            f.isAccessible = true
                            (f.get(instance) as? MutableMap<*, *>)?.clear()
                            Log.i(TAG, "Cleared MusicRepository in-memory cache map")
                        }
                    }
                }
            }

            for (method in repoClass.declaredMethods) {
                if (!Modifier.isStatic(method.modifiers)) {
                    method.hook {
                        after {
                            val set = result as? Set<*>
                            if (set != null && (set.isEmpty() || set.firstOrNull() is String)) {
                                @Suppress("UNCHECKED_CAST")
                                val mutable = (set as Set<String>).toMutableSet()
                                val targetCountry = getTargetCountry()
                                mutable.add(targetCountry)
                                mutable.add("US")
                                result = mutable
                            }
                        }
                    }
                }
            }
            Log.i(TAG, "Successfully installed MusicRepository eligible countries hooks")
        } catch (e: Throwable) {
            Log.e(TAG, "Error hooking MusicRepository", e)
        }
    }

    override fun getPluginName(): String = "Music Region Spoof"
}
