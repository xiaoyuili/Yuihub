package me.yui.yuihub.ui.pages.setting

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.FileImport
import me.yui.yuihub.R
import me.yui.yuihub.data.datastore.ChatFontFamily
import me.yui.yuihub.data.datastore.DisplaySetting
import me.yui.yuihub.data.datastore.findModelById
import me.yui.yuihub.data.datastore.getCurrentAssistant
import me.yui.yuihub.data.files.FileFolders
import me.yui.yuihub.data.files.FileUtils
import me.yui.yuihub.data.model.Assistant
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.richtext.MarkdownBlock
import me.yui.yuihub.ui.components.ui.AutoAIIcon
import me.yui.yuihub.ui.components.ui.CardGroup
import me.yui.yuihub.ui.components.ui.Select
import me.yui.yuihub.ui.components.ui.UIAvatar
import me.yui.yuihub.ui.context.LocalToaster
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.ui.pages.assistant.detail.BackgroundPicker
import me.yui.yuihub.ui.theme.rememberChatFontFamily
import me.yui.yuihub.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.io.File
import kotlin.math.roundToInt

enum class UiSettingsSection { ALL, MESSAGE, TYPOGRAPHY }

@Composable
fun SettingPreferencesUIPage(
    vm: SettingVM = koinViewModel(),
    section: UiSettingsSection = UiSettingsSection.ALL,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val titleRes = when (section) {
        UiSettingsSection.MESSAGE -> R.string.setting_appearance_message_ui
        UiSettingsSection.TYPOGRAPHY -> R.string.setting_appearance_fonts_and_code
        UiSettingsSection.ALL -> R.string.setting_page_preferences_ui
    }
    val showMessages = section == UiSettingsSection.ALL || section == UiSettingsSection.MESSAGE
    val showTypography = section == UiSettingsSection.ALL || section == UiSettingsSection.TYPOGRAPHY
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val chatFontFamily = rememberChatFontFamily(displaySetting)
    var fontSizeDraft by remember(displaySetting.fontSizeRatio) {
        mutableStateOf(displaySetting.fontSizeRatio)
    }
    val currentAssistant = remember(settings) { settings.getCurrentAssistant() }
    val previewModelName = remember(settings) {
        settings.findModelById(currentAssistant.chatModelId ?: settings.chatModelId)?.displayName.orEmpty()
    }
    val settingsListState = rememberLazyListState()
    SettingsAnchorHost(settingsListState, "appearance.message.")
    SettingsAnchorHost(settingsListState, "appearance.typography.")
    // 搜索跳转锚点：CardGroup 的 item 是普通 builder，不能在里面调 composable，先在这里算好
    val anchorUserAvatar = Modifier.settingsAnchor("appearance.message.user_avatar")
    val anchorAssistantBubble = Modifier.settingsAnchor("appearance.message.assistant_bubble")
    val anchorBubbleOpacity = Modifier.settingsAnchor("appearance.message.bubble_opacity")
    val anchorModelIcon = Modifier.settingsAnchor("appearance.message.model_icon")
    val anchorModelName = Modifier.settingsAnchor("appearance.message.model_name")
    val anchorDatetime = Modifier.settingsAnchor("appearance.message.datetime")
    val anchorTokenUsage = Modifier.settingsAnchor("appearance.message.token_usage")
    val anchorThinking = Modifier.settingsAnchor("appearance.message.thinking")
    val anchorAutoCollapseThinking = Modifier.settingsAnchor("appearance.message.auto_collapse_thinking")
    val anchorLatex = Modifier.settingsAnchor("appearance.message.latex")
    val anchorFontFamily = Modifier.settingsAnchor("appearance.typography.font_family")
    val anchorCustomFont = Modifier.settingsAnchor("appearance.typography.custom_font")
    val anchorFontSize = Modifier.settingsAnchor("appearance.typography.font_size")
    val anchorCodeWrap = Modifier.settingsAnchor("appearance.typography.code_wrap")
    val anchorCodeCollapse = Modifier.settingsAnchor("appearance.typography.code_collapse")
    val anchorLineNumbers = Modifier.settingsAnchor("appearance.typography.line_numbers")

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    val importSuccessMsg = stringResource(R.string.setting_display_page_custom_font_import_success)
    val importFailedMsg = stringResource(R.string.setting_display_page_custom_font_import_failed)
    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    importCustomChatFontInternal(context, uri)
                }
            }.onSuccess { importedFont ->
                updateDisplaySetting(
                    displaySetting.copy(
                        chatFontFamily = ChatFontFamily.CUSTOM,
                        chatCustomFontPath = importedFont.relativePath,
                        chatCustomFontName = importedFont.displayName,
                    )
                )
                toaster.show(importSuccessMsg, type = ToastType.Success)
            }.onFailure { error ->
                toaster.show(importFailedMsg.format(error.message.orEmpty()), type = ToastType.Error)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(titleRes))
                },
                navigationIcon = {
                    BackButton()
                },
                colors = CustomColors.pageTopBarColors
            )
        },
        containerColor = CustomColors.pageBackground
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            state = settingsListState,
        ) {
            if (showMessages) {
                stickyHeader(key = "message_preview") {
                    MessagePreview(
                        displaySetting = displaySetting,
                        assistant = currentAssistant,
                        modelName = previewModelName,
                    )
                }
                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text(stringResource(R.string.setting_page_message_display_settings)) },
                    ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_appearance_show_assistant_identity)) },
                        supportingContent = { Text(stringResource(R.string.assistant_page_use_assistant_avatar_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.useAssistantAvatar,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(useAssistantAvatar = it))
                                }
                            )
                        },
                    )
                    item(
                        modifier = anchorUserAvatar,
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_user_avatar_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_user_avatar_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showUserAvatar,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showUserAvatar = it))
                                }
                            )
                        },
                    )
                    item(
                        modifier = anchorAssistantBubble,
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_assistant_bubble_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_assistant_bubble_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showAssistantBubble,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showAssistantBubble = it))
                                }
                            )
                        },
                    )
                    item(
                        modifier = anchorBubbleOpacity,
                        headlineContent = { Text(stringResource(R.string.setting_display_page_bubble_opacity_title)) },
                        supportingContent = {
                            // 拖动只改本地草稿，松手再落库（同背景透明度，避免每帧写 DataStore）
                            var opacityDraft by remember(displaySetting.bubbleOpacity) {
                                mutableFloatStateOf(displaySetting.bubbleOpacity)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Slider(
                                    value = opacityDraft,
                                    onValueChange = { opacityDraft = it },
                                    onValueChangeFinished = {
                                        updateDisplaySetting(displaySetting.copy(bubbleOpacity = opacityDraft))
                                    },
                                    valueRange = 0.1f..1.0f,
                                    steps = 8,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(text = "${(opacityDraft * 100).roundToInt()}%")
                            }
                        }
                    )
                    item(
                        modifier = anchorModelIcon,
                        headlineContent = { Text(stringResource(R.string.setting_display_page_chat_list_model_icon_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_chat_list_model_icon_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showModelIcon,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showModelIcon = it))
                                }
                            )
                        },
                    )
                    item(
                        modifier = anchorModelName,
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_model_name_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_model_name_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showModelName,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showModelName = it))
                                }
                            )
                        },
                    )
                    item(
                        modifier = anchorDatetime,
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_datetime_in_message_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_datetime_in_message_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showDateTimeInMessage,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showDateTimeInMessage = it))
                                }
                            )
                        },
                    )
                    item(
                        modifier = anchorTokenUsage,
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_token_usage_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_token_usage_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showTokenUsage,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showTokenUsage = it))
                                }
                            )
                        },
                    )
                    item(
                        modifier = anchorThinking,
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_thinking_content_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_thinking_content_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showThinkingContent,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showThinkingContent = it))
                                }
                            )
                        },
                    )
                    item(
                        modifier = anchorAutoCollapseThinking,
                        headlineContent = { Text(stringResource(R.string.setting_display_page_auto_collapse_thinking_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_auto_collapse_thinking_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.autoCloseThinking,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(autoCloseThinking = it))
                                }
                            )
                        },
                    )
                    item(
                        modifier = anchorLatex,
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_latex_rendering_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_latex_rendering_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableLatexRendering,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableLatexRendering = it))
                                }
                            )
                        },
                    )
                }
            }
            item {
                ChatBackgroundSettings(
                    displaySetting = displaySetting,
                    onUpdate = ::updateDisplaySetting,
                )
            }
            }

            if (showTypography) {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(148.dp)
                            .padding(horizontal = 8.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = CustomColors.groupedCardSurface,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.setting_appearance_font_preview_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                            MarkdownBlock(
                                content = stringResource(R.string.setting_display_page_font_size_preview),
                                style = LocalTextStyle.current.copy(
                                    fontSize = LocalTextStyle.current.fontSize * fontSizeDraft,
                                    lineHeight = LocalTextStyle.current.lineHeight * fontSizeDraft,
                                    fontFamily = chatFontFamily,
                                )
                            )
                        }
                    }
                }

                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text(stringResource(R.string.setting_appearance_font_settings)) },
                    ) {
                    item(
                        modifier = anchorFontFamily,
                        headlineContent = { Text(stringResource(R.string.setting_display_page_chat_font_family_title)) },
                        supportingContent = {
                            Select(
                                options = ChatFontFamily.entries,
                                selectedOption = displaySetting.chatFontFamily,
                                onOptionSelected = { family ->
                                    if (family == ChatFontFamily.CUSTOM && displaySetting.chatCustomFontPath.isBlank()) {
                                        fontPickerLauncher.launch(CustomFontMimeTypesUI)
                                    } else {
                                        updateDisplaySetting(displaySetting.copy(chatFontFamily = family))
                                    }
                                },
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .fillMaxWidth(),
                                optionToString = { it.labelUI() },
                                optionLeading = { family ->
                                    Text(
                                        text = "Aa",
                                        fontFamily = family.toFontFamilyUI(chatFontFamily),
                                    )
                                }
                            )
                        }
                    )
                    item(
                        modifier = anchorCustomFont,
                        headlineContent = { Text(stringResource(R.string.setting_display_page_custom_font_title)) },
                        supportingContent = {
                            Text(
                                if (displaySetting.chatCustomFontName.isNotBlank()) {
                                    displaySetting.chatCustomFontName
                                } else {
                                    stringResource(R.string.setting_display_page_custom_font_not_imported)
                                }
                            )
                        },
                        trailingContent = {
                            Row {
                                IconButton(
                                    onClick = { fontPickerLauncher.launch(CustomFontMimeTypesUI) }
                                ) {
                                    Icon(
                                        HugeIcons.FileImport,
                                        contentDescription = stringResource(
                                            R.string.setting_display_page_custom_font_import
                                        )
                                    )
                                }
                                if (displaySetting.chatCustomFontPath.isNotBlank()) {
                                    IconButton(
                                        onClick = {
                                            deleteCustomChatFontInternal(context, displaySetting.chatCustomFontPath)
                                            updateDisplaySetting(
                                                displaySetting.copy(
                                                    chatFontFamily = ChatFontFamily.DEFAULT,
                                                    chatCustomFontPath = "",
                                                    chatCustomFontName = "",
                                                )
                                            )
                                        }
                                    ) {
                                        Icon(
                                            HugeIcons.Delete02,
                                            contentDescription = stringResource(
                                                R.string.setting_display_page_custom_font_remove
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    )
                    item(
                        modifier = anchorFontSize,
                        headlineContent = { Text(stringResource(R.string.setting_display_page_font_size_title)) },
                        supportingContent = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Slider(
                                    value = fontSizeDraft,
                                    onValueChange = { fontSizeDraft = it },
                                    onValueChangeFinished = {
                                        updateDisplaySetting(displaySetting.copy(fontSizeRatio = fontSizeDraft))
                                    },
                                    valueRange = 0.5f..2f,
                                    steps = 11,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(text = "${(fontSizeDraft * 100).roundToInt()}%")
                            }
                        }
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_code_display_settings)) },
                ) {
                    item(
                        modifier = anchorCodeWrap,
                        headlineContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_wrap_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_wrap_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.codeBlockAutoWrap,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(codeBlockAutoWrap = it))
                                }
                            )
                        },
                    )
                    item(
                        modifier = anchorCodeCollapse,
                        headlineContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_collapse_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_collapse_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.codeBlockAutoCollapse,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(codeBlockAutoCollapse = it))
                                }
                            )
                        },
                    )
                    item(
                        modifier = anchorLineNumbers,
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_line_numbers_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_line_numbers_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showLineNumbers,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showLineNumbers = it))
                                }
                            )
                        },
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun MessagePreview(
    displaySetting: DisplaySetting,
    assistant: Assistant,
    modelName: String,
) {
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    val assistantName = if (displaySetting.useAssistantAvatar) {
        assistant.name.ifBlank { defaultAssistantName }
    } else {
        modelName.ifBlank { stringResource(R.string.setting_appearance_model_fallback) }
    }
    val bubbleOpacity = displaySetting.bubbleOpacity.coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CustomColors.pageBackground)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.setting_appearance_preview),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = CustomColors.groupedCardSurface,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(178.dp)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp),
                        modifier = Modifier.widthIn(max = 220.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.setting_appearance_preview_user_message),
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }
                    if (displaySetting.showUserAvatar) {
                        Spacer(modifier = Modifier.size(6.dp))
                        UIAvatar(
                            name = displaySetting.userNickname.ifBlank {
                                stringResource(R.string.user_default_name)
                            },
                            value = displaySetting.userAvatar,
                            modifier = Modifier.size(24.dp),
                            showEditBadge = false,
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (displaySetting.showModelIcon) {
                        if (displaySetting.useAssistantAvatar) {
                            UIAvatar(
                                name = assistantName,
                                value = assistant.avatar,
                                modifier = Modifier.size(24.dp),
                                showEditBadge = false,
                            )
                        } else {
                            AutoAIIcon(
                                name = assistantName,
                                modifier = Modifier.size(24.dp),
                                loading = false,
                            )
                        }
                        Spacer(modifier = Modifier.size(6.dp))
                    }
                    if (displaySetting.showModelName) {
                        Text(
                            text = assistantName,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }

                Surface(
                    color = if (displaySetting.showAssistantBubble) {
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = bubbleOpacity)
                    } else {
                        Color.Transparent
                    },
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_appearance_preview_assistant_message),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(
                            horizontal = if (displaySetting.showAssistantBubble) 10.dp else 0.dp,
                            vertical = if (displaySetting.showAssistantBubble) 7.dp else 0.dp,
                        ),
                    )
                }

                if (displaySetting.showThinkingContent) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.setting_appearance_preview_thinking),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (displaySetting.showDateTimeInMessage) {
                        Text(
                            text = "14:32",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (displaySetting.showTokenUsage) {
                        Text(
                            text = "128 tokens",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBackgroundSettings(
    displaySetting: DisplaySetting,
    onUpdate: (DisplaySetting) -> Unit,
) {
    val backgroundOpacity = displaySetting.chatBackgroundOpacity.coerceIn(0f, 1f)
    // 拖动时只改本地草稿，松手才落库：updateSettings 会把整个 Settings 重新序列化写 DataStore，
    // 每帧都写会直接卡死滑杆
    var opacityDraft by remember(displaySetting.chatBackgroundOpacity) {
        mutableFloatStateOf(backgroundOpacity)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .settingsAnchor("appearance.message.chat_background"),
        shape = RoundedCornerShape(20.dp),
        color = CustomColors.groupedCardSurface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.settingsAnchor("appearance.message.gradient_background"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.assistant_page_gradient_background),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                    Text(
                        text = stringResource(R.string.assistant_page_gradient_background_desc),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(
                    checked = displaySetting.useGradientBackground,
                    onCheckedChange = {
                        onUpdate(displaySetting.copy(useGradientBackground = it))
                    },
                )
            }

            if (!displaySetting.useGradientBackground) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                BackgroundPicker(
                    background = displaySetting.chatBackground,
                    backgroundOpacity = opacityDraft,
                    onUpdate = { background ->
                        onUpdate(displaySetting.copy(chatBackground = background))
                    },
                )
                if (displaySetting.chatBackground != null) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    Column(modifier = Modifier.settingsAnchor("appearance.message.background_opacity")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.assistant_page_background_opacity),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = stringResource(
                                    R.string.assistant_page_background_opacity_value,
                                    (opacityDraft * 100).roundToInt(),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Slider(
                            value = opacityDraft,
                            onValueChange = { opacityDraft = it },
                            onValueChangeFinished = {
                                onUpdate(displaySetting.copy(chatBackgroundOpacity = opacityDraft))
                            },
                            valueRange = 0f..1f,
                            steps = 19,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

private val CustomFontMimeTypesUI = arrayOf(
    "font/*",
    "application/x-font-ttf",
    "application/x-font-otf",
    "application/vnd.ms-opentype",
    "application/octet-stream",
    "*/*",
)

private val CustomFontExtensionsUI = setOf("ttf", "otf", "ttc")

private data class ImportedChatFontUI(
    val relativePath: String,
    val displayName: String,
)

@Composable
private fun ChatFontFamily.labelUI(): String = when (this) {
    ChatFontFamily.DEFAULT -> stringResource(R.string.setting_display_page_chat_font_family_default)
    ChatFontFamily.SERIF -> stringResource(R.string.setting_display_page_chat_font_family_serif)
    ChatFontFamily.MONOSPACE -> stringResource(R.string.setting_display_page_chat_font_family_monospace)
    ChatFontFamily.CUSTOM -> stringResource(R.string.setting_display_page_chat_font_family_custom)
}

private fun ChatFontFamily.toFontFamilyUI(customFontFamily: FontFamily): FontFamily = when (this) {
    ChatFontFamily.DEFAULT -> FontFamily.Default
    ChatFontFamily.SERIF -> FontFamily.Serif
    ChatFontFamily.MONOSPACE -> FontFamily.Monospace
    ChatFontFamily.CUSTOM -> customFontFamily
}

private fun importCustomChatFontInternal(context: Context, uri: Uri): ImportedChatFontUI {
    val displayName = FileUtils.getFileNameFromUri(context, uri)?.takeIf { it.isNotBlank() } ?: "custom_font"
    val extension = displayName.substringAfterLast('.', "")
        .lowercase()
        .takeIf { it in CustomFontExtensionsUI }
        ?: "ttf"
    val fontDir = File(context.filesDir, FileFolders.FONTS).apply { mkdirs() }
    val targetFile = File(fontDir, "chat_font.${System.currentTimeMillis()}.$extension")
    val tempFile = File(fontDir, "chat_font_import.tmp")

    try {
        tempFile.delete()
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Unable to open selected font")

        runCatching {
            Typeface.createFromFile(tempFile)
        }.onFailure { error ->
            throw IllegalArgumentException(error.message ?: "Invalid font file", error)
        }

        replaceCustomChatFontInternal(fontDir, tempFile, targetFile)
    } catch (error: Throwable) {
        tempFile.delete()
        throw error
    }

    val relativePath = FileUtils.getRelativePathInFilesDir(context.filesDir, targetFile)
        ?: "${FileFolders.FONTS}/${targetFile.name}"
    return ImportedChatFontUI(relativePath = relativePath, displayName = displayName)
}

private fun replaceCustomChatFontInternal(fontDir: File, tempFile: File, targetFile: File) {
    val existingFiles = fontDir.listFiles { file ->
        file.isFile && file.name.startsWith("chat_font.") && file != tempFile
    }?.toList().orEmpty()
    val backups = existingFiles.map { file ->
        file to File(fontDir, "previous_${file.name}").also { it.delete() }
    }

    try {
        backups.forEach { (file, backup) ->
            check(file.renameTo(backup)) { "Unable to prepare existing font for replacement" }
        }
        check(tempFile.renameTo(targetFile)) { "Unable to save selected font" }
        backups.forEach { (_, backup) -> backup.delete() }
    } catch (error: Throwable) {
        tempFile.delete()
        backups.forEach { (file, backup) ->
            if (!file.exists() && backup.exists()) {
                backup.renameTo(file)
            }
        }
        throw error
    }
}

private fun deleteCustomChatFontInternal(context: Context, relativePath: String) {
    val filesDir = runCatching { context.filesDir.canonicalFile }.getOrNull() ?: return
    val fontFile = runCatching { File(filesDir, relativePath).canonicalFile }.getOrNull() ?: return
    if (fontFile.path.startsWith("${filesDir.path}${File.separator}")) {
        fontFile.delete()
    }
}
