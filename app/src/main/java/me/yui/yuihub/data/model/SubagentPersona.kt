package me.yui.yuihub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 子代理角色（persona）。
 *
 * 默认内置三个通用角色（探索 / 审查 / 规划）；用户可在「子代理角色」页新增自定义角色。
 * 角色由 [systemPrompt]（角色提示词）与 [allowedTools]（工具白名单，空 = 全部可用）组成，
 * spawn_agent 调用时按 [id] 或 [name] 选择，未指定则用父代理默认行为。
 */
@Serializable
data class SubagentPersona(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    @SerialName("system_prompt")
    val systemPrompt: String = "",
    /** 工具白名单；空集表示不限制（继承父代理全部可用工具） */
    @SerialName("allowed_tools")
    val allowedTools: Set<String> = emptySet(),
    @SerialName("created_at")
    val createdAt: Long = 0L,
    @SerialName("updated_at")
    val updatedAt: Long = 0L,
)

/** 可被角色白名单引用的工具名（用于设置页勾选；与实际注册名一致） */
object SubagentToolCatalog {
    val ENTRIES: List<Pair<String, String>> = listOf(
        "workspace_read_file" to "读取工作区文件",
        "workspace_write_file" to "写入文件",
        "workspace_edit_file" to "编辑文件",
        "workspace_present_file" to "呈现文件给用户",
        "workspace_shell" to "执行 shell 命令",
        "search_web" to "联网搜索",
        "scrape_web" to "抓取网页",
        "memory_tool" to "读写长期记忆",
        "recent_chats" to "读取最近会话",
        "conversation_search" to "搜索历史会话",
        "use_skill" to "调用技能",
        "manage_skill" to "管理技能库",
        "manage_mcp_server" to "管理 MCP 服务",
        "eval_javascript" to "执行 JavaScript",
        "get_time_info" to "获取当前时间",
        "clipboard_tool" to "读写剪贴板",
        "get_screen_time" to "读取屏幕使用时间",
        "calendar_query" to "查询日历",
        "calendar_create" to "创建日历事件",
        "vision_analyze" to "图像理解",
    ).sortedBy { it.first }

    /** 工具名 -> 中文显示名；未知工具回退为原名 */
    fun displayName(toolName: String): String =
        ENTRIES.firstOrNull { it.first == toolName }?.second ?: toolName
}

/**
 * 内置角色：首次启动写入设置，用户可编辑/删除（删除后不再恢复）。
 */
object BuiltinSubagentPersonas {
    val EXPLORER_ID: Uuid = Uuid.parse("6b0f0c9a-6f4d-4a0e-9e4b-9d0e0a1b2c30")
    val REVIEWER_ID: Uuid = Uuid.parse("6b0f0c9a-6f4d-4a0e-9e4b-9d0e0a1b2c31")
    val PLANNER_ID: Uuid = Uuid.parse("6b0f0c9a-6f4d-4a0e-9e4b-9d0e0a1b2c32")

    fun all(now: Long = System.currentTimeMillis()): List<SubagentPersona> = listOf(
        SubagentPersona(
            id = EXPLORER_ID,
            name = "探索者",
            systemPrompt = """
                You are an exploration specialist. Your job is to answer a scoped question about a
                codebase or a body of files, and return precise findings — not to make changes.

                Method: search broadly first (list/search, grep for symbols), then read only the
                relevant ranges. Prefer reporting exact file paths with line numbers and short quoted
                snippets over prose summaries. If you cannot find something after a genuine search,
                say so explicitly and list what you checked — never guess.
            """.trimIndent(),
            allowedTools = setOf(
                "workspace_read_file",
                "workspace_shell",
                "search_web",
                "scrape_web",
            ),
            createdAt = now,
            updatedAt = now,
        ),
        SubagentPersona(
            id = REVIEWER_ID,
            name = "审查者",
            systemPrompt = """
                You are a code reviewer. Audit the given change or files for correctness, edge cases,
                security issues and concurrency hazards. Do not rewrite the code unless explicitly
                asked — report findings instead.

                Output format: a numbered list of findings, each with severity (blocker / major /
                minor), the exact file and line, why it is a problem, and a concrete suggested fix.
                If an area is clean, say so briefly rather than inventing issues. Be specific and
                evidence-based; avoid generic advice.
            """.trimIndent(),
            allowedTools = setOf(
                "workspace_read_file",
                "workspace_shell",
            ),
            createdAt = now,
            updatedAt = now,
        ),
        SubagentPersona(
            id = PLANNER_ID,
            name = "规划者",
            systemPrompt = """
                You are a planning specialist. Break the given goal into an ordered, verifiable plan
                before any implementation happens.

                Explore just enough to make the plan concrete (read the key files), then produce:
                scope, step-by-step plan with the files each step touches, risks/unknowns, and how to
                verify the result. Do not implement the change. Keep the plan as short as the task
                allows — no filler.
            """.trimIndent(),
            allowedTools = setOf(
                "workspace_read_file",
                "workspace_shell",
                "search_web",
                "scrape_web",
            ),
            createdAt = now,
            updatedAt = now,
        ),
    )
}
