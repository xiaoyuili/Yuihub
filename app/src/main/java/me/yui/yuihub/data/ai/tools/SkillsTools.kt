package me.yui.yuihub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.data.ai.prompts.DEFAULT_SKILLS_PROMPT
import me.yui.yuihub.data.files.SkillFrontmatterParser
import me.yui.yuihub.data.files.SkillMetadata
import me.yui.yuihub.data.files.SkillPaths

private const val MAX_INLINE_SKILLS = 24

fun createSkillTools(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
    prompt: String? = DEFAULT_SKILLS_PROMPT,
): List<Tool> {
    val available = allSkills.filter { it.name in enabledSkills }
    if (available.isEmpty()) return emptyList()

    return listOf(
        Tool(
            name = "use_skill",
            description = """
                Load and apply a skill to get specialized instructions or capabilities.
                Call this tool when the user's request matches one of the available skills.
            """.trimIndent(),
            systemPrompt = { _, _ ->
                if (prompt == null) {
                    ""
                } else {
                    buildString {
                        if (prompt.isNotBlank()) appendLine(prompt)
                        appendLine(skillManagementHint())
                        appendLine("<available_skills>")
                        available.take(MAX_INLINE_SKILLS).forEach { skill ->
                            val description = skill.description.replace("\n", " ").trim()
                                .let { if (it.length > 120) it.take(120) + "…" else it }
                            appendLine("  <skill>")
                            appendLine("    <name>${skill.name}</name>")
                            appendLine("    <description>$description</description>")
                            appendLine("  </skill>")
                        }
                        if (available.size > MAX_INLINE_SKILLS) {
                            appendLine("  <more count=\"${available.size - MAX_INLINE_SKILLS}\">use manage_skill action=list</more>")
                        }
                        append("</available_skills>")
                        appendLine()
                    }
                }
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "The name of the skill to use")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional relative path to a file inside the skill directory. Omit to read the default SKILL.md instructions. Only use paths extracted from Markdown links in the SKILL.md content. Do NOT guess or infer paths."
                            )
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = {
                val name = it.jsonObject["name"]?.jsonPrimitive?.content
                    ?: error("name is required")
                val skill = available.firstOrNull { skill -> skill.name == name }
                    ?: error("Skill '$name' is not available. Available skills: ${available.joinToString { it.name }}")
                val path = it.jsonObject["path"]?.jsonPrimitive?.content
                val content = if (path.isNullOrBlank()) {
                    require(skill.skillFile.exists()) { "Skill '$name' not found" }
                    SkillFrontmatterParser.extractBody(skill.skillFile.readText())
                } else {
                    val target = SkillPaths.resolveSkillFile(skill.skillDir, path)
                        ?: error("Path '$path' is outside the skill directory")
                    require(target.exists()) { "File '$path' not found in skill '$name'" }
                    target.readText()
                }
                listOf(UIMessagePart.Text(content))
            }
        )
    )
}
