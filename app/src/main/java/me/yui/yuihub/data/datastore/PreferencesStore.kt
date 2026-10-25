package me.yui.yuihub.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.yui.yuihub.AppScope
import me.yui.yuihub.data.ai.mcp.McpServerConfig
import me.yui.yuihub.data.ai.prompts.COMPACTION_CHECKPOINT_PREAMBLE
import me.yui.yuihub.data.ai.prompts.DEFAULT_CHILD_AGENT_PROMPT
import me.yui.yuihub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.yui.yuihub.data.ai.prompts.DEFAULT_MEMORY_CONSOLIDATE_PROMPT
import me.yui.yuihub.data.ai.prompts.DEFAULT_MEMORY_EXTRACT_PROMPT
import me.yui.yuihub.data.ai.prompts.DEFAULT_MEMORY_SNAPSHOT_PROMPT
import me.yui.yuihub.data.ai.prompts.DEFAULT_SCHEDULED_TASK_PROMPT
import me.yui.yuihub.data.ai.prompts.DEFAULT_SKILLS_PROMPT
import me.yui.yuihub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.yui.yuihub.data.ai.prompts.DEFAULT_VISION_PROMPT
import me.yui.yuihub.data.ai.prompts.DEFAULT_WORKSPACE_PROMPT
import me.yui.yuihub.data.ai.AGENT_TOOL_STYLE_PROMPT as DEFAULT_AGENT_TOOL_STYLE_PROMPT
import me.yui.yuihub.data.datastore.migration.PreferenceStoreV1Migration
import me.yui.yuihub.data.datastore.migration.PreferenceStoreV2Migration
import me.yui.yuihub.data.datastore.migration.PreferenceStoreV3Migration
import me.yui.yuihub.data.datastore.migration.PreferenceStoreV4Migration
import me.yui.yuihub.data.datastore.migration.PreferenceStoreV5Migration
import me.yui.yuihub.data.model.Assistant
import me.yui.yuihub.data.model.Avatar
import me.yui.yuihub.data.model.InjectionPosition
import me.yui.yuihub.data.model.Lorebook
import me.yui.yuihub.data.model.PromptInjection
import me.yui.yuihub.data.model.BuiltinSubagentPersonas
import me.yui.yuihub.data.model.SubagentPersona
import me.yui.yuihub.data.model.Tag
import me.yui.yuihub.ui.theme.CustomTheme
import me.yui.yuihub.ui.theme.PresetThemes
import me.yui.yuihub.utils.JsonInstant
import me.yui.yuihub.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

private const val TAG = "PreferencesStore"
private const val SETTINGS_STORE_NAME = "settings"

// 读取失败时的最大重试次数
private const val READ_MAX_RETRIES = 3

@Volatile
private var settingsDataStore: DataStore<Preferences>? = null

// 进程内单例, 同一文件只能存在一个 DataStore 实例
private val Context.settingsStore: DataStore<Preferences>
    get() = settingsDataStore ?: synchronized(SettingsStore::class) {
        settingsDataStore ?: createSettingsDataStore(applicationContext).also { settingsDataStore = it }
    }

fun settingsDataStoreFile(context: Context): File = context.preferencesDataStoreFile(SETTINGS_STORE_NAME)

private fun createSettingsDataStore(
    context: Context,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
): DataStore<Preferences> {
    val file = settingsDataStoreFile(context)
    return PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { exception ->
            // 文件已损坏无法解析, 先留一份原文件用于排查/抢救, 再重建为空
            Log.e(TAG, "Settings datastore corrupted, resetting", exception)
            runCatching {
                file.copyTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
            }.onFailure {
                Log.e(TAG, "Failed to backup corrupted settings file", it)
            }
            emptyPreferences()
        },
        migrations = listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration(),
            PreferenceStoreV4Migration(),
            PreferenceStoreV5Migration(),
        ),
        scope = scope,
        produceFile = { file },
    )
}

suspend fun restoreSettingsBeforeStartup(context: Context, settings: Settings) {
    val job = SupervisorJob()
    val scope = CoroutineScope(job + Dispatchers.IO)
    try {
        createSettingsDataStore(context.applicationContext, scope).edit { preferences ->
            preferences.writeSettings(settings)
        }
    } finally {
        withContext(NonCancellable) {
            job.cancelAndJoin()
        }
    }
}

private fun MutablePreferences.writeSettings(settings: Settings) {
    this[SettingsStore.DYNAMIC_COLOR] = settings.dynamicColor
    this[SettingsStore.THEME_ID] = settings.themeId
    this[SettingsStore.CUSTOM_THEMES] = JsonInstant.encodeToString(settings.customThemes)
    this[SettingsStore.DEVELOPER_MODE] = settings.developerMode
    this[SettingsStore.DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)
    this[SettingsStore.NETWORK_SETTING] = JsonInstant.encodeToString(settings.networkSetting)

    this[SettingsStore.FAVORITE_MODELS] = JsonInstant.encodeToString(settings.favoriteModels)
    this[SettingsStore.SELECT_MODEL] = settings.chatModelId.toString()
    if (settings.titleModelId != null) {
        this[SettingsStore.TITLE_MODEL] = settings.titleModelId.toString()
    } else {
        remove(SettingsStore.TITLE_MODEL)
    }
    this[SettingsStore.TITLE_MODEL_REASONING_LEVEL] = settings.titleModelReasoningLevel.name
    this[SettingsStore.IMAGE_GENERATION_MODEL] = settings.imageGenerationModelId.toString()
    if (settings.visionModelId != null) {
        this[SettingsStore.VISION_MODEL] = settings.visionModelId.toString()
    } else {
        remove(SettingsStore.VISION_MODEL)
    }
    this[SettingsStore.TITLE_PROMPT] = settings.titlePrompt
    this[SettingsStore.COMPRESS_PROMPT] = settings.compressPrompt
    this[SettingsStore.MEMORY_EXTRACT_PROMPT] = settings.memoryExtractPrompt
    this[SettingsStore.MEMORY_CONSOLIDATE_PROMPT] = settings.memoryConsolidatePrompt
    this[SettingsStore.MEMORY_SNAPSHOT_PROMPT] = settings.memorySnapshotPrompt
    this[SettingsStore.WORKSPACE_PROMPT] = settings.workspacePrompt
    this[SettingsStore.CHILD_AGENT_PROMPT] = settings.childAgentPrompt
    this[SettingsStore.SKILLS_PROMPT] = settings.skillsPrompt
    this[SettingsStore.SCHEDULED_TASK_PROMPT] = settings.scheduledTaskPrompt
    this[SettingsStore.VISION_PROMPT] = settings.visionPrompt
    this[SettingsStore.COMPACTION_CHECKPOINT_PROMPT] = settings.compactionCheckpointPrompt
    this[SettingsStore.AGENT_TOOL_STYLE_PROMPT] = settings.agentToolStylePrompt
    this[SettingsStore.DISABLED_PROMPTS] = settings.disabledPromptIds
    this[SettingsStore.AUTO_COMPRESS_ENABLED] = settings.autoCompressEnabled
    this[SettingsStore.AUTO_COMPRESS_THRESHOLD] = settings.autoCompressThreshold.coerceIn(0.5f, 0.95f)
    this[SettingsStore.MEMORY_HIT_TOP_K] = settings.memoryHitTopK.coerceIn(0, 3)
    this[SettingsStore.MEMORY_HIT_CHAR_BUDGET] = settings.memoryHitCharBudget.coerceIn(300, 900)
    this[SettingsStore.MEMORY_CORE_CHAR_BUDGET] = settings.memoryCoreCharBudget.coerceIn(800, 2400)
    this[SettingsStore.MEMORY_ORGANIZE_GROWTH] = settings.memoryOrganizeGrowth.coerceIn(10, 50)
    if (settings.memoryMaintenanceModelId != null) {
        this[SettingsStore.MEMORY_MAINTENANCE_MODEL] = settings.memoryMaintenanceModelId.toString()
    } else {
        remove(SettingsStore.MEMORY_MAINTENANCE_MODEL)
    }
    this[SettingsStore.TOOL_SCHEMA_COMPACT_LEVEL] = settings.toolSchemaCompactLevel.coerceIn(0, 2)

    this[SettingsStore.PROVIDERS] = JsonInstant.encodeToString(settings.providers)

    this[SettingsStore.ASSISTANTS] = JsonInstant.encodeToString(settings.assistants)
    this[SettingsStore.SELECT_ASSISTANT] = settings.assistantId.toString()
    this[SettingsStore.ASSISTANT_TAGS] = JsonInstant.encodeToString(settings.assistantTags)
    this[SettingsStore.SUBAGENT_PERSONAS] = JsonInstant.encodeToString(settings.subagentPersonas)

    this[SettingsStore.SEARCH_SERVICES] = JsonInstant.encodeToString(settings.searchServices)
    this[SettingsStore.SEARCH_COMMON] = JsonInstant.encodeToString(settings.searchCommonOptions)
    this[SettingsStore.SEARCH_SELECTED] = settings.searchServiceSelected.coerceIn(0, maxOf(settings.searchServices.size - 1, 0))

    this[SettingsStore.MCP_SERVERS] = JsonInstant.encodeToString(settings.mcpServers)
    this[SettingsStore.MODE_INJECTIONS] = JsonInstant.encodeToString(settings.modeInjections)
    this[SettingsStore.LOREBOOKS] = JsonInstant.encodeToString(settings.lorebooks)
    this[SettingsStore.KEEP_AWAKE_ENABLED] = settings.keepAwakeEnabled
    this[SettingsStore.BACKUP_REMINDER_CONFIG] = JsonInstant.encodeToString(settings.backupReminderConfig)
    this[SettingsStore.MODEL_CATALOG_ENABLED] = settings.modelCatalogEnabled
    this[SettingsStore.LAUNCH_COUNT] = settings.launchCount
}

class SettingsStore(
    context: Context,
    scope: AppScope,
) : KoinComponent {
    companion object {
        // 版本号
        val VERSION = intPreferencesKey("data_version")

        // UI设置
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val NETWORK_SETTING = stringPreferencesKey("network_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

        // 模型选择
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        val TITLE_MODEL_REASONING_LEVEL = stringPreferencesKey("title_model_reasoning_level")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val VISION_MODEL = stringPreferencesKey("vision_model")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")
        val MEMORY_EXTRACT_PROMPT = stringPreferencesKey("memory_extract_prompt")
        val MEMORY_CONSOLIDATE_PROMPT = stringPreferencesKey("memory_consolidate_prompt")
        val MEMORY_SNAPSHOT_PROMPT = stringPreferencesKey("memory_snapshot_prompt")
        val WORKSPACE_PROMPT = stringPreferencesKey("workspace_prompt")
        val CHILD_AGENT_PROMPT = stringPreferencesKey("child_agent_prompt")
        val SKILLS_PROMPT = stringPreferencesKey("skills_prompt")
        val SCHEDULED_TASK_PROMPT = stringPreferencesKey("scheduled_task_prompt")
        val VISION_PROMPT = stringPreferencesKey("vision_prompt")
        val COMPACTION_CHECKPOINT_PROMPT = stringPreferencesKey("compaction_checkpoint_prompt")
        val AGENT_TOOL_STYLE_PROMPT = stringPreferencesKey("agent_tool_style_prompt")
        val DISABLED_PROMPTS = stringSetPreferencesKey("disabled_prompts")

        // 上下文自动压缩
        val AUTO_COMPRESS_ENABLED = booleanPreferencesKey("auto_compress_enabled")
        val AUTO_COMPRESS_THRESHOLD = floatPreferencesKey("auto_compress_threshold")

        // 记忆注入与整理
        val MEMORY_HIT_TOP_K = intPreferencesKey("memory_hit_top_k")
        val MEMORY_HIT_CHAR_BUDGET = intPreferencesKey("memory_hit_char_budget")
        val MEMORY_CORE_CHAR_BUDGET = intPreferencesKey("memory_core_char_budget")
        val MEMORY_ORGANIZE_GROWTH = intPreferencesKey("memory_organize_growth")
        val MEMORY_MAINTENANCE_MODEL = stringPreferencesKey("memory_maintenance_model")
        val TOOL_SCHEMA_COMPACT_LEVEL = intPreferencesKey("tool_schema_compact_level")

        // 供应商
        val PROVIDERS = stringPreferencesKey("providers")

        // 助手
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")
        val SUBAGENT_PERSONAS = stringPreferencesKey("subagent_personas")

        // 搜索
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")

        // MCP
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")

        // 提示词注入
        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        val LOREBOOKS = stringPreferencesKey("lorebooks")

        // 后台保活
        val KEEP_AWAKE_ENABLED = booleanPreferencesKey("keep_awake_enabled")

        // 备份提醒
        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")

        // 模型信息自动识别（models.dev）
        val MODEL_CATALOG_ENABLED = booleanPreferencesKey("model_catalog_enabled")

        // 统计
        val LAUNCH_COUNT = intPreferencesKey("launch_count")
    }

    private val updateMutex = Mutex()

    private val dataStore = context.settingsStore

    // 上次触发过模板缓存失效的助手列表，用于把失效范围收窄到真正相关的变更
    private var templateCacheAssistants: List<Assistant>? = null

    // 共享上游：settingsFlowRaw 会被多处 .first() / collect 使用，
    // 不 share 的话每次订阅都会重读 DataStore 并全量反序列化一遍
    // 读取失败时绝不能回退为空配置, 否则默认值会被当成用户数据写回, 覆盖全部设置
    // 偶发 IO 错误重试, 仍失败则向上抛出 (文件损坏由 corruptionHandler 处理)
    val settingsFlowRaw = dataStore.data
        .retryWhen { cause, attempt ->
            val shouldRetry = cause is IOException && attempt < READ_MAX_RETRIES
            if (shouldRetry) {
                Log.w(TAG, "Failed to read settings, retrying (${attempt + 1}/$READ_MAX_RETRIES)", cause)
                delay((100L shl attempt.toInt()).milliseconds)
            }
            shouldRetry
        }.map { preferences ->
            Settings(
                favoriteModels = preferences[FAVORITE_MODELS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                titleModelId = preferences[TITLE_MODEL]?.let { Uuid.parse(it) },
                titleModelReasoningLevel = preferences[TITLE_MODEL_REASONING_LEVEL]
                    ?.let { value -> ReasoningLevel.entries.find { it.name == value } }
                    ?: ReasoningLevel.LOW,
                imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                visionModelId = preferences[VISION_MODEL]?.let { Uuid.parse(it) },
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                compressPrompt = preferences[COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
                memoryExtractPrompt = preferences[MEMORY_EXTRACT_PROMPT] ?: DEFAULT_MEMORY_EXTRACT_PROMPT,
                memoryConsolidatePrompt = preferences[MEMORY_CONSOLIDATE_PROMPT] ?: DEFAULT_MEMORY_CONSOLIDATE_PROMPT,
                memorySnapshotPrompt = preferences[MEMORY_SNAPSHOT_PROMPT] ?: DEFAULT_MEMORY_SNAPSHOT_PROMPT,
                workspacePrompt = preferences[WORKSPACE_PROMPT] ?: DEFAULT_WORKSPACE_PROMPT,
                childAgentPrompt = preferences[CHILD_AGENT_PROMPT] ?: DEFAULT_CHILD_AGENT_PROMPT,
                skillsPrompt = preferences[SKILLS_PROMPT] ?: DEFAULT_SKILLS_PROMPT,
                scheduledTaskPrompt = preferences[SCHEDULED_TASK_PROMPT] ?: DEFAULT_SCHEDULED_TASK_PROMPT,
                visionPrompt = preferences[VISION_PROMPT] ?: DEFAULT_VISION_PROMPT,
                compactionCheckpointPrompt = preferences[COMPACTION_CHECKPOINT_PROMPT] ?: COMPACTION_CHECKPOINT_PREAMBLE,
                agentToolStylePrompt = preferences[AGENT_TOOL_STYLE_PROMPT] ?: DEFAULT_AGENT_TOOL_STYLE_PROMPT,
                disabledPromptIds = preferences[DISABLED_PROMPTS] ?: emptySet(),
                autoCompressEnabled = preferences[AUTO_COMPRESS_ENABLED] ?: true,
                autoCompressThreshold = (preferences[AUTO_COMPRESS_THRESHOLD] ?: 0.85f).coerceIn(0.5f, 0.95f),
                memoryHitTopK = (preferences[MEMORY_HIT_TOP_K] ?: 2).coerceIn(0, 3),
                memoryHitCharBudget = (preferences[MEMORY_HIT_CHAR_BUDGET] ?: 440).coerceIn(300, 900),
                memoryCoreCharBudget = (preferences[MEMORY_CORE_CHAR_BUDGET] ?: 1200).coerceIn(800, 2400),
                memoryOrganizeGrowth = (preferences[MEMORY_ORGANIZE_GROWTH] ?: 20).coerceIn(10, 50),
                memoryMaintenanceModelId = preferences[MEMORY_MAINTENANCE_MODEL]?.let { Uuid.parse(it) },
                toolSchemaCompactLevel = (preferences[TOOL_SCHEMA_COMPACT_LEVEL] ?: 1).coerceIn(0, 2),
                assistantId = preferences[SELECT_ASSISTANT]?.let { Uuid.parse(it) }
                    ?: DEFAULT_ASSISTANT_ID,
                assistantTags = preferences[ASSISTANT_TAGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                subagentPersonas = preferences[SUBAGENT_PERSONAS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: BuiltinSubagentPersonas.all(),
                providers = JsonInstant.decodeFromString(preferences[PROVIDERS] ?: "[]"),
                assistants = JsonInstant.decodeFromString<List<Assistant>>(preferences[ASSISTANTS] ?: "[]")
                    .map {
                        it.copy(
                            avatar = if (it.avatar == Avatar.Dummy) Avatar.Logo else it.avatar,
                            streamOutput = true,
                        )
                    },
                dynamicColor = preferences[DYNAMIC_COLOR] == true,
                themeId = preferences[THEME_ID] ?: PresetThemes.find { it.id == "minimal" }?.id ?: PresetThemes[0].id,
                customThemes = preferences[CUSTOM_THEMES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                developerMode = preferences[DEVELOPER_MODE] == true,
                displaySetting = JsonInstant.decodeFromString<DisplaySetting>(preferences[DISPLAY_SETTING] ?: "{}")
                    .copy(
                        sendOnEnter = false,
                        skipCropImage = true,
                        enableMessageGenerationHapticEffect = false,
                        useAppIconStyleLoadingIndicator = true,
                    ),
                networkSetting = JsonInstant.decodeFromString(preferences[NETWORK_SETTING] ?: "{}"),
                searchServices = preferences[SEARCH_SERVICES]?.let {
                    // 过滤已移除的搜索供应商(如 yuihub, 以及 2.5.7 起不再提供的
                    // searxng/linkup/brave/ollama/perplexity/firecrawl/jina/bocha/tinyfish/serper/custom_js):
                    // sealed 多态反序列化遇到未知 SerialName 会直接抛异常, 导致整条设置流不可读
                    val unknownTypes = setOf(
                        "yuihub",
                        "searxng",
                        "linkup",
                        "brave",
                        "ollama",
                        "perplexity",
                        "firecrawl",
                        "jina",
                        "bocha",
                        "tinyfish",
                        "serper",
                        "custom_js",
                    )
                    runCatching {
                        JsonInstant.decodeFromString<List<SearchServiceOptions>>(it)
                    }.getOrElse { _ ->
                        runCatching {
                            val filtered = JsonInstant.parseToJsonElement(it)
                                .jsonArray
                                .filterNot { el ->
                                    el.jsonObject["type"]?.jsonPrimitive?.contentOrNull in unknownTypes
                                }
                            JsonInstant.decodeFromString<List<SearchServiceOptions>>(
                                JsonArray(filtered).toString()
                            )
                        }.getOrElse { emptyList() }
                    }.ifEmpty { listOf(SearchServiceOptions.DEFAULT) }
                } ?: listOf(SearchServiceOptions.DEFAULT),
                searchCommonOptions = preferences[SEARCH_COMMON]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: SearchCommonOptions(),
                searchServiceSelected = preferences[SEARCH_SELECTED] ?: 0,
                mcpServers = preferences[MCP_SERVERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                modeInjections = preferences[MODE_INJECTIONS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                lorebooks = preferences[LOREBOOKS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                keepAwakeEnabled = preferences[KEEP_AWAKE_ENABLED] == true,
                backupReminderConfig = preferences[BACKUP_REMINDER_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: BackupReminderConfig(),
                modelCatalogEnabled = preferences[MODEL_CATALOG_ENABLED] != false,
                launchCount = preferences[LAUNCH_COUNT] ?: 0,
            )
        }
        .map {
            val providers = it.providers.ifEmpty { DEFAULT_PROVIDERS }
                .withoutLegacyBuiltinProviders()
                .toMutableList()
            val assistants = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
            DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
                if (assistants.none { it.id == defaultAssistant.id }) {
                    assistants.add(defaultAssistant.copy())
                }
            }
            it.copy(
                providers = providers,
                assistants = assistants,
            )
        }
        .map { settings ->
            // 去重并清理无效引用
            val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
            val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
            val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
            settings.copy(
                providers = settings.providers.distinctBy { it.id }.map { provider ->
                    when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Google -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Claude -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )
                    }
                },
                assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
                    assistant.copy(
                        // 过滤掉不存在的 MCP 服务器 ID
                        mcpServers = assistant.mcpServers.filter { serverId ->
                            serverId in validMcpServerIds
                        }.toSet(),
                        // 过滤掉不存在的模式注入 ID
                        modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                            id in validModeInjectionIds
                        }.toSet(),
                        // 过滤掉不存在的 Lorebook ID
                        lorebookIds = assistant.lorebookIds.filter { id ->
                            id in validLorebookIds
                        }.toSet(),
                    )
                },
                favoriteModels = settings.favoriteModels.filter { uuid ->
                    settings.providers.flatMap { it.models }.any { it.id == uuid }
                },
                modeInjections = settings.modeInjections.distinctBy { it.id },
                lorebooks = settings.lorebooks.distinctBy { it.id },
            )
        }
        .onEach { settings ->
            // Pebble 模板缓存按助手 messageTemplate 构建，只有助手配置变化才需要失效；
            // 之前对任意设置变更（甚至 launchCount）都清空，等于白扔缓存
            if (settings.assistants != templateCacheAssistants) {
                templateCacheAssistants = settings.assistants
                get<PebbleEngine>().templateCache.invalidateAll()
            }
        }
        .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    val settingsFlow = settingsFlowRaw
        .distinctUntilChanged()
        .toMutableStateFlow(scope, Settings.dummy())

    suspend fun update(settings: Settings) = updateMutex.withLock { updateLocked(settings) }

    suspend fun update(fn: (Settings) -> Settings) = updateMutex.withLock {
        // 读-改-写整体串行，避免并发 update 互相覆盖
        updateLocked(fn(settingsFlow.value))
    }

    private suspend fun updateLocked(settings: Settings) {
        if(settings.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return
        }
        settingsFlow.value = settings
        dataStore.edit { preferences ->
            preferences.writeSettings(settings)
        }
    }

    // 只原子地修改单个 key, 不能用 update() 写回整份快照
    suspend fun incrementLaunchCount(): Int {
        var count = 0
        dataStore.edit { preferences ->
            count = (preferences[LAUNCH_COUNT] ?: 0) + 1
            preferences[LAUNCH_COUNT] = count
        }
        return count
    }

    suspend fun updateAssistant(assistantId: Uuid) {
        // 冷启动进聊天页时会调这里; assistantId 未变时直接跳过,
        // 避免每次启动都全量序列化 27 个 key 写回 DataStore
        if (settingsFlow.value.assistantId == assistantId) return
        // 走统一的读-改-写：既受 updateMutex 保护，也会同步内存态；
        // 直接 dataStore.edit 会被并发的 update 用旧 assistantId 覆盖回去
        update { it.copy(assistantId = assistantId) }
    }

    suspend fun updateAssistantModel(assistantId: Uuid, modelId: Uuid) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(chatModelId = modelId)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantReasoningLevel(assistantId: Uuid, reasoningLevel: ReasoningLevel) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(reasoningLevel = reasoningLevel)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantWebSearch(assistantId: Uuid, enabled: Boolean) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(enableWebSearch = enabled)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantInjections(
        assistantId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
    ) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(
                            modeInjectionIds = modeInjectionIds,
                            lorebookIds = lorebookIds,
                        )
                    } else {
                        assistant
                    }
                }
            )
        }
    }
}

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = false,
    val themeId: String = "minimal",
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val networkSetting: NetworkSetting = NetworkSetting(),
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val titleModelId: Uuid? = null,
    val titleModelReasoningLevel: ReasoningLevel = ReasoningLevel.LOW,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val visionModelId: Uuid? = null,
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val memoryExtractPrompt: String = DEFAULT_MEMORY_EXTRACT_PROMPT,
    val memoryConsolidatePrompt: String = DEFAULT_MEMORY_CONSOLIDATE_PROMPT,
    val memorySnapshotPrompt: String = DEFAULT_MEMORY_SNAPSHOT_PROMPT,
    val memoryHitTopK: Int = 2,
    val memoryHitCharBudget: Int = 440,
    val memoryCoreCharBudget: Int = 1200,
    val memoryOrganizeGrowth: Int = 20,
    /** 提取/整理使用的模型；null = 当前聊天模型。 */
    val memoryMaintenanceModelId: Uuid? = null,
    /** 0=关闭；1=均衡（默认）；2=激进。只压缩工具描述，不删参数/能力。 */
    val toolSchemaCompactLevel: Int = 1,
    val workspacePrompt: String = DEFAULT_WORKSPACE_PROMPT,
    val childAgentPrompt: String = DEFAULT_CHILD_AGENT_PROMPT,
    val skillsPrompt: String = DEFAULT_SKILLS_PROMPT,
    val scheduledTaskPrompt: String = DEFAULT_SCHEDULED_TASK_PROMPT,
    val visionPrompt: String = DEFAULT_VISION_PROMPT,
    val compactionCheckpointPrompt: String = COMPACTION_CHECKPOINT_PREAMBLE,
    val agentToolStylePrompt: String = DEFAULT_AGENT_TOOL_STYLE_PROMPT,
    val disabledPromptIds: Set<String> = emptySet(),
    // 上下文自动压缩：达到窗口占比阈值时自动压缩早期历史，默认开启
    val autoCompressEnabled: Boolean = true,
    val autoCompressThreshold: Float = 0.85f,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    // 子代理角色（空列表 = 用户删光了，不再恢复内置）
    val subagentPersonas: List<SubagentPersona> = BuiltinSubagentPersonas.all(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val modeInjections: List<PromptInjection.ModeInjection> = emptyList(),
    val lorebooks: List<Lorebook> = emptyList(),
    val keepAwakeEnabled: Boolean = false,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    // models.dev 自动识别新添加模型的上下文/模态/能力，默认开启
    val modelCatalogEnabled: Boolean = true,
    val launchCount: Int = 0,
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

@Serializable
data class NetworkSetting(
    val userAgent: String = "",
    val proxyUrl: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val enableAutoRetry: Boolean = true,
)

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,
    @SerialName("serif")
    SERIF,
    @SerialName("monospace")
    MONOSPACE,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val useAssistantAvatar: Boolean = false,
    val chatBackground: String? = null,
    val chatBackgroundOpacity: Float = 1.0f,
    val useGradientBackground: Boolean = false,
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableBlurEffect: Boolean = false,
    val inputBarBlurRadius: Float = 16f,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
)

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
    // 自动备份：按间隔在后台自动生成备份文件（保留最新一份，旧的自动备份会被删除）
    val autoBackupEnabled: Boolean = false,
    val autoBackupIntervalDays: Int = 7,
    val autoBackupLastTime: Long = 0L,
    // 最近一次自动备份的文件名，用于下次清理（只删自己的上一份，不动手动备份）
    val autoBackupLastFileName: String = "",
)

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

fun Settings.findModelById(uuid: Uuid?, fallback: Uuid? = null): Model? {
    if (uuid == null && fallback == null) return null
    return uuid?.let { this.providers.findModelById(it) }
        ?: fallback?.let { this.providers.findModelById(it) }
}

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

// 标题模型：未配置时回退当前对话模型
fun Settings.getTitleModelOrDefault(): Model? =
    findModelById(titleModelId) ?: getCurrentChatModel()

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId } ?: this.assistants.first()
}

fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}


fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(models = emptyList())
    }
    return provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "",
        systemPrompt = ""
    ),
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }
