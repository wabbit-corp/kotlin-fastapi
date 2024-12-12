//package fastapi.rpc
//
//import kotlinx.coroutines.*
//import kotlinx.coroutines.sync.Mutex
//import kotlinx.coroutines.sync.withLock
//import kotlinx.serialization.*
//import kotlinx.serialization.json.Json
//import java.io.ByteArrayOutputStream
//import java.lang.reflect.InvocationHandler
//import java.lang.reflect.Proxy
//import java.net.*
//import java.nio.charset.StandardCharsets
//import java.util.*
//import java.util.concurrent.ConcurrentHashMap
//import java.util.concurrent.atomic.AtomicInteger
//import kotlin.coroutines.Continuation
//import kotlin.coroutines.resume
//import kotlin.coroutines.resumeWithException
//import kotlin.reflect.KClass
//import kotlin.reflect.full.createType
//import kotlin.reflect.full.declaredFunctions
//import kotlin.reflect.full.valueParameters
//import kotlin.reflect.jvm.javaMethod
//
//object RPC {
//    annotation class FireAndForget
//    annotation class Unreliable
//    annotation class ServiceId(val id: String)
//
//    private data class MethodInfo(
//        val name: String,
//        val isSuspend: Boolean,
//        val argSerializers: List<KSerializer<*>>,
//        val resultDeserializer: ResultDeserializer<*>?
//    )
//
//    @OptIn(InternalSerializationApi::class)
//    private fun <T : Any> desc(kClass: KClass<T>): MutableMap<java.lang.reflect.Method, MethodInfo> {
//        val clazz = kClass.java
//        val className = kClass.simpleName
//
//        val methods = mutableMapOf<java.lang.reflect.Method, MethodInfo>()
//        for (method in kClass.declaredFunctions) {
//            val fullMethodName = "$className::${method.name}"
//
//            require(method.isAbstract) { "Method $fullMethodName is not abstract" }
//
//            val argSerializers = mutableListOf<KSerializer<*>>()
//            for (param in method.valueParameters) {
//                argSerializers.add(serializer(param.type))
//            }
//
//            val resultDeserializer = if (method.returnType != Unit::class.createType()) {
//                val returnType = method.returnType
//                val serializer = serializer(returnType)
//                val isNullable = method.returnType.isMarkedNullable
//                ResultDeserializer(isNullable, serializer)
//            } else null
//
//            val methodInfo = MethodInfo(
//                method.name,
//                method.isSuspend,
//                argSerializers,
//                resultDeserializer
//            )
//
//            methods[method.javaMethod!!] = methodInfo
//        }
//
//        return methods
//    }
//
//    fun unsafeClient(kClass: KClass<*>, path: String): Any {
//        val methods = desc(kClass)
//        val rawClient = RawClient("localhost", 8080)
//
//        val proxy = Proxy.newProxyInstance(
//            kClass.java.classLoader,
//            arrayOf(kClass.java),
//            InvocationHandler { _, method, args ->
//                val methodInfo = methods[method]!!
//                val argSerializers = methodInfo.argSerializers
//                val resultDeserializer = methodInfo.resultDeserializer
//
//                val resultJson = rawClient.sendBlocking(code)
//
//                if (resultDeserializer != null) {
//                    val result = Json.decodeFromString(resultDeserializer.serializer, resultJson)
//                    return@InvocationHandler result
//                } else {
//                    return@InvocationHandler Unit
//                }
//            }
//        )
//    }
//
//    fun unsafeServer(impl: Any, kClass: KClass<*>) {
//        val methods = desc(kClass)
//    }
//
//    inline fun <reified T : Any> client(path: String): T {
//        return unsafeClient(T::class, path) as T
//    }
//    inline fun <reified T : Any> server(t: T, path: String) {
//        unsafeServer(t, T::class)
//    }
//}
//
//// Protocol:
////   Stage 1:
////     We start with a version check. If the versions don't match,
////     the server closes the connection.
////   Stage 2:
////
////
//// The top bits of the length are used to indicate the type of the packet.
//// If the message is a request, the next 4 bytes are the request ID.
//// If the message is a response, the next 4 bytes are the request ID.
//
//const val PROTOCOL_VERSION      = 1
//
//const val PACKET_REQ_REQUEST        = 0
//const val PACKET_REQ_GET_SERVICE    = 1
//
//const val PACKET_REQ_FLAG_RELIABLE  = 1
//const val PACKET_REQ_FLAG_SEQUENCED = 2
//const val PACKET_REQ_FLAG_NO_RETURN = 4
//
//internal class RawClient(val host: String, val port: Int) {
//    private val scope = CoroutineScope(Dispatchers.IO)
//
//    private var id: AtomicInteger = AtomicInteger(0)
//
//    private val lock = Mutex()
//
//    private var tcpSocket = Socket(host, port)
//    private var tcpReader = tcpSocket.getInputStream()
//    private var tcpWriter = tcpSocket.getOutputStream()
//
//    private var udpSocket = DatagramSocket(port, InetAddress.getByName(host))
//
//    private val responses = ConcurrentHashMap<Int, CompletableDeferred<String>>()
//
//    @Serializable data class StackTraceLine(
//        val file: String,
//        val line: Int,
//        val function: String)
//
//    @Serializable data class ResultError(
//        val type: String,
//        val message: String,
//        val stack: List<StackTraceLine>
//    )
//
//    @Serializable data class Result(
//        val id: Long,
//        val result: String? = null,
//        val error: ResultError? = null)
//
//    init {
//        tcpSocket.tcpNoDelay = true
//        tcpWriter.write(PROTOCOL_VERSION)
//        tcpWriter.flush()
//
//        require(tcpReader.read() == PROTOCOL_VERSION) { "Protocol version mismatch" }
//
//        scope.launch {
//            while (isActive) {
//                val result = try {
//                    receive()
//                } catch (e: Throwable) {
//                    if (e is VirtualMachineError) throw e
//                    // throw e
//                    Thread.yield()
//                    continue
//                }
//
//                if (result.result != null) {
//                    responses[result.id]?.complete(result.result)
//                } else {
//                    result.error!!
//                    val exc = PythonException(result.error.type, result.error.message)
//                    val old = exc.stackTrace
//                    exc.stackTrace = result.error.stack.map { StackTraceElement("<none>", it.function, it.file, it.line) }.toTypedArray()
//                    responses[result.id]?.completeExceptionally(exc)
//                }
//            }
//        }
//    }
//
//    fun close() {
//        scope.cancel()
//        tcpSocket.close()
//    }
//
//    inline suspend fun <reified T> send(value: T): String {
//        val bytes = ByteArrayOutputStream()
//
//        val id = this.id.getAndIncrement()
//        bytes.writeInt32(id)
//
//        FastPack.encodeToByteArray(value)
//
//        withContext(Dispatchers.IO) {
//            lock.withLock {
//                if (tcpSocket.isClosed) {
//                    tcpSocket = Socket(host, port)
//                    tcpReader = tcpSocket.getInputStream()
//                    tcpWriter = tcpSocket.getOutputStream()
//                }
//
//                tcpWriter.write(message)
//                tcpWriter.write("\u0000")
//                tcpWriter.flush()
//            }
//        }
//
//        val deferred = CompletableDeferred<String>()
//        responses[id] = deferred
//        return deferred.await()
//    }
//
//    fun send(code: String, continuation: Continuation<String>) {
//        scope.launch {
//            try {
//                val result = send(code)
//                continuation.resume(result)
//            } catch (e: Throwable) {
//                continuation.resumeWithException(e)
//            }
//        }
//    }
//
//    fun sendBlocking(code: String): String = runBlocking { send(code) }
//
//    private fun receive(): Result {
//        var c: Int
//        try {
//            while (tcpReader.read().also { c = it } != -1) {
//                if (c.toChar() == '\u0000') {
//                    break
//                }
//                resultJson.append(c.toChar())
//            }
//        } catch (e: SocketException) {
//            throw e
//        }
//
//        if (c == -1) {
//            throw SocketException("Connection closed")
//        }
//
//        return Json.decodeFromString<Result>(resultJson.toString())
//    }
//}
//
//internal class RawServer(val host: String, val port: Int) {
//    private val socket = ServerSocket(port)
//    private val scope = CoroutineScope(Dispatchers.IO)
//
//    init {
//        scope.launch {
//            while (isActive) {
//                val client = socket.accept()
//                scope.launch {
//                    handleClient(client)
//                }
//            }
//        }
//    }
//
//    fun registerService(service: Any) {
//        val methods = desc(service::class)
//    }
//
//    fun close() {
//        scope.cancel()
//        socket.close()
//    }
//
//    private suspend fun handleClient(client: Socket) {
//        client.tcpNoDelay = true
//
//        val reader = client.getInputStream()
//        val writer = client.getOutputStream()
//
//        writer.write(PROTOCOL_VERSION)
//        writer.flush()
//
//        require(reader.read() == PROTOCOL_VERSION) { "Protocol version mismatch" }
//
//        while (scope.isActive) {
//            val code = StringBuilder()
//            var c: Int
//            try {
//                while (reader.read().also { c = it } != -1) {
//                    if (c.toChar() == '\u0000') {
//                        break
//                    }
//                    code.append(c.toChar())
//                }
//            } catch (e: SocketException) {
//                throw e
//            }
//
//            if (c == -1) {
//                throw SocketException("Connection closed")
//            }
//
//            val result = try {
//                val result = eval(code.toString())
//                Result(result = result)
//            } catch (e: Throwable) {
//                val stack = e.stackTrace.map { RawClient.StackTraceLine(it.fileName ?: "<none>", it.lineNumber, it.methodName) }
//                Result(error = RawClient.ResultError(e::class.qualifiedName!!, e.message ?: "<none>", stack))
//            }
//
//            val resultJson = Json.encodeToString(result)
//            writer.write(resultJson)
//            writer.write("\u0000")
//            writer.flush()
//        }
//    }
//}
