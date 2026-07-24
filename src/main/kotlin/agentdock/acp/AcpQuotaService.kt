package agentdock.acp

import agentdock.settings.GlobalSettingsStore
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*

data class QuotaDetail(
    val adapterId: String,
    val adapterName: String,
    val mainPercentage: Int,
    val details: List<String> = emptyList()
)

@Service(Service.Level.APP)
class AcpQuotaService : Disposable {
    private val log = Logger.getInstance(AcpQuotaService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _quotas = MutableStateFlow<Map<String, QuotaDetail>>(emptyMap())
    val quotas = _quotas.asStateFlow()
    private var pollingJob: Job? = null

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowSpecialFloatingPointValues = true
    }

    init {
        if (GlobalSettingsStore.load().quotaWidgetEnabled) {
            startPolling()
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                updateQuotas()
            }
        }
    }

    fun onQuotaWidgetEnabledChanged(enabled: Boolean) {
        if (enabled) {
            scope.launch { updateQuotas() }
            startPolling()
        } else {
            pollingJob?.cancel()
            pollingJob = null
            _quotas.update { emptyMap() }
        }
    }

    suspend fun updateQuotas() {
        val adapters = AcpAdapterConfig.getAllAdapters().values
            .filter { AcpAdapterPaths.isDownloaded(it.id) }
        supervisorScope {
            adapters.forEach { adapter ->
                launch(Dispatchers.IO) {
                    try {
                        val rawJson = when (adapter.id) {
                            "claude-code" -> AcpUsageDataFetcher.fetchClaudeUsageData()
                            else -> null
                        }
                        // Only update if we actually got a response, to avoid clearing bridge-pushed data
                        // with a background fetch that failed or returned empty (e.g. auth file transiently missing)
                        if (!rawJson.isNullOrBlank()) {
                            updateQuotaForAdapter(adapter.id, rawJson)
                        }
                    } catch (e: Exception) {
                        log.warn("Quota poll failed for adapter ${adapter.id}", e)
                    }
                }
            }
        }
    }

    fun updateQuotaForAdapter(adapterId: String, rawJson: String) {
        val trimmedId = adapterId.trim()
        val allAdapters = AcpAdapterConfig.getAllAdapters()
        val adapter = allAdapters[trimmedId]
            ?: allAdapters.values.find { it.id.equals(trimmedId, ignoreCase = true) }

        if (adapter == null) {
            log.warn("Quota update received for unknown adapter id: '$trimmedId'")
            return
        }
        if (rawJson.isBlank()) {
            log.debug("Quota update received with empty payload for ${adapter.id}")
            return
        }

        val detail = parseUsageDetail(adapter, rawJson) ?: return
        _quotas.update { it + (adapter.id to detail) }
    }

    private fun hasDisplayableQuotaReset(resetTime: String?): Boolean {
        if (resetTime.isNullOrBlank()) return false
        val time = try {
            // Chrome's Date is very lenient, Kotlin's OffsetDateTime is strict.
            // Try ISO first, then fallback to common CLI formats or timestamp.
            runCatching { java.time.OffsetDateTime.parse(resetTime).toInstant().toEpochMilli() }
                .getOrElse {
                    runCatching { java.time.LocalDateTime.parse(resetTime.replace(" ", "T")).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
                        .getOrElse { resetTime.toLongOrNull() ?: 0L }
                }
        } catch (_: Exception) { 0L }

        if (time == 0L) return false
        val diff = time - System.currentTimeMillis()
        // Allow resets that are slightly in the past (up to 5 mins) to avoid race conditions with frontend
        return diff > -CLOCK_SKEW_MS && diff < MAX_DISPLAYABLE_RESET_MS
    }

    private fun roundPercent(value: Double?): Int? = value?.let { Math.round(it).toInt().coerceIn(0, 100) }

    private fun parseUsageDetail(adapter: AcpAdapterConfig.AdapterInfo, rawJson: String): QuotaDetail? {
        return try {
            val root = jsonParser.parseToJsonElement(rawJson) as? JsonObject
                ?: return null
            val details = mutableListOf<String>()
            var mainPercent = 0

            when (adapter.id) {
                "claude-code" -> {
                    val fiveHour = root["five_hour"] as? JsonObject
                    val sevenDay = root["seven_day"] as? JsonObject
                    val fiveHourResets = (fiveHour?.get("resets_at") as? JsonPrimitive)?.contentOrNull
                    val sevenDayResets = (sevenDay?.get("resets_at") as? JsonPrimitive)?.contentOrNull

                    val fiveHourPct = if (fiveHourResets.isNullOrBlank() || hasDisplayableQuotaReset(fiveHourResets)) {
                        roundPercent((fiveHour?.get("utilization") as? JsonPrimitive)?.doubleOrNull)
                    } else null
                    val sevenDayPct = if (hasDisplayableQuotaReset(sevenDayResets)) {
                        roundPercent((sevenDay?.get("utilization") as? JsonPrimitive)?.doubleOrNull)
                    } else null

                    fiveHourPct?.let { details.add("5h: $it%") }
                    sevenDayPct?.let { details.add("7d: $it%") }

                    if (details.isNotEmpty()) {
                        mainPercent = when {
                            sevenDayPct != null && sevenDayPct > 89 && (fiveHourPct == null || fiveHourPct < 89) -> sevenDayPct
                            fiveHourPct != null -> fiveHourPct
                            else -> sevenDayPct ?: 0
                        }
                    }
                }
            }
            if (details.isEmpty()) null
            else QuotaDetail(adapter.id, adapter.name, mainPercent, details)
        } catch (e: Exception) {
            log.warn("Failed to parse usage payload for ${adapter.id}", e)
            null
        }
    }

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 5L * 60 * 1000
        private const val CLOCK_SKEW_MS = 5L * 60 * 1000
        private const val MAX_DISPLAYABLE_RESET_MS = 100L * 24 * 60 * 60 * 1000

        fun getInstance(): AcpQuotaService =
            ApplicationManager.getApplication().getService(AcpQuotaService::class.java)
    }
}
