package fastapi.rpc

class ByteBuf {
    var bytes = ByteArray(16)
    var size = 0

    fun ensureCapacity(capacity: Int) {
        if (capacity > bytes.size) {
            val newBytes = ByteArray(capacity * 3 / 2)
            bytes.copyInto(newBytes)
            bytes = newBytes
        }
    }

    fun addByte(byte: Byte) {
        ensureCapacity(size + 1)
        bytes[size++] = byte
    }

    fun addBytes(bytes: ByteArray) {
        ensureCapacity(size + bytes.size)
        bytes.copyInto(this.bytes, size)
        size += bytes.size
    }

    fun addShort(short: Short) {
        ensureCapacity(size + 2)
        bytes[size++] = (short.toInt() shr 8).toByte()
        bytes[size++] = short.toByte()
    }

    fun addInt(int: Int) {
        ensureCapacity(size + 4)
        bytes[size++] = (int shr 24).toByte()
        bytes[size++] = (int shr 16).toByte()
        bytes[size++] = (int shr 8).toByte()
        bytes[size++] = int.toByte()
    }

    fun addLong(long: Long) {
        ensureCapacity(size + 8)
        bytes[size++] = (long shr 56).toByte()
        bytes[size++] = (long shr 48).toByte()
        bytes[size++] = (long shr 40).toByte()
        bytes[size++] = (long shr 32).toByte()
        bytes[size++] = (long shr 24).toByte()
        bytes[size++] = (long shr 16).toByte()
        bytes[size++] = (long shr 8).toByte()
        bytes[size++] = long.toByte()
    }
}
