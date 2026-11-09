package `is`.juni.sleeptalk.util

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavEncoder {
    fun write(file: File, pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val dataSize = pcm.size
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)                                  // PCM chunk size
            putShort(1)                                 // PCM format
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * channels * bitsPerSample / 8) // byte rate
            putShort((channels * bitsPerSample / 8).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray())
            putInt(dataSize)
        }
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            out.write(header.array())
            out.write(pcm)
        }
    }
}
