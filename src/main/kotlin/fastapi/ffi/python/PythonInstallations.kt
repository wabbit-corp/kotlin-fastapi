package fastapi.ffi.python

import fastapi.ffi.Version
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files

data class PythonInstallation(
    val path: File,
    val version: Version,
    val sitePackageDirs: List<File>
) {
    companion object {
        private fun findPythonExecutable(parent: File): File? {
            val files = parent.listFiles() ?: return null
            for (file in files) {
                if (isPythonExecutable(file)) {
                    return file
                }
            }
            return null
        }

        private fun isPythonExecutableName(name: String): Boolean {
            if (name.lowercase() == "python.exe") return true
            if (name.lowercase() == "python") return true
            if (Regex("python\\d+\\.exe").matches(name)) return true
            if (Regex("python\\d+").matches(name)) return true
            if (Regex("python\\d+\\.\\d+\\.exe").matches(name)) return true
            if (Regex("python\\d+\\.\\d+").matches(name)) return true
            return false
        }

        private fun isPythonExecutable(file: File): Boolean {
            val file = file.canonicalFile

            if (Files.isSymbolicLink(file.toPath())) {
                // Special case: Symbolic link on Unix
                return isPythonExecutableName(file.name.lowercase())
            }

            val executable = file.exists() && file.isFile && file.canExecute()
            if (!executable) return false
            if (isPythonExecutableName(file.name.lowercase()))
                return true

            return false
        }

        fun fromPath(path: String): PythonInstallation {
            // Even though this is technically used from findUsingPath, we can make it a bit more generic.
            if (isPythonExecutable(File(path))) {
                return fromPath(File(path).parent)
            } else if (isPythonExecutable(File(path, "bin/python.exe")) || isPythonExecutable(File(path, "bin/python"))) {
                return fromPath(File(path, "bin").absolutePath)
            }

            val pythonExe = findPythonExecutable(File(path))
            require(pythonExe != null) { "Python executable not found at $path" }

            val versionStr = ProcessBuilder(pythonExe.absolutePath, "--version").start().inputStream.bufferedReader().readLine()

            // Match the version string
            val versionRegex = Regex("Python (\\d+\\.\\d+\\.\\d+)")
            val match = versionRegex.find(versionStr)
            if (match == null) {
                throw Exception("Could not parse version string: $versionStr")
            }
            val versionString = match.groupValues[1]
            val versionParts = versionString.split(".").map { it.toInt() }
            val version = Version(versionParts)

            val sitePackagesJson = ProcessBuilder(pythonExe.absolutePath, "-c", "import site; import json; print(json.dumps(site.getsitepackages()))").start().inputStream.bufferedReader().readLine()
            val sitePackages = Json.decodeFromString<List<String>>(sitePackagesJson)
            return PythonInstallation(pythonExe, version, sitePackages.map { File(it) })
        }

        fun fromExact(file: File): PythonInstallation {
            val versionStr = ProcessBuilder(file.absolutePath, "--version").start().inputStream.bufferedReader().readLine()

            // Match the version string
            val versionRegex = Regex("Python (\\d+\\.\\d+\\.\\d+)")
            val match = versionRegex.find(versionStr)
            if (match == null) {
                throw Exception("Could not parse version string: $versionStr")
            }
            val versionString = match.groupValues[1]
            val versionParts = versionString.split(".").map { it.toInt() }
            val version = Version(versionParts)

            val sitePackagesJson = ProcessBuilder(file.absolutePath, "-c", "import site; import json; print(json.dumps(site.getsitepackages()))").start().inputStream.bufferedReader().readLine()
            val sitePackages = Json.decodeFromString<List<String>>(sitePackagesJson)
            return PythonInstallation(file, version, sitePackages.map { File(it) })
        }

        fun findUsingPath(): List<PythonInstallation> {
            val path = System.getenv("PATH")
            val paths: List<String>

            if (System.getProperty("os.name").contains("Windows")) {
                paths = path.split(";")
            } else {
                paths = path.split(":")
            }

            val installations = mutableListOf<PythonInstallation>()
            for (p in paths) {
                if (!isPythonExecutable(File(p, "python.exe"))
                    && !isPythonExecutable(File(p, "python"))
                    && !isPythonExecutable(File(p, "python3.exe"))
                    && !isPythonExecutable(File(p, "python3"))) continue
                installations.add(fromPath(p))
            }
            return installations
        }
    }
}
