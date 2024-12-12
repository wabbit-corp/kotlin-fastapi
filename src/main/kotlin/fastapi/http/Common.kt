package fastapi.http

import io.ktor.http.*
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.full.createType
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.javaMethod

annotation class Cache(val client: Double)
annotation class Get(val name: String)
annotation class Post(val name: String)
annotation class Put(val name: String)
annotation class Delete(val name: String)
annotation class Patch(val name: String)
annotation class Head(val name: String)
annotation class Options(val name: String)
annotation class JsonBody()

sealed class PathChunk {
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

data class BodyParameter<T>(val index: Int, val serializer: KSerializer<T>)
data class ResultDeserializer<T>(val isNullable: Boolean, val serializer: KSerializer<T>)

data class CombinedInterface(val list: List<Interface>)
data class Interface(val list: List<MethodInfo>)

data class MethodInfo(
    val name: String,
    val isSuspend: Boolean,
    val httpMethod: HttpMethod,
    // val originalHttpPath: String,
    val httpPath: List<PathChunk>,
    val bodyParameter: BodyParameter<*>?,
    val resultDeserializer: ResultDeserializer<*>?
)

//@OptIn(InternalSerializationApi::class)
//internal fun parseKClasses(kClasses: List<KClass<*>>): CombinedInterface {
//    val methods = mutableMapOf<java.lang.reflect.Method, MethodInfo>()
//
//    for (kClass in kClasses) {
//        val clazz = kClass.java
//        val className = kClass.simpleName
//
//        fun <T : Annotation> isHttpMethodAnnotation(it: T) =
//            it is Get || it is Post || it is Put || it is Delete ||
//                    it is Patch || it is Head || it is Options
//        fun <T : Annotation> decodeHttpMethodAnnotation(annotation: T): Pair<HttpMethod, String> = when (annotation) {
//            is Get     -> HttpMethod.Get     to annotation.name
//            is Post    -> HttpMethod.Post    to annotation.name
//            is Put     -> HttpMethod.Put     to annotation.name
//            is Delete  -> HttpMethod.Delete  to annotation.name
//            is Patch   -> HttpMethod.Patch   to annotation.name
//            is Head    -> HttpMethod.Head    to annotation.name
//            is Options -> HttpMethod.Options to annotation.name
//            else -> throw IllegalArgumentException("No annotation found")
//        }
//
//        for (method in kClass.declaredFunctions) {
//            val fullMethodName = "$className::${method.name}"
//
//            require(method.isAbstract) { "Method $fullMethodName is not abstract" }
//            require(method.annotations.isNotEmpty()) { "Method $fullMethodName in $className has no annotations" }
//            require(method.annotations.count { isHttpMethodAnnotation(it) } == 1) {
//                "Method $fullMethodName has more than one annotation"
//            }
//
//            val annotation = method.annotations.first { isHttpMethodAnnotation(it) }
//            val (httpMethod, httpPath) = decodeHttpMethodAnnotation(annotation)
//
//            // Check that there is only one parameter with JsonBody annotation
//            require(method.valueParameters.count { it.annotations.any { it is JsonBody } } <= 1) {
//                "Method $fullMethodName has more than one parameter with JsonBody annotation"
//            }
//
//            // Extract the JsonBody parameter
//            val bodyParamIndex = method.valueParameters.indexOfFirst { it.annotations.any { it is JsonBody } }
//            val jsonBodyParameter = method.valueParameters.getOrNull(bodyParamIndex)
//
//            // Extract the serializer for the JsonBody parameter if it exists
//            val type = jsonBodyParameter?.type?.classifier
//            val serializer = jsonBodyParameter?.type?.let { serializer(it) }
//
//            val pathChunks = mutableListOf<PathChunk>()
//            val argIndex: Map<String, Int> = method.valueParameters
//                .filter { it != jsonBodyParameter }
//                .map { it.name!! }
//                .withIndex().associate { it.value to it.index }
//
//            pathChunks.addAll(PathChunk.split(httpPath, argIndex))
//            val usedArgs = pathChunks
//                .filterIsInstance<PathChunk.Arg>()
//                .map { method.valueParameters[it.name].name!! }
//                .toSet()
//            val unusedArgs = argIndex.keys - usedArgs
//
//            // Unused arguments become query parameters
//            if (unusedArgs.isNotEmpty()) {
//                pathChunks.add(PathChunk.Static("?"))
//                for ((index, arg) in unusedArgs.withIndex()) {
//                    if (index > 0) {
//                        pathChunks.add(PathChunk.Static("&"))
//                    }
//                    pathChunks.add(PathChunk.Static(arg))
//                    pathChunks.add(PathChunk.Static("="))
//                    pathChunks.add(PathChunk.Arg(argIndex[arg]!!))
//                }
//            }
//
//            val resultDeserializer: ResultDeserializer<*>? = if (method.returnType != Unit::class.createType()) {
//                val resultType: KClass<*>
//                val isNullable: Boolean
//
//                if (method.returnType.isMarkedNullable) {
//                    val rt = method.returnType.classifier
//                    require(rt is KClass<*>) {
//                        "Method $fullMethodName has non-class return type"
//                    }
//                    resultType = rt
//                    isNullable = true
//                } else {
//                    val rt = method.returnType.classifier
//                    require(rt is KClass<*>) {
//                        "Method $fullMethodName has non-class return type"
//                    }
//                    resultType = rt
//                    isNullable = false
//                }
//
//                val serializer = resultType.serializer()
//                require(serializer != null) {
//                    "Method $fullMethodName has parameter with JsonBody annotation without serializer"
//                }
//                ResultDeserializer(isNullable, serializer)
//            } else null
//
//            val methodInfo = MethodInfo(
//                name = method.name,
//                isSuspend = method.isSuspend,
//                httpMethod = httpMethod,
//                originalHttpPath = httpPath,
//                httpPath = pathChunks,
//                bodyParameter = if (jsonBodyParameter != null) BodyParameter(bodyParamIndex, serializer!!) else null,
//                resultDeserializer = resultDeserializer
//            )
//
//            methods[method.javaMethod!!] = methodInfo
//        }
//    }
//
//    return CombinedInterface(listOf(Interface(methods.values.toList())))
//}
