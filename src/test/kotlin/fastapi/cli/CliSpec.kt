package fastapi.cli

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Coverage goals:
 * - Long/short flags, negation (--no-*)
 * - Short flag clustering (-abc)
 * - Options with "=v" and as split tokens
 * - Repeatable options (List<T>)
 * - Positionals and "--"
 * - Enum parsing (FOO_BAR <- "foo-bar")
 * - JSON return
 * - Client builds argv correctly (Unix; skipped on Windows)
 */
private class TestExit(val code: Int) : RuntimeException("exit $code")

private fun runMainExpectExit(
    impls: List<Pair<Any, kotlin.reflect.KClass<*>>>,
    args: Array<String>
): Triple<Int, String, String> {
    val outBuf = ByteArrayOutputStream()
    val errBuf = ByteArrayOutputStream()
    val out = PrintStream(outBuf, true, "UTF-8")
    val err = PrintStream(errBuf, true, "UTF-8")
    val exit: (Int) -> Nothing = { code -> throw TestExit(code) }

    val code = try {
        unsafeCliMain(impls, args, exit = exit, out = out, err = err)
        // If we get here, the CLI didn't exit; treat as success (code 0)
        0
    } catch (e: TestExit) {
        e.code
    }
    return Triple(code, outBuf.toString("UTF-8"), errBuf.toString("UTF-8"))
}

// --- Demo interface & impl used by tests ---

enum class Mode { FAST_PATH, SAFE_MODE }

@Serializable
data class Report(val sum: Int, val tags: List<String>)

interface DemoCli {
    @Cli.Command("sum")
    fun sum(@Cli.Option("a") a: Int, @Cli.Option("b") b: Int): Int

    @Cli.Command("cp")
    fun cp(@Cli.Positional(0) src: String, @Cli.Positional(1) dest: String): String

    @Cli.Command("mode")
    fun mode(@Cli.Option("plan") plan: Mode): String
}

class DemoImpl : DemoCli {
    override fun sum(a: Int, b: Int): Int = a + b
    override fun cp(src: String, dest: String): String = "$src->$dest"
    override fun mode(plan: Mode): String = plan.name
}

class CliSpec {

    @Test fun top_level_help_when_no_command_selected() {
        val (code, _out, err) = runMainExpectExit(listOf(DemoImpl() to DemoCli::class), arrayOf())
        assertEquals(2, code)
        assertTrue(err.contains("Available commands:"), "expected help in stderr:\n$err")
        assertTrue(err.contains("sum"), "missing 'sum' in help:\n$err")
    }

    @Test fun command_help_with_double_dash_help() {
        val (code, _out, err) = runMainExpectExit(listOf(DemoImpl() to DemoCli::class), arrayOf("sum", "--help"))
        assertEquals(1, code)
        assertTrue(err.trim().startsWith("Usage:\n"), "expected usage line, got:\n$err")
    }

    @Test fun unknown_long_option_reports_error_and_exit_1() {
        val (code, _out, err) = runMainExpectExit(listOf(DemoImpl() to DemoCli::class), arrayOf("cp", "--wat", "src", "dst"))
        assertEquals(1, code)
        assertTrue(err.contains("Unknown option --wat"), "unexpected stderr:\n$err")
    }

    @Test fun unknown_short_option_reports_error_and_exit_1() {
        val (code, _out, err) = runMainExpectExit(listOf(DemoImpl() to DemoCli::class), arrayOf("cp", "-z", "src", "dst"))
        assertEquals(1, code)
        assertTrue(err.contains("Unknown short option -z"), "unexpected stderr:\n$err")
    }

    @Test fun missing_positional_reports_error_and_exit_1() {
        val (code, _out, err) = runMainExpectExit(listOf(DemoImpl() to DemoCli::class), arrayOf("cp", "only-src"))
        assertEquals(1, code)
        assertTrue(err.contains("Missing positional arguments"), "unexpected stderr:\n$err")
    }

    @Test fun option_without_value_reports_error_and_exit_1() {
        val (code, _out, err) = runMainExpectExit(listOf(DemoImpl() to DemoCli::class), arrayOf("sum", "--a"))
        assertEquals(1, code)
        assertTrue(err.contains("Option --a requires a value"), "unexpected stderr:\n$err")
    }

    interface D1 { @Cli.Command("dup go") fun one(): String }
    interface D2 { @Cli.Command("dup go") fun two(): String }
    @Test fun ambiguous_subcommand_is_detected_and_exit_2() {

        class Impl1 : D1 { override fun one() = "one" }
        class Impl2 : D2 { override fun two() = "two" }

        val (code, _out, err) = runMainExpectExit(
            listOf(Impl1() to D1::class, Impl2() to D2::class),
            arrayOf("dup", "go")
        )
        assertEquals(2, code)
        assertTrue(err.contains("Ambiguous command"), "unexpected stderr:\n$err")
    }

    interface Empty { fun nothing(): Unit } // no @Command
    class E : Empty { override fun nothing() {} }
    @Test fun no_command_methods_found_exit_2() {
        val (code, _out, err) = runMainExpectExit(listOf(E() to Empty::class), arrayOf("--anything"))
        assertEquals(2, code)
        assertTrue(err.contains("No @Command methods found"), "unexpected stderr:\n$err")
    }

    // ---------- Test types (unique names to avoid clashes with other test files) ----------

    enum class PMode { FAST_PATH, SAFE_MODE }

    @Serializable
    data class PReport(val sum: Int, val tags: List<String>)

    interface PDemoCli {
        @Cli.Command("flags")
        fun flags(
            @Cli.Flag("dry-run", short = 'n') dryRun: Boolean = false,
            @Cli.Flag("verbose", short = 'v') verbose: Boolean = false,
            @Cli.Flag("color") color: Boolean = true
        ): String

        @Cli.Command("sum")
        fun sum(
            @Cli.Option("a", short = 'a') a: Int,
            @Cli.Option("b", short = 'b') b: Int,
            @Cli.Flag("abs") abs: Boolean = false
        ): Int

        @Cli.Command("tags")
        fun tags(@Cli.Option("tag", short = 't') tags: List<String> = emptyList()): String

        @Cli.Command("cp")
        fun cp(@Cli.Positional(0) src: String, @Cli.Positional(1) dest: String): String

        @Cli.Command("cluster")
        fun cluster(
            @Cli.Flag("alpha", short = 'a') a: Boolean = false,
            @Cli.Flag("beta", short = 'b') b: Boolean = false,
            @Cli.Flag("charlie", short = 'c') c: Boolean = false
        ): String

        @Cli.Command("mode")
        fun mode(@Cli.Option("plan") plan: PMode): String

        @Cli.Command("json report")
        fun jsonReport(
            @Cli.Option("a") a: Int,
            @Cli.Option("tag") tags: List<String> = emptyList()
        ): PReport
    }

    class PDemoImpl : PDemoCli {
        override fun flags(dryRun: Boolean, verbose: Boolean, color: Boolean): String =
            "dry=$dryRun,verb=$verbose,color=$color"

        override fun sum(a: Int, b: Int, abs: Boolean): Int =
            if (abs) kotlin.math.abs(a + b) else a + b

        override fun tags(tags: List<String>): String = tags.joinToString("|")

        override fun cp(src: String, dest: String): String = "$src->$dest"

        override fun cluster(a: Boolean, b: Boolean, c: Boolean): String = "a=$a,b=$b,c=$c"

        override fun mode(plan: PMode): String = plan.name

        override fun jsonReport(a: Int, tags: List<String>): PReport = PReport(sum = a + tags.size, tags = tags)
    }

    // ---------- Harness helpers (success path: must not call exit) ----------

    private class UnexpectedExit(code: Int) : RuntimeException("exit($code) called on success path")

    private fun runMainOk(
        impls: List<Pair<Any, kotlin.reflect.KClass<*>>>,
        args: Array<String>
    ): Pair<String, String> {
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val out = PrintStream(outBuf, true, "UTF-8")
        val err = PrintStream(errBuf, true, "UTF-8")
        val exit: (Int) -> Nothing = { code -> throw UnexpectedExit(code) }

        try {
            unsafeCliMain(impls, args, exit = exit, out = out, err = err)
        } catch (e: UnexpectedExit) {
            fail("CLI attempted to exit(${e.message}). This is a success test.")
        }
        return outBuf.toString(StandardCharsets.UTF_8) to errBuf.toString(StandardCharsets.UTF_8)
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    private fun createUnixEchoWrapper(): Path {
        // Bash script that prints each arg on its own line
        val tmp = Files.createTempFile("echo-argv", "")
        val content = """
            |#!/usr/bin/env bash
            |printf '%s\n' "$@"
        """.trimMargin()
        Files.writeString(tmp, content)
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwxr-xr-x"))
        } catch (_: UnsupportedOperationException) {
            // Not POSIX; test will be skipped.
        }
        return tmp
    }

    // ---------- Positive tests ----------

    @Test fun defaults_no_flags() {
        val (out, err) = runMainOk(listOf(PDemoImpl() to PDemoCli::class), arrayOf("flags"))
        assertEquals("dry=false,verb=false,color=true\n", out)
        assertEquals("", err)
    }

    @Test fun flags_presence_and_negation() {
        val (out, err) = runMainOk(listOf(PDemoImpl() to PDemoCli::class),
            arrayOf("flags", "--dry-run", "-v", "--no-color"))
        assertEquals("dry=true,verb=true,color=false\n", out)
        assertEquals("", err)
    }

    @Test fun short_flag_clustering() {
        val (out, _) = runMainOk(listOf(PDemoImpl() to PDemoCli::class),
            arrayOf("cluster", "-abc"))
        assertEquals("a=true,b=true,c=true\n", out)
    }

    @Test fun options_equals_and_split_and_abs_flag() {
        val (out1, _) = runMainOk(listOf(PDemoImpl() to PDemoCli::class),
            arrayOf("sum", "--a=40", "--b", "2"))
        assertEquals("42\n", out1)

        val (out2, _) = runMainOk(listOf(PDemoImpl() to PDemoCli::class),
            arrayOf("sum", "-a", "40", "-b", "2", "--abs"))
        assertEquals("42\n", out2)
    }

    @Test fun repeatable_list_options() {
        val (out, _) = runMainOk(listOf(PDemoImpl() to PDemoCli::class),
            arrayOf("tags", "--tag=a", "--tag=b", "-t", "c"))
        assertEquals("a|b|c\n", out)
    }

    @Test fun positionals_and_double_dash_stop_parsing() {
        val (out, _) = runMainOk(listOf(PDemoImpl() to PDemoCli::class),
            arrayOf("cp", "--", "--not-an-option", "dst"))
        assertEquals("--not-an-option->dst\n", out)
    }

    @Test fun enum_kebab_case() {
        val (out, _) = runMainOk(listOf(PDemoImpl() to PDemoCli::class),
            arrayOf("mode", "--plan", "fast-path"))
        assertEquals("FAST_PATH\n", out)
    }

    @Test fun json_return_is_valid_and_decodes() {
        val (out, _) = runMainOk(listOf(PDemoImpl() to PDemoCli::class),
            arrayOf("json", "report", "--a=40", "--tag", "x", "--tag", "y"))
        val decoded = Json.decodeFromString(PReport.serializer(), out.trim())
        assertEquals(42, decoded.sum)
        assertEquals(listOf("x", "y"), decoded.tags)
    }

    interface EmitConfigCli {
        @Cli.Command("config update")
        fun update(
            @Cli.Flag("global", short = 'g') global: Boolean = false,
            @Cli.Option("timeout", short = 't') timeout: Int? = null,
            @Cli.Positional(0) key: String,
            @Cli.Positional(1) value: String
        ): String
    }

    @Test fun client_emits_expected_argv_on_unix() {
        if (isWindows()) return // Keep it simple.

        // Tiny wrapper that prints argv, one per line
        val echoExec = createUnixEchoWrapper().toString()

        val client = cliClient<EmitConfigCli>(echoExec)
        val lines = client.update(global = true, timeout = 5, key = "foo", value = "bar")
            .trim()
            .split('\n')

        assertEquals(listOf("config", "update", "--global", "--timeout=5", "foo", "bar"), lines)
    }

    interface SleepCli { @Cli.Command("nap") suspend fun nap(@Cli.Option("seconds") seconds: Int = 10) }

    private fun createUnixNapWrapper(): Path {
        val tmp = Files.createTempFile("napper", "")
        val content = """
        |#!/usr/bin/env bash
        |# Ignore args; just sleep long enough to notice cancellation.
        |sleep 30
    """.trimMargin()
        Files.writeString(tmp, content)
        try { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwxr-xr-x")) } catch (_: UnsupportedOperationException) {}
        return tmp
    }

    @Test fun suspend_call_is_cancellable_and_kills_child() = runBlocking {
        if (isWindows()) return@runBlocking
        val napExec = createUnixNapWrapper().toString()
        val client = cliClient<SleepCli>(napExec)

        val job = launch { client.nap(seconds = 30) } // would hang without cancellation support
        delay(200) // give it time to start
        println("cancelling...")
        val start = System.nanoTime()
        job.cancelAndJoin()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs < 2_000, "cancellation should be quick; took ${elapsedMs}ms")
    }


    // ---------- Help/Example demo types ----------
    enum class HRole { USER, ADMIN }

    interface HelpedCli {
        @Cli.Command("user add")
        @Cli.Help("Create a user account.\nSecond line should still appear in detailed help.")
        @Cli.Example("user add --name=Neo --role=ADMIN 42")
        fun add(
            @Cli.Option("name", short = 'n') @Cli.Help("User's display name.") name: String,
            @Cli.Option("role", short = 'r') @Cli.Help("Role of the user.") role: HRole,
            @Cli.Positional(0) @Cli.Help("The numeric id.") id: Int,
            @Cli.Flag("notify", short = 'N') @Cli.Help("Send a welcome email.") notify: Boolean = false
        ): String
    }

    class HelpedImpl : HelpedCli {
        override fun add(name: String, role: HRole, id: Int, notify: Boolean): String =
            "ok:$name:$role:$id:$notify"
    }

    // ---------- New behavior tests ----------

    @Test fun top_level_help_includes_command_help_first_line() {
        val (code, _out, err) = runMainExpectExit(listOf(HelpedImpl() to HelpedCli::class), arrayOf())
        assertEquals(2, code)
        assertTrue(err.contains("Available commands:"), "expected help in stderr:\n$err")
        assertTrue(err.contains("user add - Create a user account."), "missing brief help on top-level:\n$err")
    }

    @Test fun command_help_shows_help_required_enums_and_examples() {
        val (code, _out, err) = runMainExpectExit(listOf(HelpedImpl() to HelpedCli::class), arrayOf("user", "add", "--help"))
        assertEquals(1, code)
        // Function help
        assertTrue(err.contains("Create a user account."), "missing function help:\n$err")
        // Required option + its help
        assertTrue(err.contains("--name"), "missing --name:\n$err")
        assertTrue(err.contains("[required]"), "missing [required] marker:\n$err")
        assertTrue(err.contains("User's display name."), "missing param help for --name:\n$err")
        // Enum values list
        assertTrue(err.contains("--role"), "missing --role:\n$err")
        assertTrue(err.contains("(values: USER|ADMIN)"), "missing enum values for --role:\n$err")
        // Positional with help and required marker
        assertTrue(err.contains("<id"), "missing positional label:\n$err")
        assertTrue(err.contains("The numeric id."), "missing positional help:\n$err")
        assertTrue(err.contains("[required]"), "positionals should show [required]:\n$err")
        // Optional flag
        assertTrue(err.contains("--notify"), "missing optional flag:\n$err")
        assertTrue(err.contains("[optional]"), "missing [optional] marker:\n$err")
        // Examples section
        assertTrue(err.contains("Examples:"), "missing Examples section:\n$err")
        assertTrue(err.contains("user add --name=Neo --role=ADMIN 42"), "missing example body:\n$err")
    }

    @Test fun short_option_equals_form_supported() {
        val (out, _) = runMainOk(listOf(PDemoImpl() to PDemoCli::class),
            arrayOf("sum", "-a=40", "-b=2"))
        assertEquals("42\n", out)
    }

    @Test fun flag_equals_form_supported_boolean() {
        val (out, _) = runMainOk(listOf(PDemoImpl() to PDemoCli::class),
            arrayOf("cluster", "-a=true", "-b=false", "-c=true"))
        assertEquals("a=true,b=false,c=true\n", out)
    }

    @Test fun flag_equals_invalid_value_errors() {
        val (code, _out, err) = runMainExpectExit(listOf(PDemoImpl() to PDemoCli::class),
            arrayOf("cluster", "-a=wat"))
        assertEquals(1, code)
        assertTrue(err.contains("expects true/false"), "expected boolean parse error, got:\n$err")
    }

    @Test fun unknown_long_option_has_suggestion() {
        // Minimal impl to reach parse layer
        val impl = object : EmitConfigCli {
            override fun update(global: Boolean, timeout: Int?, key: String, value: String): String = "ok"
        }
        val (code, _out, err) = runMainExpectExit(
            listOf(impl to EmitConfigCli::class),
            arrayOf("config", "update", "--timout", "5", "foo", "bar")
        )
        assertEquals(1, code)
        assertTrue(err.contains("Unknown option --timout"), "unexpected stderr:\n$err")
        assertTrue(err.contains("Did you mean --timeout"), "missing suggestion:\n$err")
    }

    @Test fun unknown_short_option_has_suggestion() {
        val (code, _out, err) = runMainExpectExit(listOf(PDemoImpl() to PDemoCli::class),
            arrayOf("cluster", "-x"))
        assertEquals(1, code)
        assertTrue(err.contains("Unknown short option -x"), "unexpected stderr:\n$err")
        assertTrue(err.contains("Did you mean -a"), "missing suggestion for -x:\n$err")
    }

    @Test fun missing_required_option_detected_and_reported() {
        val (code, _out, err) = runMainExpectExit(listOf(PDemoImpl() to PDemoCli::class),
            arrayOf("sum", "--a=40"))
        assertEquals(1, code)
        assertTrue(err.contains("Missing required option"), "unexpected stderr:\n$err")
        assertTrue(err.contains("--b"), "should mention missing option --b:\n$err")
    }

    @Test fun missing_positional_error_includes_types_and_help() {
        val (code, _out, err) = runMainExpectExit(listOf(HelpedImpl() to HelpedCli::class),
            arrayOf("user", "add", "--name=Neo", "--role=ADMIN"))

        assertEquals(1, code)
        assertTrue(err.contains("Missing positional arguments"), "unexpected stderr:\n$err")
        assertTrue(err.contains("<id:Int>"), "should include typed positional label:\n$err")
        assertTrue(err.contains("The numeric id."), "should include positional help:\n$err")
    }

    @Test fun enum_values_listed_in_help_for_option() {
        val (code, _out, err) = runMainExpectExit(listOf(PDemoImpl() to PDemoCli::class),
            arrayOf("mode", "--help"))

        assertEquals(1, code)
        assertTrue(err.contains("(values: FAST_PATH|SAFE_MODE)"), "missing enum list in help:\n$err")
    }
}
