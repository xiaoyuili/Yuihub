package me.yui.yuihub.data.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.data.files.SkillManager

/**
 * 让模型自行创建/修改/删除技能的管理工具。
 *
 * 与只读的 `use_skill` 分开：`use_skill` 受 assistant.enabledSkills 门控，而本工具必须
 * 无条件可用，否则模型连第一个技能都创建不出来。
 */
fun createSkillManageTools(skillManager: SkillManager): List<Tool> = listOf(
    Tool(
        name = "manage_skill",
        description = """
            Manage skills (specialized instruction packs stored as SKILL.md files).
            Use `action` to control the operation:
            - `list`: show all skills with their names and descriptions
            - `read`: read a skill's SKILL.md (pass `name`; optional `path` for another file in the skill dir)
            - `save`: create or overwrite a skill (pass `name` + `content`; optional `path` to write a file inside the skill dir)
            - `delete`: remove a whole skill (pass `name`)
            A skill's SKILL.md must start with YAML frontmatter containing `name` and `description`,
            followed by the instructions body. The `description` decides when the skill gets used,
            so make it state what task the skill covers and when to apply it.
            Skill names may contain letters, digits, '-' and '_'.
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
                                add("read")
                                add("save")
                                add("delete")
                            },
                        )
                        put("description", "Operation to perform")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Skill name (required for read/save/delete)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Full file content including frontmatter (required for save)",
                        )
                    })
                    put("path", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Relative path inside the skill directory. Omit to target SKILL.md.",
                        )
                    })
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            val action = obj["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val path = obj["path"]?.jsonPrimitive?.contentOrNull?.trim()

            when (action) {
                "list" -> listOf(UIMessagePart.Text(renderSkills(skillManager)))

                "read" -> {
                    require(name.isNotEmpty()) { "name is required for action=read" }
                    val content = if (path.isNullOrBlank()) {
                        skillManager.readSkillContent(name)
                    } else {
                        skillManager.resolveSkillFile(name, path)?.takeIf { it.isFile }
                            ?.readText()
                    } ?: "Skill '$name'${path?.let { " file '$it'" } ?: ""} not found"
                    listOf(UIMessagePart.Text(content))
                }

                "save" -> {
                    require(name.isNotEmpty()) { "name is required for action=save" }
                    val content = obj["content"]?.jsonPrimitive?.contentOrNull
                    require(!content.isNullOrBlank()) { "content is required for action=save" }
                    val result = if (path.isNullOrBlank() || path == "SKILL.md") {
                        skillManager.saveSkill(name, content)?.let { "Saved skill '${it.name}'" }
                            ?: "Failed to save skill '$name'"
                    } else {
                        val ok = skillManager.saveSkillFile(name, path, content)
                        if (ok) "Saved '$path' in skill '$name'" else "Failed to save '$path'"
                    }
                    listOf(UIMessagePart.Text(result))
                }

                "delete" -> {
                    require(name.isNotEmpty()) { "name is required for action=delete" }
                    val ok = skillManager.deleteSkill(name)
                    listOf(
                        UIMessagePart.Text(
                            if (ok) "Deleted skill '$name'" else "Skill '$name' not found",
                        ),
                    )
                }

                else -> listOf(UIMessagePart.Text("Unknown action '$action'"))
            }
        },
    ),
)

private fun renderSkills(skillManager: SkillManager): String {
    val skills = skillManager.listSkills()
    if (skills.isEmpty()) return "No skills exist yet. Use action=save to create one."
    return buildString {
        appendLine("Skills (${skills.size}):")
        skills.forEach { skill ->
            appendLine("- ${skill.name}: ${skill.description.ifBlank { "(no description)" }}")
        }
    }
}

/**
 * 供 `use_skill` 的 systemPrompt 引用，告知模型技能可被自行管理。
 */
internal fun skillManagementHint(): String =
    "You can create, update and delete skills yourself with the `manage_skill` tool."
