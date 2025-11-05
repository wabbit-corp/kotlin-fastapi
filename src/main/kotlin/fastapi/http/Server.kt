package fastapi.http

import io.ktor.http.*
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.server.sessions.Cache
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlinx.serialization.serializerOrNull
import org.slf4j.event.Level
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.full.createType
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.full.valueParameters

/* ---------------------------- Public API ---------------------------- */

inline fun <reified T1 : Any> server(
    t1: T1,
    host: String = "0.0.0.0",
    port: Int = 8080,
    includeStacktraces: Boolean = false
): EmbeddedServer<out ApplicationEngine, out ApplicationEngine.Configuration> {
    return unsafeServer(listOf(t1 to T1::class), host, port, includeStacktraces)
}

inline fun <reified T1 : Any, reified T2 : Any> server(
    t1: T1,
    t2: T2,
    host: String = "0.0.0.0",
    port: Int = 8080,
    includeStacktraces: Boolean = false
): EmbeddedServer<out ApplicationEngine, out ApplicationEngine.Configuration> {
    return unsafeServer(listOf(t1 to T1::class, t2 to T2::class), host, port, includeStacktraces)
}

inline fun <reified T1 : Any, reified T2 : Any, reified T3 : Any> server(
    t1: T1,
    t2: T2,
    t3: T3,
    host: String = "0.0.0.0",
    port: Int = 8080,
    includeStacktraces: Boolean = false
): EmbeddedServer<out ApplicationEngine, out ApplicationEngine.Configuration> {
    return unsafeServer(listOf(t1 to T1::class, t2 to T2::class, t3 to T3::class), host, port, includeStacktraces)
}

/* --------------------------- Implementation ------------------------ */

@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
fun unsafeServer(
    implementations: List<Pair<Any, KClass<*>>>,
    host: String = "0.0.0.0",
    port: Int = 8080,
    includeStacktraces: Boolean = false
): EmbeddedServer<out ApplicationEngine, out ApplicationEngine.Configuration> {
    for ((impl, kClass) in implementations) {
        require(kClass.isInstance(impl)) { "Implementation $impl is not an instance of $kClass" }
    }

    return embeddedServer(
        Netty,
        host = host,
        port = port,
    ) {
        install(CallLogging) { level = Level.INFO }
        install(StatusPages) {
            exception<Throwable> { call, ex ->
                val payload = ErrorEnvelope(
                    WireError(
                        type = ex::class.simpleName ?: "Error",
                        message = ex.message,
                        stack = if (includeStacktraces)
                            ex.stackTrace.map {
                                WireStack(file = it.fileName, function = it.methodName, line = it.lineNumber)
                            } else null
                    )
                )
                call.respond(HttpStatusCode.InternalServerError, payload)
                throw ex
            }
        }
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(Json)
        }
        install(CORS) {
            anyHost()
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Patch)
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            allowNonSimpleContentTypes = true
            allowCredentials = true
        }
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = false
                isLenient = true
                ignoreUnknownKeys = true
            })
        }

        routing {
            for ((impl, kClass) in implementations) {
                registerInterfaceRoutes(impl, kClass, includeStacktraces)
            }
        }
    }.start(wait = false)
}

@OptIn(InternalSerializationApi::class)
private fun Route.registerInterfaceRoutes(impl: Any, kClass: KClass<*>, includeStacktraces: Boolean) {
    val className = kClass.simpleName ?: kClass.toString()

    fun httpAnno(fn: kotlin.reflect.KFunction<*>): Pair<HttpMethod, String> {
        val ann = fn.annotations.firstOrNull {
            it is Http.Get || it is Http.Post || it is Http.Put || it is Http.Delete || it is Http.Patch || it is Http.Head || it is Http.Options
        } ?: error("$className::${fn.name} has no HTTP method annotation")

        return when (ann) {
            is Http.Get     -> HttpMethod.Get     to ann.name
            is Http.Post    -> HttpMethod.Post    to ann.name
            is Http.Put     -> HttpMethod.Put     to ann.name
            is Http.Delete  -> HttpMethod.Delete  to ann.name
            is Http.Patch   -> HttpMethod.Patch   to ann.name
            is Http.Head    -> HttpMethod.Head    to ann.name
            is Http.Options -> HttpMethod.Options to ann.name
            else -> error("Unsupported HTTP annotation on $className::${fn.name}")
        }
    }

    fun isFlowOrChannel(t: KType): Boolean {
        val c = t.classifier as? KClass<*> ?: return false
        val n = c.qualifiedName ?: return false
        return n == "kotlinx.coroutines.flow.Flow" ||
               n == "kotlinx.coroutines.channels.ReceiveChannel" ||
               n == "kotlinx.coroutines.channels.Channel"
    }

    for (fn in kClass.declaredFunctions) {
        val annotated = fn.annotations.any {
            it is Http.Get || it is Http.Post || it is Http.Put || it is Http.Delete || it is Http.Patch || it is Http.Head || it is Http.Options
        }
        if (!annotated) continue

        val (httpMethod, rawPath) = httpAnno(fn)
        val isStream = fn.annotations.any { it is Http.Stream }

        val bodyParams = fn.valueParameters.filter { p -> p.annotations.any { it is Http.JsonBody } }
        require(bodyParams.size <= 1) { "$className::${fn.name} has more than one @JsonBody parameter" }
        val bodyParam: KParameter? = bodyParams.firstOrNull()

        val streamParam: KParameter? = fn.valueParameters.firstOrNull { it != bodyParam && isFlowOrChannel(it.type) }
        val returnsStream = isFlowOrChannel(fn.returnType)

        val cacheAnn = fn.annotations.filterIsInstance<Http.Cache>().firstOrNull()

        // Streaming routes handled specially
        if (isStream && streamParam != null) {
            // Bidirectional via WebSocket
            webSocket(rawPath) {
                try {
                    val args = mutableMapOf<KParameter, Any?>()
                    fn.instanceParameter?.let { args[it] = impl }

                    // Bind non-stream params (headers / path / query)
                    for (param in fn.valueParameters) {
                        if (param == bodyParam || param == streamParam) continue
                        val headerAnn = param.annotations.filterIsInstance<Http.Header>().firstOrNull()
                        val bearerAnn = param.annotations.any { it is Http.Bearer }
                        val name = param.name ?: error("Parameter without name on $className::${fn.name}; enable Kotlin parameter names")
                        val raw: String? = when {
                            headerAnn != null -> call.request.headers[headerAnn.name]
                            bearerAnn -> call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
                            else -> call.parameters[name] ?: call.request.queryParameters[name]
                        }
                        if (raw == null) {
                            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing parameter '$name'"))
                            return@webSocket
                        }
                        val coerced = try { coerceFromString(param.type, raw) }
                        catch (t: Throwable) {
                            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Invalid parameter '$name': ${t.message}"))
                            return@webSocket
                        }
                        args[param] = coerced
                    }

                    // Build incoming Flow<T> from frames
                    val tIn = streamParam.type.arguments.first().type
                        ?: error("Streaming parameter must be generic (Flow<T>)")
                    val sIn = serializer(tIn) as KSerializer<Any?>
                    val incomingFlow: Flow<Any?> = channelFlow {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val txt = frame.readText()
                                if (txt == WS_EOF) {
                                    // Client signaled end-of-input for the bidirectional stream.
                                    break
                                }
                                val obj = Json.decodeFromString(sIn, txt)
                                trySend(obj)
                            }
                        }
                        close() // finish the input flow cleanly
                    }
                    args[streamParam] = incomingFlow

                    val result = if (fn.isSuspend) fn.callSuspendBy(args) else fn.callBy(args)
                    val returnsUnit = fn.returnType == Unit::class.createType()
                    if (returnsUnit) {
                        return@webSocket
                    } else {
                        val tOut = fn.returnType.arguments.first().type
                            ?: error("Streaming return must be generic (Flow<T>)")
                        val sOut = serializer(tOut) as KSerializer<Any?>
                        @Suppress("UNCHECKED_CAST")
                        val outFlow = result as Flow<Any?>
                        outFlow.collect { v ->
                            val txt = Json.encodeToString(sOut, v)
                            outgoing.send(Frame.Text(txt))
                        }
                    }
                } catch (ex: Throwable) {
                    val payload = ErrorEnvelope(
                        WireError(
                            type = ex::class.simpleName ?: "Error",
                            message = ex.message,
                            stack = if (includeStacktraces)
                                ex.stackTrace.map { WireStack(it.fileName, it.methodName, it.lineNumber) }
                            else null
                        )
                    )
                    val json = Json.encodeToString(ErrorEnvelope.serializer(), payload)
                    outgoing.send(Frame.Text(json))
                    close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, ex.message ?: "error"))
                }
            }
            continue
        }

        // SSE when marked @Stream and returns Flow<T> but has no streaming param
        if (isStream && streamParam == null && returnsStream) {
            route(rawPath, httpMethod) {
                handle {
                    val args = mutableMapOf<KParameter, Any?>()
                    fn.instanceParameter?.let { args[it] = impl }

                    for (param in fn.valueParameters) {
                        if (param == bodyParam) continue
                        val headerAnn = param.annotations.filterIsInstance<Http.Header>().firstOrNull()
                        val bearerAnn = param.annotations.any { it is Http.Bearer }
                        val name = param.name
                            ?: error("Parameter without name on $className::${fn.name}; enable Kotlin parameter names")
                        val raw: String? = when {
                            headerAnn != null -> call.request.headers[headerAnn.name]
                            bearerAnn -> call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
                            else -> call.parameters[name] ?: call.request.queryParameters[name]
                        }
                        if (raw == null) {
                            call.respond(HttpStatusCode.BadRequest, "Missing parameter '$name'")
                            return@handle
                        }
                        val coerced = try { coerceFromString(param.type, raw) }
                        catch (t: Throwable) {
                            call.respond(HttpStatusCode.BadRequest, "Invalid parameter '$name': ${t.message}")
                            return@handle
                        }
                        args[param] = coerced
                    }

                    val result = if (fn.isSuspend) fn.callSuspendBy(args) else fn.callBy(args)
                    val tOut = fn.returnType.arguments.first().type
                        ?: error("Streaming return must be generic (Flow<T>)")
                    val sOut = serializer(tOut) as KSerializer<Any?>

                    call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                    call.response.headers.append(HttpHeaders.Connection, "keep-alive")
                    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val flow = result as Flow<Any?>
                            flow.collect { v ->
                                val txt = Json.encodeToString(sOut, v)
                                write("data: ")
                                write(txt)
                                write("\n\n")
                                flush()
                            }
                        } catch (ex: Throwable) {
                            val payload = ErrorEnvelope(
                                WireError(
                                    type = ex::class.simpleName ?: "Error",
                                    message = ex.message,
                                    stack = if (includeStacktraces)
                                        ex.stackTrace.map { WireStack(it.fileName, it.methodName, it.lineNumber) }
                                    else null
                                )
                            )
                            val j = Json.encodeToString(ErrorEnvelope.serializer(), payload)
                            write("event: error\n")
                            write("data: ")
                            write(j)
                            write("\n\n")
                            flush()
                        }
                    }
                }
            }
            continue
        }

        // Non-streaming HTTP
        route(rawPath, httpMethod) {
            handle {
                val args = mutableMapOf<KParameter, Any?>()
                fn.instanceParameter?.let { args[it] = impl }

                // Non-body params = from path or query
                for (param in fn.valueParameters) {
                    if (param == bodyParam) continue

                    val headerAnn = param.annotations.filterIsInstance<Http.Header>().firstOrNull()
                    val bearerAnn = param.annotations.any { it is Http.Bearer }
                    val name = param.name
                        ?: error("Parameter without name on $className::${fn.name}; enable Kotlin parameter names")

                    val raw: String? = when {
                        headerAnn != null -> call.request.headers[headerAnn.name]
                        bearerAnn -> call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
                        else -> call.parameters[name] ?: call.request.queryParameters[name]
                    }

                    if (raw == null) {
                        call.respond(HttpStatusCode.BadRequest, "Missing parameter '$name'")
                        return@handle
                    }

                    val coerced = try {
                        coerceFromString(param.type, raw)
                    } catch (t: Throwable) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid parameter '$name': ${t.message}")
                        return@handle
                    }
                    args[param] = coerced
                }

                // Body param (single) via JSON
                if (bodyParam != null) {
                    val text = call.receiveText()
                    val s = serializer(bodyParam.type)
                    @Suppress("UNCHECKED_CAST")
                    val decoded = Json.decodeFromString(s as KSerializer<Any?>, text)
                    args[bodyParam] = decoded
                }

                val result = if (fn.isSuspend) fn.callSuspendBy(args) else fn.callBy(args)

                cacheAnn?.let {
                    call.response.headers.append(HttpHeaders.CacheControl, "max-age=${it.client.toLong()}")
                }

                val returnsUnit = fn.returnType == Unit::class.createType()
                if (returnsUnit) {
                    call.respond(HttpStatusCode.OK)
                } else {
                    val rs = serializer(fn.returnType)
                    @Suppress("UNCHECKED_CAST")
                    val json = Json.encodeToString(rs as KSerializer<Any?>, result)
                    call.respondText(json, ContentType.Application.Json)
                }
            }
        }
    }
}

/* -------------------------- Helpers --------------------------- */

@OptIn(InternalSerializationApi::class)
private fun coerceFromString(type: KType, raw: String): Any? {
    val k = type.classifier as? KClass<*> ?: error("Unsupported parameter type $type")
    return when (k) {
        String::class -> raw
        Int::class    -> raw.toInt()
        Long::class   -> raw.toLong()
        Double::class -> raw.toDouble()
        Float::class  -> raw.toFloat()
        Short::class  -> raw.toShort()
        Byte::class   -> raw.toByte()
        Boolean::class -> raw.toBooleanStrictOrNull()
            ?: when (raw.lowercase()) {
                "1", "y", "yes", "true" -> true
                "0", "n", "no", "false" -> false
                else -> error("expected boolean")
            }
        else -> {
            // Fallback to JSON decoding for custom types
            val s = serializer(type)
            @Suppress("UNCHECKED_CAST")
            Json.decodeFromString(s as KSerializer<Any?>, raw)
        }
    }
}
