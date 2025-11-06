package fastapi.http

import io.ktor.http.HttpMethod
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

internal sealed class PathChunk {
    data class Static(val value: String) : PathChunk()

    data class Arg(val name: Int) : PathChunk()

    companion object {
        fun split(path: String, args: Map<String, Int>): List<PathChunk> {
            val chunks = mutableListOf<PathChunk>()
            var start = 0
            var index = 0
            while (index < path.length) {
                if (path[index] == '{') {
                    if (index > start) {
                        chunks.add(Static(path.substring(start, index)))
                    }
                    start = index + 1
                } else if (path[index] == '}') {
                    val name = path.substring(start, index)
                    val i = args[name] ?: error("Unknown argument $name")
                    chunks.add(Arg(i))
                    start = index + 1
                }
                index++
            }
            if (index > start) {
                chunks.add(Static(path.substring(start, index)))
            }
            return chunks
        }
    }
}

internal data class BodyParameter<T>(val index: Int, val serializer: KSerializer<T>)

internal data class ResultDeserializer<T>(val isNullable: Boolean, val serializer: KSerializer<T>)

internal data class CombinedInterface(val list: List<Interface>)

internal data class Interface(val list: List<MethodInfo>)

internal data class MethodInfo(
    val name: String,
    val isSuspend: Boolean,
    val httpMethod: HttpMethod,
    // val originalHttpPath: String,
    val httpPath: List<PathChunk>,
    val bodyParameter: BodyParameter<*>?,
    val resultDeserializer: ResultDeserializer<*>?,
    // Streaming metadata
    val isStream: Boolean,
    val isBidi: Boolean,
    val streamInParamIndex: Int?,
    val streamInSerializer: KSerializer<*>?,
    val streamOutSerializer: KSerializer<*>?,
    // Header binding
    val headerParams: Map<Int, String>,
    val bearerParamIndex: Int?,
)

@Serializable
internal data class WireStack(
    val file: String? = null,
    val function: String? = null,
    val line: Int? = null,
)

@Serializable
internal data class WireError(
    val type: String,
    val message: String? = null,
    val stack: List<WireStack>? = null,
)

@Serializable internal data class ErrorEnvelope(val error: WireError)

// Internal control frame used on WebSocket streams to indicate end-of-input
// (chosen to be absurdly unlikely to collide with user payloads).
internal const val WS_EOF: String = "\u0000\u0001__FASTAPI_STREAM_END__\u0001\u0000"
