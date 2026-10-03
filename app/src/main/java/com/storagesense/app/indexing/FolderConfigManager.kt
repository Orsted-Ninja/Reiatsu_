package com.storagesense.app.indexing

import android.content.Context
import android.os.Environment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class IndexedFolder(
    val id: String,
    val displayName: String,
    val path: String,
    val isEnabled: Boolean = true,
    val isDefault: Boolean = true,
    val description: String = ""
)

@Singleton
class FolderConfigManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("storagesense_folder_config", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _folders = MutableStateFlow<List<IndexedFolder>>(emptyList())
    val folders: StateFlow<List<IndexedFolder>> = _folders.asStateFlow()

    init {
        loadFolders()
    }

    @Synchronized
    fun loadFolders() {
        val defaultList = getDefaultFolders()
        val customFolders = getSavedCustomFolders()

        val merged = mutableListOf<IndexedFolder>()

        for (df in defaultList) {
            val isEnabled = prefs.getBoolean("enabled_${df.id}", df.isEnabled)
            merged.add(df.copy(isEnabled = isEnabled))
        }

        for (cf in customFolders) {
            val isEnabled = prefs.getBoolean("enabled_${cf.id}", cf.isEnabled)
            merged.add(cf.copy(isEnabled = isEnabled))
        }

        _folders.value = merged
    }

    private fun getDefaultFolders(): List<IndexedFolder> {
        val list = mutableListOf<IndexedFolder>()
        try {
            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            list.add(
                IndexedFolder(
                    id = "dcim",
                    displayName = "Camera & Photos (DCIM)",
                    path = dcim?.absolutePath ?: "/storage/emulated/0/DCIM",
                    isEnabled = true,
                    isDefault = true,
                    description = "Photos, videos, and camera captures"
                )
            )

            val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            list.add(
                IndexedFolder(
                    id = "pictures",
                    displayName = "Pictures & Screenshots",
                    path = pictures?.absolutePath ?: "/storage/emulated/0/Pictures",
                    isEnabled = true,
                    isDefault = true,
                    description = "Screenshots, wallpapers, and downloaded images"
                )
            )

            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            list.add(
                IndexedFolder(
                    id = "download",
                    displayName = "Downloads",
                    path = downloads?.absolutePath ?: "/storage/emulated/0/Download",
                    isEnabled = true,
                    isDefault = true,
                    description = "Web downloads, documents, received files"
                )
            )

            val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            list.add(
                IndexedFolder(
                    id = "documents",
                    displayName = "Documents & Work",
                    path = documents?.absolutePath ?: "/storage/emulated/0/Documents",
                    isEnabled = true,
                    isDefault = true,
                    description = "PDFs, spreadsheets, presentations, and texts"
                )
            )

            val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            list.add(
                IndexedFolder(
                    id = "movies",
                    displayName = "Movies & Videos",
                    path = movies?.absolutePath ?: "/storage/emulated/0/Movies",
                    isEnabled = true,
                    isDefault = true,
                    description = "Saved videos, screen recordings, clips"
                )
            )

            val music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            list.add(
                IndexedFolder(
                    id = "music",
                    displayName = "Music & Audio",
                    path = music?.absolutePath ?: "/storage/emulated/0/Music",
                    isEnabled = true,
                    isDefault = true,
                    description = "Audio tracks, podcasts, songs"
                )
            )

            val external = Environment.getExternalStorageDirectory()
            val androidMedia = File(external, "Android/media")
            list.add(
                IndexedFolder(
                    id = "android_media",
                    displayName = "App Media (WhatsApp, Telegram)",
                    path = androidMedia.absolutePath,
                    isEnabled = true,
                    isDefault = true,
                    description = "Chat media, voice notes, stickers (often 10,000+ files)"
                )
            )
        } catch (e: Exception) {
            // Handled
        }
        return list
    }

    private fun getSavedCustomFolders(): List<IndexedFolder> {
        val json = prefs.getString("custom_folders", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<IndexedFolder>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveCustomFolders(folders: List<IndexedFolder>) {
        val json = gson.toJson(folders)
        prefs.edit().putString("custom_folders", json).apply()
    }

    @Synchronized
    fun toggleFolder(id: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled_$id", enabled).apply()
        _folders.value = _folders.value.map { folder ->
            if (folder.id == id) folder.copy(isEnabled = enabled) else folder
        }
    }

    @Synchronized
    fun addCustomFolder(rawPath: String, customName: String = ""): Result<IndexedFolder> {
        val trimmedPath = rawPath.trim()
        if (trimmedPath.isBlank()) {
            return Result.failure(IllegalArgumentException("Folder path cannot be empty"))
        }

        val dir = File(trimmedPath)
        if (!dir.exists()) {
            return Result.failure(IllegalArgumentException("Directory does not exist"))
        }
        if (!dir.isDirectory) {
            return Result.failure(IllegalArgumentException("Path is not a directory"))
        }
        if (!dir.canRead()) {
            return Result.failure(IllegalArgumentException("Directory cannot be read (Permission denied)"))
        }

        val canonicalPath = try {
            dir.canonicalPath
        } catch (e: Exception) {
            dir.absolutePath
        }

        val current = _folders.value
        if (current.any { it.path.equals(canonicalPath, ignoreCase = true) }) {
            return Result.failure(IllegalArgumentException("This folder is already in the list"))
        }

        val folderName = if (customName.isNotBlank()) customName.trim() else dir.name
        val newFolder = IndexedFolder(
            id = "custom_${System.currentTimeMillis()}",
            displayName = folderName,
            path = canonicalPath,
            isEnabled = true,
            isDefault = false,
            description = "Custom folder: $canonicalPath"
        )

        val customList = getSavedCustomFolders().toMutableList()
        customList.add(newFolder)
        saveCustomFolders(customList)
        prefs.edit().putBoolean("enabled_${newFolder.id}", true).apply()

        _folders.value = _folders.value + newFolder
        return Result.success(newFolder)
    }

    @Synchronized
    fun removeCustomFolder(id: String) {
        val customList = getSavedCustomFolders().toMutableList()
        customList.removeAll { it.id == id }
        saveCustomFolders(customList)
        prefs.edit().remove("enabled_$id").apply()
        _folders.value = _folders.value.filter { it.id != id }
    }

    fun getEnabledRoots(): List<File> {
        return _folders.value
            .filter { it.isEnabled }
            .map { File(it.path) }
            .filter { it.exists() && it.canRead() }
    }

    fun getDisabledPaths(): Set<String> {
        return _folders.value
            .filter { !it.isEnabled }
            .map { it.path.replace('\\', '/').trimEnd('/') }
            .toSet()
    }

    fun getEnabledPaths(): Set<String> {
        return _folders.value
            .filter { it.isEnabled }
            .map { it.path.replace('\\', '/').trimEnd('/') }
            .toSet()
    }

    fun isPathAllowed(filePath: String): Boolean {
        val norm = filePath.replace('\\', '/').trimEnd('/')
        val disabled = getDisabledPaths()
        for (d in disabled) {
            if (norm.equals(d, ignoreCase = true) || norm.startsWith("$d/", ignoreCase = true)) {
                return false
            }
        }

        val enabled = getEnabledPaths()
        for (e in enabled) {
            if (norm.equals(e, ignoreCase = true) || norm.startsWith("$e/", ignoreCase = true)) {
                return true
            }
        }

        // If directly in external root (shallow file)
        val extRoot = Environment.getExternalStorageDirectory()?.absolutePath?.replace('\\', '/')?.trimEnd('/')
        if (extRoot != null) {
            val parent = File(norm).parent?.replace('\\', '/')?.trimEnd('/')
            if (parent != null && parent.equals(extRoot, ignoreCase = true)) {
                return true
            }
        }

        return false
    }

    fun getSuggestedFolders(): List<String> {
        val suggestions = mutableListOf<String>()
        try {
            val external = Environment.getExternalStorageDirectory()
            if (external != null && external.exists() && external.canRead()) {
                val existingPaths = _folders.value.map { it.path.replace('\\', '/').trimEnd('/') }.toSet()
                val subdirs = external.listFiles { file -> file.isDirectory && !file.name.startsWith(".") } ?: emptyArray()
                for (dir in subdirs) {
                    val name = dir.name
                    if (name.equals("Android", ignoreCase = true) ||
                        name.equals("StorageSense", ignoreCase = true) ||
                        name.equals(".storagesense", ignoreCase = true) ||
                        name.equals("models", ignoreCase = true)) {
                        continue
                    }
                    val normPath = dir.absolutePath.replace('\\', '/').trimEnd('/')
                    if (!existingPaths.contains(normPath)) {
                        suggestions.add(dir.absolutePath)
                    }
                }
            }
        } catch (e: Exception) {
            // Handled
        }
        return suggestions.sorted()
    }
}
