package com.storagesense.app.data.local.room

import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Converters {
    @TypeConverter
    fun fromFloatArray(array: FloatArray?): ByteArray? {
        if (array == null) return null
        val byteBuffer = ByteBuffer.allocate(array.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in array) {
            byteBuffer.putFloat(f)
        }
        return byteBuffer.array()
    }

    @TypeConverter
    fun toFloatArray(bytes: ByteArray?): FloatArray? {
        if (bytes == null || bytes.isEmpty()) return null
        val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) {
            floats[i] = byteBuffer.float
        }
        return floats
    }
}
