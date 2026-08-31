package me.yui.yuihub.ui.context

import androidx.compose.runtime.compositionLocalOf
import me.yui.yuihub.ui.hooks.CustomTtsState

val LocalTTSState = compositionLocalOf<CustomTtsState> { error("Not provided yet") }
