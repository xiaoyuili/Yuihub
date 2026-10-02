package me.yui.yuihub.data.datastore.migration

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.yui.yuihub.utils.JsonInstant

private const val TAG = "SettingsJsonMigrator"

/**
 * 对备份文件中的 settings.json 应用与 DataStore migration 相同的迁移逻辑。
 *
 * DataStore migration 作用于分散的 key-value 存储，而备份文件中的 settings.json
 * 是整个 [me.yui.yuihub.data.datastore.Settings] 对象的序列化结果。
 * 此工具类负责在反序列化前对旧格式的 JSON 执行等价的迁移操作。
 */
object SettingsJsonMigrator {

    /**
     * 对 settings JSON 字符串依次应用所有版本的迁移。
     * 若发生异常则返回原始 JSON，不中断恢复流程。
     */
    fun migrate(settingsJson: String): String {
        return runCatching {
            val root = JsonInstant.parseToJsonElement(settingsJson).jsonObject.toMutableMap()

            // V1: 修复 mcpServers 中全限定类名的 type 字段
            root["mcpServers"]?.let { element ->
                val migrated = migrateMcpServersJson(JsonInstant.encodeToString(element))
                root["mcpServers"] = JsonInstant.parseToJsonElement(migrated)
            }

            // V2: 修复 assistants 中 UIMessagePart 的 type 字段
            root["assistants"]?.let { element ->
                val migrated = migrateAssistantsJson(JsonInstant.encodeToString(element))
                root["assistants"] = JsonInstant.parseToJsonElement(migrated)
            }

            // V3: 重写上游 RikkaHub 备份中的多态 type 全限定名。
            // 上游包名是 me.rerere.rikkahub，fork 是 me.yui.yuihub；多态序列化
            // （Avatar / UIMessagePart 等）的 type 字段存全限定类名，不改写则
            // 反序列化直接失败（如 displaySetting.userAvatar），整个 settings
            // 被丢弃——表现为「供应商 / 世界书都没导入」。两边类层次自 fork
            // 以来保持同步，因此整体前缀替换是安全的。
            val migratedRoot = rewriteUpstreamTypeNames(JsonObject(root))

            JsonInstant.encodeToString(migratedRoot)
        }.onFailure {
            Log.e(TAG, "migrate: Failed to migrate settings JSON, using original", it)
        }.getOrDefault(settingsJson)
    }

    /** 上游 RikkaHub 备份中的多态 type 前缀 → fork 前缀 */
    private const val UPSTREAM_PACKAGE = "me.rerere.rikkahub"
    private const val FORK_PACKAGE = "me.yui.yuihub"

    /**
     * 递归重写 JSON 中所有 "type" 字段的包前缀（me.rerere.rikkahub.* → me.yui.yuihub.*）。
     *
     * 只改写以 UPSTREAM_PACKAGE 开头的值（含内部类用 . 分隔，如
     * me.rerere.rikkahub.data.model.Avatar.Dummy）；其它值原样保留。
     * 对不含上游类名的 JSON 是无操作，因此对 fork 自身备份也安全。
     */
    internal fun rewriteUpstreamTypeNames(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
        fun rewriteString(value: String): String =
            if (value.startsWith(UPSTREAM_PACKAGE)) {
                FORK_PACKAGE + value.removePrefix(UPSTREAM_PACKAGE)
            } else {
                value
            }

        return when (element) {
            is kotlinx.serialization.json.JsonObject -> kotlinx.serialization.json.JsonObject(
                element.mapValues { (key, value) ->
                    val rewritten = rewriteUpstreamTypeNames(value)
                    if (key == "type") {
                        val primitive = rewritten as? kotlinx.serialization.json.JsonPrimitive
                        if (primitive?.isString == true) {
                            kotlinx.serialization.json.JsonPrimitive(rewriteString(primitive.content))
                        } else {
                            rewritten
                        }
                    } else {
                        rewritten
                    }
                }
            )

            is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(
                element.map(::rewriteUpstreamTypeNames)
            )

            else -> element
        }
    }
}
