package fastapi.ffi.python

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NoPythonInstallationFoundException : Exception()

fun runPython(code: String, interpreter: PythonInstallation? = null): String {
    var interpreter = interpreter

    if (interpreter == null) {
        val installations = PythonInstallation.findUsingPath()
        if (installations.isEmpty()) {
            throw NoPythonInstallationFoundException()
        }
        interpreter = installations.maxBy { it.version }!!
    }

    if (interpreter == null) {
        throw NoPythonInstallationFoundException()
    }

    val process = ProcessBuilder(interpreter.path.absolutePath, "-c", code).start()

    val output = process.inputStream.bufferedReader().readText()
    val error = process.errorStream.bufferedReader().readText()

    val exitCode = process.waitFor()
    if (exitCode != 0) {
        throw Exception("Python exited with code $exitCode: $error")
    }
    return output
}
