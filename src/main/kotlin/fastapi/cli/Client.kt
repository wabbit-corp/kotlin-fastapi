@file:OptIn(InternalCoroutinesApi::class)

package fastapi.cli

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.serializerOrNull
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.lang.reflect.Proxy
import kotlin.concurrent.thread
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.javaMethod
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.InternalCoroutinesApi
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.jvm.jvmErasure

class CliExit(
    val code: Int,
    val stderr: String,
    val stdout: String = ""
) : RuntimeException("Process exited $code: $stderr")

data class ClientOptions(
    val json: Json = Json,
    val cwd: File? = null,
    val env: Map<String, String> = emptyMap(),
    val inheritParentEnv: Boolean = true,
    val timeoutMs: Long? = null,
    val maxOutputBytes: Int = 4 * 1024 * 1024,
    val redirectErrorStream: Boolean = false,
    val charset: Charset = StandardCharsets.UTF_8
)

@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
inline fun <reified T : Any> cliClient(executable: String): T = cliClient(T::class, executable)

@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
fun <T : Any> cliClient(kClass: KClass<T>, executable: String, options: ClientOptions = ClientOptions()): T {
    val clazz = kClass.java
    val methods = describeInterface(kClass).associateBy { spec ->
        kClass.declaredFunctions.first { it.name == spec.functionName }.javaMethod!!
    }

    data class InvocationRecipe(
        val argv: List<String>,
        val stdinSpec: ParamSpec?,
        val stdinValue: Any?
    )

    fun buildInvocation(spec: MethodSpec, realArgs: List<Any?>): InvocationRecipe {
        val argv = mutableListOf<String>()
        argv.addAll(spec.path.segments)

        val baseIndex = spec.params.minOfOrNull { it.kParam.index } ?: 0
        val byIndex = spec.params.associateBy { it.kParam.index - baseIndex }

        var stdinSpec: ParamSpec? = null
        var stdinValue: Any? = null

        for ((i, anyVal) in realArgs.withIndex()) {
            val p = byIndex[i] ?: continue
            when (p.kind) {
                ParamKind.STDIN_JSON, ParamKind.STDIN_TEXT, ParamKind.STDIN_BYTES -> { stdinSpec = p; stdinValue = anyVal }
                ParamKind.FLAG -> {
                    val b = anyVal as Boolean
                    if (b) argv += "--${p.long}" else if (p.negatable) argv += "--no-${p.long}"
                }
                ParamKind.OPTION -> {
                    if (anyVal == null) continue
                    if (p.repeatKind != RepeatKind.NONE) {
                        @Suppress("UNCHECKED_CAST")
                        val list = anyVal as Iterable<Any>
                        for (v in list) argv += "--${p.long}=${v}"
                    } else {
                        argv += "--${p.long}=${anyVal}"
                    }
                }
                ParamKind.POSITIONAL -> {
                    if (p.repeatKind != RepeatKind.NONE) {
                        @Suppress("UNCHECKED_CAST")
                        val list = anyVal as Iterable<Any>
                        list.forEach { argv += it.toString() }
                    } else {
                        argv += anyVal.toString()
                    }
                }
            }
        }

        return InvocationRecipe(argv, stdinSpec, stdinValue)
    }

    data class IOCollectors(
        val stdoutBuf: ByteArrayOutputStream,
        val stderrBuf: ByteArrayOutputStream,
        val stdoutThread: Thread,
        val stderrThread: Thread
    )

    fun readStreamLimited(ins: InputStream, maxBytes: Int): ByteArrayOutputStream {
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        var total = 0
        while (true) {
            val n = ins.read(tmp)
            if (n == -1) break
            total += n
            if (total > maxBytes) {
                // Truncate and stop; the process may still run but we'll ignore extra
                buf.write(tmp, 0, (n - (total - maxBytes)).coerceAtLeast(0))
                break
            } else {
                buf.write(tmp, 0, n)
            }
        }
        return buf
    }

    fun startCollectors(proc: Process): IOCollectors {
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val tOut = thread(start = true, isDaemon = true, name = "cliClient-stdout") {
            proc.inputStream.use { ins ->
                val b = readStreamLimited(ins, options.maxOutputBytes)
                outBuf.write(b.toByteArray())
            }
        }
        val tErr = thread(start = true, isDaemon = true, name = "cliClient-stderr") {
            val src = if (options.redirectErrorStream) proc.inputStream else proc.errorStream
            src.use { ins ->
                val b = readStreamLimited(ins, options.maxOutputBytes)
                errBuf.write(b.toByteArray())
            }
        }
        return IOCollectors(outBuf, errBuf, tOut, tErr)
    }

    fun writeStdinIfNeeded(proc: Process, stdinSpec: ParamSpec?, stdinValue: Any?) {
        if (stdinSpec != null) {
            proc.outputStream.use { os ->
                when (stdinSpec.kind) {
                    ParamKind.STDIN_JSON -> {
                        val ser = serializerOrNull(stdinSpec.kType)
                            ?: throw IllegalArgumentException("No serializer for stdin param: ${stdinSpec.kParam.name}")
                        options.json.encodeToStream(ser, stdinValue, os)
                    }
                    ParamKind.STDIN_TEXT -> {
                        val text = when (stdinValue) {
                            null -> ""
                            is String -> stdinValue
                            else -> stdinValue.toString()
                        }
                        os.write(text.toByteArray(options.charset))
                    }
                    ParamKind.STDIN_BYTES -> {
                        val bytes = when (stdinValue) {
                            is ByteArray -> stdinValue
                            null -> ByteArray(0)
                            else -> throw IllegalArgumentException("Expected ByteArray for @StdinBytes")
                        }
                        os.write(bytes)
                    }
                    else -> {}
                }
            }
        } else {
            try { proc.outputStream.close() } catch (_: Throwable) {}
        }
    }

    fun decodeReturn(spec: MethodSpec, stdout: String): Any? =
        when {
            spec.returnKType.jvmErasure == String::class -> stdout.removeSuffix("\n")
            spec.returnSerializer != null -> {
                stdout.byteInputStream().use { options.json.decodeFromStream(spec.returnSerializer, it) }
            }
            else -> Unit
        }

    return Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { _, method, args ->
        val spec = methods[method] ?: error("No spec for ${method.name}")

        val isSuspend = method.parameterTypes.lastOrNull()?.name == Continuation::class.java.name
        val realArgs = if (isSuspend) args.dropLast(1) else args.toList()
        val cont = if (isSuspend) args.last() as Continuation<Any?> else null

        fun configure(pb: ProcessBuilder) {
            if (options.cwd != null) pb.directory(options.cwd)
            pb.redirectErrorStream(options.redirectErrorStream)
            if (!options.inheritParentEnv) {
                val env = pb.environment()
                env.clear()
                // minimal defaults (very small; caller can add more)
                val os = System.getProperty("os.name").lowercase()
                if (os.contains("win")) {
                    env.putIfAbsent("SystemRoot", System.getenv("SystemRoot") ?: "C:\\Windows")
                    env.putIfAbsent("ComSpec", System.getenv("ComSpec") ?: "C:\\Windows\\System32\\cmd.exe")
                    env.putIfAbsent("PATH", System.getenv("PATH") ?: "C:\\Windows\\System32;C:\\Windows")
                } else {
                    env.putIfAbsent("PATH", "/usr/bin:/bin")
                }
                env.putAll(options.env)
            } else if (options.env.isNotEmpty()) {
                pb.environment().putAll(options.env)
            }
        }

        fun runBlockingOnce(): Any? {
            val recipe = buildInvocation(spec, realArgs)
            val pb = ProcessBuilder(listOf(executable) + recipe.argv)
            configure(pb)
            val proc = pb.start()
            val io = startCollectors(proc)

            writeStdinIfNeeded(proc, recipe.stdinSpec, recipe.stdinValue)

            val finished = if (options.timeoutMs != null) {
                proc.waitFor(options.timeoutMs, TimeUnit.MILLISECONDS)
            } else {
                proc.waitFor(); true
            }

            io.stdoutThread.join()
            io.stderrThread.join()

            val stdout = io.stdoutBuf.toString(options.charset)
            val stderr = io.stderrBuf.toString(options.charset)

            if (!finished) {
                killProcessTree(proc)
                throw CliExit(124, "timed out after ${options.timeoutMs}ms", stdout)
            }

            val code = proc.exitValue()
            if (code != 0) throw CliExit(code, stderr.trim(), stdout.trimEnd())
            return decodeReturn(spec, stdout)
        }

        if (!isSuspend) {
            runBlockingOnce()
        } else {
            val recipe = buildInvocation(spec, realArgs)
            val contJob: Job? = cont!!.context[Job]
            if (contJob != null && !contJob.isActive) {
                cont.resumeWith(Result.failure(CancellationException("cancelled before start")))
                return@newProxyInstance COROUTINE_SUSPENDED
            }

            val pb = ProcessBuilder(listOf(executable) + recipe.argv)
            configure(pb)
            val proc = pb.start()
            val io = startCollectors(proc)

            try {
                writeStdinIfNeeded(proc, recipe.stdinSpec, recipe.stdinValue)
            } catch (t: Throwable) {
                proc.destroyForcibly()
                cont.resumeWith(Result.failure(t))
                return@newProxyInstance COROUTINE_SUSPENDED
            }

            val completed = AtomicBoolean(false)

            contJob?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                if (completed.compareAndSet(false, true)) {
                    killProcessTree(proc)
                    try { proc.inputStream.close() } catch (_: Throwable) {}
                    try { proc.errorStream.close() } catch (_: Throwable) {}
                    try { proc.outputStream.close() } catch (_: Throwable) {}
                    cont.resumeWith(Result.failure(cause ?: CancellationException("cancelled")))
                }
            }

            // Optional timeout
            val timeoutThread =
                if (options.timeoutMs != null) thread(start = true, isDaemon = true, name = "cliClient-timeout") {
                    try {
                        Thread.sleep(options.timeoutMs)
                        if (completed.compareAndSet(false, true)) {
                            killProcessTree(proc)
                            cont.resumeWith(Result.failure(CliExit(124, "timed out after ${options.timeoutMs}ms")))
                        }
                    } catch (_: InterruptedException) {}
                } else null

            proc.onExit().whenComplete { _, ex ->
                timeoutThread?.interrupt()
                if (!completed.compareAndSet(false, true)) return@whenComplete
                try {
                    io.stdoutThread.join()
                    io.stderrThread.join()
                    if (ex != null) {
                        cont.resumeWith(Result.failure(ex)); return@whenComplete
                    }
                    val code = try { proc.exitValue() } catch (_: IllegalThreadStateException) { -1 }
                    val stdout = io.stdoutBuf.toString(options.charset)
                    val stderr = io.stderrBuf.toString(options.charset)
                    if (code != 0) cont.resumeWith(Result.failure(CliExit(code, stderr.trim(), stdout.trimEnd())))
                    else cont.resumeWith(Result.success(decodeReturn(spec, stdout)))
                } catch (t: Throwable) {
                    cont.resumeWith(Result.failure(t))
                }
            }

            COROUTINE_SUSPENDED
        }
    } as T
}

// Kill process and its descendants (JDK 9+). Prevents orphaned children like `sleep` under a shell.
fun killProcessTree(proc: Process) {
    try {
        val h = proc.toHandle()
        h.descendants().forEach { ph ->
            try { ph.destroyForcibly() } catch (_: Throwable) {}
        }
        h.destroyForcibly()
    } catch (_: Throwable) {
        try { proc.destroyForcibly() } catch (_: Throwable) {}
    }
}
