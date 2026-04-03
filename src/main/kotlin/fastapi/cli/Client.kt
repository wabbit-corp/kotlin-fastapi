package fastapi.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.jvmErasure
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.io.files.Path as KxPath
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.serializerOrNull
import one.wabbit.exec.EnvPolicy
import one.wabbit.exec.Exec
import one.wabbit.exec.ExecError
import one.wabbit.exec.ExecException
import one.wabbit.exec.ExecResult
import one.wabbit.exec.ExecSpec
import one.wabbit.exec.ExitPolicy
import one.wabbit.exec.TextEncoding

class CliExit(val code: Int, val stderr: String, val stdout: String = "") :
    RuntimeException("Process exited $code: $stderr")

data class ClientOptions(
    val json: Json = Json,
    val cwd: File? = null,
    val env: Map<String, String> = emptyMap(),
    val inheritParentEnv: Boolean = true,
    val timeoutMs: Long? = null,
    val maxOutputBytes: Int = 4 * 1024 * 1024,
    val redirectErrorStream: Boolean = false,
    val charset: Charset = StandardCharsets.UTF_8,
)

@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
inline fun <reified T : Any> cliClient(executable: String): T = cliClient(T::class, executable)

@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
fun <T : Any> cliClient(
    kClass: KClass<T>,
    executable: String,
    options: ClientOptions = ClientOptions(),
): T {
    val clazz = kClass.java
    val methods =
        describeInterface(kClass).associateBy { spec ->
            kClass.declaredFunctions.first { it.name == spec.functionName }.javaMethod!!
        }

    data class InvocationRecipe(
        val argv: List<String>,
        val stdinSpec: ParamSpec?,
        val stdinValue: Any?,
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
                ParamKind.STDIN_JSON,
                ParamKind.STDIN_TEXT,
                ParamKind.STDIN_BYTES -> {
                    stdinSpec = p
                    stdinValue = anyVal
                }
                ParamKind.FLAG -> {
                    val b = anyVal as Boolean
                    if (b) {
                        argv += "--${p.long}"
                    } else if (p.negatable) {
                        argv += "--no-${p.long}"
                    }
                }
                ParamKind.OPTION -> {
                    if (anyVal == null) continue
                    if (p.repeatKind != RepeatKind.NONE) {
                        @Suppress("UNCHECKED_CAST") val list = anyVal as Iterable<Any>
                        for (v in list) argv += "--${p.long}=$v"
                    } else {
                        argv += "--${p.long}=$anyVal"
                    }
                }
                ParamKind.POSITIONAL -> {
                    if (p.repeatKind != RepeatKind.NONE) {
                        @Suppress("UNCHECKED_CAST") val list = anyVal as Iterable<Any>
                        list.forEach { argv += it.toString() }
                    } else {
                        argv += anyVal.toString()
                    }
                }
            }
        }

        return InvocationRecipe(argv, stdinSpec, stdinValue)
    }

    fun textEncoding(): TextEncoding = TextEncoding.Named(options.charset.name())

    fun envPolicy(): EnvPolicy =
        if (options.inheritParentEnv) {
            EnvPolicy.Inherit(overlay = options.env)
        } else {
            EnvPolicy.Hermetic(base = options.env)
        }

    fun stdinInput(stdinSpec: ParamSpec?, stdinValue: Any?): ExecSpec.Input =
        when (stdinSpec?.kind) {
            null -> ExecSpec.Input.None
            ParamKind.STDIN_JSON -> {
                val ser =
                    serializerOrNull(stdinSpec.kType)
                        ?: throw IllegalArgumentException(
                            "No serializer for stdin param: ${stdinSpec.kParam.name}"
                        )
                val bytes = ByteArrayOutputStream()
                options.json.encodeToStream(ser, stdinValue, bytes)
                ExecSpec.Input.Bytes(bytes.toByteArray())
            }
            ParamKind.STDIN_TEXT -> {
                val text =
                    when (stdinValue) {
                        null -> ""
                        is String -> stdinValue
                        else -> stdinValue.toString()
                    }
                ExecSpec.Input.Text(text, encoding = textEncoding())
            }
            ParamKind.STDIN_BYTES -> {
                val bytes =
                    when (stdinValue) {
                        is ByteArray -> stdinValue
                        null -> ByteArray(0)
                        else -> throw IllegalArgumentException("Expected ByteArray for @StdinBytes")
                    }
                ExecSpec.Input.Bytes(bytes)
            }
            else -> ExecSpec.Input.None
        }

    fun captureSink(): ExecSpec.SinkSpec.Capture =
        ExecSpec.SinkSpec.Capture(maxBytes = options.maxOutputBytes, keep = ExecSpec.Keep.Head)

    fun buildExecSpec(recipe: InvocationRecipe): ExecSpec =
        ExecSpec(
            argv = listOf(executable) + recipe.argv,
            cwd = options.cwd?.let { KxPath(it.absolutePath) },
            env = envPolicy(),
            stdin = stdinInput(recipe.stdinSpec, recipe.stdinValue),
            stdout = ExecSpec.StdoutSpec.Pipe(captureSink()),
            stderr =
                if (options.redirectErrorStream) {
                    ExecSpec.StderrSpec.ToStdout
                } else {
                    ExecSpec.StderrSpec.Pipe(captureSink())
                },
            timeout = options.timeoutMs?.milliseconds,
            exitPolicy = ExitPolicy.ThrowOnNonZero,
        )

    fun decodeCaptured(captured: ExecResult.Captured?): String =
        captured?.text(textEncoding(), trimLineEndings = false).orEmpty()

    fun decodeReturn(spec: MethodSpec, stdout: String): Any? =
        when {
            spec.returnKType.jvmErasure == String::class -> stdout.removeSuffix("\n")
            spec.returnSerializer != null -> {
                stdout.byteInputStream(options.charset).use {
                    options.json.decodeFromStream(spec.returnSerializer, it)
                }
            }
            else -> Unit
        }

    fun unexpectedCliExit(error: ExecError, stderr: String, stdout: String): CliExit =
        CliExit(-1, stderr.ifEmpty { error.message }, stdout)

    fun toCliExit(error: ExecError): CliExit {
        val stdout = decodeCaptured(error.captures?.stdout).trimEnd()
        val stderr =
            if (options.redirectErrorStream) {
                stdout.trim().ifEmpty { decodeCaptured(error.captures?.stderr).trim() }
            } else {
                decodeCaptured(error.captures?.stderr).trim()
            }
        return when (error) {
            is ExecError.TimedOut -> CliExit(124, "timed out after ${error.timeoutMs}ms", stdout)
            is ExecError.ExitNonZero -> CliExit(error.exitCode, stderr, stdout)
            else -> unexpectedCliExit(error, stderr, stdout)
        }
    }

    fun throwableFromExec(error: ExecError): Throwable =
        when (error) {
            is ExecError.TimedOut,
            is ExecError.ExitNonZero -> toCliExit(error)
            is ExecError.Cancelled ->
                (error.cause as? CancellationException) ?: CancellationException(error.message)
            else -> error.cause ?: ExecException(error)
        }

    fun runBlockingOnce(spec: MethodSpec, realArgs: List<Any?>): Any? {
        val recipe = buildInvocation(spec, realArgs)
        val result =
            try {
                Exec.execBlocking(buildExecSpec(recipe))
            } catch (e: ExecException) {
                throw throwableFromExec(e.error)
            }
        return decodeReturn(spec, decodeCaptured(result.stdout))
    }

    return Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { _, method, args ->
        val spec = methods[method] ?: error("No spec for ${method.name}")

        val isSuspend = method.parameterTypes.lastOrNull()?.name == Continuation::class.java.name
        val realArgs = if (isSuspend) args.dropLast(1) else args.toList()

        if (!isSuspend) {
            runBlockingOnce(spec, realArgs)
        } else {
            val cont = args.last() as Continuation<Any?>
            val recipe = buildInvocation(spec, realArgs)
            CoroutineScope(cont.context).launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    val result = Exec.exec(buildExecSpec(recipe))
                    cont.resumeWith(Result.success(decodeReturn(spec, decodeCaptured(result.stdout))))
                } catch (e: ExecException) {
                    cont.resumeWith(Result.failure(throwableFromExec(e.error)))
                } catch (t: Throwable) {
                    cont.resumeWith(Result.failure(t))
                }
            }
            COROUTINE_SUSPENDED
        }
    } as T
}
