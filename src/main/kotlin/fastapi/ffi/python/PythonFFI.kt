package fastapi.ffi.python

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.lang.ref.Cleaner
import java.lang.reflect.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.reflect.KClass
import kotlin.reflect.full.createType
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.javaMethod

private fun String.indent(indent: String): String {
    return this.split("\n").joinToString("\n") { indent + it }
}

private sealed interface PythonFuncParsed {
    data class Func(val isAsync: Boolean, val module: String, val func: String) : PythonFuncParsed
    data class Code(val isAsync: Boolean, val funcName: String, val code: String) : PythonFuncParsed
}
private fun parsePythonFuncAnnotation(value: String): PythonFuncParsed {
    // Known functions:
    //   module::func
    //   async module::func
    // Code:
    //   def name(args...): ...
    //   async def name(args...): ...

    val trimmedValue = value.trimIndent()

    // println(value)

    val m1 = Regex("""^(async\s+)?([a-zA-Z0-9_]+)::([a-zA-Z0-9_]+)$""").matchEntire(trimmedValue)
    if (m1 != null) {
        val isAsync = m1.groupValues[1].isNotEmpty()
        val module = m1.groupValues[2]
        val func = m1.groupValues[3]
        return PythonFuncParsed.Func(isAsync, module, func)
    } else {
        val m2 = Regex("""^(async\s+)?def\s+([a-zA-Z0-9_]+)(.|\n)+$""").matchEntire(trimmedValue)
        if (m2 != null) {
            val isAsync = m2.groupValues[1].isNotEmpty()
            val funcName = m2.groupValues[2]
            return PythonFuncParsed.Code(isAsync, funcName, trimmedValue)
        } else {
            throw IllegalArgumentException("Invalid PythonFunc annotation: $trimmedValue")
        }
    }
}

// C:\Program Files\Wolfram Research\WolframScript\;

// D:\Python310\Scripts\;
// D:\Python310\;
// C:\Users\alexk\AppData\Local\Programs\Python\Python310\Scripts\;
// C:\Users\alexk\AppData\Local\Programs\Python\Python310\;
// C:\Users\alexk\AppData\Local\Programs\Python\Python38\Scripts\;
// C:\Users\alexk\AppData\Local\Programs\Python\Python38\;

// D:\MiKTeX\miktex\bin\x64\;

private fun makePythonCode(host: String, port: Int) = """
import asyncio
import uuid
import json
from types import SimpleNamespace
from json import JSONEncoder, JSONDecoder

class ResultEncoder(JSONEncoder):
    def default(self, o):
        if isinstance(o, Exception):
            return format_exception(type(o), o, o.__traceback__)
        try: 
            return super().default(o)
        except:
            return { '$': id(o) }

def format_exception(et, ev, tb):
    result = {
        'type': et.__name__,
        'message': str(ev),
        'stack': []
    }

    while tb:
        result['stack'].append({
            'file': tb.tb_frame.f_code.co_filename,
            'function': tb.tb_frame.f_code.co_name,
            'line': tb.tb_lineno
        })
        tb = tb.tb_next
    
    return result

class CommandServer:
    def __init__(self, host='127.0.0.1', port=65432):
        self.host = host
        self.port = port
        self.server = None
        self.loop = None
        self.pending_tasks = {}
        self.mutable_state = SimpleNamespace()

    async def handle_client(self, reader, writer) -> None:
        leftover_packets = list()
        leftover_data = bytearray()

        while True:
            data, leftover_data = await self.read_message(reader, leftover_data, leftover_packets)

            if not data:  # connection closed
                break
            message = json.loads(data)

            # print(message, leftover_data, leftover_packets)

            # Generate unique id for the task if it doesn't exist
            id = message.get('id', str(uuid.uuid4()))
            command = message['command']

            # Execute the command in a non-blocking manner
            task = self.loop.create_task(self.execute_command(command))
            self.pending_tasks[id] = task

            # Non-blocking wait for the tasks to finish and send the results back
            self.loop.create_task(self.send_result(writer, id))

    async def read_message(self, reader, leftover_data: bytearray, leftover_packets: list) -> str:
        while True:
            if len(leftover_packets) > 0:
                packet = leftover_packets.pop(0)
                return packet, leftover_data

            try:
                data = await reader.read(256)
            except ConnectionResetError:
                return None, leftover_data
            if not data:  # connection closed
                return None, leftover_data
            
            leftover_data.extend(data)

            while True:
                try:
                    index = leftover_data.index(b'\x00')
                    packet = leftover_data[:index]
                    leftover_data = leftover_data[index + 1:]
                    leftover_packets.append(packet.decode())
                except ValueError:
                    break

    async def send_result(self, writer, id) -> None:
        task = self.pending_tasks[id]

        result_obj = {'id': id, 'result': None, 'error': None}
        try:
            await task
            if task.exception() is not None:
                result_obj['error'] = format_exception(*task.exc_info())
            else:
                result_obj['result'] = task.result()
        except:
            import sys
            et, ev, tb = sys.exc_info()
            result_obj['error'] = format_exception(et, ev, tb)
        
        writer.write(json.dumps(result_obj).encode() + b'\x00')
        # await writer.drain()

        # writer.close()
        del self.pending_tasks[id]

    async def execute_command(self, command) -> None:
        # WARNING: eval allows execution of arbitrary code - this is dangerous and should be replaced with safer code
        l = dict()
        l['G'] = self.mutable_state
        g = globals().copy()
        # print("Executing command: ")
        # print(command)
        exec(command, l, g)

        # The code may define either async def main() or def main() that returns a value
        main = g.get('main')
        if main is None:
            raise Exception('No main function found')
        else:
            if asyncio.iscoroutinefunction(main):
                return await main()
            else:
                return main()

    def run(self) -> None:
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)

        self.server = self.loop.run_until_complete(asyncio.start_server(self.handle_client, self.host, self.port))

        try:
            self.loop.run_forever()
        except KeyboardInterrupt:
            pass

        self.server.close()
        self.loop.run_until_complete(self.server.wait_closed())
        self.loop.close()

# print('Starting Python command server')
server = CommandServer(host='${host}', port=${port})
server.run()
""".trimIndent()

class JsonContinuationInterceptor<T>(
    private val cont: Continuation<T>,
    private val decoder: KSerializer<T>?
) : Continuation<String> {

    override val context: CoroutineContext = cont.context

    override fun resumeWith(result: Result<String>) {
        if (result.isSuccess) {
            if (decoder != null) {
                val value = Json.decodeFromString(decoder, result.getOrNull() ?: "")
                cont.resumeWith(Result.success(value))
            } else {
                cont.resumeWith(Result.success(Unit) as Result<T>)
            }
        } else {
            cont.resumeWith(Result.failure(result.exceptionOrNull()!!))
        }
    }
}

class PythonException(val type: String, message: String) : Exception(message, null, true, true)

class Agent(val host: String, val port: Int) {
    private var socket = run {
        for (retryCount in 0..100) {
            try {
                return@run Socket(host, port)
            } catch (e: IOException) {
                Thread.sleep(100)
            }
        }
        throw IllegalStateException("Could not connect to Python agent")
    }
    private var nextId: Long = 0
    private val lock = Mutex()
    private var reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
    private var writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
    private val responses = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val scope = CoroutineScope(Dispatchers.IO)

    @Serializable data class Message(val id: String, val command: String)

    @Serializable data class StackTraceLine(val file: String, val line: Int, val function: String)

    @Serializable data class ResultError(
        val type: String,
        val message: String,
        val stack: List<StackTraceLine>
    )

    @Serializable data class Result(val id: String, val result: String? = null, val error: ResultError? = null)

    init {
        scope.launch {
            while (isActive) {
                val result = try {
                    receive()
                } catch (e: Throwable) {
                    if (e is VirtualMachineError) throw e
                    // throw e
                    Thread.yield()
                    continue
                }

                if (result.result != null) {
                    responses[result.id]?.complete(result.result)
                } else {
                    result.error!!
                    val exc = PythonException(result.error.type, result.error.message)
                    val old = exc.stackTrace
                    exc.stackTrace = result.error.stack.map { StackTraceElement("<none>", it.function, it.file, it.line) }.toTypedArray()
                    responses[result.id]?.completeExceptionally(exc)
                }
            }
        }
    }

    fun close() {
        scope.cancel()
        socket.close()
    }

    suspend fun send(code: String): String {
        val id = UUID.randomUUID().toString()
        val message = Json.encodeToString(Message(id, code))

        withContext(Dispatchers.IO) {
            lock.withLock {
                if (socket.isClosed) {
                    socket = Socket(host, port)
                    reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                    writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
                }

                writer.write(message)
                writer.write("\u0000")
                writer.flush()
            }
        }

        val deferred = CompletableDeferred<String>()
        responses[id] = deferred
        return deferred.await()
    }

    fun send(code: String, continuation: Continuation<String>) {
        scope.launch {
            try {
                val result = send(code)
                continuation.resume(result)
            } catch (e: Throwable) {
                continuation.resumeWithException(e)
            }
        }
    }

    fun sendBlocking(code: String): String = runBlocking { send(code) }

    private fun receive(): Result {
        val resultJson = StringBuilder()
        var c: Int
        try {
            while (reader.read().also { c = it } != -1) {
                if (c.toChar() == '\u0000') {
                    break
                }
                resultJson.append(c.toChar())
            }
        } catch (e: SocketException) {
            throw e
        }

        if (c == -1) {
            throw SocketException("Connection closed")
        }

        return Json.decodeFromString<Result>(resultJson.toString())
    }
}

@OptIn(ExperimentalContracts::class)
object Python {
    annotation class Func(val name: String)

    class Ref<out T>(val id: Long)
    class WeakRef<out T>(val id: Long)

    fun start(code: String, interpreter: PythonInstallation? = null): Process {
        var interpreter = interpreter

        if (interpreter == null) {
            val installations = PythonInstallation.findUsingPath()
            if (installations.isEmpty()) {
                throw NoPythonInstallationFoundException()
            }
            interpreter = installations.maxBy { it.version }
        }

        val pb = ProcessBuilder(interpreter.path.absolutePath, "-c", code)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)

        val env = pb.environment()
        env["PYTHONUNBUFFERED"] = "1"
        env["PYTHONUTF8"] = "1"
        return pb.start()
    }

    fun start(file: File, interpreter: PythonInstallation? = null): Process {
        var interpreter = interpreter

        if (interpreter == null) {
            val installations = PythonInstallation.findUsingPath()
            if (installations.isEmpty()) {
                throw NoPythonInstallationFoundException()
            }
            interpreter = installations.maxBy { it.version }
        }

        val pb = ProcessBuilder(interpreter.path.absolutePath, file.absolutePath)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)

        val env = pb.environment()
        env["PYTHONUNBUFFERED"] = "1"
        env["PYTHONUTF8"] = "1"
        return pb.start()
    }

    private data class ResultDeserializer<T>(val isNullable: Boolean, val serializer: KSerializer<T>)

    private data class MethodInfo(
        val name: String,
        val pythonFuncParsed: PythonFuncParsed,
        val isSuspend: Boolean,
        val argSerializers: List<KSerializer<*>>,
        val resultDeserializer: ResultDeserializer<*>?
    )

    private fun isTcpPortAvailable(port: Int): Boolean {
        require(port in 0..65535) { "Invalid port number: $port" }
        var ss: ServerSocket? = null
        try {
            ss = ServerSocket(port)
            ss.reuseAddress = true
            return true
        } catch (e: IOException) {
        } finally {
            if (ss != null) {
                try {
                    ss.close()
                } catch (e: IOException) {
                    /* should not be thrown */
                }
            }
        }
        return false
    }

    private fun findAvailablePort(): Int {
        for (port in 10000..65535) {
            if (isTcpPortAvailable(port)) {
                return port
            }
        }
        throw IllegalStateException("No available port found")
    }

    interface FFI {
        fun __ffi_close()
    }

    interface Interpreter {
        interface Session

    }

    fun unsafeFFI(kClasses: List<KClass<*>>, installation: PythonInstallation?): FFI {
        require(kClasses.isNotEmpty()) { "kClasses must not be empty" }

        val methods = mutableMapOf<java.lang.reflect.Method, MethodInfo>()

        for (kClass in kClasses) {
            val clazz = kClass.java
            val className = kClass.simpleName

            for (method in kClass.declaredFunctions) {
                val fullMethodName = "$className::${method.name}"

                require(method.isAbstract) { "Method $fullMethodName is not abstract" }
                require(method.annotations.isNotEmpty()) { "Method $fullMethodName in $className has no annotations" }
                require(method.annotations.count { it is Func } == 1) {
                    "Method $fullMethodName has more than one annotation"
                }

                val annotation = method.annotations.filterIsInstance<Func>().single().name.trimIndent()
                val parsed = parsePythonFuncAnnotation(annotation)

                val argSerializers = mutableListOf<KSerializer<*>>()
                for (param in method.valueParameters) {
                    argSerializers.add(serializer(param.type))
                }

                val resultDeserializer = if (method.returnType != Unit::class.createType()) {
                    val returnType = method.returnType
                    val serializer = serializer(returnType)
                    val isNullable = method.returnType.isMarkedNullable
                    ResultDeserializer(isNullable, serializer)
                } else null

                val methodInfo = MethodInfo(
                    method.name,
                    parsed,
                    method.isSuspend,
                    argSerializers,
                    resultDeserializer
                )

                methods[method.javaMethod!!] = methodInfo
            }
        }

        val host = "0.0.0.0"
        val port = findAvailablePort()

        val tmpFile = File.createTempFile("python-ffi", ".py")
        val code = makePythonCode("0.0.0.0", port)
        tmpFile.writeText(code)

        val process = start(tmpFile, installation)
        Thread.sleep(200)
        val agent = Agent("localhost", port)

        // Register a GC hook to kill the Python process when the reference is collected.
        // FIXME: This doesn't work on JVM 8, but it is not a _huge issue_ since we expose a close method.
        // val cleaner: Cleaner = Cleaner.create()
        // cleaner.register(agent, Runnable { process.destroy() })

        val classLoader = kClasses.first().java.classLoader
        val proxyClasses = Array(kClasses.size + 1) {
            if (it == kClasses.size) FFI::class.java else kClasses[it].java
        }

        return Proxy.newProxyInstance(classLoader, proxyClasses) { _, method, args ->
            if (method == FFI::__ffi_close.javaMethod) {
                var error: Throwable? = null
                try {
                    agent.close()
                } catch (e: Throwable) {
                    if (e is VirtualMachineError) throw e
                    error = e
                }
                process.destroy()
                if (error != null) throw error
                return@newProxyInstance null
            }

            val info = methods[method]!!

            val args = args?.toMutableList() ?: mutableListOf()

            val ct: Continuation<Any?>?
            if (info.isSuspend) {
                ct = args.last() as Continuation<Any?>
                args.removeLast()
            } else {
                ct = null
            }

            val serializedArgs = mutableListOf<String>()
            for ((i, arg) in args.withIndex()) {
                val serializer = info.argSerializers[i]
                serializedArgs.add(Json.encodeToString<Any?>(serializer as KSerializer<Any?>, arg))
            }

            val code = when (val info = info.pythonFuncParsed) {
                is PythonFuncParsed.Func -> {
                    if (info.isAsync) {
                        """
                        async def main():
                            import ${info.module}
                            import json
                            args = ${Json.encodeToString(serializedArgs)}
                            args = [json.loads(arg) for arg in args]
                            result = await ${info.module}.${info.func}(*args)
                            return json.dumps(result)
                    """.trimIndent()
                    } else {
                        """
                        def main():
                            import ${info.module}
                            import json
                            args = ${Json.encodeToString(serializedArgs)}
                            args = [json.loads(arg) for arg in args]
                            result = ${info.module}.${info.func}(*args)
                            return json.dumps(result)
                    """.trimIndent()
                    }
                }
                is PythonFuncParsed.Code -> {
                    // println(info.code.indent("    "))

                    if (info.isAsync) {
                        """
                        async def main():
                            import json
                            args = ${Json.encodeToString(serializedArgs)}
                            args = [json.loads(arg) for arg in args]
                        {{CODE}}
                            result = await ${info.funcName}(*args)
                            return json.dumps(result)
                    """.trimIndent().replace("{{CODE}}", info.code.indent("    "))
                    } else {
                        """
                        def main():
                            import json
                            args = ${Json.encodeToString(serializedArgs)}
                            args = [json.loads(arg) for arg in args]
                        {{CODE}}
                            result = ${info.funcName}(*args)
                            return json.dumps(result)
                    """.trimIndent().replace("{{CODE}}", info.code.indent("    "))
                    }
                }
            }

            // println(code)

            if (ct == null) {
                // NOT suspend function, we need to block until the result is received.
                val result = agent.sendBlocking(code)

                if (info.resultDeserializer != null) {
                    return@newProxyInstance Json.decodeFromString(
                        info.resultDeserializer.serializer as KSerializer<Any?>,
                        result
                    )
                } else return@newProxyInstance Unit
            } else {
                // suspend function, we need to return
                agent.send(code, JsonContinuationInterceptor(ct, info.resultDeserializer?.serializer))
                return@newProxyInstance kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
            }

            // result
            // return@newProxyInstance kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
        } as FFI
    }

    inline fun <reified T : Any> unsafeFFI(installation: PythonInstallation? = null, withShutdownHook: Boolean = true): T {
        val ffi = unsafeFFI(listOf(T::class), installation)
        if (withShutdownHook) {
            val closeThread = Thread { ffi.__ffi_close() }
            Runtime.getRuntime().addShutdownHook(closeThread)
        }
        return ffi as T
    }

    inline fun <reified T1 : Any, reified T2 : Any> unsafeFFI2(installation: PythonInstallation? = null, withShutdownHook: Boolean = true): Pair<T1, T2> {
        val ffi = unsafeFFI(listOf(T1::class, T2::class), installation)
        if (withShutdownHook) {
            val closeThread = Thread { ffi.__ffi_close() }
            Runtime.getRuntime().addShutdownHook(closeThread)
        }
        return Pair(ffi as T1, ffi as T2)
    }

    inline fun <reified T1 : Any, reified T2 : Any, reified T3 : Any> unsafeFFI3(installation: PythonInstallation? = null, withShutdownHook: Boolean = true): Triple<T1, T2, T3> {
        val ffi = unsafeFFI(listOf(T1::class, T2::class, T3::class), installation)
        if (withShutdownHook) {
            val closeThread = Thread { ffi.__ffi_close() }
            Runtime.getRuntime().addShutdownHook(closeThread)
        }
        return Triple(ffi as T1, ffi as T2, ffi as T3)
    }

    inline fun withUnsafeFFI(list: List<KClass<*>>, installation: PythonInstallation?, block: (FFI) -> Unit) {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        val ffi = unsafeFFI(list, installation)
        val closeThread = Thread { ffi.__ffi_close() }
        Runtime.getRuntime().addShutdownHook(closeThread)
        try {
            block(ffi)
        } finally {
            ffi.__ffi_close()
            Runtime.getRuntime().removeShutdownHook(closeThread)
        }
    }

    inline suspend fun withUnsafeFFISuspend(list: List<KClass<*>>, installation: PythonInstallation?, block: suspend (FFI) -> Unit) {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        val ffi = unsafeFFI(list, installation)
        val closeThread = Thread { ffi.__ffi_close() }
        Runtime.getRuntime().addShutdownHook(closeThread)
        try {
            block(ffi)
        } finally {
            ffi.__ffi_close()
            Runtime.getRuntime().removeShutdownHook(closeThread)
        }
    }

    inline fun <reified T : Any> withFFI(installation: PythonInstallation? = null, block: (T) -> Unit) {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        withUnsafeFFI(listOf(T::class), installation) { ffi ->
            block(ffi as T)
        }
    }

    @JvmName("withFFI2")
    inline fun <reified T1 : Any, reified T2 : Any> withFFI(installation: PythonInstallation? = null, block: (T1, T2) -> Unit) {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        withUnsafeFFI(listOf(T1::class, T2::class), installation) { ffi ->
            block(ffi as T1, ffi as T2)
        }
    }

    @JvmName("withFFI3")
    inline fun <reified T1 : Any, reified T2 : Any, reified T3 : Any> withFFI(installation: PythonInstallation? = null, block: (T1, T2, T3) -> Unit) {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        withUnsafeFFI(listOf(T1::class, T2::class, T3::class), installation) { ffi ->
            block(ffi as T1, ffi as T2, ffi as T3)
        }
    }

    inline suspend fun <reified T : Any> withFFIAsync(installation: PythonInstallation? = null, block: suspend (T) -> Unit) {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        withUnsafeFFI(listOf(T::class), installation) { ffi ->
            block(ffi as T)
        }
    }

    @JvmName("withFFI2")
    inline suspend fun <reified T1 : Any, reified T2 : Any> withFFIAsync(installation: PythonInstallation? = null, block: suspend (T1, T2) -> Unit) {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        withUnsafeFFI(listOf(T1::class, T2::class), installation) { ffi ->
            block(ffi as T1, ffi as T2)
        }
    }

    @JvmName("withFFI3")
    inline suspend fun <reified T1 : Any, reified T2 : Any, reified T3 : Any> withFFIAsync(installation: PythonInstallation? = null, block: suspend (T1, T2, T3) -> Unit) {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        withUnsafeFFI(listOf(T1::class, T2::class, T3::class), installation) { ffi ->
            block(ffi as T1, ffi as T2, ffi as T3)
        }
    }
}
