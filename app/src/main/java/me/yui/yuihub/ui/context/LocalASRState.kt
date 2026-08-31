package me.yui.yuihub.ui.context

import androidx.compose.runtime.compositionLocalOf
import me.yui.yuihub.ui.hooks.CustomAsrState

val LocalASRState = compositionLocalOf<CustomAsrState> { error("Not provided yet") }

