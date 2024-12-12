package fastapi.http

import io.ktor.http.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.serializerOrNull
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.Continuation
import kotlin.reflect.KClass
import kotlin.reflect.full.createType
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.javaMethod

@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
inline fun <reified T> client(uri: String): T {
    val kClass = T::class
    val clazz = kClass.java

    val className = kClass.simpleName

    val methods = mutableMapOf<java.lang.reflect.Method, MethodInfo>()
    for (method in kClass.declaredFunctions) {
        val fullMethodName = "$className::${method.name}"

        require(method.isAbstract) { "Method $fullMethodName is not abstract" }
        require(method.annotations.isNotEmpty()) { "Method $fullMethodName in $className has no annotations" }
        require(method.annotations.count { it is Get || it is Post || it is Put || it is Delete } == 1) {
            "Method $fullMethodName has more than one annotation"
        }

        val annotation = method.annotations.first { it is Get || it is Post || it is Put || it is Delete }

        val (httpMethod, httpPath) = when (annotation) {
            is Get     -> HttpMethod.Get to annotation.name
            is Post    -> HttpMethod.Post to annotation.name
            is Put     -> HttpMethod.Put to annotation.name
            is Delete  -> HttpMethod.Delete to annotation.name
            is Patch   -> HttpMethod.Patch to annotation.name
            is Head    -> HttpMethod.Head to annotation.name
            is Options -> HttpMethod.Options to annotation.name
            else -> throw IllegalArgumentException("No annotation found")
        }

        // Check that there is only one parameter with JsonBody annotation
        require(method.valueParameters.count { it.annotations.any { it is JsonBody } } <= 1) {
            "Method $fullMethodName has more than one parameter with JsonBody annotation"
        }

        // Extract the JsonBody parameter
        val bodyParamIndex = method.valueParameters.indexOfFirst { it.annotations.any { it is JsonBody } }
        val jsonBodyParameter = method.valueParameters.getOrNull(bodyParamIndex)

        // Extract the serializer for the JsonBody parameter if it exists
        val type = jsonBodyParameter?.type?.classifier
        val serializer = if (type != null) {
            require(type is KClass<*>) {
                "Method $fullMethodName has parameter with JsonBody annotation with non-class type"
            }
            val serializer = type.serializerOrNull()
            require(serializer != null) {
                "Method $fullMethodName has parameter with JsonBody annotation without serializer"
            }
            serializer
        } else null

        val pathBuilder = mutableListOf<PathChunk>()
        val argIndex: Map<String, Int> = method.valueParameters
            .filter { it != jsonBodyParameter }
            .map { it.name!! }
            .withIndex().associate { it.value to it.index }

        pathBuilder.addAll(PathChunk.split(httpPath, argIndex))
        val usedArgs =
            pathBuilder.filterIsInstance<PathChunk.Arg>().map { method.valueParameters[it.name].name!! }.toSet()
        val unusedArgs = argIndex.keys - usedArgs

        // Unused arguments become query parameters
        if (unusedArgs.isNotEmpty()) {
            pathBuilder.add(PathChunk.Static("?"))
            for ((index, arg) in unusedArgs.withIndex()) {
                if (index > 0) {
                    pathBuilder.add(PathChunk.Static("&"))
                }
                pathBuilder.add(PathChunk.Static(arg))
                pathBuilder.add(PathChunk.Static("="))
                pathBuilder.add(PathChunk.Arg(argIndex[arg]!!))
            }
        }

        val resultDeserializer = if (method.returnType != Unit::class.createType()) {
            val resultType: KClass<*>
            val isNullable: Boolean

            if (method.returnType.isMarkedNullable) {
                val rt = method.returnType.classifier
                require(rt is KClass<*>) {
                    "Method $fullMethodName has non-class return type"
                }
                resultType = rt
                isNullable = true
            } else {
                val rt = method.returnType.classifier
                require(rt is KClass<*>) {
                    "Method $fullMethodName has non-class return type"
                }
                resultType = rt
                isNullable = false
            }

            val serializer = resultType.serializerOrNull()
            require(serializer != null) {
                "Method $fullMethodName has parameter with JsonBody annotation without serializer"
            }
            ResultDeserializer(isNullable, serializer)
        } else null

        val methodInfo = MethodInfo(
            method.name, method.isSuspend,
            httpMethod,
            pathBuilder,
            if (jsonBodyParameter != null) BodyParameter(bodyParamIndex, serializer!!) else null,
            resultDeserializer
        )

        methods[method.javaMethod!!] = methodInfo
    }

    return Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { _, method, args ->
        val info = methods[method]!!
        if (info.isSuspend) {
            val ct = args.last() as Continuation<*>
        }

        val path = info.httpPath.joinToString("") {
            when (it) {
                is PathChunk.Static -> it.value
                is PathChunk.Arg -> args[it.name].toString()
            }
        }
        val bodyParameter = info.bodyParameter as? BodyParameter<Any>

        val url = URL(uri + path)

        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = info.httpMethod.value
        if (bodyParameter != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
        }
        connection.connect()

        // write body
        if (bodyParameter != null) {
            val body = args[bodyParameter.index]
            connection.outputStream.use {
                Json.encodeToStream(bodyParameter.serializer, body, it)
            }
        }

        val resultDeserializer = info.resultDeserializer as? ResultDeserializer<Any>

        val result = connection.inputStream.use {
            if (resultDeserializer == null) Unit
            else {
                if (resultDeserializer.isNullable) {
                    if (it.available() == 0) null
                    else Json.decodeFromStream(resultDeserializer.serializer.nullable, it)
                } else Json.decodeFromStream(resultDeserializer.serializer, it)
            }
        }

        result
        // return@newProxyInstance kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
    } as T
}
