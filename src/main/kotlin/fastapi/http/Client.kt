package fastapi.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.closeExceptionally
import io.ktor.websocket.readText
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.reflect.KClass
import kotlin.reflect.full.createType
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.javaMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

inline fun <reified T : Any> client(uri: String): T = client(T::class, uri)

@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
fun <T : Any> client(kClass: KClass<T>, uri: String): T {
    val className = kClass.simpleName
    // Shared Ktor client + scope for async resumption of suspend calls
    val http by lazy {
        HttpClient(CIO) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(WebSockets)
        }
    }
    val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val clazz = kClass.java

    val methods = mutableMapOf<java.lang.reflect.Method, MethodInfo>()
    for (method in kClass.declaredFunctions) {
        val fullMethodName = "$className::${method.name}"

        require(method.isAbstract) { "Method $fullMethodName is not abstract" }
        require(method.annotations.isNotEmpty()) {
            "Method $fullMethodName in $className has no annotations"
        }
        require(
            method.annotations.count {
                it is Http.Get ||
                    it is Http.Post ||
                    it is Http.Put ||
                    it is Http.Delete ||
                    it is Http.Patch ||
                    it is Http.Head ||
                    it is Http.Options
            } == 1
        ) {
            "Method $fullMethodName has more than one annotation"
        }

        val annotation =
            method.annotations.first {
                it is Http.Get ||
                    it is Http.Post ||
                    it is Http.Put ||
                    it is Http.Delete ||
                    it is Http.Patch ||
                    it is Http.Head ||
                    it is Http.Options
            }

        val (httpMethod, httpPath) =
            when (annotation) {
                is Http.Get -> HttpMethod.Get to annotation.name
                is Http.Post -> HttpMethod.Post to annotation.name
                is Http.Put -> HttpMethod.Put to annotation.name
                is Http.Delete -> HttpMethod.Delete to annotation.name
                is Http.Patch -> HttpMethod.Patch to annotation.name
                is Http.Head -> HttpMethod.Head to annotation.name
                is Http.Options -> HttpMethod.Options to annotation.name
                else -> throw IllegalArgumentException("No annotation found")
            }

        // Check that there is only one parameter with JsonBody annotation
        require(method.valueParameters.count { it.annotations.any { it is Http.JsonBody } } <= 1) {
            "Method $fullMethodName has more than one parameter with JsonBody annotation"
        }

        // Extract the JsonBody parameter (ordinal among valueParameters)
        val vparams = method.valueParameters
        val bodyParamIndex = vparams.indexOfFirst { it.annotations.any { it is Http.JsonBody } }
        val jsonBodyParameter = vparams.getOrNull(bodyParamIndex)

        // 4) Support generics: derive serializer from KType (not KClass)
        val bodySerializer = jsonBodyParameter?.type?.let { serializer(it) }

        // Streaming metadata
        val isStream = method.annotations.any { it is Http.Stream }

        fun isStreamingType(t: kotlin.reflect.KType): Boolean {
            val c = t.classifier as? KClass<*> ?: return false
            val n = c.qualifiedName ?: return false
            return n == "kotlinx.coroutines.flow.Flow" ||
                n == "kotlinx.coroutines.channels.ReceiveChannel" ||
                n == "kotlinx.coroutines.channels.Channel"
        }
        val streamInParam =
            vparams.firstOrNull { it != jsonBodyParameter && isStreamingType(it.type) }
        val streamInOrdinal = streamInParam?.let { vparams.indexOf(it) }
        val returnsStream = isStreamingType(method.returnType)

        // Header params
        val headerParams: MutableMap<Int, String> = mutableMapOf()
        var bearerParamIndex: Int? = null
        for ((ord, p) in vparams.withIndex()) {
            if (p == jsonBodyParameter || p == streamInParam) continue
            p.annotations.filterIsInstance<Http.Header>().firstOrNull()?.let {
                headerParams[ord] = it.name
            }
            if (p.annotations.any { it is Http.Bearer }) bearerParamIndex = ord
        }

        val pathBuilder = mutableListOf<PathChunk>()
        // Use ordinals into the *actual Java args[]*, not KParameter.index
        val argIndex: Map<String, Int> =
            vparams
                .withIndex()
                .filter { (ord, p) ->
                    p != jsonBodyParameter &&
                        p != streamInParam &&
                        !headerParams.containsKey(ord) &&
                        bearerParamIndex != ord
                }
                .associate { it.value.name!! to it.index }

        pathBuilder.addAll(PathChunk.split(httpPath, argIndex))
        val usedArgs =
            pathBuilder
                .filterIsInstance<PathChunk.Arg>()
                .map { method.valueParameters[it.name].name!! }
                .toSet()
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

        val resultDeserializer =
            if (!isStream && method.returnType != Unit::class.createType()) {
                val isNullable = method.returnType.isMarkedNullable
                ResultDeserializer(isNullable, serializer(method.returnType))
            } else {
                null
            }

        // Streaming serializers
        val streamInSerializer =
            if (isStream && streamInParam != null) {
                val t =
                    streamInParam.type.arguments.first().type
                        ?: error("$fullMethodName streaming parameter must be generic (Flow<T>)")
                serializer(t)
            } else {
                null
            }
        val streamOutSerializer =
            if (isStream && returnsStream) {
                val t =
                    method.returnType.arguments.first().type
                        ?: error("$fullMethodName streaming return must be generic (Flow<T>)")
                serializer(t)
            } else {
                null
            }

        val methodInfo =
            MethodInfo(
                method.name,
                method.isSuspend,
                httpMethod,
                pathBuilder,
                if (jsonBodyParameter != null) BodyParameter(bodyParamIndex, bodySerializer!!)
                else null,
                resultDeserializer,
                isStream = isStream,
                isBidi = isStream && streamInParam != null,
                // IMPORTANT: store ordinal among valueParameters, not KParameter.index
                streamInParamIndex = streamInOrdinal,
                streamInSerializer = streamInSerializer,
                streamOutSerializer = streamOutSerializer,
                headerParams = headerParams.toMap(),
                bearerParamIndex = bearerParamIndex,
            )

        methods[method.javaMethod!!] = methodInfo
    }

    return Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { _, method, args ->
        val info = methods[method]!!
        // Build the URL with proper encoding
        val path = buildString {
            for ((i, chunk) in info.httpPath.withIndex()) {
                when (chunk) {
                    is PathChunk.Static -> append(chunk.value)
                    is PathChunk.Arg -> {
                        val raw = args[chunk.name]?.toString() ?: ""
                        val prev = info.httpPath.getOrNull(i - 1)
                        val isQueryValue = (prev is PathChunk.Static) && prev.value.endsWith("=")
                        append(if (isQueryValue) encodeQueryValue(raw) else encodePathSegment(raw))
                    }
                }
            }
        }
        val url = uri + path

        // Prepare JSON body if present
        val bodyParameter = info.bodyParameter as? BodyParameter<Any>
        val jsonBody: String? =
            if (bodyParameter != null) {
                val body = args[bodyParameter.index]
                Json.encodeToString(bodyParameter.serializer, body)
            } else {
                null
            }

        // Common headers
        val extraHeaders: MutableList<Pair<String, String>> = mutableListOf()
        for ((idx, name) in info.headerParams) {
            val v = args[idx]?.toString() ?: continue
            extraHeaders += name to v
        }
        info.bearerParamIndex?.let { idx ->
            val token = args[idx]?.toString()
            if (!token.isNullOrBlank()) extraHeaders += HttpHeaders.Authorization to "Bearer $token"
        }

        // --- Streaming paths ---
        if (info.isStream) {
            // Bidirectional WebSocket
            if (info.isBidi) {
                val wsUrl =
                    when {
                        url.startsWith("https://") -> "wss://" + url.removePrefix("https://")
                        url.startsWith("http://") -> "ws://" + url.removePrefix("http://")
                        else -> url // assume already ws
                    }
                val outSer = info.streamInSerializer as KSerializer<Any?>
                val inSer = info.streamOutSerializer as KSerializer<Any?>
                val inFlowArg = args[info.streamInParamIndex!!] as Flow<Any?>

                @Suppress("UNCHECKED_CAST")
                val flowResult: Flow<Any?> = callbackFlow {
                    val job =
                        scope.launch {
                            try {
                                http.webSocket(
                                    urlString = wsUrl,
                                    request = {
                                        header(HttpHeaders.Accept, "application/json")
                                        for ((k, v) in extraHeaders) header(k, v)
                                    },
                                ) {
                                    // Sender: collect input flow and push frames
                                    val sender = launch {
                                        inFlowArg.collect { v ->
                                            val txt = Json.encodeToString(outSer, v)
                                            send(Frame.Text(txt))
                                        }
                                        // Signal end-of-input without closing the socket,
                                        // so the server can finish its outbound stream.
                                        send(Frame.Text(WS_EOF))
                                    }
                                    // Receiver: emit frames to callbackFlow
                                    val receiver = launch {
                                        for (frame in incoming) {
                                            if (frame is Frame.Text) {
                                                val txt = frame.readText()
                                                if (txt.startsWith("{\"error\"")) {
                                                    val env =
                                                        Json.decodeFromString(
                                                            ErrorEnvelope.serializer(),
                                                            txt,
                                                        )
                                                    closeExceptionally(RemoteException(env.error))
                                                    return@launch
                                                }
                                                val obj = Json.decodeFromString(inSer, txt)
                                                trySend(obj)
                                            }
                                        }
                                    }
                                    sender.join()
                                    receiver.join()
                                }
                                close()
                            } catch (t: Throwable) {
                                throw t
                            }
                        }
                    job.invokeOnCompletion { cause -> if (cause == null) close() else close(cause) }
                    awaitClose { job.cancel() }
                }

                // Suspend-friendly return path
                if (info.isSuspend) {
                    @Suppress("UNCHECKED_CAST") val ct = args.last() as Continuation<Any?>
                    scope.launch { ct.resume(flowResult) }
                    return@newProxyInstance COROUTINE_SUSPENDED
                } else {
                    return@newProxyInstance flowResult
                }
            }

            // Server-push SSE (return Flow<T>)
            val inSer = info.streamOutSerializer as KSerializer<Any?>
            @Suppress("UNCHECKED_CAST")
            return@newProxyInstance callbackFlow {
                val job =
                    scope.launch(Dispatchers.IO) {
                        var conn: HttpURLConnection? = null
                        try {
                            conn =
                                (URL(url).openConnection() as HttpURLConnection).apply {
                                    requestMethod = "GET"
                                    setRequestProperty("Accept", "text/event-stream")
                                    for ((k, v) in extraHeaders) setRequestProperty(k, v)
                                    doInput = true
                                    connect()
                                }
                            val code = conn.responseCode
                            if (code !in 200..299) {
                                val text =
                                    (conn.errorStream ?: conn.inputStream)
                                        ?.readAllBytesCompat()
                                        ?.toString(Charsets.UTF_8) ?: ""
                                val ct = conn.contentType ?: ""
                                val env = tryParseError(text, ct)
                                if (env != null) {
                                    throw RemoteException(env.error)
                                } else {
                                    throw HttpResponseException(
                                        code,
                                        if (text.isBlank()) null else text,
                                        ct,
                                    )
                                }
                            }
                            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                                .use { br ->
                                    var dataBuf = StringBuilder()
                                    var line: String?
                                    while (true) {
                                        line = br.readLine() ?: break
                                        if (line!!.startsWith("data:")) {
                                            // Spec: multiple `data:` lines in one event are joined
                                            // with '\n'
                                            if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                                            dataBuf.append(line!!.substring(5).trimStart())
                                        } else if (line!!.isBlank()) {
                                            val payload = dataBuf.toString()
                                            dataBuf = StringBuilder()
                                            if (payload.isNotEmpty()) {
                                                if (payload.startsWith("{\"error\"")) {
                                                    val env =
                                                        Json.decodeFromString(
                                                            ErrorEnvelope.serializer(),
                                                            payload,
                                                        )
                                                    throw RemoteException(env.error)
                                                }
                                                val obj = Json.decodeFromString(inSer, payload)
                                                trySend(obj)
                                            }
                                        }
                                    }
                                }
                        } catch (t: Throwable) {
                            throw t
                        } finally {
                            conn?.disconnect()
                        }
                    }
                // Close the Flow when the reader finishes; propagate exception if there was one.
                job.invokeOnCompletion { cause -> if (cause == null) close() else close(cause) }
                awaitClose { job.cancel() }
            }
        }

        // Core execution as a suspend function
        suspend fun execute(): Any? {
            val response: HttpResponse =
                http.request(url) {
                    this.method = info.httpMethod
                    header(HttpHeaders.Accept, "application/json")
                    for ((k, v) in extraHeaders) header(k, v)
                    if (jsonBody != null) {
                        contentType(ContentType.Application.Json)
                        setBody(jsonBody)
                    }
                }

            val code = response.status.value
            val isHead = info.httpMethod == HttpMethod.Head
            val contentType = response.headers[HttpHeaders.ContentType] ?: ""
            val ok = code in 200..299

            val text = if (isHead) "" else response.bodyAsText()
            if (!ok) {
                val env = tryParseError(text, contentType)
                if (env != null) throw RemoteException(env.error)
                throw HttpResponseException(code, text.ifEmpty { null }, contentType)
            }

            val rd = info.resultDeserializer as? ResultDeserializer<Any>
            if (rd == null) return Unit

            val emptyBody = code == 204 || code == 205 || text.isEmpty()
            if (isHead || emptyBody) {
                if (rd.isNullable) return null
                error("Empty response but return type is non-null")
            }
            ensureJsonContentType(contentType)
            return Json.decodeFromString(rd.serializer, text)
        }

        if (info.isSuspend) {
            @Suppress("UNCHECKED_CAST") val ct = args.last() as Continuation<Any?>
            scope.launch {
                try {
                    val r = execute()
                    ct.resume(r)
                } catch (e: Throwable) {
                    ct.resumeWithException(e)
                }
            }
            COROUTINE_SUSPENDED
        } else {
            // Non-suspend method: block for result
            runBlocking { execute() }
        }
    } as T
}

private fun encodeQueryValue(s: String): String = URLEncoder.encode(s, "UTF-8")

// RFC 3986 path segment percent-encoding (unreserved = ALPHA / DIGIT / "-" / "." / "_" / "~")
private fun encodePathSegment(s: String): String {
    val bytes = s.toByteArray(Charsets.UTF_8)
    val sb = StringBuilder(bytes.size * 3)
    for (b in bytes) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        val unreserved =
            (ch in 'A'..'Z') ||
                (ch in 'a'..'z') ||
                (ch in '0'..'9') ||
                ch == '-' ||
                ch == '.' ||
                ch == '_' ||
                ch == '~'
        if (unreserved) {
            sb.append(ch)
        } else {
            sb.append('%')
            val hex = "0123456789ABCDEF"
            sb.append(hex[(c shr 4) and 0xF])
            sb.append(hex[c and 0xF])
        }
    }
    return sb.toString()
}

private fun InputStream.readAllBytesCompat(): ByteArray {
    val buf = ByteArrayOutputStream()
    val tmp = ByteArray(8192)
    while (true) {
        val n = this.read(tmp)
        if (n == -1) break
        buf.write(tmp, 0, n)
    }
    return buf.toByteArray()
}

class HttpResponseException(val status: Int, val body: String?, val contentType: String?) :
    RuntimeException("HTTP $status: ${body ?: "<no body>"}")

class RemoteException internal constructor(error: WireError) :
    RuntimeException("${error.type}: ${error.message ?: ""}".trim()) {
    init {
        val st =
            error.stack
                ?.map {
                    StackTraceElement(
                        "<remote>",
                        it.function ?: "<fn>",
                        it.file ?: "<none>",
                        it.line ?: -1,
                    )
                }
                ?.toTypedArray()
        if (st != null && st.isNotEmpty()) this.stackTrace = st
    }
}

private fun ensureJsonContentType(ct: String) {
    if (ct.isEmpty()) return // be tolerant if server didn't set it
    val lower = ct.lowercase()
    val ok = "application/json" in lower || lower.endsWith("+json")
    if (!ok) throw IllegalStateException("Unexpected Content-Type: $ct (expected JSON)")
}

private fun tryParseError(text: String, ct: String?): ErrorEnvelope? =
    try {
        if (text.startsWith("{\"error\"")) Json.decodeFromString(ErrorEnvelope.serializer(), text)
        else null
    } catch (_: Throwable) {
        null
    }
