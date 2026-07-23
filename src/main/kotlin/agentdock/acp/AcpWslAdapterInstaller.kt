package agentdock.acp

import agentdock.eel.AcpEelEnvironment
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Installs NPM-distributed adapters natively inside the project's WSL environment via
 * EelApi, so the launch path in AcpAdapterInitializer finds a real Linux install rather
 * than a Windows-side download that a WSL guest can't run.
 *
 * Archive-distributed adapters aren't wired up for WSL yet - see
 * downloadArchiveDistributionLocal for the LOCAL equivalent that would need mirroring.
 */
private val wslAdapterMetadataJson = Json { ignoreUnknownKeys = true }

internal fun isWslAdapterDownloaded(adapterInfo: AcpAdapterConfig.AdapterInfo): Boolean {
    if (adapterInfo.distribution.type != AcpAdapterConfig.DistributionType.NPM) return false
    val eel = AcpEelEnvironment.resolveWslEelApiBlocking() ?: return false
    val adapterRoot = AcpEelEnvironment.adapterDependenciesDir(eel, adapterInfo.id)
    val launchFile = adapterRoot.resolve(wslRelativeLaunchPath(adapterInfo))
    return runCatching { Files.isRegularFile(launchFile) }.getOrDefault(false)
}

internal fun wslInstalledVersion(adapterInfo: AcpAdapterConfig.AdapterInfo): String? {
    if (adapterInfo.distribution.type != AcpAdapterConfig.DistributionType.NPM) return null
    val eel = AcpEelEnvironment.resolveWslEelApiBlocking() ?: return null
    val packageName = adapterInfo.distribution.packageName ?: return null
    val packageJson = AcpEelEnvironment.adapterDependenciesDir(eel, adapterInfo.id)
        .resolve("node_modules").resolve(packageName).resolve("package.json")
    return runCatching {
        wslAdapterMetadataJson.parseToJsonElement(Files.readString(packageJson))
            .jsonObject["version"]?.toString()?.trim('"')
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

internal fun deleteWslAdapterRuntime(adapterInfo: AcpAdapterConfig.AdapterInfo): Boolean {
    val eel = AcpEelEnvironment.resolveWslEelApiBlocking() ?: return false
    val adapterRoot = AcpEelEnvironment.adapterDependenciesDir(eel, adapterInfo.id)
    return runCatching {
        if (Files.exists(adapterRoot)) {
            Files.walk(adapterRoot).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        true
    }.getOrDefault(false)
}

internal suspend fun installNpmAdapterOverWsl(
    project: Project,
    adapterInfo: AcpAdapterConfig.AdapterInfo,
    statusCallback: ((String) -> Unit)? = null,
    cancellation: AcpAdapterInstallCancellation? = null,
    versionOverride: String? = null
): Boolean {
    if (adapterInfo.distribution.type != AcpAdapterConfig.DistributionType.NPM) {
        statusCallback?.invoke("Error: WSL execution currently only supports NPM-distributed adapters")
        return false
    }
    return try {
        cancellation?.throwIfCancelled()
        val eel = AcpEelEnvironment.resolveWslEelApi(project)
        val packageName = adapterInfo.distribution.packageName
            ?: throw IllegalStateException("Adapter '${adapterInfo.id}' missing distribution.packageName in configuration")
        val version = versionOverride?.trim()?.takeIf { it.isNotEmpty() } ?: adapterInfo.distribution.version

        val adapterRoot = AcpEelEnvironment.adapterDependenciesDir(eel, adapterInfo.id)
        Files.createDirectories(adapterRoot)
        Files.writeString(adapterRoot.resolve("package.json"), """{"name":"${adapterInfo.id}-runtime","private":true}""")

        statusCallback?.invoke("Installing $packageName@$version via npm (WSL: ${eel.descriptor.name})...")
        val npm = eel.exec.findExeFilesInPath("npm").firstOrNull()
            ?: run {
                statusCallback?.invoke("Error: No 'npm' executable found in WSL distribution '${eel.descriptor.name}'. Install Node.js inside the distro.")
                return false
            }
        cancellation?.throwIfCancelled()

        val installProc = AcpEelEnvironment.spawn(
            eel = eel,
            executable = npm.toString(),
            args = listOf("install", "$packageName@$version", "--no-save", "--no-package-lock"),
            workingDirectory = adapterRoot,
            environment = emptyMap()
        )
        cancellation?.register(installProc)

        val recentOutput = java.util.Collections.synchronizedList(mutableListOf<String>())
        val outputDrainer = Thread {
            installProc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotBlank()) {
                        recentOutput.add(trimmed)
                        if (recentOutput.size > 12) recentOutput.removeAt(0)
                    }
                    if (trimmed.contains("added", ignoreCase = true) || trimmed.contains("install", ignoreCase = true)) {
                        statusCallback?.invoke("NPM: $trimmed")
                    }
                }
            }
        }
        outputDrainer.isDaemon = true
        outputDrainer.start()

        try {
            while (true) {
                cancellation?.throwIfCancelled()
                if (installProc.waitFor(250, TimeUnit.MILLISECONDS)) break
            }
        } catch (e: CancellationException) {
            installProc.destroyForcibly()
            outputDrainer.join(1000)
            throw e
        } finally {
            cancellation?.unregister(installProc)
        }

        outputDrainer.join(1000)
        val exitCode = installProc.exitValue()
        if (exitCode == 0) {
            true
        } else {
            val detail = recentOutput.joinToString("\n").ifBlank { "npm install failed" }
            statusCallback?.invoke("Error: npm install failed with exit code $exitCode\n$detail")
            false
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        statusCallback?.invoke("Error: ${e.message ?: "npm install failed"}")
        false
    }
}
