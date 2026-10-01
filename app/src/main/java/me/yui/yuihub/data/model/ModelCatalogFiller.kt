package me.yui.yuihub.data.model

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry

/** ProviderSetting 的 baseUrl（基类不含该字段，逐类型收窄） */
fun ProviderSetting.baseUrlOrNull(): String? = when (this) {
    is ProviderSetting.OpenAI -> baseUrl
    is ProviderSetting.Google -> baseUrl
    is ProviderSetting.Claude -> baseUrl
}

/** 用 models.dev 元数据填充模型字段；目录未收录到的字段保持原值 */
fun Model.withCatalogInfo(info: ModelInfo): Model = copy(
    inputModalities = if (info.inputHasImage) {
        listOf(Modality.TEXT, Modality.IMAGE)
    } else {
        listOf(Modality.TEXT)
    },
    outputModalities = if (info.outputHasImage) {
        listOf(Modality.TEXT, Modality.IMAGE)
    } else {
        listOf(Modality.TEXT)
    },
    abilities = buildList {
        if (info.toolCall) add(ModelAbility.TOOL)
        if (info.reasoning) add(ModelAbility.REASONING)
    },
    contextLength = info.contextLength ?: contextLength,
)

/**
 * 自动填充模型的元数据（模态/能力/上下文长度）。
 *
 * 优先 models.dev 目录（数据新、覆盖广）；未收录时回退内置 ModelRegistry。
 * [catalogEnabled] 为 false 或目录未加载时只走内置回退。
 */
fun fillModelMetadata(
    model: Model,
    provider: ProviderSetting,
    catalog: ModelCatalogService?,
    catalogEnabled: Boolean,
): Model {
    if (catalogEnabled && catalog != null) {
        val info = catalog.resolve(model.modelId, provider.name, provider.baseUrlOrNull())
        if (info != null) return model.withCatalogInfo(info)
    }
    return model.withRegistryInfo()
}

/** 用内置 ModelRegistry 填充（旧逻辑，作为目录未命中时的回退） */
fun Model.withRegistryInfo(): Model = copy(
    inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId),
    outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(modelId),
    abilities = ModelRegistry.MODEL_ABILITIES.getData(modelId),
    contextLength = ModelRegistry.MODEL_CONTEXT_LENGTH.getData(modelId) ?: contextLength,
)
