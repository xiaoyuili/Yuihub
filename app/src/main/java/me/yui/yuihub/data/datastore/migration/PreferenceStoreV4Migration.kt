package me.yui.yuihub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import me.yui.yuihub.data.ai.AGENT_TOOL_STYLE_PROMPT
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
import me.yui.yuihub.data.ai.prompts.LEGACY_DEFAULT_CHILD_AGENT_PROMPT
import me.yui.yuihub.data.ai.prompts.LEGACY_DEFAULT_MEMORY_CONSOLIDATE_PROMPT
import me.yui.yuihub.data.ai.prompts.LEGACY_DEFAULT_MEMORY_EXTRACT_PROMPT
import me.yui.yuihub.data.ai.prompts.LEGACY_DEFAULT_WORKSPACE_PROMPT
import me.yui.yuihub.data.ai.prompts.LEGACY_V4_AGENT_TOOL_STYLE_PROMPT
import me.yui.yuihub.data.ai.prompts.LEGACY_V4_CHILD_AGENT_PROMPT
import me.yui.yuihub.data.ai.prompts.LEGACY_V4_COMPACTION_CHECKPOINT_PREAMBLE
import me.yui.yuihub.data.ai.prompts.LEGACY_V4_COMPRESS_PROMPT
import me.yui.yuihub.data.ai.prompts.LEGACY_V4_MEMORY_CONSOLIDATE_PROMPT
import me.yui.yuihub.data.ai.prompts.LEGACY_V4_MEMORY_EXTRACT_PROMPT
import me.yui.yuihub.data.ai.prompts.LEGACY_V4_MEMORY_SNAPSHOT_PROMPT
import me.yui.yuihub.data.ai.prompts.LEGACY_V4_SCHEDULED_TASK_PROMPT
import me.yui.yuihub.data.ai.prompts.LEGACY_V4_SKILLS_PROMPT
import me.yui.yuihub.data.ai.prompts.LEGACY_V4_TITLE_PROMPT
import me.yui.yuihub.data.ai.prompts.LEGACY_V4_VISION_PROMPT
import me.yui.yuihub.data.ai.prompts.LEGACY_V4_WORKSPACE_PROMPT
import me.yui.yuihub.data.datastore.SettingsStore

/** V4：把仍等于旧默认值的提示词更新为精简版；用户编辑过的内容保持不变。 */
class PreferenceStoreV4Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 4
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        prefs.replaceIfLegacy(
            SettingsStore.WORKSPACE_PROMPT,
            DEFAULT_WORKSPACE_PROMPT,
            listOf(LEGACY_DEFAULT_WORKSPACE_PROMPT, LEGACY_V4_WORKSPACE_PROMPT),
        )
        prefs.replaceIfLegacy(
            SettingsStore.CHILD_AGENT_PROMPT,
            DEFAULT_CHILD_AGENT_PROMPT,
            listOf(LEGACY_DEFAULT_CHILD_AGENT_PROMPT, LEGACY_V4_CHILD_AGENT_PROMPT),
        )
        prefs.replaceIfLegacy(
            SettingsStore.MEMORY_EXTRACT_PROMPT,
            DEFAULT_MEMORY_EXTRACT_PROMPT,
            listOf(LEGACY_DEFAULT_MEMORY_EXTRACT_PROMPT, LEGACY_V4_MEMORY_EXTRACT_PROMPT),
        )
        prefs.replaceIfLegacy(
            SettingsStore.MEMORY_CONSOLIDATE_PROMPT,
            DEFAULT_MEMORY_CONSOLIDATE_PROMPT,
            listOf(LEGACY_DEFAULT_MEMORY_CONSOLIDATE_PROMPT, LEGACY_V4_MEMORY_CONSOLIDATE_PROMPT),
        )
        prefs.replaceIfLegacy(
            SettingsStore.TITLE_PROMPT,
            DEFAULT_TITLE_PROMPT,
            listOf(LEGACY_V4_TITLE_PROMPT),
        )
        prefs.replaceIfLegacy(
            SettingsStore.COMPRESS_PROMPT,
            DEFAULT_COMPRESS_PROMPT,
            listOf(LEGACY_V4_COMPRESS_PROMPT),
        )
        prefs.replaceIfLegacy(
            SettingsStore.COMPACTION_CHECKPOINT_PROMPT,
            COMPACTION_CHECKPOINT_PREAMBLE,
            listOf(LEGACY_V4_COMPACTION_CHECKPOINT_PREAMBLE),
        )
        prefs.replaceIfLegacy(
            SettingsStore.MEMORY_SNAPSHOT_PROMPT,
            DEFAULT_MEMORY_SNAPSHOT_PROMPT,
            listOf(LEGACY_V4_MEMORY_SNAPSHOT_PROMPT),
        )
        prefs.replaceIfLegacy(
            SettingsStore.SKILLS_PROMPT,
            DEFAULT_SKILLS_PROMPT,
            listOf(LEGACY_V4_SKILLS_PROMPT),
        )
        prefs.replaceIfLegacy(
            SettingsStore.SCHEDULED_TASK_PROMPT,
            DEFAULT_SCHEDULED_TASK_PROMPT,
            listOf(LEGACY_V4_SCHEDULED_TASK_PROMPT),
        )
        prefs.replaceIfLegacy(
            SettingsStore.VISION_PROMPT,
            DEFAULT_VISION_PROMPT,
            listOf(LEGACY_V4_VISION_PROMPT),
        )
        prefs.replaceIfLegacy(
            SettingsStore.AGENT_TOOL_STYLE_PROMPT,
            AGENT_TOOL_STYLE_PROMPT,
            listOf(LEGACY_V4_AGENT_TOOL_STYLE_PROMPT),
        )

        prefs[SettingsStore.VERSION] = 4
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

internal fun MutablePreferences.replaceIfLegacy(
    key: Preferences.Key<String>,
    replacement: String,
    legacyValues: List<String>,
) {
    if (this[key] in legacyValues) {
        this[key] = replacement
    }
}
