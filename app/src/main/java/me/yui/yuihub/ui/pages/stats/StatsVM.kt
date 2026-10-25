package me.yui.yuihub.ui.pages.stats

import androidx.lifecycle.ViewModel
import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.yui.yuihub.data.datastore.SettingsStore
import me.yui.yuihub.data.db.dao.TokenUsageHour
import me.yui.yuihub.data.db.dao.TokenUsageSourceTotals
import me.yui.yuihub.data.db.dao.TokenUsageTotals
import me.yui.yuihub.data.repository.TokenUsageRepository
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth
import java.time.format.DateTimeFormatter

enum class StatsRange(val days: Long?) {
    TODAY(1),
    LAST_7(7),
    LAST_30(30),
    LAST_90(90),
    ALL(null),
}

data class StatsProviderOption(
    val id: String,
    val name: String,
)

data class StatsModelOption(
    val id: String,
    val name: String,
    val providerId: String,
)

data class StatsDailyPoint(
    val label: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
)

data class AppStats(
    val isLoading: Boolean = true,
    val totals: TokenUsageTotals = TokenUsageTotals(),
    val lifetimeTotals: TokenUsageTotals = TokenUsageTotals(),
    val daily: List<StatsDailyPoint> = emptyList(),
    val sources: List<TokenUsageSourceTotals> = emptyList(),
    val providers: List<StatsProviderOption> = emptyList(),
    val models: List<StatsModelOption> = emptyList(),
    val providerId: String? = null,
    val modelId: String? = null,
    val range: StatsRange = StatsRange.ALL,
)

class StatsVM(
    private val tokenUsageRepository: TokenUsageRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats = _stats.asStateFlow()
    private var loadJob: Job? = null

    init {
        reload()
    }

    fun setProvider(providerId: String?) {
        _stats.value = _stats.value.copy(
            providerId = providerId,
            modelId = null,
            isLoading = true,
        )
        reload()
    }

    fun setModel(modelId: String?) {
        _stats.value = _stats.value.copy(modelId = modelId, isLoading = true)
        reload()
    }

    fun setRange(range: StatsRange) {
        _stats.value = _stats.value.copy(range = range, isLoading = true)
        reload()
    }

    fun refresh() {
        _stats.value = _stats.value.copy(isLoading = true)
        reload()
    }

    private fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("StatsVM", "Failed to load token stats", e)
                _stats.value = _stats.value.copy(isLoading = false)
            }
        }
    }

    private suspend fun load() {
        val current = _stats.value
        val settings = settingsStore.settingsFlow.value
        val today = LocalDate.now()
        val start = current.range.days?.let { today.minusDays(it - 1) } ?: LocalDate.of(1970, 1, 1)
        val startDay = start.toString()
        val endDay = today.toString()

        val options = withContext(Dispatchers.IO) {
            val ledgerProviders = runCatching { tokenUsageRepository.providers() }.getOrDefault(emptyList())
            val ledgerModels = runCatching { tokenUsageRepository.models(null) }.getOrDefault(emptyList())

            val providerMap = linkedMapOf<String, String>()
            settings.providers.forEach { providerMap[it.id.toString()] = it.name }
            ledgerProviders.forEach { providerMap.putIfAbsent(it.providerId, it.providerName) }

            val modelMap = linkedMapOf<String, StatsModelOption>()
            settings.providers.forEach { provider ->
                provider.models.forEach { model ->
                    modelMap[model.id.toString()] = StatsModelOption(
                        id = model.id.toString(),
                        name = model.displayName.ifBlank { model.modelId },
                        providerId = provider.id.toString(),
                    )
                }
            }
            ledgerModels.forEach { model ->
                modelMap.putIfAbsent(
                    model.modelId,
                    StatsModelOption(
                        id = model.modelId,
                        name = model.modelName.ifBlank { model.modelId },
                        providerId = model.providerId,
                    ),
                )
            }

            providerMap.map { StatsProviderOption(it.key, it.value) } to modelMap.values.toList()
        }
        val providers = options.first
        val models = options.second

        val totals = withContext(Dispatchers.IO) {
            tokenUsageRepository.totals(
                startDay = startDay,
                endDay = endDay,
                providerId = current.providerId,
                modelId = current.modelId,
            )
        }
        val lifetime = withContext(Dispatchers.IO) {
            tokenUsageRepository.lifetimeTotals()
        }
        val sources = withContext(Dispatchers.IO) {
            tokenUsageRepository.sourceTotals(
                startDay = startDay,
                endDay = endDay,
                providerId = current.providerId,
                modelId = current.modelId,
            )
        }
        val daily = if (current.range == StatsRange.TODAY) {
            val zone = ZoneId.systemDefault()
            val startMillis = start.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMillis = start.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            val hourlyRows = withContext(Dispatchers.IO) {
                tokenUsageRepository.hourly(
                    startMillis = startMillis,
                    endMillis = endMillis,
                    providerId = current.providerId,
                    modelId = current.modelId,
                )
            }
            buildHourlyPoints(hourlyRows)
        } else {
            val dailyRows = withContext(Dispatchers.IO) {
                tokenUsageRepository.daily(
                    startDay = startDay,
                    endDay = endDay,
                    providerId = current.providerId,
                    modelId = current.modelId,
                )
            }
            buildDailyPoints(dailyRows, current.range, start, today)
        }

        val chartPoints = trimLeadingEmpty(daily, current.range)
        val latest = _stats.value
        if (latest.providerId != current.providerId ||
            latest.modelId != current.modelId ||
            latest.range != current.range
        ) {
            return
        }
        _stats.value = current.copy(
            isLoading = false,
            totals = totals,
            lifetimeTotals = lifetime,
            sources = sources,
            daily = chartPoints,
            providers = providers,
            models = models,
        )
    }

    /**
     * 长时间范围里如果前面大段没有数据，左侧会只剩空白。
     * 仅在超过三分之二的区间为空时裁掉前导空段，保留一天基线。
     */
    private fun trimLeadingEmpty(
        points: List<StatsDailyPoint>,
        range: StatsRange,
    ): List<StatsDailyPoint> {
        if (range == StatsRange.TODAY || points.size < 3) return points
        val first = points.indexOfFirst {
            it.inputTokens != 0L || it.outputTokens != 0L || it.cachedTokens != 0L
        }
        if (first <= 0) return points
        if (first < points.size * 2 / 3) return points
        return points.drop((first - 1).coerceAtLeast(0))
    }

    private fun buildHourlyPoints(rows: List<TokenUsageHour>): List<StatsDailyPoint> {
        val byHour = rows.associateBy { it.hour }
        return (0..23).map { hour ->
            val row = byHour[hour]
            StatsDailyPoint(
                label = "%02d:00".format(hour),
                inputTokens = row?.promptTokens ?: 0,
                outputTokens = row?.completionTokens ?: 0,
                cachedTokens = row?.cachedTokens ?: 0,
            )
        }
    }

    private fun buildDailyPoints(
        rows: List<me.yui.yuihub.data.db.dao.TokenUsageDaily>,
        range: StatsRange,
        start: LocalDate,
        end: LocalDate,
    ): List<StatsDailyPoint> {
        if (range == StatsRange.ALL) {
            if (rows.isEmpty()) return emptyList()
            val grouped = linkedMapOf<YearMonth, LongArray>()
            rows.forEach { row ->
                val month = runCatching { YearMonth.from(LocalDate.parse(row.day)) }.getOrNull() ?: return@forEach
                val bucket = grouped.getOrPut(month) { LongArray(3) }
                bucket[0] += row.promptTokens
                bucket[1] += row.completionTokens
                bucket[2] += row.cachedTokens
            }
            val keys = grouped.keys.sorted()
            return keys.map { month ->
                val bucket = grouped.getValue(month)
                StatsDailyPoint(
                    label = month.format(DateTimeFormatter.ofPattern("yy/MM")),
                    inputTokens = bucket[0],
                    outputTokens = bucket[1],
                    cachedTokens = bucket[2],
                )
            }
        }

        val byDay = rows.associateBy { it.day }
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
        val formatter = DateTimeFormatter.ofPattern("MM/dd")
        return days.map { day ->
            val row = byDay[day.toString()]
            StatsDailyPoint(
                label = day.format(formatter),
                inputTokens = row?.promptTokens ?: 0,
                outputTokens = row?.completionTokens ?: 0,
                cachedTokens = row?.cachedTokens ?: 0,
            )
        }
    }
}
