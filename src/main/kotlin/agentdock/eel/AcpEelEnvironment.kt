package agentdock.eel

import agentdock.acp.AcpExecutionMode
import com.intellij.openapi.project.Project
import com.intellij.platform.eel.EelApi
import com.intellij.platform.eel.EelOsFamily
import com.intellij.platform.eel.EelProcess
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.platform.eel.provider.toEelApi
import com.intellij.platform.eel.provider.utils.EelPathUtils
import com.intellij.platform.eel.provider.utils.JEelUtils
import com.intellij.platform.eel.spawnProcess
import java.io.File
import java.nio.file.Path

/**
 * Single seam for WSL execution: resolves the project's EelApi, spawns processes through
 * EelApi.exec instead of ProcessBuilder/GeneralCommandLine, and converts paths to their
 * target-side string form only at the point they cross into the agent process.
 *
 * WSL execution is only supported for a project already opened from the configured WSL
 * distribution (its EelDescriptor is non-local) - there is no supported EelApi mechanism
 * for bridging a Windows-local path into an unrelated WSL machine without custom path
 * rewriting, which the fork intentionally avoids. The LOCAL target never touches this file.
 */
internal object AcpEelEnvironment {

    class UnsupportedWslProjectException(message: String) : IllegalStateException(message)

    /**
     * Resolves the EelApi for the current project when WSL execution is configured.
     * Throws if the project isn't actually opened from a (posix) WSL environment, or if
     * a distro is configured but doesn't match the project's own environment.
     */
    suspend fun resolveWslEelApi(project: Project): EelApi {
        val descriptor = project.getEelDescriptor()
        if (descriptor.osFamily != EelOsFamily.Posix) {
            throw UnsupportedWslProjectException(
                "WSL execution is enabled in Agent Dock settings, but this project is not open " +
                    "from a WSL path. Open the project from \\\\wsl.localhost\\<distro>\\... to use WSL execution."
            )
        }
        val configuredDistro = AcpExecutionMode.wslDistro()
        if (!configuredDistro.isNullOrBlank() && !descriptor.name.equals(configuredDistro, ignoreCase = true)) {
            throw UnsupportedWslProjectException(
                "Agent Dock is configured to use WSL distribution '$configuredDistro', but this project " +
                    "is open from '${descriptor.name}'. Update the WSL distribution in settings or reopen the project."
            )
        }
        return descriptor.toEelApi()
    }

    /** Native runtime directory for downloaded adapters inside the resolved WSL environment. */
    fun runtimeDir(eel: EelApi): Path = EelPathUtils.getHomePath(eel.descriptor).resolve(".agent-dock")

    /** Converts a host-side path to the string form the target environment's process sees. */
    fun targetPathString(path: Path): String = JEelUtils.toEelPath(path).toString()

    fun targetPathString(file: File): String = targetPathString(file.toPath())

    /**
     * Spawns [executable] inside [eel] and returns a standard [Process], so every existing
     * stdio-wiring / process-registry call site downstream keeps working unmodified.
     */
    suspend fun spawn(
        eel: EelApi,
        executable: String,
        args: List<String>,
        workingDirectory: Path,
        environment: Map<String, String>
    ): Process {
        val eelProcess: EelProcess = eel.exec.spawnProcess(executable)
            .args(args)
            .env(environment)
            .workingDirectory(JEelUtils.toEelPath(workingDirectory))
            .eelIt()
        return eelProcess.convertToJavaProcess()
    }
}
