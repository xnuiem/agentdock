package agentdock.acp

import com.agentclientprotocol.annotations.UnstableApi
import kotlinx.coroutines.flow.toList
import agentdock.history.SessionMeta
import agentdock.history.fallbackHistoryTitle
import agentdock.history.historyComparablePath
import agentdock.history.parseHistoryTimestamp

@OptIn(UnstableApi::class)
internal suspend fun AcpClientService.listHistorySessions(
    adapterInfo: AcpAdapterConfig.AdapterInfo,
    projectPath: String
): List<SessionMeta> {
    ensureExecutionTargetCurrent()
    if (!AcpAdapterPaths.isDownloaded(adapterInfo.id)) return emptyList()

    val sharedProc = activeProcesses[processKey(adapterInfo.id)]?.takeIf { it.isHealthy() } ?: return emptyList()
    val client = sharedProc.client ?: return emptyList()
    val expectedProjectPath = historyComparablePath(projectPath)
    val requestedCwd = resolveSessionCwd(projectPath)

    return client.listSessions(cwd = requestedCwd).toList().mapNotNull { session ->
        val sessionProjectPath = historyComparablePath(session.cwd)
        if (expectedProjectPath.isNotBlank() && sessionProjectPath != expectedProjectPath) {
            return@mapNotNull null
        }

        val updatedAt = parseHistoryTimestamp(session.updatedAt) ?: 0L
        SessionMeta(
            sessionId = session.sessionId.value,
            adapterName = adapterInfo.id,
            projectPath = projectPath,
            title = fallbackHistoryTitle(session.title),
            filePath = "",
            createdAt = updatedAt,
            updatedAt = updatedAt
        )
    }
}
