package me.yui.yuihub.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.Zap
import me.yui.yuihub.R
import me.yui.yuihub.Screen
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.data.model.ModelCatalogService
import me.yui.yuihub.ui.components.ai.ModelListSheet
import me.yui.yuihub.ui.components.ai.ReasoningButton
import me.yui.yuihub.ui.components.ai.rememberModelListState
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.nav.FloatingBottomBarDefaults
import me.yui.yuihub.ui.context.LocalNavController
import me.yui.yuihub.ui.components.ui.AutoAIIcon
import me.yui.yuihub.ui.components.ui.CardGroup
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.ui.theme.EntryAccents
import me.yui.yuihub.utils.plus
import me.yui.yuihub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.time.Instant
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = CustomColors.pageBackground,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_model_page_title)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.pageTopBarColors,
            )
        },
    ) { contentPadding ->
        ModelSettingsPage(
            settings = settings,
            vm = vm,
            contentPadding = contentPadding,
        )
    }
}

@Composable
private fun ModelSettingsPage(settings: Settings, vm: SettingVM, contentPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = FloatingBottomBarDefaults.ContentBottom),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ModelDefaultsGroup(settings = settings, vm = vm)
        }
        item {
            AutoCompressSettingItem(settings = settings, vm = vm)
        }
    }
}

@Composable
private fun ModelDefaultsGroup(settings: Settings, vm: SettingVM) {
    // 搜索跳转锚点：CardGroup 的 item 是普通 builder，不能在里面调 composable，先在这里算好
    val anchorTitleModel = Modifier.settingsAnchor("models.title_model")
    val anchorThinkingBudget = Modifier.settingsAnchor("models.thinking_budget")
    val anchorVisionModel = Modifier.settingsAnchor("models.vision_model")
    val titleState = rememberModelListState(
        modelId = settings.titleModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    val visionState = rememberModelListState(
        modelId = settings.visionModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )

    CardGroup(title = { Text(stringResource(R.string.setting_model_page_default_models_section)) }) {
        item(
            onClick = { titleState.open() },
            headlineContent = { Text(stringResource(R.string.setting_model_page_title_model)) },
            modifier = anchorTitleModel,
            trailingContent = {
                ModelValue(
                    value = titleState.currentModel?.displayName
                        ?: stringResource(R.string.setting_model_page_default_chat_model)
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.assistant_page_thinking_budget)) },
            modifier = anchorThinkingBudget,
            trailingContent = {
                ReasoningButton(
                    reasoningLevel = settings.titleModelReasoningLevel,
                    onUpdateReasoningLevel = {
                        vm.updateSettings(settings.copy(titleModelReasoningLevel = it))
                    },
                )
            },
        )
        item(
            onClick = { visionState.open() },
            headlineContent = { Text(stringResource(R.string.setting_model_page_vision_model)) },
            modifier = anchorVisionModel,
            trailingContent = {
                ModelValue(
                    value = visionState.currentModel?.displayName
                        ?: stringResource(R.string.setting_model_page_no_model)
                )
            },
        )
    }

    ModelListSheet(state = titleState, onSelect = {
        vm.updateSettings(settings.copy(titleModelId = it.id))
    })
    ModelListSheet(state = visionState, onSelect = {
        vm.updateSettings(settings.copy(visionModelId = it.id))
    })
}

@Composable
private fun ModelValue(value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 170.dp),
        )
        Icon(
            imageVector = HugeIcons.ArrowRight01,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 上下文自动压缩：开关、触发阈值（默认 85%）、关闭时的手动压缩提示 */
@Composable
private fun AutoCompressSettingItem(settings: Settings, vm: SettingVM) {
    val anchorAutoCompress = Modifier.settingsAnchor("models.auto_compress")
    val anchorThreshold = Modifier.settingsAnchor("models.auto_compress_threshold")
    Column {
        CardGroup(title = { Text(stringResource(R.string.setting_model_page_auto_compress_title)) }) {
            item(
                trailingContent = {
                    Switch(
                        checked = settings.autoCompressEnabled,
                        onCheckedChange = { enabled ->
                            vm.updateSettings(settings.copy(autoCompressEnabled = enabled))
                        },
                    )
                },
                headlineContent = { Text(stringResource(R.string.setting_model_page_auto_compress_enable)) },
                modifier = anchorAutoCompress,
            )

            if (settings.autoCompressEnabled) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_model_page_auto_compress_threshold)) },
                    modifier = anchorThreshold,
                    supportingContent = {
                        // 拖动过程只改本地状态（避免每帧全量写 DataStore 导致掉帧），松手才提交一次
                        var threshold by remember(settings.autoCompressThreshold) {
                            mutableStateOf(settings.autoCompressThreshold)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Slider(
                                value = threshold,
                                onValueChange = { threshold = it },
                                onValueChangeFinished = {
                                    vm.updateSettings(settings.copy(autoCompressThreshold = threshold))
                                },
                                valueRange = 0.5f..0.95f,
                                steps = 8,
                                modifier = Modifier.weight(1f),
                            )
                            Text(text = "${(threshold * 100).roundToInt()}%")
                        }
                    },
                )
            }
        }
    }
}

