package me.yui.yuihub.ui.components.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Moon02
import me.rerere.hugeicons.stroke.Sun03
import me.rerere.hugeicons.stroke.Sunrise
import me.rerere.hugeicons.stroke.Sunset
import me.yui.yuihub.R
import me.yui.yuihub.data.model.Avatar
import java.util.Calendar

/**
 * 空对话页的问候区：助手头像 + 时段角标 + 分时问候语。
 *
 * 按设备本地时间分四段（与 [Greeting] 的分段一致），配对应时段图标，
 * 让每次新建会话都不再是一整页空白。
 */
@Composable
fun ChatEmptyGreeting(
    assistantName: String,
    assistantAvatar: Avatar,
    modifier: Modifier = Modifier,
) {
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val (greetingRes, periodIcon) = when (hour) {
        in 5..11 -> R.string.menu_page_morning_greeting to HugeIcons.Sunrise
        in 12..17 -> R.string.menu_page_afternoon_greeting to HugeIcons.Sun03
        in 18..22 -> R.string.menu_page_evening_greeting to HugeIcons.Sunset
        else -> R.string.menu_page_night_greeting to HugeIcons.Moon02
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            // 头像背后的柔和光晕，避免圆形头像孤零零贴在白底上
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0f),
                            ),
                        ),
                        shape = CircleShape,
                    ),
            )
            Box(
                modifier = Modifier.size(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                UIAvatar(
                    name = assistantName.ifEmpty { defaultAssistantName },
                    value = assistantAvatar,
                    modifier = Modifier.size(64.dp),
                )
            }
            // 时段角标：小圆片压在头像右下角，图标随时段切换
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceBright,
                shadowElevation = 2.dp,
                modifier = Modifier.size(26.dp),
            ) {
                PeriodIcon(icon = periodIcon)
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(greetingRes),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.chat_empty_greeting_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PeriodIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .padding(5.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
    }
}
