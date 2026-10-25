package me.yui.yuihub.ui.pages.setting.components

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Tick02
import me.yui.yuihub.R
import me.yui.yuihub.ui.components.ui.AutoAIIcon
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.ui.theme.EntryAccents

/** 供应商配置里的一种接口类型及其展示信息 */
internal data class ProviderTypeInfo(
    val type: kotlin.reflect.KClass<out ProviderSetting>,
    val iconName: String,
    @StringRes val subtitle: Int,
    val accent: Color,
    /** 自定义 = OpenAI 兼容 + 手填 completions 路径 */
    val custom: Boolean = false,
)

private val OpenAIInfo = ProviderTypeInfo(
    type = ProviderSetting.OpenAI::class,
    iconName = "OpenAI",
    subtitle = R.string.setting_provider_page_api_type_openai_desc,
    accent = EntryAccents.Green,
)

private val CustomInfo = ProviderTypeInfo(
    type = ProviderSetting.OpenAI::class,
    iconName = "OpenAI",
    subtitle = R.string.setting_provider_page_api_type_custom_desc,
    accent = EntryAccents.Teal,
    custom = true,
)

private val GoogleInfo = ProviderTypeInfo(
    type = ProviderSetting.Google::class,
    iconName = "Google",
    subtitle = R.string.setting_provider_page_api_type_google_desc,
    accent = EntryAccents.Blue,
)

private val ClaudeInfo = ProviderTypeInfo(
    type = ProviderSetting.Claude::class,
    iconName = "Claude",
    subtitle = R.string.setting_provider_page_api_type_claude_desc,
    accent = EntryAccents.Amber,
)

/** 下拉弹层里的全部选项（自定义与 OpenAI 同底层类型，但可改路径） */
internal fun providerTypeOptions(): List<ProviderTypeInfo> =
    listOf(OpenAIInfo, CustomInfo, GoogleInfo, ClaudeInfo)

internal fun currentProviderTypeInfo(provider: ProviderSetting): ProviderTypeInfo = when (provider) {
    is ProviderSetting.OpenAI -> if (provider.customApiType) CustomInfo else OpenAIInfo
    is ProviderSetting.Google -> GoogleInfo
    is ProviderSetting.Claude -> ClaudeInfo
}

/** 选中某个类型：保留 API Key / Base URL，自定义模式强制走 chat/completions 路径 */
internal fun ProviderSetting.withTypeOption(info: ProviderTypeInfo): ProviderSetting {
    val converted = convertTo(info.type)
    return if (converted is ProviderSetting.OpenAI) {
        converted.copy(
            customApiType = info.custom,
            useResponseApi = if (info.custom) false else converted.useResponseApi,
        )
    } else {
        converted
    }
}

@Composable
private fun ProviderTypeInfo.displayTitle(): String =
    if (custom) {
        stringResource(R.string.setting_provider_page_api_type_custom)
    } else {
        type.simpleName.orEmpty()
    }

/**
 * 表单卡片：与 CardGroup 同一套视觉语言（20dp 圆角 + 1dp 描边 + groupedCardSurface），
 * 内部自由排版，用于配置页里需要放输入框的分组。
 */
@Composable
internal fun FormCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = CustomColors.groupedCardSurface,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            ),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

/** 带辅助说明的开关行 */
@Composable
internal fun SettingToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supporting: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 供应商图标小底座（透明背景，图标用强调色） */
@Composable
internal fun ProviderTypeBadge(info: ProviderTypeInfo, size: androidx.compose.ui.unit.Dp = 34.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(11.dp))
            .background(info.accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        if (info.custom) {
            Icon(
                imageVector = HugeIcons.Code,
                contentDescription = null,
                tint = info.accent,
                modifier = Modifier.size(size * 0.52f),
            )
        } else {
            CompositionLocalProvider(LocalContentColor provides info.accent) {
                AutoAIIcon(
                    name = info.iconName,
                    modifier = Modifier.size(size * 0.58f),
                    color = Color.Transparent,
                )
            }
        }
    }
}

/**
 * 接口类型选择：一行「图标 + 当前类型 + 下拉箭头」，点开底部弹层选类型。
 * 切换走 [convertTo]：保留 API Key，Base URL 按新类型的默认地址换算。
 */
@Composable
internal fun ProviderTypeField(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    val info = currentProviderTypeInfo(provider)

    Surface(
        onClick = { showSheet = true },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.35f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProviderTypeBadge(info = info)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.setting_provider_page_api_type),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = info.displayTitle(),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            Icon(
                imageVector = HugeIcons.ArrowDown01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_provider_page_api_type),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
                providerTypeOptions().forEach { option ->
                    ProviderTypeRow(
                        info = option,
                        selected = option == info,
                        onClick = {
                            showSheet = false
                            onEdit(provider.withTypeOption(option))
                        },
                    )
                }
                Text(
                    text = stringResource(R.string.setting_provider_page_api_type_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ProviderTypeRow(
    info: ProviderTypeInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.35f)
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProviderTypeBadge(info = info, size = 36.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info.displayTitle(),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Text(
                    text = stringResource(info.subtitle),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Icon(
                    imageVector = HugeIcons.Tick02,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
