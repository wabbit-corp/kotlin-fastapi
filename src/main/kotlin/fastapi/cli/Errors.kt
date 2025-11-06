package fastapi.cli

/**
 * Typed CLI errors; can be aggregated. Keep textual payloads lean and render final messages in one
 * place to keep UX consistent.
 */
sealed interface CliError {
    // Discovery / selection errors
    object NoCommands : CliError

    data class Usage(val doc: String) : CliError

    data class AmbiguousCommand(val prefix: List<String>, val candidates: List<String>) : CliError

    // Parse-time errors
    data class ParseErrors(val errors: List<ParseError>) : CliError

    // Invocation/runtime failure
    data class InvocationFailed(val message: String, val cause: Throwable? = null) : CliError
}

sealed interface ParseError {
    data class UnknownLong(val name: String, val suggestions: List<String>) : ParseError

    data class UnknownShort(val short: Char, val suggestions: List<Char>) : ParseError

    data class OptionNeedsValue(val name: String) : ParseError

    data class InvalidValue(val name: String, val raw: String, val reason: String) : ParseError

    data class MissingPositionals(val needed: List<String>) : ParseError

    data class MissingRequiredOptions(val names: List<String>) : ParseError
}

/**
 * Exit code policy is centralized here. This preserves legacy semantics (1 vs 2) while making it
 * trivial to change later.
 */
object ExitCodes {
    fun code(e: CliError): Int =
        when (e) {
            is CliError.Usage -> 2
            is CliError.NoCommands -> 2
            is CliError.AmbiguousCommand -> 2
            is CliError.ParseErrors -> 1
            is CliError.InvocationFailed -> 1
        }
}

/** Render user-facing error text from typed errors (and collections thereof). */
fun renderCliError(err: CliError): String =
    when (err) {
        is CliError.NoCommands -> "No @Command methods found"
        is CliError.Usage -> err.doc
        is CliError.AmbiguousCommand ->
            "Ambiguous command: ${err.prefix.joinToString(" ")} matches ${err.candidates}"
        is CliError.InvocationFailed -> "error: ${err.message}"
        is CliError.ParseErrors -> renderParseErrors(err.errors)
    }

private fun renderParseErrors(errors: List<ParseError>): String {
    fun suggestSuffix(ss: List<String>, prefix: String) =
        if (ss.isEmpty()) "" else " Did you mean ${ss.joinToString(" or ") { "$prefix$it" }}?"

    fun suggestShortSuffix(cs: List<Char>) =
        if (cs.isEmpty()) "" else " Did you mean ${cs.joinToString(" or ") { "-$it" }}?"

    // Preserve legacy single-error messaging for compatibility
    if (errors.size == 1) {
        return when (val e = errors.first()) {
            is ParseError.UnknownLong ->
                "Unknown option --${e.name}.${suggestSuffix(e.suggestions, "--")}".trim()
            is ParseError.UnknownShort ->
                "Unknown short option -${e.short}.${suggestShortSuffix(e.suggestions)}".trim()
            is ParseError.OptionNeedsValue -> "Option ${e.name} requires a value"
            is ParseError.InvalidValue -> "Invalid value for ${e.name}: '${e.raw}' (${e.reason})"
            is ParseError.MissingPositionals ->
                "Missing positional arguments. Needed: ${e.needed.joinToString(" ")}\nUse --help for usage."
            is ParseError.MissingRequiredOptions ->
                "Missing required option(s):\n  ${e.names.joinToString("\\n  ")}\nUse --help for full details."
        }
    }

    // Multiple issues: list them cleanly.
    val lines =
        errors.map { e ->
            when (e) {
                is ParseError.UnknownLong ->
                    "- Unknown option --${e.name}.${suggestSuffix(e.suggestions, "--")}".trim()
                is ParseError.UnknownShort ->
                    "- Unknown short option -${e.short}.${suggestShortSuffix(e.suggestions)}".trim()
                is ParseError.OptionNeedsValue -> "- Option ${e.name} requires a value"
                is ParseError.InvalidValue ->
                    "- Invalid value for ${e.name}: '${e.raw}' (${e.reason})"
                is ParseError.MissingPositionals ->
                    "- Missing positional arguments. Needed: ${e.needed.joinToString(" ")}"
                is ParseError.MissingRequiredOptions ->
                    "- Missing required option(s): ${e.names.joinToString(", ")}"
            }
        }
    val footer =
        if (
            errors.any {
                it is ParseError.MissingPositionals || it is ParseError.MissingRequiredOptions
            }
        ) {
            "\nUse --help for details."
        } else {
            ""
        }
    return "Found ${errors.size} problems:\n" + lines.joinToString("\n") + footer
}
