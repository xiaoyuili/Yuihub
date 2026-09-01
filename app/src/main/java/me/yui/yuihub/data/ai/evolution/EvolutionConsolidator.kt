package me.yui.yuihub.data.ai.evolution

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.data.datastore.findProvider
import me.yui.yuihub.data.datastore.getFastModelOrDefault
import me.yui.yuihub.data.model.EvolutionLesson
import me.yui.yuihub.data.repository.EvolutionRepository
import me.yui.yuihub.service.backgroundTextGenerationParams

private const val TAG = "EvolutionConsolidator"

/**
 * 自进化的「自我整理」层：同类方法积累到阈值后，让模型把它们合并成
 * 更通用的一条（1+1=2），然后删除被吸收的旧条目，避免无限堆叠成记忆。
 */
class EvolutionConsolidator(
    private val evolutionRepository: EvolutionRepository,
    private val providerManager: ProviderManager,
    private val json: Json,
) {
    suspend fun consolidate(assistantId: String, settings: Settings): ConsolidationResult =
        withContext(Dispatchers.IO) {
            val model = settings.getFastModelOrDefault() ?: return@withContext ConsolidationResult(0, 0)
            val provider = model.findProvider(settings.providers)
                ?: return@withContext ConsolidationResult(0, 0)

            val all = evolutionRepository.getLessons(assistantId)
            var created = 0
            var removed = 0
            EvolutionLesson.KINDS.forEach { kind ->
                val group = all.filter { it.kind == kind }
                if (group.size < MIN_LESSONS_TO_CONSOLIDATE) return@forEach

                val merge = mergeGroup(kind, group, model, provider, settings) ?: return@forEach
                merge.merged.forEach { merged ->
                    if (merged.title.isBlank() || merged.content.isBlank()) return@forEach
                    evolutionRepository.addLesson(assistantId, kind, merged.title, merged.content)
                    created += 1
                }
                // 只删除被吸收的旧条目；未被引用的保留
                merge.absorbedIds.forEach { id ->
                    if (group.any { it.id == id }) {
                        evolutionRepository.deleteLesson(id)
                        removed += 1
                    }
                }
            }
            Log.i(TAG, "consolidate: created=$created removed=$removed")
            ConsolidationResult(created, removed)
        }

    private suspend fun mergeGroup(
        kind: String,
        group: List<EvolutionLesson>,
        model: me.rerere.ai.provider.Model,
        provider: me.rerere.ai.provider.ProviderSetting,
        settings: Settings,
    ): MergeOutcome? {
        val listing = group.joinToString("\n") { lesson ->
            "id=${lesson.id} | ${lesson.title}: ${lesson.content.take(200)}"
        }
        val prompt = """
            You are consolidating lessons an assistant has learned (kind=$kind).
            Below is a list of learned methods. Find groups of lessons that solve the SAME problem
            or describe the SAME behavior pattern, and merge each group into ONE more general method
            that covers all of them (1+1=2). Keep concrete, actionable wording. Write in the same language as the lessons.

            <lessons>
            $listing
            </lessons>

            Rules:
            - Only merge lessons that truly overlap. Do not merge unrelated ones.
            - A merged lesson must keep every distinct constraint from its sources.
            - Every source lesson you merged MUST be listed in "sources".
            - Do not invent new methods with no source.

            Respond with ONLY a JSON object:
            {"merged":[{"title":"...","content":"...","sources":[1,2]}]}
            If nothing can be merged, respond with {"merged":[]}.
        """.trimIndent()

        val handler = providerManager.getProviderByType(provider)
        val result = handler.generateText(
            providerSetting = provider,
            messages = listOf(UIMessage.user(prompt = prompt)),
            params = backgroundTextGenerationParams(model, ReasoningLevel.AUTO),
        )
        val parsed = parse(result.message.toText().trim()) ?: return null
        val validMerged = parsed.merged.filter { !it.title.isNullOrBlank() && !it.content.isNullOrBlank() }
        val absorbed = parsed.merged.flatMap { it.sources }.filter { id -> group.any { it.id == id } }
        if (validMerged.isEmpty() || absorbed.isEmpty()) return null
        return MergeOutcome(
            merged = validMerged.map { MergedLesson(it.title!!.trim(), it.content!!.trim()) },
            absorbedIds = absorbed,
        )
    }

    private fun parse(raw: String): ParsedConsolidation? {
        val cleaned = raw
            .substringAfter("```json", raw)
            .substringBefore("```", raw)
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            json.decodeFromString<ParsedConsolidation>(cleaned.substring(start, end + 1))
        }.getOrNull()
    }

    data class ConsolidationResult(
        val created: Int,
        val removed: Int,
    )

    private data class MergeOutcome(
        val merged: List<MergedLesson>,
        val absorbedIds: List<Int>,
    )

    @Serializable
    private data class ParsedConsolidation(
        val merged: List<ParsedMerged> = emptyList(),
    )

    @Serializable
    private data class ParsedMerged(
        val title: String? = null,
        val content: String? = null,
        val sources: List<Int> = emptyList(),
    )

    private data class MergedLesson(
        val title: String,
        val content: String,
    )

    companion object {
        // 同类条目达到该数量才触发整理，避免频繁调用
        const val MIN_LESSONS_TO_CONSOLIDATE = 4
    }
}