package com.nav.automator

import android.content.Context
import androidx.core.content.edit

enum class OrientationMode {
    LANDSCAPE,
    PORTRAIT
}

object AppPreferences {
    private const val PREFS_NAME = "nav_automator_prefs"
    private const val KEY_TARGET_PACKAGE = "target_package_name"
    private const val KEY_TARGET_NAME = "target_app_name"
    private const val KEY_ORIENTATION_MODE = "orientation_mode"

    fun getTargetPackage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_TARGET_PACKAGE, "") ?: ""
    }

    fun getTargetAppName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_TARGET_NAME, "") ?: ""
    }

    fun setTargetApp(context: Context, packageName: String, appName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putString(KEY_TARGET_PACKAGE, packageName)
            putString(KEY_TARGET_NAME, appName)
        }
    }

    fun getOrientationMode(context: Context): OrientationMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val modeStr = prefs.getString(KEY_ORIENTATION_MODE, OrientationMode.LANDSCAPE.name)
        return try {
            OrientationMode.valueOf(modeStr ?: OrientationMode.LANDSCAPE.name)
        } catch (_: Exception) {
            OrientationMode.LANDSCAPE
        }
    }

    fun setOrientationMode(context: Context, mode: OrientationMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putString(KEY_ORIENTATION_MODE, mode.name)
        }
    }
}
