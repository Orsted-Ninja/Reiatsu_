package com.storagesense.app.ui.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecentFilesHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("storagesense_recent_opened", Context.MODE_PRIVATE)

    fun recordOpened(filePath: String) {
        if (filePath.isBlank()) return
        val current = getRecentlyOpenedPaths().toMutableList()
        current.remove(filePath)
        current.add(0, filePath)
        val capped = current.take(50)
        prefs.edit().putString("paths", capped.joinToString("\n")).apply()
    }

    fun getRecentlyOpenedPaths(): List<String> {
        val raw = prefs.getString("paths", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }
    }
}
