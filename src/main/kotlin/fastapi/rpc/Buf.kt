//package fastapi.rpc
//
//import kotlinx.serialization.BinaryFormat
//import kotlinx.serialization.DeserializationStrategy
//import kotlinx.serialization.ExperimentalSerializationApi
//import kotlinx.serialization.SerializationStrategy
//import kotlinx.serialization.descriptors.SerialDescriptor
//import kotlinx.serialization.descriptors.StructureKind
//import kotlinx.serialization.encoding.AbstractEncoder
//import kotlinx.serialization.encoding.CompositeEncoder
//import kotlinx.serialization.encoding.Encoder
//import kotlinx.serialization.modules.SerializersModule
//import java.io.ByteArrayOutputStream
//
//data class FastPackConfiguration(
//    val writeNames: Boolean = false,
//    val writeTypes: Boolean = false,
//    val ordinalEnums: Boolean = false,
//)
//
//open class FastPack @JvmOverloads constructor(
//    val configuration: FastPackConfiguration = FastPackConfiguration(),
//    final override val serializersModule: SerializersModule = SerializersModule { }
//) : BinaryFormat {
//    companion object Default : FastPack()
//
//    final override fun <T> decodeFromByteArray(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T {
//        val decoder = MsgPackDecoder(BasicMsgPackDecoder(configuration, serializersModule, bytes.toMsgPackBuffer(), inlineDecoders = inlineDecoders))
//        return decoder.decodeSerializableValue(deserializer)
//    }
//
//    final override fun <T> encodeToByteArray(serializer: SerializationStrategy<T>, value: T): ByteArray {
//        val encoder = MsgPackEncoder(BasicMsgPackEncoder(configuration, serializersModule, inlineEncoders = inlineEncoders))
//        kotlin.runCatching {
//            encoder.encodeSerializableValue(serializer, value)
//        }.fold(
//            onSuccess = { return encoder.result.toByteArray() },
//            onFailure = {
//                throw it
//            }
//        )
//    }
//}
//
//enum class FastPackType {
//    NIL, INT1, INT8, INT16, INT32, INT64, FLOAT32, FLOAT64, LIST, MAP, SET, UTF8;
//}
//
//internal class BasicMsgPackEncoder(
//    val configuration: FastPackConfiguration,
//    override val serializersModule: SerializersModule
//) : AbstractEncoder()
//{
//    val result = ByteArrayOutputStream()
//
//    override fun encodeBoolean(value: Boolean) {
//        if (value) {
//            result.write(0x00)
//        } else {
//            result.write(0x01)
//        }
//    }
//
//    override fun encodeNull() {
//        if (configuration.writeTypes) {
//            result.write(FastPackType.NIL.ordinal)
//        }
//    }
//
//    override fun encodeByte(value: Byte) {
//        if (configuration.writeTypes) result.write(FastPackType.INT8.ordinal)
//        result.writeInt8(value)
//    }
//
//    override fun encodeShort(value: Short) {
//        if (configuration.writeTypes) result.write(FastPackType.INT16.ordinal)
//        result.writeInt16(value)
//    }
//
//    private fun writeTypedInt(value: Long) {
//        if (-128 <= value && value <= 127) {
//            result.write(FastPackType.INT8.ordinal)
//            result.write(value.toInt())
//        } else if (-32768 <= value && value <= 32767) {
//            result.write(FastPackType.INT16.ordinal)
//            result.write(value.toInt().ushr(8))
//            result.write(value.toInt().and(0xFF))
//        } else if (-2147483648 <= value && value <= 2147483647) {
//            result.write(FastPackType.INT32.ordinal)
//            result.writeInt32(value.toInt())
//        } else {
//            result.write(FastPackType.INT64.ordinal)
//            result.writeInt64(value)
//        }
//    }
//
//    override fun encodeInt(value: Int) {
//        if (configuration.writeTypes) result.write(FastPackType.INT32.ordinal)
//        result.writeInt32(value)
//    }
//
//    override fun encodeLong(value: Long) {
//        if (configuration.writeTypes) result.write(FastPackType.INT64.ordinal)
//        result.writeInt64(value)
//    }
//
//    override fun encodeFloat(value: Float) {
//        if (configuration.writeTypes) result.write(FastPackType.FLOAT32.ordinal)
//        result.writeFloat32(value)
//    }
//
//    override fun encodeDouble(value: Double) {
//        if (configuration.writeTypes) result.write(FastPackType.FLOAT64.ordinal)
//        result.writeFloat64(value)
//    }
//
//    override fun encodeString(value: String) {
//        if (configuration.writeTypes) {
//            result.write(FastPackType.UTF8.ordinal)
//        }
//        val bytes = value.encodeToByteArray()
//        writeTypedInt(bytes.size.toLong())
//        result.write(bytes)
//    }
//
//    fun encodeByteArray(value: ByteArray) {
//        if (configuration.writeTypes) {
//            result.write(FastPackType.LIST.ordinal)
//            result.write(FastPackType.INT8.ordinal)
//        }
//        writeTypedInt(value.size.toLong())
//        result.write(value)
//    }
//
//    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
//        if (configuration.ordinalEnums) {
//            result.addAll(packer.packInt(index, configuration.strictTypeWriting))
//        } else {
//            result.addAll(packer.packString(enumDescriptor.getElementName(index), configuration.rawCompatibility))
//        }
//    }
//
//    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
//        return if (descriptor.kind in arrayOf(StructureKind.CLASS, StructureKind.OBJECT)) {
//            if (descriptor.serialName == "com.ensarsarajcic.kotlinx.serialization.msgpack.extensions.MsgPackExtension") {
//                ExtensionTypeEncoder(this)
//            } else {
//                beginCollection(descriptor, descriptor.elementsCount)
//                MsgPackClassEncoder(this)
//            }
//        } else {
//            this
//        }
//    }
//
//    override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder {
//        when (descriptor.kind) {
//            StructureKind.LIST ->
//                when {
//                    collectionSize <= MsgPackType.Array.MAX_FIXARRAY_SIZE -> {
//                        result.add(MsgPackType.Array.FIXARRAY_SIZE_MASK.maskValue(collectionSize.toByte()))
//                    }
//                    collectionSize <= MsgPackType.Array.MAX_ARRAY16_LENGTH -> {
//                        result.add(MsgPackType.Array.ARRAY16)
//                        result.addAll(collectionSize.toShort().splitToByteArray().toList())
//                    }
//                    collectionSize <= MsgPackType.Array.MAX_ARRAY32_LENGTH -> {
//                        result.add(MsgPackType.Array.ARRAY32)
//                        result.addAll(collectionSize.toInt().splitToByteArray().toList())
//                    }
//                    else -> throw MsgPackSerializationException.serialization(result, "Collection too long (max size = ${MsgPackType.Array.MAX_ARRAY32_LENGTH}, size = $collectionSize)!")
//                }
//
//            StructureKind.CLASS, StructureKind.OBJECT, StructureKind.MAP ->
//                when {
//                    collectionSize <= MsgPackType.Map.MAX_FIXMAP_SIZE -> {
//                        result.add(MsgPackType.Map.FIXMAP_SIZE_MASK.maskValue(collectionSize.toByte()))
//                    }
//                    collectionSize <= MsgPackType.Map.MAX_MAP16_LENGTH -> {
//                        result.add(MsgPackType.Map.MAP16)
//                        result.addAll(collectionSize.toShort().splitToByteArray().toList())
//                    }
//                    collectionSize <= MsgPackType.Map.MAX_MAP32_LENGTH -> {
//                        result.add(MsgPackType.Map.MAP32)
//                        result.addAll(collectionSize.toInt().splitToByteArray().toList())
//                    }
//                    else -> throw MsgPackSerializationException.serialization(result, "Object too long (max size = ${MsgPackType.Map.MAX_MAP32_LENGTH}, size = $collectionSize)!")
//                }
//
//            else -> throw MsgPackSerializationException.serialization(result, "Unsupported collection type: ${descriptor.kind}")
//        }
//        return this
//    }
//
//    override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
//        if (serializer == ByteArraySerializer()) {
//            encodeByteArray(value as ByteArray)
//        } else {
//            super.encodeSerializableValue(serializer, value)
//        }
//    }
//
//    override fun endStructure(descriptor: SerialDescriptor) {
//        // no-op, everything is handled when starting structure/collection
//    }
//}
//
//internal class MsgPackEncoder(
//    private val basicMsgPackEncoder: BasicMsgPackEncoder
//) : Encoder by basicMsgPackEncoder, CompositeEncoder by basicMsgPackEncoder {
//    override val serializersModule: SerializersModule = basicMsgPackEncoder.serializersModule
//    val result = basicMsgPackEncoder.result
//}
//
//internal class ExtensionTypeEncoder(
//    private val basicMsgPackEncoder: BasicMsgPackEncoder
//) : AbstractEncoder()
//{
//    override val serializersModule: SerializersModule = basicMsgPackEncoder.serializersModule
//    val result = basicMsgPackEncoder.result
//
//    // TODO refactor
//    private var bytesWritten = 0
//    private var type: Byte? = null
//    private var size: Int? = null
//    private var typeId: Byte? = null
//
//    override fun encodeByte(value: Byte) {
//        if (bytesWritten == 0) {
//            result.add(value)
//            type = value
//        } else if (bytesWritten == 1) {
//            if (MsgPackType.Ext.SIZES.containsKey(type)) {
//                result.add(value)
//                size = MsgPackType.Ext.SIZES[type]
//            }
//            typeId = value
//        }
//        bytesWritten += 1
//    }
//
//    override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
//        val value = value as ByteArray
//        if (size == null) {
//            size = value.size
//            val maxSize = when (type) {
//                MsgPackType.Ext.EXT8 -> MsgPackType.Ext.MAX_EXT8_LENGTH
//                MsgPackType.Ext.EXT16 -> MsgPackType.Ext.MAX_EXT16_LENGTH
//                MsgPackType.Ext.EXT32 -> MsgPackType.Ext.MAX_EXT32_LENGTH
//                else -> throw MsgPackSerializationException.serialization(result, "Unexpected extension type: $type")
//            }.toLong()
//            if (size!!.toLong() > maxSize) throw MsgPackSerializationException.serialization(result, "Size ($size) too long for extension type ($maxSize)!")
//            result.addAll(
//                when (type) {
//                    MsgPackType.Ext.EXT8 -> size!!.toByte().splitToByteArray()
//                    MsgPackType.Ext.EXT16 -> size!!.toShort().splitToByteArray()
//                    MsgPackType.Ext.EXT32 -> size!!.toInt().splitToByteArray()
//                    else -> throw MsgPackSerializationException.serialization(result, "Unexpected extension type: $type")
//                }
//            )
//            result.add(typeId!!)
//        } else {
//            if (value.size != size) throw MsgPackSerializationException.serialization(result, "Invalid size for fixed size extension type! Expected $size but found ${value.size}")
//        }
//        result.addAll(value)
//    }
//}
//
//internal class MsgPackClassEncoder(
//    private val basicMsgPackEncoder: BasicMsgPackEncoder
//) : Encoder by basicMsgPackEncoder, CompositeEncoder by basicMsgPackEncoder
//{
//    override val serializersModule: SerializersModule = basicMsgPackEncoder.serializersModule
//    val result = basicMsgPackEncoder.result
//
//    private fun encodeName(descriptor: SerialDescriptor, index: Int) {
//        encodeString(descriptor.getElementName(index))
//    }
//
//    override fun encodeBooleanElement(descriptor: SerialDescriptor, index: Int, value: Boolean) {
//        encodeName(descriptor, index)
//        encodeBoolean(value)
//    }
//
//    override fun encodeByteElement(descriptor: SerialDescriptor, index: Int, value: Byte) {
//        encodeName(descriptor, index)
//        encodeByte(value)
//    }
//
//    override fun encodeCharElement(descriptor: SerialDescriptor, index: Int, value: Char) {
//        encodeName(descriptor, index)
//        encodeChar(value)
//    }
//
//    override fun encodeDoubleElement(descriptor: SerialDescriptor, index: Int, value: Double) {
//        encodeName(descriptor, index)
//        encodeDouble(value)
//    }
//
//    override fun encodeFloatElement(descriptor: SerialDescriptor, index: Int, value: Float) {
//        encodeName(descriptor, index)
//        encodeFloat(value)
//    }
//
//    @ExperimentalSerializationApi
//    override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder {
//        encodeName(descriptor, index)
//        return basicMsgPackEncoder.encodeInline(descriptor)
//    }
//
//    override fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int) {
//        encodeName(descriptor, index)
//        encodeInt(value)
//    }
//
//    override fun encodeLongElement(descriptor: SerialDescriptor, index: Int, value: Long) {
//        encodeName(descriptor, index)
//        encodeLong(value)
//    }
//
//    override fun encodeShortElement(descriptor: SerialDescriptor, index: Int, value: Short) {
//        encodeName(descriptor, index)
//        encodeShort(value)
//    }
//
//    override fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String) {
//        encodeName(descriptor, index)
//        encodeString(value)
//    }
//
//    override fun endStructure(descriptor: SerialDescriptor) {
//        // No-op
//    }
//
//    @ExperimentalSerializationApi
//    override fun <T : Any> encodeNullableSerializableElement(
//        descriptor: SerialDescriptor,
//        index: Int,
//        serializer: SerializationStrategy<T>,
//        value: T?
//    ) {
//        encodeName(descriptor, index)
//        encodeNullableSerializableValue(serializer, value)
//    }
//
//    override fun <T> encodeSerializableElement(
//        descriptor: SerialDescriptor,
//        index: Int,
//        serializer: SerializationStrategy<T>,
//        value: T
//    ) {
//        encodeName(descriptor, index)
//        encodeSerializableValue(serializer, value)
//    }
//}
//
//data class InlineEncoderHelper(
//    val serializersModule: SerializersModule,
//    val outputBuffer: MsgPackDataOutputBuffer
//)
