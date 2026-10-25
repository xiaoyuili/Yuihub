package me.yui.yuihub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import me.yui.yuihub.data.ai.prompts.DEFAULT_CHILD_AGENT_PROMPT
import me.yui.yuihub.data.ai.prompts.DEFAULT_MEMORY_CONSOLIDATE_PROMPT
import me.yui.yuihub.data.ai.prompts.DEFAULT_MEMORY_EXTRACT_PROMPT
import me.yui.yuihub.data.ai.prompts.DEFAULT_WORKSPACE_PROMPT
import me.yui.yuihub.data.ai.prompts.LEGACY_DEFAULT_CHILD_AGENT_PROMPT
import me.yui.yuihub.data.ai.prompts.LEGACY_DEFAULT_MEMORY_CONSOLIDATE_PROMPT
import me.yui.yuihub.data.ai.prompts.LEGACY_DEFAULT_MEMORY_EXTRACT_PROMPT
import me.yui.yuihub.data.ai.prompts.LEGACY_DEFAULT_WORKSPACE_PROMPT
import me.yui.yuihub.data.datastore.SettingsStore

/** 把仍等于旧默认值的长提示词换成精简版；用户编辑过的内容保持不变。 */
class PreferenceStoreV3Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 3
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        prefs.replaceIfLegacy(
            SettingsStore.WORKSPACE_PROMPT,
            LEGACY_DEFAULT_WORKSPACE_PROMPT,
            DEFAULT_WORKSPACE_PROMPT,
        )
        prefs.replaceIfLegacy(
            SettingsStore.CHILD_AGENT_PROMPT,
            LEGACY_DEFAULT_CHILD_AGENT_PROMPT,
            DEFAULT_CHILD_AGENT_PROMPT,
        )
        prefs.replaceIfLegacy(
            SettingsStore.MEMORY_EXTRACT_PROMPT,
            LEGACY_DEFAULT_MEMORY_EXTRACT_PROMPT,
            DEFAULT_MEMORY_EXTRACT_PROMPT,
        )
        prefs.replaceIfLegacy(
            SettingsStore.MEMORY_CONSOLIDATE_PROMPT,
            LEGACY_DEFAULT_MEMORY_CONSOLIDATE_PROMPT,
            DEFAULT_MEMORY_CONSOLIDATE_PROMPT,
        )

        prefs[SettingsStore.VERSION] = 3
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

private fun MutablePreferences.replaceIfLegacy(
    key: Preferences.Key<String>,
    legacy: String,
    replacement: String,
) {
    if (this[key] == legacy) {
        this[key] = replacement
    }
}
