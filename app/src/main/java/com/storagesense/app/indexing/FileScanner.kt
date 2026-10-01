package com.storagesense.app.indexing

import android.os.Environment
import com.storagesense.app.domain.model.FileCategory
import com.storagesense.app.domain.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileScanner @Inject constructor() {

    companion object {
        const val LARGE_FILE_THRESHOLD_BYTES = 512L * 1024L // 512 KB
        const val FAST_HASH_SAMPLE_BYTES = 64L * 1024L // 64 KB
    }

    /**
     * Scans specified directory or default public external storage directories.
     */
    suspend fun scanDirectories(
        roots: List<File> = getDefaultScanRoots(),
        onFileFound: (suspend (FileItem) -> Unit)? = null
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val found = mutableListOf<FileItem>()

        for (root in roots) {
            if (!root.exists() || !root.canRead()) continue
            scanRecursive(root, found, onFileFound)
        }

        found
    }

    private suspend fun scanRecursive(
        directory: File,
        results: MutableList<FileItem>,
        onFileFound: (suspend (FileItem) -> Unit)?
    ) {
        val entries = directory.listFiles() ?: return
        for (entry in entries) {
            // Ignore hidden directories and trash
            if (entry.name.startsWith(".") || entry.name.equals(".storagesense", ignoreCase = true)) {
                continue
            }

            if (entry.isDirectory) {
                scanRecursive(entry, results, onFileFound)
            } else if (entry.isFile && entry.length() > 0) {
                val item = createFileItem(entry)
                results.add(item)
                onFileFound?.invoke(item)
            }
        }
    }

    fun createFileItem(file: File): FileItem {
        val name = file.name
        val ext = file.extension.lowercase()
        val size = file.length()
        val lastModified = file.lastModified()
        val hash = computeSha256(file)
        val isImportant = checkImportanceHeuristic(name, ext, file.absolutePath)

        return FileItem(
            path = file.absolutePath,
            name = name,
            extension = ext,
            sizeBytes = size,
            lastModifiedEpochMs = lastModified,
            sha256Hash = hash,
            category = FileCategory.fromExtension(ext),
            isImportant = isImportant
        )
    }

    /**
     * Fast SHA-256 computation:
     * Full stream for files <= 10 MB.
     * For files > 10 MB: hash (first 1 MB + file length + last 1 MB).
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

    private fun checkImportanceHeuristic(name: String, ext: String, path: String): Boolean {
        val lowerName = name.lowercase()
        val lowerPath = path.lowercase()

        val importantKeywords = listOf(
            "resume", "cv", "passport", "tax", "w2", "aadhaar", "pan", "certificate",
            "invoice", "contract", "salary", "offer_letter", "insurance"
        )

        return importantKeywords.any { lowerName.contains(it) || lowerPath.contains(it) }
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
        val roots = mutableListOf<File>()
        try {
            val external = Environment.getExternalStorageDirectory() ?: return emptyList()
            if (!external.exists()) return emptyList()

            // 1. Standard Media & Documents Folders
            val standardDirs = listOf(
                "Documents",
                "Download",
                "Pictures",
                "DCIM",
                "Movies",
                "Music"
            )
            for (sub in standardDirs) {
                val dir = File(external, sub)
                if (dir.exists()) roots.add(dir)
            }

            // 2. WhatsApp & Messaging Media Locations (Major source of file sharing)
            val messagingMediaDirs = listOf(
                "Android/media/com.whatsapp/WhatsApp/Media",
                "Android/media/com.whatsapp.w4b/WhatsApp Business/Media",
                "WhatsApp/Media",
                "Telegram",
                "Android/media/org.telegram.messenger"
            )
            for (sub in messagingMediaDirs) {
                val dir = File(external, sub)
                if (dir.exists()) roots.add(dir)
            }

            // 3. User custom top-level directories (e.g. Books, College, Work)
            val topLevel = external.listFiles() ?: emptyArray()
            for (entry in topLevel) {
                if (!entry.isDirectory) continue
                val name = entry.name
                // Skip hidden, trash, and restricted Android system sandboxes
                if (name.startsWith(".") ||
                    name.equals(".storagesense", ignoreCase = true) ||
                    name.equals("Android", ignoreCase = true) ||
                    standardDirs.any { it.equals(name, ignoreCase = true) } ||
                    name.equals("WhatsApp", ignoreCase = true) ||
                    name.equals("Telegram", ignoreCase = true)) {
                    continue
                }
                roots.add(entry)
            }

            if (roots.isEmpty()) {
                roots.add(external)
            }
        } catch (e: Exception) {
            // Handled
        }
        return roots.distinctBy { it.absolutePath }
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
