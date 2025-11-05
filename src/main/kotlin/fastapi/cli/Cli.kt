package fastapi.cli

import kotlin.annotation.Repeatable

object Cli {
    /**
     * Usage:
     *   interface Tool {
     *     @Command("config update")
     *     fun update(@Flag("global") global: Boolean = false,
     *                @Option("timeout") timeout: Int? = null,
     *                @Positional(0) key: String,
     *                @Positional(1) value: String): String
     *   }
     */

    @Target(AnnotationTarget.FUNCTION)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class Command(val path: String) // e.g. "config update"

    @Target(AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class Flag(val name: String = "", val short: Char = '\u0000', val negatable: Boolean = true)

    @Target(AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class Option(val name: String = "", val short: Char = '\u0000')

    @Target(AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class Positional(val index: Int)

    @Target(AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class StdinJson // exactly one parameter per method allowed

    @Target(AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class StdinText // reads UTF-8 text from stdin

    @Target(AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class StdinBytes // reads raw bytes from stdin

    @Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class Help(val text: String)

    /**
     * Attach one or more runnable examples to a command. Rendered in --help.
     *
     * Example:
     *   @Example("config update -t 5 foo bar")
     */
    @Target(AnnotationTarget.FUNCTION)
    @Retention(AnnotationRetention.RUNTIME)
    @Repeatable
    annotation class Example(val text: String)
}
