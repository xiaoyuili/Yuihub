package me.yui.yuihub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import me.yui.yuihub.data.ai.AGENT_TOOL_STYLE_PROMPT
import me.yui.yuihub.data.ai.prompts.LEGACY_V5_AGENT_TOOL_STYLE_PROMPT
import me.yui.yuihub.data.datastore.SettingsStore

/** V5：给「工具使用风格」补上任务清单实时更新的约束；用户自己改过的内容保持不变。 */
class PreferenceStoreV5Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 5
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs.replaceIfLegacy(
            SettingsStore.AGENT_TOOL_STYLE_PROMPT,
            AGENT_TOOL_STYLE_PROMPT,
            listOf(LEGACY_V5_AGENT_TOOL_STYLE_PROMPT),
        )
        prefs[SettingsStore.VERSION] = 5
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}
