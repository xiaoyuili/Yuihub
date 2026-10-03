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
            Manage skills (instruction packs stored as SKILL.md).
            `action`: list | read | save | delete — read/save/delete need `name`; save needs `content`; optional `path` targets another file inside the skill dir.
            SKILL.md = YAML frontmatter (`name`, `description`) + instructions body. `description` decides when the skill gets used — state the task and when to apply it. Names: letters, digits, '-', '_'.
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
                "list" -> renderSkills(skillManager)

                "read" -> {
                    require(name.isNotEmpty()) { "name is required for action=read" }
                    val content = if (path.isNullOrBlank()) {
                        skillManager.readSkillContent(name)
                    } else {
                        skillManager.resolveSkillFile(name, path)?.takeIf { it.isFile }
                            ?.readText()
                    }
                    if (content == null) {
                        toolErrorResult(
                            "Skill '$name'${path?.let { " file '$it'" } ?: ""} not found",
                        )
                    } else {
                        toolJson {
                            put("action", "read")
                            put("name", name)
                            path?.takeIf { it.isNotBlank() }?.let { put("path", it) }
                            put("content", content)
                        }
                    }
                }

                "save" -> {
                    require(name.isNotEmpty()) { "name is required for action=save" }
                    val content = obj["content"]?.jsonPrimitive?.contentOrNull
                    require(!content.isNullOrBlank()) { "content is required for action=save" }
                    if (path.isNullOrBlank() || path == "SKILL.md") {
                        val saved = skillManager.saveSkill(name, content)
                        if (saved == null) {
                            toolErrorResult("Failed to save skill '$name'")
                        } else {
                            toolJson {
                                put("action", "save")
                                put("name", saved.name)
                                put("saved", true)
                            }
                        }
                    } else {
                        val ok = skillManager.saveSkillFile(name, path, content)
                        if (!ok) {
                            toolErrorResult("Failed to save '$path' in skill '$name'")
                        } else {
                            toolJson {
                                put("action", "save")
                                put("name", name)
                                put("path", path)
                                put("saved", true)
                            }
                        }
                    }
                }

                "delete" -> {
                    require(name.isNotEmpty()) { "name is required for action=delete" }
                    val ok = skillManager.deleteSkill(name)
                    if (!ok) {
                        toolErrorResult("Skill '$name' not found")
                    } else {
                        toolJson {
                            put("action", "delete")
                            put("name", name)
                            put("deleted", true)
                        }
                    }
                }

                else -> toolErrorResult("Unknown action '$action' (expected list / read / save / delete)")
            }
        },
    ),
)

private fun renderSkills(skillManager: SkillManager): List<UIMessagePart> {
    val skills = skillManager.listSkills()
    // P2: /skills 下无 SKILL.md 的目录与普通文件会被 listSkills 静默过滤，
    // 模型看不到它们的存在就无法清理或复用，这里显式提示
    val skillsDir = skillManager.getSkillsDir()
    val registeredDirs = skills.mapTo(HashSet()) { it.skillDir.name }
    val unregistered = skillsDir.listFiles()
        .orEmpty()
        .filter { entry ->
            if (entry.isDirectory) {
                entry.name !in registeredDirs && !entry.name.startsWith(".")
            } else {
                !entry.name.startsWith(".")
            }
        }
        .map { it.name }
    return toolJson {
        put("action", "list")
        put("count", skills.size)
        put("skills", buildJsonArray {
            skills.forEach { skill ->
                add(buildJsonObject {
                    put("name", skill.name)
                    put("description", skill.description)
                })
            }
        })
        if (skills.isEmpty()) {
            put("hint", "No skills exist yet. Use action=save to create one.")
        }
        if (unregistered.isNotEmpty()) {
            // 这些条目对 use_skill 不可见：要么清理，要么补上合法 SKILL.md
            put("unregistered", buildJsonArray { unregistered.forEach { add(it) } })
            put(
                "unregisteredWarning",
                "These entries in /skills have no valid SKILL.md, so use_skill cannot see them. " +
                    "Clean them up or add a valid SKILL.md."
            )
        }
    }
}

/**
 * 供 `use_skill` 的 systemPrompt 引用，告知模型技能可被自行管理。
 */
internal fun skillManagementHint(): String =
    "You can create, update and delete skills yourself with the `manage_skill` tool."
