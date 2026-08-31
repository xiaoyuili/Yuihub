package me.yui.yuihub.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import me.yui.yuihub.data.datastore.Settings

val LocalSettings = staticCompositionLocalOf<Settings> {
    error("No SettingsStore provided")
}
