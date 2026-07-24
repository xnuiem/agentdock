package agentdock.history

import agentdock.acp.AcpExecutionTarget
import agentdock.acp.buildAdapterCliCommandParts
import agentdock.acp.isWindowsLocalTarget
import com.intellij.platform.eel.provider.utils.EelPathUtils
import java.io.File

internal fun runAgentHistoryCliCommand(
    adapterId: String,
    projectPath: String,
    args: List<String>
): String? {
    val (_, commandParts) = buildAdapterCliCommandParts(adapterId, args) ?: return null
    // This launches a host-local process, so a WSL-routed projectPath (from a WSL-opened
    // project) must not be used as its working directory - see AcpAdapterInitializer for the
    // same guard on the primary launch path.
    val workingDir = File(projectPath).takeIf { EelPathUtils.isPathLocal(it.toPath()) } ?: return null
    return runCatching {
        val localCommandParts = if (
            isWindowsLocalTarget(AcpExecutionTarget.LOCAL) &&
            commandParts.firstOrNull()?.let { it.endsWith(".cmd", true) || it.endsWith(".bat", true) } == true
        ) {
            listOf("cmd.exe", "/c") + commandParts
        } else {
            commandParts
        }
        val process = ProcessBuilder(localCommandParts)
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) null else output
    }.getOrNull()?.trim()
}
