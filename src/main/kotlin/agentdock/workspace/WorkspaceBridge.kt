package agentdock.workspace

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.cef.browser.CefBrowser
import agentdock.history.AgentDockHistoryService
import agentdock.settings.GlobalSettingsStore
import agentdock.utils.escapeForJsString
import java.io.File

/**
 * Bridge for registering workspaces from the frontend rail. The only thing the JCEF page cannot do
 * itself is open a native OS folder chooser, so that (plus git-repo + execution-target validation)
 * lives here. Everything else about workspaces is persisted through GlobalSettingsStore and driven
 * from the frontend.
 */
class WorkspaceBridge(
    private val browser: JBCefBrowser,
    private val project: Project,
    private val scope: CoroutineScope
) {
    private var pickDirectoryQuery: JBCefJSQuery? = null
    private var projectRootQuery: JBCefJSQuery? = null
    private var activityQuery: JBCefJSQuery? = null
    private var lspQuery: JBCefJSQuery? = null

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    fun install() {
        val browserBase = browser as com.intellij.ui.jcef.JBCefBrowserBase

        projectRootQuery = JBCefJSQuery.create(browserBase).apply {
            addHandler {
                pushProjectRoot()
                JBCefJSQuery.Response("ok")
            }
        }

        activityQuery = JBCefJSQuery.create(browserBase).apply {
            addHandler { payload ->
                val rootPaths = parseRootPaths(payload)
                scope.launch(Dispatchers.IO) {
                    val results = rootPaths.map { rp ->
                        val last = runCatching {
                            AgentDockHistoryService.getHistoryList(rp).maxOfOrNull { it.updatedAt } ?: 0L
                        }.getOrDefault(0L)
                        rp to last
                    }
                    pushActivity(results)
                }
                JBCefJSQuery.Response("ok")
            }
        }

        lspQuery = JBCefJSQuery.create(browserBase).apply {
            addHandler { payload ->
                val rootPath = payload?.trim().orEmpty()
                scope.launch(Dispatchers.IO) { pushLspServers(rootPath) }
                JBCefJSQuery.Response("ok")
            }
        }

        pickDirectoryQuery = JBCefJSQuery.create(browserBase).apply {
            addHandler {
                ApplicationManager.getApplication().invokeLater {
                    val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
                    descriptor.title = "Add Workspace"
                    descriptor.description = "Choose a git repository directory to register as a workspace."
                    FileChooser.chooseFiles(descriptor, project, null) { files ->
                        val file = files.firstOrNull()
                        if (file == null) {
                            pushPicked(rootPath = "", name = "", isGitRepo = false, sameTarget = false, cancelled = true)
                            return@chooseFiles
                        }
                        val rootPath = file.path
                        val name = file.name.ifBlank { rootPath.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\') }
                        pushPicked(
                            rootPath = rootPath,
                            name = name,
                            isGitRepo = isGitRepo(rootPath),
                            sameTarget = matchesExecutionTarget(rootPath),
                            error = validationError(rootPath)
                        )
                    }
                }
                JBCefJSQuery.Response("ok")
            }
        }
    }

    fun injectApi(cefBrowser: CefBrowser) {
        val pickInject = pickDirectoryQuery?.inject("") ?: "console.error('[WorkspaceBridge] Pick query not ready')"
        val projectRootInject = projectRootQuery?.inject("") ?: "console.error('[WorkspaceBridge] Project-root query not ready')"
        val activityInject = activityQuery?.inject("payload") ?: "console.error('[WorkspaceBridge] Activity query not ready')"
        val lspInject = lspQuery?.inject("rootPath") ?: "console.error('[WorkspaceBridge] LSP query not ready')"
        val script = """
            (function() {
                window.__onWorkspacePicked = window.__onWorkspacePicked || function(payload) {};
                window.__onWorkspaceProjectRoot = window.__onWorkspaceProjectRoot || function(payload) {};
                window.__onWorkspaceActivity = window.__onWorkspaceActivity || function(payload) {};
                window.__onLspServers = window.__onLspServers || function(payload) {};
                window.__pickWorkspaceDirectory = function() {
                    try { $pickInject } catch (e) { console.error('[WorkspaceBridge] Pick error', e); }
                };
                window.__requestWorkspaceProjectRoot = function() {
                    try { $projectRootInject } catch (e) { console.error('[WorkspaceBridge] Project-root error', e); }
                };
                window.__requestWorkspaceActivity = function(payload) {
                    try { $activityInject } catch (e) { console.error('[WorkspaceBridge] Activity error', e); }
                };
                window.__requestLspServers = function(rootPath) {
                    try { $lspInject } catch (e) { console.error('[WorkspaceBridge] LSP error', e); }
                };
            })();
        """.trimIndent()
        cefBrowser.executeJavaScript(script, cefBrowser.url, 0)
    }

    private fun parseRootPaths(payload: String?): List<String> {
        val raw = payload?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        return runCatching {
            Json.parseToJsonElement(raw).jsonArray.mapNotNull { it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() } }
        }.getOrDefault(emptyList())
    }

    /**
     * Reads opencode's own config (opencode.jsonc / opencode.json at the workspace root) and reports
     * the LSP servers it is CONFIGURED to use. This is configured state, not a live health check —
     * opencode/ACP does not expose running LSP status. `source` = the config file the entry came from.
     */
    private fun pushLspServers(rootPath: String) {
        val servers = runCatching { readOpencodeLspServers(rootPath) }.getOrDefault(emptyList())
        val payload = buildJsonArray {
            servers.forEach { s ->
                addJsonObject {
                    put("name", s.name)
                    put("command", s.command)
                    put("extensions", s.extensions)
                    put("disabled", s.disabled)
                    put("source", s.source)
                }
            }
        }.toString().escapeForJsString()
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(
                "if(window.__onLspServers) window.__onLspServers(JSON.parse('$payload'));",
                browser.cefBrowser.url,
                0
            )
        }
    }

    private data class LspServerInfo(
        val name: String,
        val command: String,
        val extensions: String,
        val disabled: Boolean,
        val source: String
    )

    private fun readOpencodeLspServers(rootPath: String): List<LspServerInfo> {
        if (rootPath.isBlank()) return emptyList()
        val candidate = listOf("opencode.jsonc", "opencode.json")
            .map { File(rootPath, it) }
            .firstOrNull { runCatching { it.isFile }.getOrDefault(false) } ?: return emptyList()
        val raw = runCatching { candidate.readText() }.getOrNull() ?: return emptyList()
        val root = runCatching { lenientJson.parseToJsonElement(stripJsonComments(raw)).jsonObject }.getOrNull() ?: return emptyList()
        val lsp = (root["lsp"] as? JsonObject) ?: return emptyList()
        return lsp.entries.map { (name, value) ->
            val obj = value as? JsonObject
            val disabled = when {
                (value as? JsonPrimitive)?.booleanOrNull == false -> true // `"name": false` disables a builtin
                obj?.get("disabled")?.jsonPrimitive?.booleanOrNull == true -> true
                else -> false
            }
            val command = (obj?.get("command") as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it.jsonPrimitive.content }?.joinToString(" ").orEmpty()
            val extensions = (obj?.get("extensions") as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it.jsonPrimitive.content }?.joinToString(", ").orEmpty()
            LspServerInfo(name, command, extensions, disabled, candidate.name)
        }
    }

    /** Strip // line comments and block comments so opencode.jsonc parses as JSON (best-effort). */
    private fun stripJsonComments(text: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(text, "")
        return noBlock.lines().joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0 && line.substring(0, idx).count { it == '"' } % 2 == 0) line.substring(0, idx) else line
        }
    }

    private fun pushActivity(results: List<Pair<String, Long>>) {
        val payload = buildJsonArray {
            results.forEach { (rootPath, last) ->
                addJsonObject {
                    put("rootPath", rootPath)
                    put("lastActivityMillis", last)
                }
            }
        }.toString().escapeForJsString()
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(
                "if(window.__onWorkspaceActivity) window.__onWorkspaceActivity(JSON.parse('$payload'));",
                browser.cefBrowser.url,
                0
            )
        }
    }

    /** The single IntelliJ project root; used to seed the default workspace for existing users. */
    private fun pushProjectRoot() {
        val rootPath = project.basePath.orEmpty()
        val name = rootPath.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        val payload = buildJsonObject {
            put("rootPath", rootPath)
            put("name", name)
        }.toString().escapeForJsString()
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(
                "if(window.__onWorkspaceProjectRoot) window.__onWorkspaceProjectRoot(JSON.parse('$payload'));",
                browser.cefBrowser.url,
                0
            )
        }
    }

    /** A git repo has a `.git` directory (normal clone) or a `.git` file (worktree/submodule). */
    private fun isGitRepo(rootPath: String): Boolean =
        runCatching { File(rootPath, ".git").exists() }.getOrDefault(false)

    /**
     * All workspaces must share the single configured execution target. We only enforce the target
     * TYPE — a WSL path for a WSL target, a non-WSL path for local — not the exact distro name.
     * Matching the distro string against a path segment proved brittle (path forms and the stored
     * distro value vary), and the user has confirmed all workspaces live in one distro anyway.
     */
    private fun matchesExecutionTarget(rootPath: String): Boolean {
        val settings = GlobalSettingsStore.load()
        val normalized = rootPath.replace('\\', '/').lowercase()
        val isWslPath = normalized.contains("wsl.localhost/") || normalized.contains("wsl$/")
        return if (settings.executionTarget == "wsl") isWslPath else !isWslPath
    }

    private fun validationError(rootPath: String): String? = when {
        !isGitRepo(rootPath) -> "That folder isn't a git repository (no .git found)."
        !matchesExecutionTarget(rootPath) -> {
            val settings = GlobalSettingsStore.load()
            if (settings.executionTarget == "wsl")
                "Execution target is WSL, so the workspace must be a WSL path (\\\\wsl.localhost\\...)."
            else
                "Execution target is Local, so the workspace can't be a WSL path. Switch the target first."
        }
        else -> null
    }

    private fun pushPicked(
        rootPath: String,
        name: String,
        isGitRepo: Boolean,
        sameTarget: Boolean,
        error: String? = null,
        cancelled: Boolean = false
    ) {
        val payload = buildJsonObject {
            put("rootPath", rootPath)
            put("name", name)
            put("isGitRepo", isGitRepo)
            put("sameTarget", sameTarget)
            put("cancelled", cancelled)
            if (error != null) put("error", error)
        }.toString().escapeForJsString()
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(
                "if(window.__onWorkspacePicked) window.__onWorkspacePicked(JSON.parse('$payload'));",
                browser.cefBrowser.url,
                0
            )
        }
    }
}
