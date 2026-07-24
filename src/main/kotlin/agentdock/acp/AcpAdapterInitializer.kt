package agentdock.acp

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.MethodName
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.atomicfu.AtomicRef
import kotlinx.collections.immutable.PersistentMap
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.Collections
import agentdock.BuildConfig
import agentdock.eel.AcpEelEnvironment
import agentdock.history.AgentDockHistoryService
import com.intellij.platform.eel.provider.utils.EelPathUtils

// Keep this aligned with the broader ACP startup budget.
// A freshly updated adapter can need materially longer than 60s
// on the first cold initialization after install/update.
private const val ADAPTER_INITIALIZATION_TIMEOUT_MS = 300_000L

internal fun AcpClientService.initializeDownloadedAdaptersInBackground() {
    if (!startupInitializationStarted.compareAndSet(false, true)) return

    // Callers include AgentDockToolWindowFactory's EDT invokeLater block, and isDownloaded's
    // WSL path does a blocking Eel bridge call that's forbidden on EDT (throws, silently
    // swallowed below as "not downloaded" without this hop) - always run off EDT so whichever
    // caller wins the startupInitializationStarted race still does real work.
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        AcpAdapterConfig.getAllAdapters().values.forEach { adapterInfo ->
            val downloaded = runCatching { AcpAdapterPaths.isDownloaded(adapterInfo.id) }.getOrDefault(false)
            if (!downloaded) return@forEach
            initializeAdapterInBackground(adapterInfo.id)
        }
    }
}

internal fun AcpClientService.initializeAdapterInBackground(adapterName: String) {
    val adapterInfo = AcpAdapterPaths.getAdapterInfo(adapterName)
    val downloaded = runCatching { AcpAdapterPaths.isDownloaded(adapterInfo.id) }.getOrDefault(false)
    if (!downloaded) return
    adapterInitializationJobs.remove(adapterInfo.id)?.cancel()
    adapterInitializationScopes.remove(adapterInfo.id)?.coroutineContext?.cancel()
    adapterInitialization.remove(adapterInfo.id)
    val deferred = CompletableDeferred<Unit>()
    adapterInitialization[adapterInfo.id] = deferred
    updateAdapterInitializationState(
        adapterInfo.id,
        AcpClientService.AdapterInitializationStatus.Initializing,
        detail = "Queued for startup..."
    )

    val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    adapterInitializationScopes[adapterInfo.id] = initScope
    val job = initScope.launch {
        try {
            AcpAdapterPaths.ensurePatched(adapterInfo.id)
            val sharedProc = replaceSharedProcess(adapterInfo.id)
            withTimeout(ADAPTER_INITIALIZATION_TIMEOUT_MS) {
                initializeSharedProcessAtStartup(sharedProc, adapterInfo)
            }
            if (!deferred.isCompleted) deferred.complete(Unit)
            updateAdapterInitializationState(adapterInfo.id, AcpClientService.AdapterInitializationStatus.Ready)
        } catch (e: TimeoutCancellationException) {
            if (!deferred.isCompleted) deferred.completeExceptionally(e)
            activeProcesses[processKey(adapterInfo.id)]?.stop()
            updateAdapterInitializationState(
                adapterInfo.id,
                AcpClientService.AdapterInitializationStatus.Failed,
                "Adapter initialization timed out after ${ADAPTER_INITIALIZATION_TIMEOUT_MS / 1000}s"
            )
        } catch (_: CancellationException) {
            if (!deferred.isCompleted) deferred.cancel()
            activeProcesses[processKey(adapterInfo.id)]?.stop()
            updateAdapterInitializationState(adapterInfo.id, AcpClientService.AdapterInitializationStatus.NotStarted)
        } catch (e: Exception) {
            if (!deferred.isCompleted) deferred.completeExceptionally(e)
            updateAdapterInitializationState(
                adapterInfo.id,
                AcpClientService.AdapterInitializationStatus.Failed,
                formatAcpError(e)
            )
        } finally {
            adapterInitializationJobs.remove(adapterInfo.id)
            adapterInitializationScopes.remove(adapterInfo.id)?.coroutineContext?.cancel()
            triggerBackgroundHistorySyncIfInitializationsSettled()
        }
    }
    adapterInitializationJobs[adapterInfo.id] = job
}

private fun AcpClientService.triggerBackgroundHistorySyncIfInitializationsSettled() {
    val projectPath = project.basePath?.takeIf { it.isNotBlank() } ?: return
    val downloadedAdapters = AcpAdapterConfig.getAllAdapters().values
        .filter { runCatching { AcpAdapterPaths.isDownloaded(it.id) }.getOrDefault(false) }
    if (downloadedAdapters.isEmpty()) return

    val hasPendingInitialization = downloadedAdapters.any { adapterInfo ->
        adapterInitializationState[adapterInfo.id] == AcpClientService.AdapterInitializationStatus.Initializing ||
            adapterInitializationJobs[adapterInfo.id]?.isActive == true
    }
    if (hasPendingInitialization) return
    if (!historySyncAfterInitializationInFlight.compareAndSet(false, true)) return

    scope.launch {
        try {
            AgentDockHistoryService.startBackgroundHistorySync(projectPath)
        } finally {
            historySyncAfterInitializationInFlight.set(false)
        }
    }
}

internal suspend fun AcpClientService.initializeAdapterIfEligible(adapterName: String) {
    val adapterInfo = AcpAdapterPaths.getAdapterInfo(adapterName)
    if (!AcpAdapterPaths.isDownloaded(adapterInfo.id)) {
        throw IllegalStateException("Agent '${adapterInfo.id}' is not downloaded")
    }

    val deferred = adapterInitialization.computeIfAbsent(adapterInfo.id) { CompletableDeferred<Unit>() }
    updateAdapterInitializationState(
        adapterInfo.id,
        AcpClientService.AdapterInitializationStatus.Initializing,
        detail = "Queued for startup..."
    )
    try {
        val sharedProc = replaceSharedProcess(adapterInfo.id)
        withTimeout(ADAPTER_INITIALIZATION_TIMEOUT_MS) {
            ensureSharedProcessStarted(sharedProc, adapterInfo)
        }
        if (!deferred.isCompleted) deferred.complete(Unit)
        updateAdapterInitializationState(adapterInfo.id, AcpClientService.AdapterInitializationStatus.Ready)
    } catch (e: Exception) {
        if (!deferred.isCompleted) deferred.completeExceptionally(e)
        updateAdapterInitializationState(
            adapterInfo.id,
            AcpClientService.AdapterInitializationStatus.Failed,
            formatAcpError(e)
        )
        throw e
    }
}

internal suspend fun AcpClientService.awaitAdapterInitialization(adapterInfo: AcpAdapterConfig.AdapterInfo) {
    val deferred = adapterInitialization[adapterInfo.id]
        ?: throw IllegalStateException("Adapter '${adapterInfo.id}' was not initialized at plugin startup")
    deferred.await()
}

internal fun AcpClientService.resolveModelToApply(
    pref: String?,
    available: List<AcpAdapterConfig.ModelInfo>,
    default: String?
): String? {
    val p = pref?.trim().takeUnless { it.isNullOrEmpty() }
    if (p != null && (available.isEmpty() || available.any { it.modelId == p })) {
        return p
    }
    return default
}

/**
 * Runtime path initializes the shared ACP process on demand when needed.
 */
internal suspend fun AcpClientService.ensureSharedProcessStarted(
    sharedProc: AcpClientService.SharedProcess,
    adapterInfo: AcpAdapterConfig.AdapterInfo,
    forceRestart: Boolean = false
) {
    if (forceRestart) {
        sharedProc.stop()
    }

    val isHealthy = sharedProc.isHealthy()

    if (!isHealthy) {
        updateAdapterInitializationState(
            adapterInfo.id,
            AcpClientService.AdapterInitializationStatus.Initializing,
            detail = "Starting adapter process..."
        )

        // Ensure all patches are applied before starting the process
        AcpAdapterPaths.ensurePatched(adapterInfo.id)

        try {
            initializeSharedProcessAtStartup(sharedProc, adapterInfo)
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) {
                updateAdapterInitializationState(adapterInfo.id, AcpClientService.AdapterInitializationStatus.NotStarted)
            } else {
                updateAdapterInitializationState(adapterInfo.id, AcpClientService.AdapterInitializationStatus.Failed, formatAcpError(e))
            }
            throw e
        }
        adapterInitialization.computeIfAbsent(adapterInfo.id) { CompletableDeferred<Unit>() }.also { deferred ->
            if (!deferred.isCompleted) deferred.complete(Unit)
        }
        updateAdapterInitializationState(adapterInfo.id, AcpClientService.AdapterInitializationStatus.Ready)
    } else {
        updateAdapterInitializationState(adapterInfo.id, AcpClientService.AdapterInitializationStatus.Ready)
    }

    ensureAsyncSessionUpdates(sharedProc)
}

/**
 * Startup-only path that initializes ACP adapter process and protocol client exactly once.
 */
@OptIn(com.agentclientprotocol.annotations.UnstableApi::class)
internal suspend fun AcpClientService.initializeSharedProcessAtStartup(
    sharedProc: AcpClientService.SharedProcess,
    adapterInfo: AcpAdapterConfig.AdapterInfo
) {
    val requestedAdapterName = adapterInfo.id
    sharedProc.mutex.withLock {
        val alreadyHealthy = sharedProc.isHealthy()
        if (alreadyHealthy) return

        if (sharedProc.process != null) {
            sharedProc.stop()
        }

        val target = AcpAdapterPaths.getExecutionTarget()

        updateAdapterInitializationState(
            requestedAdapterName,
            AcpClientService.AdapterInitializationStatus.Initializing,
            detail = "Resolving launch command..."
        )

        val (proc, adapterRootForRegistry) = if (target == AcpExecutionTarget.WSL) {
            launchAdapterProcessOverWsl(adapterInfo, requestedAdapterName)
        } else {
            val adapterRoot = AcpAdapterPaths.getDownloadPath(adapterInfo.id, target)
            val command = AcpAdapterPaths.buildLaunchCommand(
                adapterRootPath = adapterRoot,
                adapterInfo = adapterInfo,
                projectPath = project.basePath,
                target = target
            )

            updateAdapterInitializationState(
                requestedAdapterName,
                AcpClientService.AdapterInitializationStatus.Initializing,
                detail = "Starting adapter process..."
            )

            var commandLine = com.intellij.execution.configurations.GeneralCommandLine(command)
                .withWorkDirectory(resolveAdapterProcessWorkingDirectory(File(adapterRoot)))
                .withEnvironment(AcpProcessEnvironment.baseEnvironment())
                .withRedirectErrorStream(false)
            AcpNodeRuntimeResolver.resolveAvailable()?.let { runtime ->
                commandLine = AcpNodeRuntimeResolver.applyTo(commandLine, runtime)
            }

            withContext(Dispatchers.IO) { commandLine.createProcess() } to adapterRoot
        }
        sharedProc.process = proc
        AcpProcessRegistry.registerProcess(adapterInfo.id, adapterRootForRegistry, proc)
        updateAdapterInitializationState(
            requestedAdapterName,
            AcpClientService.AdapterInitializationStatus.Initializing,
            detail = "Opening ACP stdio transport..."
        )
        val startupOutput = Collections.synchronizedList(mutableListOf<String>())

        Thread {
            proc.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        startupOutput.add(line)
                        onLogEntry(AcpLogEntry(AcpLogEntry.Direction.RECEIVED, line, AcpLogEntry.Category.STDERR))
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val inputStream = if (BuildConfig.IS_DEV) {
            LineLoggingInputStream(proc.inputStream) { line ->
                startupOutput.add(line)
                onLogEntry(AcpLogEntry(AcpLogEntry.Direction.RECEIVED, line))
            }
        } else {
            proc.inputStream
        }
        val outputStream = if (BuildConfig.IS_DEV) {
            LineLoggingOutputStream(proc.outputStream) { line ->
                onLogEntry(AcpLogEntry(AcpLogEntry.Direction.SENT, line))
            }
        } else {
            proc.outputStream
        }
        val input = inputStream.bufferedReader(Charsets.UTF_8)
        val output = outputStream.bufferedWriter(Charsets.UTF_8)

        val protocolScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sharedProc.protocolScope = protocolScope
        val transport = StdioTransport(protocolScope, Dispatchers.IO, input.asLineFlow(), output.asLineWriter())
        val prot = Protocol(protocolScope, transport)
        sharedProc.protocol = prot

        val c = Client(prot)
        sharedProc.client = c
        prot.start()
        updateAdapterInitializationState(
            requestedAdapterName,
            AcpClientService.AdapterInitializationStatus.Initializing,
            detail = "Waiting for ACP initialize..."
        )

        var initialized = false
        var attempts = 0
        val maxAttempts = 60
        var lastInitializeError: Exception? = null

        while (!initialized && attempts < maxAttempts) {
            if (!proc.isAlive) {
                val exitValue = runCatching { proc.exitValue() }.getOrNull()
                throw normalizeAdapterStartupException(
                    lastInitializeError ?: IllegalStateException("Agent process exited immediately with code $exitValue"),
                    startupOutput
                )
            }
            try {
                attempts++
                updateAdapterInitializationState(
                    requestedAdapterName,
                    AcpClientService.AdapterInitializationStatus.Initializing,
                    detail = "Waiting for ACP initialize... (attempt $attempts)"
                )
                val initResult = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                    c.initialize(ClientInfo(LATEST_PROTOCOL_VERSION, ClientCapabilities()))
                }
                if (initResult == null) {
                    throw java.util.concurrent.TimeoutException("Timed out waiting for 10000 ms")
                }
                initialized = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val normalized = normalizeAdapterStartupException(e, startupOutput)
                if (normalized !== e) throw normalized
                lastInitializeError = e
                if (attempts < maxAttempts) {
                    kotlinx.coroutines.delay(5_000L)
                }
            }
        }
        if (!initialized) {
            throw normalizeAdapterStartupException(
                lastInitializeError ?: IllegalStateException("Adapter initialization failed"),
                startupOutput
            )
        }
        runCatching { ensureAsyncSessionUpdates(sharedProc) }
        try {
            updateAdapterInitializationState(
                requestedAdapterName,
                AcpClientService.AdapterInitializationStatus.Initializing,
                detail = "Fetching models and modes..."
            )
            val protocol = sharedProc.protocol
                ?: throw IllegalStateException("ACP protocol was not initialized for adapter '${adapterInfo.id}'")
            val client = sharedProc.client
                ?: throw IllegalStateException("ACP client was not initialized for adapter '${adapterInfo.id}'")
            adapterRuntimeMetadataMap[requestedAdapterName] = fetchAdapterRuntimeMetadata(protocol, client, adapterInfo)
        } catch (_: kotlinx.serialization.SerializationException) {
            // Protocol version mismatch between adapter binary and ACP SDK -
            // models/modes will fall back to config defaults in pushAdapters.
        } catch (e: Exception) {
            throw normalizeAdapterStartupException(e, startupOutput)
        }
        sharedProc.isInitialized = true
    }
}

/** Relative launch path for an adapter inside the WSL environment's own runtime dir. */
internal fun wslRelativeLaunchPath(adapterInfo: AcpAdapterConfig.AdapterInfo): String {
    return when (adapterInfo.distribution.type) {
        AcpAdapterConfig.DistributionType.ARCHIVE ->
            platformBinaryForTarget(adapterInfo.distribution.binaryName, AcpExecutionTarget.WSL)
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Missing WSL launch binary for adapter '${adapterInfo.id}'")
        AcpAdapterConfig.DistributionType.NPM -> {
            val launchBinary = platformBinaryForTarget(adapterInfo.launchBinary, AcpExecutionTarget.WSL).orEmpty().trim()
            if (launchBinary.isNotEmpty()) {
                launchBinary
            } else {
                val packageName = adapterInfo.distribution.packageName
                    ?: throw IllegalStateException("Adapter '${adapterInfo.id}' missing distribution.packageName in configuration")
                val launchPath = adapterInfo.launchPath.ifBlank { "dist/index.js" }
                "node_modules/$packageName/$launchPath"
            }
        }
    }
}

/**
 * Launches the adapter process inside the project's WSL environment via EelApi.exec.
 * Requires the project itself to be opened from the configured WSL distribution - see
 * [agentdock.eel.AcpEelEnvironment.resolveWslEelApi]. Adapter installation into the WSL
 * environment is not wired up yet, so this expects the launch file to already exist at
 * the resolved runtime path.
 */
private suspend fun AcpClientService.launchAdapterProcessOverWsl(
    adapterInfo: AcpAdapterConfig.AdapterInfo,
    requestedAdapterName: String
): Pair<Process, String> {
    val eel = AcpEelEnvironment.resolveWslEelApi(project)
    val runtimeDir = AcpEelEnvironment.runtimeDir(eel)
    val adapterRoot = AcpEelEnvironment.adapterDependenciesDir(eel, adapterInfo.id)
    val launchFile = adapterRoot.resolve(wslRelativeLaunchPath(adapterInfo))

    if (!Files.isRegularFile(launchFile)) {
        throw IllegalStateException(
            "Agent '${adapterInfo.id}' is not installed for WSL execution. Expected launch file at " +
                "${AcpEelEnvironment.targetPathString(launchFile)} inside distribution '${eel.descriptor.name}'."
        )
    }

    updateAdapterInitializationState(
        requestedAdapterName,
        AcpClientService.AdapterInitializationStatus.Initializing,
        detail = "Starting adapter process..."
    )

    val launchFileTarget = AcpEelEnvironment.targetPathString(launchFile)
    val name = launchFile.fileName.toString().lowercase()
    val (executable, args) = if (name.endsWith(".js") || name.endsWith(".mjs")) {
        val node = eel.exec.findExeFilesInPath("node").firstOrNull()
            ?: throw IllegalStateException(
                "No 'node' executable found in WSL distribution '${eel.descriptor.name}'. Install Node.js inside the distro."
            )
        node.toString() to (listOf(launchFileTarget) + adapterInfo.args)
    } else {
        launchFileTarget to adapterInfo.args
    }

    // The process's OS-level working directory must be the project root, not the adapter's own
    // runtime/install dir - most agent CLIs (OpenCode, Kilo) discover project-local custom-agent
    // config (.opencode/, .kilo/) relative to their own process cwd, not the ACP session's cwd
    // parameter. LOCAL mode already gets this right via resolveAdapterProcessWorkingDirectory;
    // mirror it here instead of defaulting to runtimeDir.
    val projectDir = project.basePath
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { java.nio.file.Path.of(it) }.getOrNull() }
        ?.takeIf { Files.isDirectory(it) }
        ?: runtimeDir

    // baseEnvironment() is Windows-shaped (System.getenv() + host shell env) - handing that to
    // a WSL process stomps its real PATH and breaks even the shebang interpreter lookup (node,
    // env). Leave env empty so the WSL environment's own login/shell environment applies.
    val proc = AcpEelEnvironment.spawn(
        eel = eel,
        executable = executable,
        args = args,
        workingDirectory = projectDir,
        environment = emptyMap()
    )
    return proc to AcpEelEnvironment.targetPathString(adapterRoot)
}

private fun normalizeAdapterStartupException(error: Exception, startupOutput: List<String>): Exception {
    val haystacks = buildList {
        add(error.message.orEmpty())
        addAll(startupOutput)
    }
    val authRequired = haystacks.any { line ->
        line.contains("Please visit the following URL to authorize the application", ignoreCase = true) ||
            line.contains("accounts.google.com/o/oauth2/", ignoreCase = true) ||
            line.contains("Enter the authorization code:", ignoreCase = true) ||
            line.contains("Authentication required", ignoreCase = true)
    }
    if (!authRequired) return error
    return IllegalStateException("[AUTH_REQUIRED] Authentication required")
}

/**
 * Working directory for the throwaway metadata-probe session, in the string form the adapter
 * process expects. This MUST be the project directory (converted to the target environment's
 * native path via resolveSessionCwd): opencode/kilo enumerate project-local custom agents
 * (.opencode/agents, .kilo/...) from the session's cwd, so a synthetic isolated probe dir would
 * surface only the agent's built-in defaults in the model/mode/agent picker. The single probe
 * session created with this cwd is deleted immediately afterward (see cleanupProbeSession) so it
 * never lingers in the project's real session history.
 */
private fun AcpClientService.resolveProbeSessionCwd(): String =
    resolveSessionCwd(project.basePath ?: System.getProperty("user.dir"))

@OptIn(com.agentclientprotocol.annotations.UnstableApi::class)
internal suspend fun AcpClientService.fetchAdapterRuntimeMetadata(
    protocol: Protocol,
    client: Client,
    adapterInfo: AcpAdapterConfig.AdapterInfo
): AcpClientService.AdapterRuntimeMetadata {
    val cwd = resolveProbeSessionCwd()
    val result = protocol.newSessionRaw(cwd)
    val sessionId = result["sessionId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (sessionId.isEmpty()) {
        throw IllegalStateException("ACP session/new response did not include sessionId")
    }
    try {
        val configMetadata = runtimeMetadataFromSessionResponseJson(result, adapterInfo)
        val adapterVersion = AcpConfigOptionsCache.adapterVersion(adapterInfo)
        val existingCache = AcpConfigOptionsCache.readValid(adapterInfo)
        val rawCached = protocol.collectConfigOptionsCatalog(
            sessionId = sessionId,
            adapterInfo = adapterInfo,
            adapterVersion = adapterVersion,
            initialMetadata = configMetadata,
            existingCache = existingCache
        )
        val cached = enrichModeModels(rawCached, adapterInfo)
        AcpConfigOptionsCache.write(cached)
        return cached.toRuntimeMetadata(adapterInfo)
    } finally {
        cleanupProbeSession(adapterInfo, cwd, sessionId)
    }
}

/**
 * Fills in each mode's declared model (from the agent's frontmatter) so the UI can move the model
 * selection to match a chosen agent. Reads the frontmatter once per distinct mode here (at metadata
 * build / adapter init), and the result is cached alongside the rest of the config options. No-op for
 * adapters/agents that declare no model - those modes keep modelId = null and leave the model as-is.
 */
private fun AcpClientService.enrichModeModels(
    cached: CachedAdapterConfigOptions,
    adapterInfo: AcpAdapterConfig.AdapterInfo
): CachedAdapterConfigOptions {
    val modelByMode = HashMap<String, String?>()
    fun modelFor(modeId: String): String? =
        modelByMode.getOrPut(modeId) { agentDeclaredModelId(adapterInfo.id, modeId) }

    val enrichedModels = cached.models.map { model ->
        model.copy(
            modes = model.modes.map { mode ->
                if (mode.modelId != null) mode else mode.copy(modelId = modelFor(mode.id))
            }
        )
    }
    return cached.copy(models = enrichedModels)
}

@OptIn(com.agentclientprotocol.annotations.UnstableApi::class)
private suspend fun AcpClientService.cleanupProbeSession(
    adapterInfo: AcpAdapterConfig.AdapterInfo,
    cwd: String,
    sessionId: String
): Unit {
    // The probe now runs in the real project directory so custom agents are discovered, which
    // means we must delete ONLY the throwaway session this probe created - never list-and-delete
    // every session at the cwd, or we would wipe the user's real project sessions.
    val trimmed = sessionId.trim()
    if (trimmed.isBlank()) return
    runCatching {
        AgentDockHistoryService.deleteSessionImmediately(
            projectPath = cwd,
            sessionId = trimmed,
            adapterName = adapterInfo.id,
            waitTimeoutMillis = 1_000L,
            pollIntervalMillis = 100L
        )
    }
}

internal fun AcpClientService.applyAdapterRuntimePreferences(
    adapterInfo: AcpAdapterConfig.AdapterInfo,
    currentModelId: String?,
    availableModels: List<AcpAdapterConfig.ModelInfo>,
    modelConfigId: String?,
    currentModeId: String?,
    availableModes: List<AcpAdapterConfig.ModeInfo>,
    modeConfigId: String?,
    currentReasoningEffortId: String? = null,
    availableReasoningEfforts: List<AcpAdapterConfig.ModeInfo> = emptyList(),
    reasoningEffortConfigId: String? = null
): AcpClientService.AdapterRuntimeMetadata {
    val cached = AcpConfigOptionsCache.readValid(adapterInfo)
    val sourceModels = availableModels.ifEmpty { cached?.models.orEmpty().map { it.toModelInfo() } }
    val filteredModels = sourceModels.filterNot { model ->
        adapterInfo.disabledModels.any { disabled -> disabled.isNotBlank() && model.modelId.contains(disabled) }
    }
    val filteredModes = availableModes.filterNot { mode ->
        adapterInfo.disabledModes.any { disabled -> disabled == mode.id }
    }
    val savedPreference = AcpAgentPreferencesStore.preferenceFor(adapterInfo.id)

    val preferredModelId = savedPreference?.modelId
        ?.takeIf { preferred -> filteredModels.any { it.modelId == preferred } }
        ?: currentModelId?.takeIf { current -> filteredModels.any { it.modelId == current } }
        ?: filteredModels.firstOrNull()?.modelId

    val cachedModel = preferredModelId?.let { modelId ->
        cached?.models?.firstOrNull { it.modelId == modelId }
    }
    val cachedModes = cachedModel?.modes
        ?.filterNot { mode -> adapterInfo.disabledModes.any { disabled -> disabled == mode.id } }
    val effectiveAvailableModes = cachedModes ?: filteredModes
    val cachedReasoningEfforts = cachedModel?.efforts
    val preferredModeId = savedPreference?.modeId
        ?.takeIf { preferred -> effectiveAvailableModes.any { it.id == preferred } }
        ?: currentModeId?.takeIf { current -> effectiveAvailableModes.any { it.id == current } }
        ?: effectiveAvailableModes.firstOrNull()?.id
    val effectiveAvailableReasoningEfforts = cachedReasoningEfforts ?: availableReasoningEfforts
    val effectiveReasoningEffortConfigId = reasoningEffortConfigId ?: cached?.reasoningEffortConfigId
    val preferredReasoningEffortId = savedPreference?.reasoningEffortId
        ?.takeIf { preferred -> effectiveAvailableReasoningEfforts.any { it.id == preferred } }
        ?: currentReasoningEffortId?.takeIf { current -> effectiveAvailableReasoningEfforts.any { it.id == current } }
        ?: effectiveAvailableReasoningEfforts.firstOrNull()?.id

    return AcpClientService.AdapterRuntimeMetadata(
        currentModelId = preferredModelId,
        availableModels = filteredModels,
        modelConfigId = modelConfigId,
        currentModeId = preferredModeId,
        availableModes = effectiveAvailableModes,
        modeConfigId = modeConfigId ?: cached?.modeConfigId,
        currentReasoningEffortId = preferredReasoningEffortId,
        availableReasoningEfforts = effectiveAvailableReasoningEfforts,
        reasoningEffortConfigId = effectiveReasoningEffortConfigId?.takeIf { effectiveAvailableReasoningEfforts.isNotEmpty() },
        availableModesByModel = cached?.models.orEmpty().associate { model ->
            model.modelId to model.modes.filterNot { mode ->
                adapterInfo.disabledModes.any { disabled -> disabled == mode.id }
            }
        },
        availableReasoningEffortsByModel = cached?.models.orEmpty().associate { model ->
            model.modelId to model.efforts
        }
    )
}

internal fun AcpClientService.resolveAdapterProcessWorkingDirectory(adapterRoot: File): File {
    // On IntelliJ Platform 2025.2+, File/Path operations are transparently routed through a
    // project's Eel environment (e.g. WSL), so a WSL-opened project's basePath can pass
    // exists()/isDirectory() here even though it isn't a real host path. LOCAL execution must
    // stay strictly local - GeneralCommandLine below builds a Windows-native command, so handing
    // it a WSL-routed working directory launches a Windows binary through a POSIX shell and fails.
    val projectBase = project.basePath
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.exists() && it.isDirectory && EelPathUtils.isPathLocal(it.toPath()) }
    return projectBase ?: adapterRoot
}

internal fun AcpClientService.ensureAsyncSessionUpdates(sharedProc: AcpClientService.SharedProcess) {
    synchronized(sharedProc) {
        if (sharedProc.sessionUpdateWrapped) return
        val protocol = sharedProc.protocol ?: return
        try {
            // The ACP SDK routes session/update through a private Protocol handler before
            // splitting updates between prompt streams and ClientSessionOperations.notify().
            // Wrapping that raw handler is the only current way to preserve one ordered
            // update queue across both public delivery paths.
            val field = Protocol::class.java.getDeclaredField("notificationHandlers")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val handlers = field.get(protocol) as AtomicRef<PersistentMap<MethodName, suspend (JsonRpcNotification) -> Unit>>
            val methodName = AcpMethod.ClientMethods.SessionUpdate.methodName
            val original = handlers.value[methodName] ?: return
            val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            sharedProc.sessionUpdateScope = updateScope
            val queue = Channel<QueuedSessionUpdate>(Channel.UNLIMITED)
            sharedProc.sessionUpdateQueue = queue
            sharedProc.sessionUpdateWorker = updateScope.launch {
                for (entry in queue) {
                    when (entry) {
                        is QueuedSessionUpdate.Notification -> {
                            try {
                                original(entry.notification)
                                entry.completed.complete(Unit)
                            } catch (t: Throwable) {
                                entry.completed.completeExceptionally(t)
                            }
                        }
                        is QueuedSessionUpdate.Barrier -> {
                            entry.completed.complete(Unit)
                        }
                    }
                }
            }
            val wrapped: suspend (JsonRpcNotification) -> Unit = { notification ->
                extractAvailableCommands(notification.params)?.let { commands ->
                    updateAvailableCommands(sharedProc.adapterName, commands)
                }
                updateRuntimeMetadataFromConfigOptionsNotification(sharedProc.adapterName, notification.params)
                val sessionId = extractSessionUpdateSessionId(notification.params)
                val isSdkOwnedSession = sessionId == null ||
                    liveOwnerBySessionId.containsKey(sessionId) ||
                    replayOwnerBySessionId.containsKey(sessionId)
                if (isSdkOwnedSession) {
                    val completed = CompletableDeferred<Unit>()
                    queue.send(QueuedSessionUpdate.Notification(notification, completed))
                    completed.await()
                }
            }
            handlers.value = handlers.value.put(methodName, wrapped)
            sharedProc.sessionUpdateWrapped = true
        } catch (_: Exception) {
            sharedProc.sessionUpdateQueue?.close()
            sharedProc.sessionUpdateQueue = null
            sharedProc.sessionUpdateWorker?.cancel()
            sharedProc.sessionUpdateWorker = null
            sharedProc.sessionUpdateScope?.coroutineContext?.cancel()
            sharedProc.sessionUpdateScope = null
            sharedProc.sessionUpdateWrapped = false
        }
    }
}

private fun java.io.BufferedReader.asLineFlow() = flow {
    while (true) {
        val line = try {
            readLine()
        } catch (_: java.io.IOException) {
            break
        } ?: break
        emit(line)
    }
}.onCompletion {
    runCatching { close() }
}

private fun java.io.BufferedWriter.asLineWriter(): suspend (String) -> Unit = { line ->
    write(line)
    newLine()
    flush()
}

private fun AcpClientService.updateRuntimeMetadataFromConfigOptionsNotification(
    adapterName: String,
    params: kotlinx.serialization.json.JsonElement?
) {
    val (sessionId, configOptions) = extractConfigOptionsUpdate(params) ?: return
    val adapterInfo = AcpAdapterPaths.getAdapterInfo(adapterName)
    val rawMetadata = runtimeMetadataFromConfigOptionsJson(configOptions, adapterInfo)
    val metadata = applyAdapterRuntimePreferences(
        adapterInfo = adapterInfo,
        currentModelId = rawMetadata.currentModelId,
        availableModels = rawMetadata.availableModels,
        modelConfigId = rawMetadata.modelConfigId,
        currentModeId = rawMetadata.currentModeId,
        availableModes = rawMetadata.availableModes,
        modeConfigId = rawMetadata.modeConfigId,
        currentReasoningEffortId = rawMetadata.currentReasoningEffortId,
        availableReasoningEfforts = rawMetadata.availableReasoningEfforts,
        reasoningEffortConfigId = rawMetadata.reasoningEffortConfigId
    )
    adapterRuntimeMetadataMap[adapterName] = metadata
    val targetContext = synchronized(liveOwnerBySessionId) {
        liveOwnerBySessionId[sessionId]?.let { ownerChatId -> sessions[ownerChatId] }
    }
    targetContext?.activeModelIdRef?.set(metadata.currentModelId)
    targetContext?.activeModeIdRef?.set(metadata.currentModeId)
    targetContext?.activeReasoningEffortIdRef?.set(metadata.currentReasoningEffortId)
}

internal fun AcpClientService.extractAvailableCommands(params: kotlinx.serialization.json.JsonElement?): List<AvailableCommandPayload>? {
    val paramsObject = params as? JsonObject ?: return null
    val updateObject = paramsObject["update"] as? JsonObject ?: return null
    val updateType = (updateObject["sessionUpdate"] as? JsonPrimitive)?.contentOrNull ?: return null
    if (updateType != "available_commands_update") return null

    val commands = (updateObject["availableCommands"] as? JsonArray)
        ?.mapNotNull { element ->
            val commandObject = element as? JsonObject ?: return@mapNotNull null
            val name = (commandObject["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            val description = (commandObject["description"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val inputHint = ((commandObject["input"] as? JsonObject)?.get("hint") as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
            AvailableCommandPayload(
                name = name,
                description = description,
                inputHint = inputHint
            )
        }
        ?: return emptyList()

    return commands
}
