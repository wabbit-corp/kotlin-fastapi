package fastapi.cli

import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Paths
import java.time.Duration
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.createType
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.jvmErasure
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializerOrNull

internal fun camelToKebab(s: String): String =
    s.replace('_', '-') // treat underscores as hyphens
        .replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")
        .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1-$2")
        .lowercase()

internal data class CommandPath(val segments: List<String>) {
    companion object {
        fun parse(s: String): CommandPath =
            CommandPath(s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() })
    }
}

internal enum class ParamKind {
    FLAG,
    OPTION,
    POSITIONAL,
    STDIN_JSON,
    STDIN_TEXT,
    STDIN_BYTES,
}

internal enum class RepeatKind {
    NONE,
    LIST,
    SET,
    ARRAY,
}

internal data class ParamSpec(
    val kParam: KParameter,
    val kind: ParamKind,
    val long: String, // kebab-case long name (for flags/options)
    val short: Char?, // single-letter alias
    val position: Int?, // for positional
    val negatable: Boolean, // for flags
    val kType: KType,
    val isList: Boolean, // kept for back-compat; true when repeatKind != NONE
    val elemKClass: KClass<*>?, // for List<T> / Set<T> / Array<T>
    val repeatKind: RepeatKind,
    val help: String?,
    val required: Boolean,
)

internal data class MethodSpec(
    val functionName: String,
    val path: CommandPath,
    val params: List<ParamSpec>,
    val returnKType: KType,
    val returnSerializer: KSerializer<Any?>?, // null means print raw String or nothing
    val help: String?,
    val examples: List<String>,
)

@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
internal fun describeInterface(kClass: KClass<*>): List<MethodSpec> {
    val list = mutableListOf<MethodSpec>()

    for (fn in kClass.members.filterIsInstance<kotlin.reflect.KFunction<*>>()) {
        if (!fn.isAbstract) continue
        val cmd = fn.annotations.filterIsInstance<Cli.Command>().firstOrNull() ?: continue
        val fnHelp = fn.annotations.filterIsInstance<Cli.Help>().firstOrNull()?.text
        val examples =
            fn.annotations
                .filterIsInstance<Cli.Example>()
                .map { it.text }
                // Defensive: avoid accidental duplicates in bytecode-weird cases
                .distinct()

        val specs = mutableListOf<ParamSpec>()
        var stdinSeen = false
        for (p in fn.valueParameters) {
            val pos = p.annotations.filterIsInstance<Cli.Positional>().firstOrNull()
            val stdinJson = p.annotations.filterIsInstance<Cli.StdinJson>().firstOrNull()
            val stdinText = p.annotations.filterIsInstance<Cli.StdinText>().firstOrNull()
            val stdinBytes = p.annotations.filterIsInstance<Cli.StdinBytes>().firstOrNull()
            val f = p.annotations.filterIsInstance<Cli.Flag>().firstOrNull()
            val o = p.annotations.filterIsInstance<Cli.Option>().firstOrNull()
            val pHelp = p.annotations.filterIsInstance<Cli.Help>().firstOrNull()?.text

            val erasure = p.type.jvmErasure
            val repeatKind =
                when {
                    erasure == List::class ||
                        erasure == MutableList::class ||
                        erasure == Collection::class -> RepeatKind.LIST
                    erasure == Set::class || erasure == MutableSet::class -> RepeatKind.SET
                    erasure == Array<Any>::class || erasure == Array::class -> RepeatKind.ARRAY
                    else -> RepeatKind.NONE
                }
            val elemK: KClass<*>? =
                when (repeatKind) {
                    RepeatKind.LIST,
                    RepeatKind.SET,
                    RepeatKind.ARRAY -> p.type.arguments.firstOrNull()?.type?.jvmErasure
                    else -> null
                }

            // "required" means: non-null, not optional, and not repeatable;
            // flags are never "required" in UX terms (they default to whatever impl decides).
            val isRequiredOption =
                (o != null) &&
                    !p.isOptional &&
                    !p.type.isMarkedNullable &&
                    repeatKind == RepeatKind.NONE
            val isRequiredPos = (pos != null) && !p.isOptional && !p.type.isMarkedNullable

            when {
                stdinJson != null || stdinText != null || stdinBytes != null -> {
                    require(!stdinSeen) {
                        "Only one @Stdin* parameter allowed for ${kClass.simpleName}::${fn.name}"
                    }
                    stdinSeen = true
                    val kind =
                        when {
                            stdinJson != null -> ParamKind.STDIN_JSON
                            stdinText != null -> ParamKind.STDIN_TEXT
                            else -> ParamKind.STDIN_BYTES
                        }
                    specs +=
                        ParamSpec(
                            kParam = p,
                            kind = kind,
                            long = "",
                            short = null,
                            position = null,
                            negatable = false,
                            kType = p.type,
                            isList = false,
                            elemKClass = null,
                            repeatKind = RepeatKind.NONE,
                            help = pHelp,
                            required = false,
                        )
                }
                pos != null -> {
                    specs +=
                        ParamSpec(
                            kParam = p,
                            kind = ParamKind.POSITIONAL,
                            long = "",
                            short = null,
                            position = pos.index,
                            negatable = false,
                            kType = p.type,
                            isList = repeatKind != RepeatKind.NONE,
                            elemKClass = elemK,
                            repeatKind = repeatKind,
                            help = pHelp,
                            required = isRequiredPos,
                        )
                }
                f != null || (erasure == Boolean::class) -> {
                    val long = (f?.name?.takeIf { it.isNotEmpty() } ?: camelToKebab(p.name!!))
                    val short = f?.short?.takeIf { it != '\u0000' }
                    val neg = f?.negatable ?: true
                    specs +=
                        ParamSpec(
                            kParam = p,
                            kind = ParamKind.FLAG,
                            long = long,
                            short = short,
                            position = null,
                            negatable = neg,
                            kType = p.type,
                            isList = false,
                            elemKClass = null,
                            repeatKind = RepeatKind.NONE,
                            help = pHelp,
                            required = false,
                        )
                }
                else -> {
                    val long = (o?.name?.takeIf { it.isNotEmpty() } ?: camelToKebab(p.name!!))
                    val short = o?.short?.takeIf { it != '\u0000' }
                    specs +=
                        ParamSpec(
                            kParam = p,
                            kind = ParamKind.OPTION,
                            long = long,
                            short = short,
                            position = null,
                            negatable = false,
                            kType = p.type,
                            isList = (repeatKind != RepeatKind.NONE),
                            elemKClass = elemK,
                            repeatKind = repeatKind,
                            help = pHelp,
                            required = isRequiredOption,
                        )
                }
            }
        }

        val retType = fn.returnType
        val retSer: KSerializer<Any?>? =
            if (retType != Unit::class.createType()) {
                @Suppress("UNCHECKED_CAST") serializerOrNull(retType)
            } else {
                null
            }

        list +=
            MethodSpec(
                fn.name,
                CommandPath.parse(cmd.path),
                specs.sortedWith(compareBy({ it.kind.ordinal }, { it.position ?: Int.MAX_VALUE })),
                retType,
                retSer,
                help = fnHelp,
                examples = examples,
            )
    }
    return list
}

internal fun isEnum(k: KClass<*>?): Boolean = k?.java?.isEnum == true

@Suppress("UNCHECKED_CAST")
internal fun parseScalar(k: KClass<*>, raw: String): Any =
    when (k) {
        String::class -> raw
        Int::class -> raw.toInt()
        Long::class -> raw.toLong()
        Double::class -> raw.toDouble()
        Float::class -> raw.toFloat()
        Boolean::class ->
            when (raw.lowercase()) {
                "true",
                "1",
                "yes",
                "y",
                "on" -> true
                "false",
                "0",
                "no",
                "n",
                "off" -> false
                else -> throw IllegalArgumentException("Invalid boolean: $raw")
            }
        File::class -> File(raw)
        java.nio.file.Path::class -> Paths.get(raw)
        URI::class -> URI(raw)
        URL::class -> URL(raw)
        Duration::class -> parseDurationBestEffort(raw)
        else ->
            if (isEnum(k)) {
                val constants = k.java.enumConstants as Array<Enum<*>>
                val rawNorm = raw.lowercase().replace('_', '-')
                constants.firstOrNull { e ->
                    val name = e.name
                    name.equals(raw, ignoreCase = true) ||
                        camelToKebab(name).equals(raw, ignoreCase = true) ||
                        name.lowercase().replace('_', '-') == rawNorm
                }
                    ?: throw IllegalArgumentException(
                        "Invalid enum value '$raw'; valid: ${constants.joinToString { it.name }}"
                    )
            } else {
                throw IllegalArgumentException("Unsupported scalar type: $k")
            }
    }

private fun parseDurationBestEffort(s: String): Duration {
    // ISO-8601 first
    runCatching {
        return Duration.parse(s)
    }
    val m =
        Regex("""^(\d+)(ms|s|m|h)$""", RegexOption.IGNORE_CASE).matchEntire(s.trim())
            ?: throw IllegalArgumentException("Invalid duration: $s")
    val v = m.groupValues[1].toLong()
    return when (m.groupValues[2].lowercase()) {
        "ms" -> Duration.ofMillis(v)
        "s" -> Duration.ofSeconds(v)
        "m" -> Duration.ofMinutes(v)
        "h" -> Duration.ofHours(v)
        else -> error("unreachable")
    }
}
