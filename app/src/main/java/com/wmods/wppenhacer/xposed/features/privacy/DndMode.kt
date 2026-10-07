package com.wmods.wppenhacer.xposed.features.privacy

import android.content.SharedPreferences
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore.getPrivBoolean
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.getMethodDescriptor
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadDndModeMethod

class DndMode(loader: ClassLoader, preferences: SharedPreferences) : Feature(loader, preferences) {

    override fun doHook() {
        val dndMethod = loadDndModeMethod(classLoader)
        logDebug(getMethodDescriptor(dndMethod))
        dndMethod.hook {
            before {
                if (getPrivBoolean("dndmode", false) || xprefs.getBoolean("dndmode", false)) {
                    result = null
                }
            }
        }
    }

    override fun getPluginName(): String {
        return "Dnd Mode"
    }
}
