package agentdock.history

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import agentdock.utils.atomicWriteText
import java.io.File

internal object SessionListDeleteSupport {
    fun resolveSourceFilePath(projectPath: String, adapterName: String, sessionId: String): String {
        return when (adapterName) {
            "claude-code" -> resolveClaudeSourceFilePath(projectPath, sessionId)
            else -> ""
        }
    }

    fun deleteSession(projectPath: String, adapterName: String, sessionId: String, sourceFilePath: String?): Boolean {
        return when (adapterName) {
            "claude-code" -> deleteClaudeSession(projectPath, sessionId, sourceFilePath)
            "kilo" -> runAgentHistoryCliCommand("kilo", projectPath, listOf("session", "delete", sessionId)) != null
            "opencode" -> runAgentHistoryCliCommand("opencode", projectPath, listOf("session", "delete", sessionId)) != null
            else -> false
        }
    }

    private fun resolveClaudeSourceFilePath(projectPath: String, sessionId: String): String {
        val files = findMatchingHistoryFiles(resolveHistoryPathTemplate("~/.claude/projects/{projectPathSlug}/*.jsonl", projectPath))
        return files.firstOrNull { file ->
            var matchedSessionId: String? = null
            runCatching {
                file.useLines { lines ->
                    for (line in lines) {
                        if (!line.trimStart().startsWith("{")) continue
                        val root = historyJson.parseToJsonElement(line).jsonObject
                        val type = root.stringOrNull("type")?.lowercase()
                        if (type != "user") continue
                        matchedSessionId = root.stringOrNull("sessionId") ?: file.nameWithoutExtension
                        break
                    }
                }
            }
            matchedSessionId == sessionId
        }?.absolutePath.orEmpty()
    }

    private fun deleteClaudeSession(projectPath: String, sessionId: String, sourceFilePath: String?): Boolean {
        val sourcePath = sourceFilePath?.takeIf { it.isNotBlank() } ?: return false
        val deletedFile = deleteHistoryFileIfExists(File(sourcePath))
        if (!deletedFile) return false

        val indexFile = File(resolveHistoryPathTemplate("~/.claude/projects/{projectPathSlug}/sessions-index.json", projectPath))
        if (!indexFile.exists()) return true

        return runCatching {
            val root = historyJson.parseToJsonElement(indexFile.readText()).jsonObject
            val entries = root["entries"] as? JsonArray ?: JsonArray(emptyList())
            val filtered = buildJsonArray {
                entries.forEach { entry ->
                    val entrySessionId = entry.jsonObject["sessionId"]?.toString()?.trim('"')
                    if (entrySessionId != sessionId) add(entry)
                }
            }
            val updatedRoot = buildJsonObject {
                root.forEach { (key, value) ->
                    if (key == "entries") put(key, filtered) else put(key, value)
                }
                if (!root.containsKey("entries")) put("entries", filtered)
            }
            indexFile.atomicWriteText(historyJson.encodeToString(JsonObject.serializer(), updatedRoot))
            true
        }.getOrDefault(true)
    }

}
