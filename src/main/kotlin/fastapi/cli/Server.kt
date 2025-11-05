package fastapi.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.PrintStream
import kotlin.math.min
import kotlin.system.exitProcess
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.jvmErasure

/**
 * Bind one or more interface implementations to a CLI entrypoint.
 */
inline fun <reified T1: Any> cliMain(impl1: T1, args: Array<String>, json: Json = Json) =
    unsafeCliMain(listOf(impl1 to T1::class), args, json = json)

inline fun <reified T1: Any, reified T2: Any> cliMain(impl1: T1, impl2: T2, args: Array<String>, json: Json = Json) =
    unsafeCliMain(listOf(impl1 to T1::class, impl2 to T2::class), args, json = json)

fun unsafeCliMain(
    impls: List<Pair<Any, KClass<*>>>,
    args: Array<String>,
    exit: (Int) -> Unit = { exitProcess(it) },
    out: PrintStream = System.out,
    err: PrintStream = System.err,
    json: Json = Json
) {
    val allSpecs = impls.flatMap { (_, k) -> describeInterface(k) }
    if (allSpecs.isEmpty()) {
        val e = CliError.NoCommands
        err.println(renderCliError(e))
        return exit(ExitCodes.code(e))
    }

    // Make a path trie for longest-prefix dispatch
    data class Node(
        val children: MutableMap<String, Node> = LinkedHashMap(),
        val targets: MutableList<Pair<Pair<Any, KClass<*>>, MethodSpec>> = mutableListOf()
    )
    val root = Node()
    fun insert(host: Pair<Any, KClass<*>>, m: MethodSpec) {
        var n = root
        for (seg in m.path.segments) {
            n = n.children.computeIfAbsent(seg) { Node() }
        }
        n.targets += (host to m)
    }
    for (pair in impls) {
        val specs = describeInterface(pair.second)
        for (m in specs) insert(pair, m)
    }

    // Split args into (prefix command tokens, remainder)
    var i = 0
    var node = root
    var picked: List<Pair<Pair<Any, KClass<*>>, MethodSpec>>? = null
    while (i < args.size) {
        val next = node.children[args[i]] ?: break
        node = next
        i++
        if (node.targets.isNotEmpty()) picked = node.targets
    }
    val remainder = args.drop(i)

    val target = when {
        picked == null -> {
            val e = CliError.Usage(buildTopHelp(allSpecs))
            err.println(renderCliError(e))
            return exit(ExitCodes.code(e))
        }
        picked.size > 1 -> {
            val e = CliError.AmbiguousCommand(
                prefix = args.take(i),
                candidates = picked!!.map { it.second.functionName }
            )
            err.println(renderCliError(e))
            return exit(ExitCodes.code(e))
        }
        else -> picked!![0]
    }
    val (host, method) = target
    val impl = host.first
    val kClass = host.second

    try {
        // val callArgs = parseArgsForMethod(method, remainder)
        // Quick path: explicit help request for this command.
        if (remainder.any { it == "--help" || it == "-h" }) {
            val e = CliError.Usage(buildCommandHelp(method))
            err.println(renderCliError(e))
            return exit(ExitCodes.code(e))
        }

        // Parse with aggregation (no prevalidation pass)
        val parsed = parseArgsForMethod(method, remainder)
        if (parsed is ParseOutcome.Err) {
            val e = CliError.ParseErrors(parsed.errors)
            err.println(renderCliError(e))
            return exit(ExitCodes.code(e))
        }
        val callArgs = (parsed as ParseOutcome.Ok).args

        val fn = kClass.memberFunctions.first { it.name == method.functionName }
        fn.isAccessible = true

        val params = LinkedHashMap<kotlin.reflect.KParameter, Any?>()
        fn.instanceParameter?.let { params[it] = impl }

        // map ParameterSpec -> KParameter
        for (spec in method.params) {
            if (callArgs.containsKey(spec.kParam)) params[spec.kParam] = callArgs[spec.kParam]
        }

        // Read stdin if present
        val stdinSpec = method.params.firstOrNull { it.kind == ParamKind.STDIN_JSON || it.kind == ParamKind.STDIN_TEXT || it.kind == ParamKind.STDIN_BYTES }
        if (stdinSpec != null) {
            val bytes = System.`in`.readAllBytes() // JDK 9+
            val value = when (stdinSpec.kind) {
                ParamKind.STDIN_JSON -> {
                    val ser = kotlinx.serialization.serializer(stdinSpec.kType)
                    @Suppress("UNCHECKED_CAST")
                    json.decodeFromString(ser, bytes.toString(Charsets.UTF_8))
                }
                ParamKind.STDIN_TEXT -> bytes.toString(Charsets.UTF_8)
                ParamKind.STDIN_BYTES -> bytes
                else -> error("unexpected stdin kind")
            }
            params[stdinSpec.kParam] = value
        }

        val result =
            if (fn.isSuspend) {
                runBlocking { fn.callSuspendBy(params) }
            } else {
                fn.callBy(params)
            }

        // Print result
        when {
            method.returnKType.jvmErasure == String::class -> out.println(result as String)
            method.returnSerializer != null -> out.println(json.encodeToString(method.returnSerializer, result))
            else -> { /* Unit or non-printable */ }
        }
    } catch (u: CliUsage) {
        val e = CliError.Usage(u.message ?: "")
        err.println(renderCliError(e))
        return exit(ExitCodes.code(e))
    } catch (e: Throwable) {
        val ce = CliError.InvocationFailed(e.message ?: e.toString(), e)
        err.println(renderCliError(ce))
        return exit(ExitCodes.code(ce))
    }
}

private fun buildTopHelp(specs: List<MethodSpec>): String {
    val lines = mutableListOf<String>()
    lines += "Available commands:"
    val seen = LinkedHashSet<String>()
    for (m in specs.sortedBy { it.path.segments.size }) {
        val p = m.path.segments.joinToString(" ")
        if (seen.add(p)) {
            val desc = m.help?.takeIf { it.isNotBlank() }?.let { " - ${it.lineSequence().first()}" } ?: ""
            lines += "  $p$desc"
        }
    }
    lines += "\nUse: <cmd> ... --help for command details"
    return lines.joinToString("\n")
}

class CliUsage(message: String) : RuntimeException(message)

// --- Aggregating parse result ---
private sealed interface ParseOutcome {
    data class Ok(val args: Map<KParameter, Any?>) : ParseOutcome
    data class Err(val errors: List<ParseError>) : ParseOutcome
}

private fun enumValues(k: KClass<*>?): List<String> =
    if (k != null && isEnum(k)) {
        @Suppress("UNCHECKED_CAST")
        (k.java.enumConstants as Array<Enum<*>>).map { it.name }
    } else emptyList()

private fun buildCommandHelp(m: MethodSpec): String = buildString {
    appendLine("Usage:")
    append("  ")
    append((m.path.segments + renderUsage(m)).joinToString(" "))
    appendLine()
    val fnHelp = m // function-level help (if you want, you can store it in MethodSpec)
    if (!m.help.isNullOrBlank()) {
        appendLine()
        appendLine(m.help!!)
    }
    val params = m.params
    if (params.any { it.kind == ParamKind.FLAG || it.kind == ParamKind.OPTION }) {
        appendLine()
        appendLine("Options:")
        for (p in params.filter { it.kind == ParamKind.FLAG || it.kind == ParamKind.OPTION }) {
            val names = buildString {
                if (p.short != null) append("-${p.short}, ")
                append("--${p.long}")
                if (p.kind == ParamKind.FLAG && p.negatable) append(" / --no-${p.long}")
            }
            val type = when {
                p.kind == ParamKind.FLAG -> "flag"
                p.repeatKind == RepeatKind.LIST -> "list<${typeName(p.elemKClass)}>"
                p.repeatKind == RepeatKind.SET -> "set<${typeName(p.elemKClass)}>"
                p.repeatKind == RepeatKind.ARRAY -> "array<${typeName(p.elemKClass)}>"
                else -> typeName((p.kType.classifier as? KClass<*>))
            }
            val valuesHint = enumValues(if (p.repeatKind == RepeatKind.NONE) (p.kType.classifier as? KClass<*>) else p.elemKClass)
                .takeIf { it.isNotEmpty() }?.joinToString("|")
                ?.let { "  (values: $it)" } ?: ""
            val reqHint = if (p.kind == ParamKind.OPTION && p.required) " [required]" else " [optional]"
            val defHint = if (p.kind == ParamKind.FLAG) {
                // We cannot reflect actual default for interface methods; be explicit.
                " (default: implementation-defined)"
            } else if (p.kind == ParamKind.OPTION && p.kParam.isOptional) {
                " (default: implementation-defined)"
            } else ""
            appendLine("  $names : $type$reqHint$defHint$valuesHint")
            if (!p.help.isNullOrBlank()) appendLine("      ${p.help}")
        }
    }
    val pos = m.params.filter { it.kind == ParamKind.POSITIONAL }.sortedBy { it.position!! }
    if (pos.isNotEmpty()) {
        appendLine()
        appendLine("Positionals:")
        for (p in pos) {
            val tn = when (p.repeatKind) {
                RepeatKind.LIST -> "list<${typeName(p.elemKClass)}>"
                RepeatKind.SET -> "set<${typeName(p.elemKClass)}>"
                RepeatKind.ARRAY -> "array<${typeName(p.elemKClass)}>"
                else -> typeName((p.kType.classifier as? KClass<*>))
            }
            val label = p.long.ifEmpty { p.kParam.name ?: "arg" }
            val valuesHint = enumValues(if (p.repeatKind == RepeatKind.NONE) (p.kType.classifier as? KClass<*>) else p.elemKClass)
                .takeIf { it.isNotEmpty() }?.joinToString("|")
                ?.let { "  (values: $it)" } ?: ""
            val reqHint = if (p.required) " [required]" else " [optional]"
            appendLine("  <$label> : $tn$reqHint$valuesHint")
            if (!p.help.isNullOrBlank()) appendLine("      ${p.help}")
        }
    }
    if (m.examples.isNotEmpty()) {
        appendLine()
        appendLine("Examples:")
        m.examples.forEach { ex -> appendLine("  $ex") }
    }
}

private fun typeName(k: KClass<*>?): String = when (k) {
    null -> "Any"
    else -> k.simpleName ?: k.toString()
}

// --- Suggestion helpers ---
private fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    val dp = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        var prev = i - 1
        dp[0] = i
        for (j in 1..b.length) {
            val tmp = dp[j]
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            dp[j] = min(min(dp[j] + 1, dp[j - 1] + 1), prev + cost)
            prev = tmp
        }
    }
    return dp[b.length]
}

private fun suggest(token: String, candidates: Collection<String>, prefix: String): String? {
    if (candidates.isEmpty()) return null
    val scored = candidates.map { it to levenshtein(token, it) }.sortedBy { it.second }
    val best = scored.take(3).filter { it.second <= maxOf(1, token.length / 2 + 1) }.map { "$prefix${it.first}" }
    return if (best.isNotEmpty()) " Did you mean ${best.joinToString(" or ")}?" else null
}


// Similar to `suggest`, but return the suggestions as a list, not a formatted suffix.
private fun topSuggestions(token: String, candidates: Collection<String>): List<String> {
    if (candidates.isEmpty()) return emptyList()
    val scored = candidates.map { it to levenshtein(token, it) }.sortedBy { it.second }
    return scored.take(3).filter { it.second <= maxOf(1, token.length / 2 + 1) }.map { it.first }
}

private fun topSuggestionsShort(ch: Char, candidates: Collection<Char>): List<Char> {
    if (candidates.isEmpty()) return emptyList()
    val scored = candidates.map { it to levenshtein(ch.toString(), it.toString()) }.sortedBy { it.second }
    return scored.take(3).filter { it.second <= 2 }.map { it.first }
}

private fun parseArgsForMethod(m: MethodSpec, argv: List<String>): ParseOutcome {
    val errors = mutableListOf<ParseError>()

    if (argv.any { it == "--help" || it == "-h" }) {
        val usage = buildCommandHelp(m)
        throw CliUsage(usage)
    }

    val byLong = m.params.filter { it.kind == ParamKind.FLAG || it.kind == ParamKind.OPTION }
        .associateBy { it.long }
    val byShort = m.params.mapNotNull { if (it.short != null) it.short to it else null }.toMap()
    val positional = m.params.filter { it.kind == ParamKind.POSITIONAL }.sortedBy { it.position!! }

    val out = LinkedHashMap<kotlin.reflect.KParameter, Any?>()
    val posValues = mutableListOf<String>()

    var idx = 0
    fun take(): String? = if (idx < argv.size) argv[idx++] else null
    fun peek(): String? = if (idx < argv.size) argv[idx] else null

    while (true) {
        val t = take() ?: break
        when {
            t == "--" -> { while (true) { val rest = take() ?: break; posValues += rest } }

            t.startsWith("--") -> {
                val eq = t.indexOf('=')
                val nameRaw = if (eq >= 0) t.substring(2, eq) else t.removePrefix("--")
                val valueRaw = if (eq >= 0) t.substring(eq + 1) else null

                val (neg, name) = if (nameRaw.startsWith("no-")) true to nameRaw.removePrefix("no-") else false to nameRaw

                val spec = byLong[name]
                if (spec == null) {
                    errors += ParseError.UnknownLong(name, topSuggestions(name, byLong.keys))
                    continue
                }

                when (spec.kind) {
                    ParamKind.FLAG -> {
                        if (neg && !spec.negatable) {
                            errors += ParseError.InvalidValue("--${spec.long}", t, "not negatable")
                        } else {
                            out[spec.kParam] = !neg
                        }
                    }
                    ParamKind.OPTION -> {
                        val need = valueRaw ?: run {
                            val n = peek()
                            if (n == null || (n.startsWith("-") && n != "-")) {
                                errors += ParseError.OptionNeedsValue("--$name")
                                null
                            } else take()
                        }
                        if (need != null) assignOptionAcc(out, spec, need, errors, nameForError = "--$name")
                    }
                    else -> error("Unexpected kind for --$name")
                }
            }
            t.startsWith("-") && t != "-" -> {
                val cluster = t.removePrefix("-")
                val eqPos = cluster.indexOf('=')
                if (eqPos >= 0) {
                    // Support -o=VAL (single short option with equals)
                    val key = cluster.substring(0, eqPos)
                    val value = cluster.substring(eqPos + 1)
                    if (key.length != 1) {
                        errors += ParseError.InvalidValue("-$key", t, "invalid short option form; only -o=VAL is supported")
                        continue
                    }
                    val ch = key.first()
                    val spec = byShort[ch]
                    if (spec == null) {
                        errors += ParseError.UnknownShort(ch, topSuggestionsShort(ch, byShort.keys))
                        continue
                    }

                    when (spec.kind) {
                        ParamKind.FLAG -> {
                            // Allow -v=true / -v=false for completeness
                            val b = try { parseScalar(Boolean::class, value) as Boolean } catch (_: Throwable) { null }
                            if (b == null) {
                                errors += ParseError.InvalidValue("-$ch", value, "expects true/false")
                            } else {
                                out[spec.kParam] = b
                            }
                        }
                        ParamKind.OPTION -> assignOptionAcc(out, spec, value, errors, nameForError = "-$ch")
                        else -> error("Unexpected short kind")
                    }
                } else if (cluster.length > 1) {
                    // Clustered flags: -abc
                    cluster.forEach { ch ->
                        val spec = byShort[ch]
                        if (spec == null) {
                            errors += ParseError.UnknownShort(ch, topSuggestionsShort(ch, byShort.keys))
                        } else if (spec.kind != ParamKind.FLAG) {
                            errors += ParseError.InvalidValue("-$ch", "-$cluster", "expects a value; don't group it")
                        } else {
                            out[spec.kParam] = true
                        }
                    }
                } else {
                    val ch = cluster.first()
                    val spec = byShort[ch]
                    if (spec == null) {
                        errors += ParseError.UnknownShort(ch, topSuggestionsShort(ch, byShort.keys))
                        continue
                    }
                    when (spec.kind) {
                        ParamKind.FLAG -> out[spec.kParam] = true
                        ParamKind.OPTION -> {
                            val need = peek()
                            if (need == null || (need.startsWith("-") && need != "-")) {
                                errors += ParseError.OptionNeedsValue("-$ch")
                            } else {
                                take()
                                assignOptionAcc(out, spec, need, errors, nameForError = "-$ch")
                            }
                        }
                        else -> error("Unexpected short kind")
                    }
                }
            }
            else -> posValues += t
        }
    }

    // Assign positionals
    val requiredPosCount = positional.count { !it.kParam.isOptional && !it.kParam.type.isMarkedNullable }
    if (posValues.size < requiredPosCount) {
        val needList = positional.map { p ->
            val label = p.long.ifEmpty { p.kParam.name ?: "arg" }
            val tn = when (p.repeatKind) {
                RepeatKind.LIST -> "list<${typeName(p.elemKClass)}>"
                RepeatKind.SET -> "set<${typeName(p.elemKClass)}>"
                RepeatKind.ARRAY -> "array<${typeName(p.elemKClass)}>"
                else -> typeName((p.kType.classifier as? KClass<*>))
            }
            val help = p.help?.let { " — $it" } ?: ""
            "<$label:$tn>$help"
        }
        errors += ParseError.MissingPositionals(needList)
    }
    positional.forEachIndexed { i, spec ->
        if (i < posValues.size) assignPositionalAcc(out, spec, posValues[i], errors)
    }

    // Detect missing required options
    val missingOpts = m.params.filter {
        it.kind == ParamKind.OPTION && it.required && (out[it.kParam] == null)
    }
    if (missingOpts.isNotEmpty()) {
        val names = missingOpts.map { "--${it.long}" }.sorted()
        errors += ParseError.MissingRequiredOptions(names)
    }

    // Post-convert repeatables into their declared types
    if (errors.isEmpty()) {
        finalizeRepeatables(out, m)
        return ParseOutcome.Ok(out)
    } else {
        return ParseOutcome.Err(errors)
    }
}

private fun assignOption(out: MutableMap<KParameter, Any?>, spec: ParamSpec, raw: String) {
    val cls = (spec.kType.classifier as? KClass<*>)
    if (spec.repeatKind != RepeatKind.NONE) {
        val elem = spec.elemKClass ?: throw IllegalArgumentException("List element type unknown")
        val existing = (out[spec.kParam] as? MutableList<Any>) ?: mutableListOf<Any>().also { out[spec.kParam] = it }
        existing += parseScalar(elem, raw)
    } else {
        val k = cls ?: throw IllegalArgumentException("Unsupported option type")
        out[spec.kParam] = parseScalar(k, raw)
    }
}

private fun assignOptionAcc(
    out: MutableMap<KParameter, Any?>,
    spec: ParamSpec,
    raw: String,
    errors: MutableList<ParseError>,
    nameForError: String = "--${spec.long}"
) {
    try {
        assignOption(out, spec, raw)
    } catch (t: Throwable) {
        val reason = (t.message ?: "invalid")
        errors += ParseError.InvalidValue(nameForError, raw, reason)
    }
}

private fun assignPositional(out: MutableMap<KParameter, Any?>, spec: ParamSpec, raw: String) {
    val cls = (spec.kType.classifier as? KClass<*>)
    when {
        spec.repeatKind != RepeatKind.NONE -> {
            val elem = spec.elemKClass ?: throw IllegalArgumentException("List element type unknown")
            val existing = (out[spec.kParam] as? MutableList<Any>) ?: mutableListOf<Any>().also { out[spec.kParam] = it }
            existing += parseScalar(elem, raw)
        }
        cls != null -> out[spec.kParam] = parseScalar(cls, raw)
        else -> throw IllegalArgumentException("Unsupported positional type")
    }
}

private fun assignPositionalAcc(
    out: MutableMap<KParameter, Any?>,
    spec: ParamSpec,
    raw: String,
    errors: MutableList<ParseError>
) {
    try {
        assignPositional(out, spec, raw)
    } catch (t: Throwable) {
        val label = spec.long.ifEmpty { spec.kParam.name ?: "arg" }
        errors += ParseError.InvalidValue("<$label>", raw, t.message ?: "invalid")
    }
}

private fun finalizeRepeatables(
    out: MutableMap<KParameter, Any?>,
    m: MethodSpec
) {
    for (spec in m.params) {
        if (spec.repeatKind == RepeatKind.NONE) continue
        val tmp = out[spec.kParam] as? MutableList<Any> ?: continue
        val finalVal: Any = when (spec.repeatKind) {
            RepeatKind.LIST -> tmp.toList()
            RepeatKind.SET -> LinkedHashSet(tmp)
            RepeatKind.ARRAY -> {
                val elem = spec.elemKClass ?: throw IllegalArgumentException("Array element type unknown")
                val arr = java.lang.reflect.Array.newInstance(elem.java, tmp.size)
                for (i in tmp.indices) java.lang.reflect.Array.set(arr, i, tmp[i])
                arr
            }
            else -> tmp
        }
        out[spec.kParam] = finalVal
    }
}

private fun renderUsage(m: MethodSpec): List<String> {
    val parts = mutableListOf<String>()
    val opts = m.params.filter { it.kind == ParamKind.FLAG || it.kind == ParamKind.OPTION }
    val pos = m.params.filter { it.kind == ParamKind.POSITIONAL }.sortedBy { it.position!! }
    if (opts.isNotEmpty()) parts += "[options]"
    parts += pos.map { "<${it.long.ifEmpty { it.kParam.name ?: "arg" }}>" }
    return parts
}
