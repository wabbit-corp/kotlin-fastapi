package fastapi.rpc

import java.io.ByteArrayOutputStream

fun ByteArrayOutputStream.writeInt8(value: Byte) {
    this.write(value.toInt())
}

fun ByteArrayOutputStream.writeInt16(value: Short) {
    this.write(value.toInt().ushr(8))
    this.write(value.toInt().and(0xFF))
}

fun ByteArrayOutputStream.writeInt32(value: Int) {
    this.write(value.ushr(24))
    this.write(value.ushr(16).and(0xFF))
    this.write(value.ushr(8).and(0xFF))
    this.write(value.and(0xFF))
}

fun ByteArrayOutputStream.writeInt64(value: Long) {
    this.write(value.ushr(56).toInt())
    this.write(value.ushr(48).toInt().and(0xFF))
    this.write(value.ushr(40).toInt().and(0xFF))
    this.write(value.ushr(32).toInt().and(0xFF))
    this.write(value.ushr(24).toInt().and(0xFF))
    this.write(value.ushr(16).toInt().and(0xFF))
    this.write(value.ushr(8).toInt().and(0xFF))
    this.write(value.toInt().and(0xFF))
}

fun ByteArrayOutputStream.writeFloat32(value: Float) {
    this.writeInt32(value.toRawBits())
}

fun ByteArrayOutputStream.writeFloat64(value: Double) {
    this.writeInt64(value.toRawBits())
}
