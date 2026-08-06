package agentdock.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.cef.browser.CefBrowser
import agentdock.utils.escapeForJsString

/**
 * Surfaces the editor's currently active file to the frontend so it can be auto-attached to prompts
 * as context ("aware of the file I have open"). This is pure IDE state — no agent-protocol dependency.
 * The path is the host display form, matching how cwd/workspace roots are represented elsewhere.
 */
class EditorContextBridge(
    private val browser: JBCefBrowser,
    private val project: Project
) {
    private var requestQuery: JBCefJSQuery? = null

    fun install() {
        val browserBase = browser as com.intellij.ui.jcef.JBCefBrowserBase

        requestQuery = JBCefJSQuery.create(browserBase).apply {
            addHandler {
                pushActiveFile(currentActiveFile())
                JBCefJSQuery.Response("ok")
            }
        }

        // Push on every editor selection change so the frontend always knows the open file.
        project.messageBus.connect(project).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    pushActiveFile(event.newFile)
                }
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    pushActiveFile(file)
                }
            }
        )
    }

    fun injectApi(cefBrowser: CefBrowser) {
        val requestInject = requestQuery?.inject("") ?: "console.error('[EditorContextBridge] Request query not ready')"
        val script = """
            (function() {
                window.__onActiveFile = window.__onActiveFile || function(payload) {};
                window.__requestActiveFile = function() {
                    try { $requestInject } catch (e) { console.error('[EditorContextBridge] Request error', e); }
                };
            })();
        """.trimIndent()
        cefBrowser.executeJavaScript(script, cefBrowser.url, 0)
        // Prime the frontend with whatever is open right now.
        pushActiveFile(currentActiveFile())
    }

    private fun currentActiveFile(): VirtualFile? =
        runCatching { FileEditorManager.getInstance(project).selectedFiles.firstOrNull() }.getOrNull()

    private fun pushActiveFile(file: VirtualFile?) {
        val payload = buildJsonObject {
            put("path", file?.path ?: "")
            put("name", file?.name ?: "")
        }.toString().escapeForJsString()
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(
                "if(window.__onActiveFile) window.__onActiveFile(JSON.parse('$payload'));",
                browser.cefBrowser.url,
                0
            )
        }
    }
}
