package com.storagesense.app.indexing

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.storagesense.app.domain.model.FileCategory
import com.storagesense.app.domain.model.FileItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val folderConfigManager: FolderConfigManager
) {

    companion object {
        const val LARGE_FILE_THRESHOLD_BYTES = 10L * 1024L * 1024L // 10 MB (per plan_project.md specification)
        const val FAST_HASH_SAMPLE_BYTES = 1L * 1024L * 1024L // 1 MB (head & tail sample)
    }

    /**
     * Scans configured external storage directories and MediaStore to discover user files.
     * High performance: Only scans enabled folders, skipping disabled ones like WhatsApp to prevent lag.
     */
    suspend fun scanDirectories(
        roots: List<File>? = null,
        onFileFound: (suspend (FileItem) -> Unit)? = null
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val foundMap = LinkedHashMap<String, FileItem>()
        val effectiveRoots = roots ?: folderConfigManager.getEnabledRoots()

        // 1. Scan shallow files directly sitting in external storage root (non-recursive)
        try {
            val external = Environment.getExternalStorageDirectory()
            if (external != null && external.exists() && external.canRead()) {
                val looseFiles = external.listFiles { file ->
                    file.isFile && file.length() > 0 && !file.name.startsWith(".")
                } ?: emptyArray()
                for (file in looseFiles) {
                    if (folderConfigManager.isPathAllowed(file.absolutePath)) {
                        val item = createFileItem(file)
                        foundMap[item.path] = item
                        onFileFound?.invoke(item)
                    }
                }
            }
        } catch (e: Exception) {
            // Handled
        }

        // 2. Recursive Filesystem Scan across configured enabled roots
        for (root in effectiveRoots) {
            if (!root.exists() || !root.canRead()) continue
            scanRecursive(root, foundMap, onFileFound)
        }

        // 3. MediaStore Provider Scan filtered by user-enabled folder scope
        try {
            val mediaStoreItems = scanMediaStore()
            for (item in mediaStoreItems) {
                if (!folderConfigManager.isPathAllowed(item.path)) {
                    continue
                }
                if (!foundMap.containsKey(item.path)) {
                    foundMap[item.path] = item
                    onFileFound?.invoke(item)
                }
            }
        } catch (e: Exception) {
            // MediaStore fallback handled safely
        }

        foundMap.values.toList()
    }

    private suspend fun scanRecursive(
        directory: File,
        results: MutableMap<String, FileItem>,
        onFileFound: (suspend (FileItem) -> Unit)?
    ) {
        val entries = directory.listFiles() ?: return
        val disabledPaths = folderConfigManager.getDisabledPaths()

        for (entry in entries) {
            val name = entry.name

            // Ignore hidden system folders, internal StorageSense trash, and model weights directory
            if (name.startsWith(".") || 
                name.equals(".storagesense", ignoreCase = true) ||
                name.equals("StorageSense", ignoreCase = true) ||
                name.equals("models", ignoreCase = true)) {
                continue
            }

            if (entry.isDirectory) {
                val absPath = entry.absolutePath.replace('\\', '/')
                // Skip OS-restricted Android/data and Android/obb which cannot be accessed directly in Android 11+
                if (absPath.endsWith("/Android/data") || absPath.endsWith("/Android/obb") ||
                    absPath.contains("/Android/data/") || absPath.contains("/Android/obb/")) {
                    continue
                }

                // Skip any disabled folder branch
                val normDir = absPath.trimEnd('/')
                if (disabledPaths.any { normDir.equals(it, ignoreCase = true) || normDir.startsWith("$it/", ignoreCase = true) }) {
                    continue
                }

                scanRecursive(entry, results, onFileFound)
            } else if (entry.isFile && entry.length() > 0) {
                if (folderConfigManager.isPathAllowed(entry.absolutePath)) {
                    val item = createFileItem(entry)
                    results[item.path] = item
                    onFileFound?.invoke(item)
                }
            }
        }
    }

    private fun scanMediaStore(): List<FileItem> {
        val list = mutableListOf<FileItem>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )

        val uri = MediaStore.Files.getContentUri("external")
        context.contentResolver.query(
            uri,
            projection,
            "${MediaStore.Files.FileColumns.SIZE} > 0",
            null,
            null
        )?.use { cursor ->
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val path = cursor.getString(dataCol) ?: continue
                val file = File(path)
                if (!file.exists() || file.isDirectory) continue

                val size = cursor.getLong(sizeCol)
                if (size <= 0) continue

                val name = if (nameCol != -1) cursor.getString(nameCol) ?: file.name else file.name
                val ext = file.extension.lowercase()
                val lastMod = cursor.getLong(dateCol) * 1000L

                val item = FileItem(
                    path = file.absolutePath,
                    name = name,
                    extension = ext,
                    sizeBytes = size,
                    lastModifiedEpochMs = if (lastMod > 0) lastMod else file.lastModified(),
                    sha256Hash = "",
                    category = FileCategory.fromExtension(ext),
                    isImportant = checkImportanceHeuristic(name, ext, file.absolutePath)
                )
                list.add(item)
            }
        }
        return list
    }

    fun createFileItem(file: File): FileItem {
        val name = file.name
        val ext = file.extension.lowercase()
        val size = file.length()
        val lastModified = file.lastModified()
        val isImportant = checkImportanceHeuristic(name, ext, file.absolutePath)

        return FileItem(
            path = file.absolutePath,
            name = name,
            extension = ext,
            sizeBytes = size,
            lastModifiedEpochMs = lastModified,
            sha256Hash = "", // Computed on demand for candidate duplicates to prevent 15-min scan lags
            category = FileCategory.fromExtension(ext),
            isImportant = isImportant
        )
    }

    /**
     * Fast SHA-256 pre-filter computation:
     * Full stream for files <= LARGE_FILE_THRESHOLD_BYTES (512 KB).
     * For larger files: fast sample (first 64 KB + file length + last 64 KB).
     */
    fun computeSha256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val length = file.length()

            if (length <= LARGE_FILE_THRESHOLD_BYTES) {
                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        digest.update(buffer, 0, read)
                    }
                }
            } else {
                RandomAccessFile(file, "r").use { raf ->
                    val sampleSize = FAST_HASH_SAMPLE_BYTES.toInt().coerceAtMost(length.toInt())
                    val buffer = ByteArray(sampleSize)

                    // Read head
                    raf.seek(0)
                    val headRead = raf.read(buffer, 0, sampleSize)
                    if (headRead > 0) digest.update(buffer, 0, headRead)

                    // Include length in digest
                    digest.update(ByteBufferHelper.longToBytes(length))

                    // Read tail
                    val tailOffset = (length - sampleSize).coerceAtLeast(0L)
                    raf.seek(tailOffset)
                    val tailRead = raf.read(buffer, 0, sampleSize)
                    if (tailRead > 0) digest.update(buffer, 0, tailRead)
                }
            }

            bytesToHex(digest.digest())
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Full cryptographic SHA-256 computation across the entire file stream.
     * Guaranteed exact match — used to confirm exact duplicates before proposing deletion.
     */
    fun computeFullSha256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(16384)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            bytesToHex(digest.digest())
        } catch (e: Exception) {
            ""
        }
    }

    fun isImportantFile(name: String, path: String): Boolean {
        val lowerName = name.lowercase()
        val lowerPath = path.lowercase()

        val importantKeywords = listOf(
            "resume", "cv", "passport", "tax", "w2", "aadhaar", "aadhar", "pan", "certificate",
            "invoice", "contract", "salary", "offer_letter", "insurance", "statement",
            "voter", "license", "licence", "marksheet", "admitcard", "degree", "hallticket", "payslip"
        )

        return importantKeywords.any { lowerName.contains(it) || lowerPath.contains(it) }
    }

    private fun checkImportanceHeuristic(name: String, ext: String, path: String): Boolean {
        return isImportantFile(name, path)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = digits[v ushr 4]
            hexChars[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(hexChars)
    }

    fun getDefaultScanRoots(): List<File> {
        return folderConfigManager.getEnabledRoots()
    }
}

internal object ByteBufferHelper {
    fun longToBytes(value: Long): ByteArray {
        var v = value
        val result = ByteArray(8)
        for (i in 7 downTo 0) {
            result[i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        return result
    }
}
