package agentdock.settings

import kotlinx.serialization.Serializable

@Serializable
data class AudioTranscriptionFeatureState(
    val id: String,
    val title: String,
    val installed: Boolean,
    val installing: Boolean,
    val supported: Boolean,
    val status: String,
    val detail: String = "",
    val installPath: String = ""
)

@Serializable
data class AudioTranscriptionRequest(
    val requestId: String,
    val audioBase64: String
)

@Serializable
data class AudioTranscriptionResultPayload(
    val requestId: String,
    val success: Boolean,
    val text: String? = null,
    val error: String? = null
)

@Serializable
data class StopRecordingRequest(
    val requestId: String
)

@Serializable
data class AudioRecordingStatePayload(
    val recording: Boolean,
    val error: String? = null
)

@Serializable
data class AudioTranscriptionSettings(
    val language: String = "auto"
)

@Serializable
data class GitCommitGenerationSettings(
    val enabled: Boolean = false,
    val adapterId: String = "",
    val modelId: String = "",
    val instructions: String = ""
)

@Serializable
data class Workspace(
    val id: String,
    // Host display form of the root dir (e.g. //wsl.localhost/<distro>/opt/mono or C:\...).
    val rootPath: String,
    // Display name; defaults to the directory leaf, renameable.
    val name: String,
    val addedAt: Long = 0
)

@Serializable
data class GlobalSettings(
    val audioNotificationsEnabled: Boolean = true,
    val uiFontSizeOffsetPx: Int = 0,
    val userMessageBackgroundStyle: String = "default",
    val audioTranscription: AudioTranscriptionSettings = AudioTranscriptionSettings(),
    val gitCommitGeneration: GitCommitGenerationSettings = GitCommitGenerationSettings(),
    val quotaWidgetEnabled: Boolean = false,
    val executionTarget: String = "local",
    val wslDistro: String = "",
    val workspaces: List<Workspace> = emptyList(),
    val activeWorkspaceId: String = ""
)

@Serializable
data class GlobalSettingsPayload(
    val settings: GlobalSettings = GlobalSettings()
)
