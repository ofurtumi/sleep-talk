package `is`.juni.sleeptalk.service

class CircularByteBuffer(private val capacity: Int) {
    private val buf = ByteArray(capacity)
    private var writePos = 0
    private var filled = 0

    fun write(data: ByteArray) {
        var src = 0
        var remaining = data.size
        while (remaining > 0) {
            val chunk = minOf(remaining, capacity - writePos)
            System.arraycopy(data, src, buf, writePos, chunk)
            writePos = (writePos + chunk) % capacity
            src += chunk
            remaining -= chunk
        }
        filled = minOf(filled + data.size, capacity)
    }

    fun toByteArray(): ByteArray {
        if (filled == 0) return ByteArray(0)
        val result = ByteArray(filled)
        val readStart = if (filled < capacity) 0 else writePos
        val firstPart = capacity - readStart
        if (filled <= firstPart) {
            System.arraycopy(buf, readStart, result, 0, filled)
        } else {
            System.arraycopy(buf, readStart, result, 0, firstPart)
            System.arraycopy(buf, 0, result, firstPart, filled - firstPart)
        }
        return result
    }

    fun clear() {
        writePos = 0
        filled = 0
    }
}
