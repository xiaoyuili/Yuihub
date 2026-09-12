package me.yui.yuihub.data.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.yui.yuihub.data.model.InjectionPosition
import me.yui.yuihub.data.model.Lorebook
import me.yui.yuihub.data.model.PromptInjection
import me.yui.yuihub.data.model.SelectiveLogic
import me.yui.yuihub.utils.JsonInstant
import me.yui.yuihub.utils.toLocalString
import java.time.LocalDateTime
import kotlin.uuid.Uuid

@Serializable
data class ExportData(
    val version: Int = 1,
    val type: String,
    val data: JsonElement
)

/** 额外导出格式（与第三方生态互通） */
data class AlternativeExport(
    val json: String,
    val fileName: String,
)

interface ExportSerializer<T> {
    val type: String

    fun export(data: T): ExportData
    fun import(context: Context, uri: Uri): Result<T>

    /** 可选的额外导出格式（如 SillyTavern 世界书）；返回 null 表示不支持 */
    fun exportAlternative(data: T): AlternativeExport? = null

    // 获取导出文件名
    fun getExportFileName(data: T): String = "${type}.json"

    // 便捷方法：直接导出为 JSON 字符串
    fun exportToJson(data: T, json: Json = DefaultJson): String {
        return json.encodeToString(ExportData.serializer(), export(data))
    }

    // 读取 URI 内容的便捷方法
    fun readUri(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Failed to read file")
    }

    fun getUriFileName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) cursor.getString(nameIndex) else null
            } else null
        }
    }

    companion object {
        val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }
    }
}

object ModeInjectionSerializer : ExportSerializer<PromptInjection.ModeInjection> {
    override val type = "mode_injection"

    override fun getExportFileName(data: PromptInjection.ModeInjection): String {
        return "${data.name.ifEmpty { type }}.json"
    }

    override fun export(data: PromptInjection.ModeInjection): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(context: Context, uri: Uri): Result<PromptInjection.ModeInjection> {
        return runCatching {
            val json = readUri(context, uri)
            // 首先尝试解析为自己的格式
            tryImportNative(json)
                ?: throw IllegalArgumentException("Unsupported format")
        }
    }

    private fun tryImportNative(json: String): PromptInjection.ModeInjection? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(
                ExportData.serializer(),
                json
            )
            if (exportData.type != type) return null
            ExportSerializer.DefaultJson
                .decodeFromJsonElement<PromptInjection.ModeInjection>(exportData.data)
                .copy(id = Uuid.random())
        }.getOrNull()
    }
}

object LorebookSerializer : ExportSerializer<Lorebook> {
    override val type = "lorebook"

    override fun getExportFileName(data: Lorebook): String {
        return "${data.name.ifEmpty { type }}.json"
    }

    override fun export(data: Lorebook): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(context: Context, uri: Uri): Result<Lorebook> {
        return runCatching {
            val json = readUri(context, uri)
            // 首先尝试解析为自己的格式
            tryImportNative(json)
            // 然后尝试解析为 SillyTavern 格式
                ?: tryImportSillyTavern(json, getUriFileName(context, uri)?.removeSuffix(".json"))
                ?: throw IllegalArgumentException("Unsupported format")
        }
    }

    private fun tryImportNative(json: String): Lorebook? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(
                ExportData.serializer(),
                json
            )
            if (exportData.type != type) return null
            ExportSerializer.DefaultJson
                .decodeFromJsonElement<Lorebook>(exportData.data)
                .copy(
                    id = Uuid.random(),
                    entries = ExportSerializer.DefaultJson
                        .decodeFromJsonElement<Lorebook>(exportData.data)
                        .entries.map { it.copy(id = Uuid.random()) }
                )
        }.getOrNull()
    }

    private fun tryImportSillyTavern(json: String, fileName: String?): Lorebook? {
        // 先试 SillyTavern World Info（entries 为对象映射）
        tryImportSillyTavernWorldInfo(json, fileName)?.let { return it }
        // 再试角色卡 CCv2/CCv3（data.character_book，entries 为数组）
        return tryImportCharacterCard(json, fileName)
    }

    private fun tryImportSillyTavernWorldInfo(json: String, fileName: String?): Lorebook? {
        val parsed = runCatching {
            ExportSerializer.DefaultJson.decodeFromString(
                SillyTavernLorebook.serializer(),
                json
            )
        }.getOrNull() ?: return null
        if (parsed.entries.isEmpty()) return null
        return Lorebook(
            id = Uuid.random(),
            name = parsed.name?.takeIf { it.isNotBlank() }
                ?: fileName ?: LocalDateTime.now().toLocalString(),
            description = parsed.description.orEmpty(),
            enabled = true,
            entries = parsed.entries.values.map { it.toRegexInjection() },
            recursiveScanning = parsed.recursiveScanning ?: false,
            tokenBudget = parsed.tokenBudget ?: 0,
        )
    }

    /** 角色卡 V2/V3：data.character_book（V3 另支持顶层直接用 character_book 字段） */
    private fun tryImportCharacterCard(json: String, fileName: String?): Lorebook? {
        val root = runCatching { JsonInstant.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        val data = root["data"] as? JsonObject ?: root
        val book = data["character_book"] as? JsonObject ?: data["characterBook"] as? JsonObject
            ?: return null
        val entriesElement = book["entries"] ?: return null
        val entries = runCatching {
            ExportSerializer.DefaultJson.decodeFromJsonElement<List<CharacterBookEntry>>(entriesElement)
        }.getOrNull() ?: return null
        if (entries.isEmpty()) return null
        // 名称优先取书自身名称，其次角色名
        val charName = data["name"]?.jsonPrimitive?.contentOrNull
        val bookName = book["name"]?.jsonPrimitive?.contentOrNull
        return Lorebook(
            id = Uuid.random(),
            name = bookName?.takeIf { it.isNotBlank() }
                ?: charName?.takeIf { it.isNotBlank() }
                ?: fileName ?: LocalDateTime.now().toLocalString(),
            description = book["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            enabled = true,
            entries = entries.map { it.toRegexInjection() },
            recursiveScanning = book["recursive_scanning"]?.jsonPrimitive?.booleanOrNull ?: false,
            tokenBudget = book["token_budget"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    /** 导出为 SillyTavern World Info 格式，便于与社区世界书互通 */
    override fun exportAlternative(data: Lorebook): AlternativeExport? {
        val entries = buildJsonObject {
            data.entries.forEachIndexed { index, entry ->
                put(
                    index.toString(),
                    ExportSerializer.DefaultJson.encodeToJsonElement(SillyTavernEntry.serializer(), entry.toSillyTavernEntry()),
                )
            }
        }
        val root = buildJsonObject {
            put("name", data.name)
            if (data.description.isNotBlank()) put("description", data.description)
            if (data.recursiveScanning) put("recursive_scanning", true)
            if (data.tokenBudget > 0) put("token_budget", data.tokenBudget)
            put("entries", entries)
        }
        return AlternativeExport(
            json = ExportSerializer.DefaultJson.encodeToString(JsonObject.serializer(), root),
            fileName = "${data.name.ifEmpty { "lorebook" }}.world.json",
        )
    }
}

private fun PromptInjection.RegexInjection.toSillyTavernEntry() = SillyTavernEntry(
    key = keywords,
    content = content,
    comment = name.ifBlank { null },
    constant = constantActive,
    position = when (position) {
        InjectionPosition.BEFORE_SYSTEM_PROMPT -> 0
        InjectionPosition.AFTER_SYSTEM_PROMPT -> 1
        InjectionPosition.TOP_OF_CHAT -> 2
        InjectionPosition.BOTTOM_OF_CHAT -> 3
        InjectionPosition.AT_DEPTH -> 4
    },
    order = priority,
    disable = !enabled,
    depth = injectDepth,
    scanDepth = scanDepth,
    caseSensitive = caseSensitive,
    keysecondary = secondaryKeywords,
    selectiveLogic = when (selectiveLogic) {
        SelectiveLogic.AND_ANY -> 0
        SelectiveLogic.NOT_ALL -> 1
        SelectiveLogic.NOT_ANY -> 2
        SelectiveLogic.AND_ALL -> 3
    },
    role = when (role) {
        MessageRole.SYSTEM -> 0
        MessageRole.ASSISTANT -> 2
        else -> 1
    },
    probability = probability,
    useProbability = useProbability,
    matchWholeWords = matchWholeWords,
    excludeRecursion = excludeRecursion,
    preventRecursion = preventRecursion,
    delayUntilRecursion = delayUntilRecursion,
    group = group,
    groupWeight = groupWeight,
    groupOverride = groupOverride,
    useGroupScoring = useGroupScoring,
    sticky = sticky,
    cooldown = cooldown,
    delay = delay,
)

private fun SillyTavernEntry.toRegexInjection(): PromptInjection.RegexInjection {
    val useRegexKey = key.any { it.startsWith("/") && it.lastIndexOf('/') > 0 }
    return PromptInjection.RegexInjection(
        id = Uuid.random(),
        name = comment.orEmpty().ifEmpty { key.firstOrNull().orEmpty() },
        enabled = !disable,
        priority = order,
        position = mapSillyTavernPosition(position),
        injectDepth = depth,
        content = content,
        keywords = key,
        useRegex = useRegexKey,
        caseSensitive = caseSensitive ?: false,
        scanDepth = scanDepth ?: 4,
        constantActive = constant,
        secondaryKeywords = keysecondary,
        selectiveLogic = mapSelectiveLogic(selectiveLogic),
        matchWholeWords = matchWholeWords ?: true,
        useProbability = useProbability ?: true,
        probability = probability ?: 100,
        excludeRecursion = excludeRecursion ?: false,
        preventRecursion = preventRecursion ?: false,
        delayUntilRecursion = delayUntilRecursion ?: false,
        group = group.orEmpty(),
        groupWeight = groupWeight ?: 100,
        groupOverride = groupOverride ?: false,
        useGroupScoring = useGroupScoring ?: false,
        sticky = sticky ?: 0,
        cooldown = cooldown ?: 0,
        delay = delay ?: 0,
        role = mapSillyTavernRole(role),
    )
}

private fun CharacterBookEntry.toRegexInjection(): PromptInjection.RegexInjection = PromptInjection.RegexInjection(
    id = Uuid.random(),
    name = comment.orEmpty().ifEmpty { keys.firstOrNull().orEmpty() },
    enabled = enabled,
    priority = insertionOrder,
    position = if (position == "before_char") InjectionPosition.BEFORE_SYSTEM_PROMPT else InjectionPosition.AFTER_SYSTEM_PROMPT,
    content = content,
    keywords = keys,
    useRegex = useRegex ?: false,
    caseSensitive = caseSensitive ?: false,
    constantActive = constant,
    secondaryKeywords = secondaryKeys,
    selectiveLogic = if (selective) SelectiveLogic.AND_ANY else SelectiveLogic.AND_ANY,
)

private fun mapSelectiveLogic(value: Int?): SelectiveLogic = when (value) {
    1 -> SelectiveLogic.NOT_ALL
    2 -> SelectiveLogic.NOT_ANY
    3 -> SelectiveLogic.AND_ALL
    else -> SelectiveLogic.AND_ANY
}

private fun mapSillyTavernRole(value: Int?): MessageRole = when (value) {
    0 -> MessageRole.SYSTEM
    2 -> MessageRole.ASSISTANT
    else -> MessageRole.USER
}

@Serializable
private data class SillyTavernLorebook(
    val name: String? = null,
    val description: String? = null,
    @SerialName("recursive_scanning")
    val recursiveScanning: Boolean? = null,
    @SerialName("token_budget")
    val tokenBudget: Int? = null,
    val entries: Map<String, SillyTavernEntry> = emptyMap(),
)

private fun mapSillyTavernPosition(position: Int): InjectionPosition = when (position) {
    0 -> InjectionPosition.BEFORE_SYSTEM_PROMPT
    1 -> InjectionPosition.AFTER_SYSTEM_PROMPT
    2 -> InjectionPosition.AFTER_SYSTEM_PROMPT // Author's Note top
    3 -> InjectionPosition.BOTTOM_OF_CHAT    // Author's Note bottom
    4 -> InjectionPosition.AT_DEPTH          // @Depth
    else -> InjectionPosition.AFTER_SYSTEM_PROMPT
}

@Serializable
private data class SillyTavernEntry(
    val key: List<String> = emptyList(),
    val keysecondary: List<String> = emptyList(),
    val content: String = "",
    val comment: String? = null,
    val constant: Boolean = false,
    val position: Int = 0,
    val order: Int = 100,
    val disable: Boolean = false,
    val depth: Int = 4,
    val scanDepth: Int? = null,
    val caseSensitive: Boolean? = null,
    val selectiveLogic: Int? = null,
    val role: Int? = null,
    val probability: Int? = null,
    val useProbability: Boolean? = null,
    val matchWholeWords: Boolean? = null,
    val excludeRecursion: Boolean? = null,
    val preventRecursion: Boolean? = null,
    val delayUntilRecursion: Boolean? = null,
    val group: String? = null,
    val groupWeight: Int? = null,
    val groupOverride: Boolean? = null,
    val useGroupScoring: Boolean? = null,
    val sticky: Int? = null,
    val cooldown: Int? = null,
    val delay: Int? = null,
)

/** Character Card V2/V3 的 character_book.entries 元素 */
@Serializable
private data class CharacterBookEntry(
    val keys: List<String> = emptyList(),
    val secondary_keys: List<String> = emptyList(),
    val content: String = "",
    val enabled: Boolean = true,
    val insertion_order: Int = 100,
    val case_sensitive: Boolean? = null,
    val name: String? = null,
    val comment: String? = null,
    val selective: Boolean = false,
    val constant: Boolean = false,
    val position: String = "after_char",
    val use_regex: Boolean? = null,
) {
    val secondaryKeys: List<String> get() = secondary_keys
    val insertionOrder: Int get() = insertion_order
    val caseSensitive: Boolean? get() = case_sensitive
    val useRegex: Boolean? get() = use_regex
}
