package com.storagesense.app.ui.util

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

object StorageStatsHelper {

    /**
     * Returns the true block-level system storage statistics.
     * Overcomes the limitation of only summing up indexed files.
     * @return Pair<TotalBytes, FreeBytes>
     */
    fun getSystemStorageStats(): Pair<Long, Long> {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong
            
            Pair(totalBlocks * blockSize, availableBlocks * blockSize)
        } catch (e: Exception) {
            Pair(0L, 0L)
        }
    }
}
