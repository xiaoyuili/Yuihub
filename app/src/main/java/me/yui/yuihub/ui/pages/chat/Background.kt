package me.yui.yuihub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import me.yui.yuihub.data.datastore.Settings

@Composable
fun AssistantBackground(setting: Settings, modifier: Modifier) {
    val displaySetting = setting.displaySetting
    if (displaySetting.useGradientBackground) {
        MeshGradientBackground(modifier = modifier)
        return
    }
    val background = displaySetting.chatBackground
    if (background != null) {
        val backgroundColor = MaterialTheme.colorScheme.background
        val backgroundOpacity = displaySetting.chatBackgroundOpacity.coerceIn(0f, 1f)
        Box(modifier = modifier) {
            AsyncImage(
                model = background,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(backgroundOpacity)
            )

            // 全屏渐变遮罩
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                backgroundColor.copy(alpha = 0.2f),
                                backgroundColor.copy(alpha = 0.5f)
                            )
                        )
                    )
            )
        }
    }
}
