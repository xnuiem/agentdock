package agentdock.acp

import kotlinx.serialization.json.*
import java.io.File

/**
 * Fetches usage/quota data from different AI provider adapters.
 */
internal object AcpUsageDataFetcher {
    fun fetchClaudeUsageData(): String {
        val accessToken = try {
            readTargetFile("~/.claude/.credentials.json")
                ?.let { Json.parseToJsonElement(it).jsonObject.get("claudeAiOauth")?.jsonObject?.get("accessToken")?.jsonPrimitive?.content }
        } catch (_: Exception) { null }

        if (accessToken == null) return """{"authType":"api_key"}"""

        return try {
            val conn = java.net.URI("https://api.anthropic.com/api/oauth/usage").toURL()
                .openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("anthropic-beta", "oauth-2025-04-20")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "claude-code/2.1.71")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = Json.parseToJsonElement(body).jsonObject
                JsonObject(obj + ("authType" to JsonPrimitive("subscription"))).toString()
            } else """{"authType":"subscription"}"""
        } catch (_: Exception) { """{"authType":"subscription"}""" }
    }

    private fun readTargetFile(rawPath: String): String? {
        val resolved = rawPath.replace("~", System.getProperty("user.home"))
        val file = File(resolved)
        return if (!file.exists()) null else file.readText()
    }
}
