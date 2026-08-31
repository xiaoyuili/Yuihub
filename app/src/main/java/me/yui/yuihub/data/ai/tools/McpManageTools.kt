package me.yui.yuihub.data.ai.tools

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.data.ai.mcp.McpCommonOptions
import me.yui.yuihub.data.ai.mcp.McpManager
import me.yui.yuihub.data.ai.mcp.McpServerConfig
import me.yui.yuihub.data.ai.mcp.serverUrl
import me.yui.yuihub.data.datastore.SettingsStore
import me.yui.yuihub.utils.jsonPrimitiveOrNull
import kotlin.uuid.Uuid

/**
 * 让模型自行登记/修改/删除 MCP 服务器配置的管理工具。
 *
 * 仅支持远程传输（sse / streamable_http）：本项目的 McpServerConfig 没有 stdio 变体，
 * 无法在 Android 上拉起本地进程，故不对外暴露 command/args 一类字段。
 */
fun createMcpManageTools(
    mcpManager: McpManager,
    settingsStore: SettingsStore,
): List<Tool> = listOf(
    Tool(
        name = "manage_mcp_server",
        description = """
            Manage MCP server registrations.
            Use `action` to control the operation:
            - `list`: show registered servers with id, name, transport, url and enabled state
            - `save`: add a server (pass `name` + `url` + `transport`), or update an existing one when `id` is given
            - `delete`: remove a server (pass `id`, or `name` to match by name)
            `transport` must be `streamable_http` or `sse`. Optional `headers` is a JSON object of
            request headers (use it for API keys / Authorization). Optional `enable` defaults to true.
            Saved servers connect in the background; their tools become available shortly after.
            Only register servers the user asked for or clearly needs — a server can expose many tools.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("list")
                                add("save")
                                add("delete")
                            },
                        )
                        put("description", "Operation to perform")
                    })
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Server id (UUID). Omit to add, pass to update, use for delete")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Display name of the server")
                    })
                    put("url", buildJsonObject {
                        put("type", "string")
                        put("description", "Server endpoint URL (required for save)")
                    })
                    put("transport", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("streamable_http")
                                add("sse")
                            },
                        )
                        put("description", "Transport type, defaults to streamable_http")
                    })
                    put("headers", buildJsonObject {
                        put("type", "object")
                        put("description", "Optional HTTP headers as key-value pairs")
                    })
                    put("enable", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Whether the server is enabled, defaults to true")
                    })
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            when (obj["action"]?.jsonPrimitive?.contentOrNull.orEmpty()) {
                "list" -> listOf(UIMessagePart.Text(renderServers(currentServers(settingsStore))))

                "save" -> saveServer(obj, mcpManager, settingsStore)

                "delete" -> deleteServer(obj, mcpManager, settingsStore)

                else -> listOf(UIMessagePart.Text("Unknown action"))
            }
        },
    ),
)

private suspend fun currentServers(settingsStore: SettingsStore): List<McpServerConfig> =
    settingsStore.settingsFlowRaw.first().mcpServers

private suspend fun saveServer(
    obj: JsonObject,
    mcpManager: McpManager,
    settingsStore: SettingsStore,
): List<UIMessagePart> {
    val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val idText = obj["id"]?.jsonPrimitive?.contentOrNull?.trim()
    val existing = currentServers(settingsStore)
    val target = idText?.takeIf { text -> text.isNotEmpty() }?.let { text ->
        existing.firstOrNull { it.id.toString() == text }
    } ?: obj["name"]?.jsonPrimitive?.contentOrNull?.trim()?.let { name ->
        existing.firstOrNull { it.commonOptions.name == name }
    }

    val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: target?.commonOptions?.name.orEmpty()
    val finalUrl = url.ifEmpty { target?.serverUrl.orEmpty() }
    require(finalUrl.isNotEmpty()) { "url is required when adding a server" }

    val transport = obj["transport"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: target?.let { transportOf(it) }
        ?: "streamable_http"
    val enable = parseBoolean(obj["enable"]) ?: target?.commonOptions?.enable ?: true
    val headers = obj["headers"].asHeaderPairs() ?: target?.commonOptions?.headers.orEmpty()

    val commonOptions = McpCommonOptions(
        enable = enable,
        name = name,
        headers = headers,
        tools = target?.commonOptions?.tools ?: emptyList(),
        oauth = target?.commonOptions?.oauth,
    )
    val config = when (transport) {
        "sse" -> McpServerConfig.SseTransportServer(
            id = target?.id ?: Uuid.random(),
            commonOptions = commonOptions,
            url = finalUrl,
        )

        else -> McpServerConfig.StreamableHTTPServer(
            id = target?.id ?: Uuid.random(),
            commonOptions = commonOptions,
            url = finalUrl,
        )
    }

    val added = target == null
    settingsStore.update { settings ->
        settings.copy(
            mcpServers = if (added) {
                settings.mcpServers + config
            } else {
                settings.mcpServers.map { if (it.id == config.id) config else it }
            },
        )
    }
    mcpManager.syncAll()

    val verb = if (added) "Added" else "Updated"
    return listOf(
        UIMessagePart.Text(
            "$verb MCP server '${name.ifBlank { finalUrl }}' (${transportOf(config)}) " +
                "id=${config.id}, enable=$enable",
        ),
    )
}

private suspend fun deleteServer(
    obj: JsonObject,
    mcpManager: McpManager,
    settingsStore: SettingsStore,
): List<UIMessagePart> {
    val idText = obj["id"]?.jsonPrimitive?.contentOrNull?.trim()
    val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()
    val target = currentServers(settingsStore).firstOrNull { server ->
        server.id.toString() == idText ||
            (!name.isNullOrEmpty() && server.commonOptions.name == name)
    } ?: return listOf(UIMessagePart.Text("No matching MCP server found"))

    mcpManager.removeClient(target)
    settingsStore.update { settings ->
        settings.copy(
            mcpServers = settings.mcpServers.filter { it.id != target.id },
            // 清掉各助手里对这台服务器的引用，避免留下悬空 id
            assistants = settings.assistants.map { assistant ->
                if (target.id in assistant.mcpServers) {
                    assistant.copy(mcpServers = assistant.mcpServers - target.id)
                } else {
                    assistant
                }
            },
        )
    }
    return listOf(UIMessagePart.Text("Deleted MCP server '${target.commonOptions.name}'"))
}

private fun transportOf(config: McpServerConfig): String = when (config) {
    is McpServerConfig.SseTransportServer -> "sse"
    is McpServerConfig.StreamableHTTPServer -> "streamable_http"
}

private fun JsonElement?.asHeaderPairs(): List<Pair<String, String>>? {
    val obj = this as? JsonObject ?: return null
    return obj.mapNotNull { (key, value) ->
        value.jsonPrimitiveOrNull?.contentOrNull?.let { key to it }
    }
}

/** 模型传来的布尔值可能是 JSON 字面量，也可能是 "true"/"1" 这类字符串。 */
private fun parseBoolean(element: JsonElement?): Boolean? = when (element) {
    null -> null
    is JsonPrimitive -> when {
        !element.isString -> element.booleanOrNull ?: element.intOrNull?.let { it != 0 }

        else -> element.content.trim().let { text ->
            text.toBooleanStrictOrNull()
                ?: when (text) {
                    "1" -> true
                    "0" -> false
                    else -> null
                }
        }
    }

    else -> null
}

private fun renderServers(servers: List<McpServerConfig>): String {
    if (servers.isEmpty()) return "No MCP servers registered. Use action=save to add one."
    return buildString {
        appendLine("MCP servers (${servers.size}):")
        servers.forEach { server ->
            appendLine(
                "- id=${server.id} | ${server.commonOptions.name.ifBlank { "(unnamed)" }} | " +
                    "${transportOf(server)} | ${server.serverUrl} | " +
                    "enable=${server.commonOptions.enable}",
            )
        }
    }
}
