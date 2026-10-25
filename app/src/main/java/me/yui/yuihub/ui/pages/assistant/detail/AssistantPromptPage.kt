package me.yui.yuihub.ui.pages.assistant.detail

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.DragDropVertical
import me.rerere.hugeicons.stroke.FilterHorizontal
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.MessagesSquare
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.SourceCode
import me.yui.yuihub.R
import me.yui.yuihub.data.ai.transformers.DefaultPlaceholderProvider
import me.yui.yuihub.data.ai.transformers.TemplateTransformer
import me.yui.yuihub.data.ai.transformers.TransformerContext
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.data.model.Assistant
import me.yui.yuihub.data.model.AssistantAffectScope
import me.yui.yuihub.data.model.AssistantRegex
import me.yui.yuihub.data.model.toMessageNode
import me.yui.yuihub.ui.components.message.ChatMessage
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.ui.Select
import me.yui.yuihub.ui.components.ui.Tag
import me.yui.yuihub.ui.components.ui.TextArea
import me.yui.yuihub.ui.theme.ChatFontProvider
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.ui.theme.JetbrainsMono
import me.yui.yuihub.utils.UiState
import me.yui.yuihub.utils.insertAtCursor
import me.yui.yuihub.utils.onError
import me.yui.yuihub.utils.onSuccess
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import sh.calvin.reorderable.ReorderableColumn
import kotlin.uuid.Uuid

private enum class PromptTab(
    @StringRes val titleRes: Int,
    @StringRes val shortTitleRes: Int,
    val icon: ImageVector,
) {
    SYSTEM(R.string.assistant_page_system_prompt, R.string.assistant_page_tab_system_short, HugeIcons.Message02),
    TEMPLATE(R.string.assistant_page_message_template, R.string.assistant_page_tab_template_short, HugeIcons.SourceCode),
    PRESETS(R.string.assistant_page_preset_messages, R.string.assistant_page_tab_presets_short, HugeIcons.MessagesSquare),
    REGEX(R.string.assistant_page_regex_title, R.string.assistant_page_tab_regex_short, HugeIcons.FilterHorizontal),
}

@Composable
fun AssistantPromptPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var tabName by rememberSaveable { mutableStateOf(PromptTab.SYSTEM.name) }
    val tab = PromptTab.entries.firstOrNull { it.name == tabName } ?: PromptTab.SYSTEM

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.assistant_page_tab_prompt)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            PromptTabBar(
                selected = tab,
                assistant = assistant,
                onSelect = { tabName = it.name },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                    (fadeIn() + slideInHorizontally { direction * it / 16 })
                        .togetherWith(fadeOut() + slideOutHorizontally { -direction * it / 16 })
                },
                label = "promptTab",
                modifier = Modifier.weight(1f),
            ) { current ->
                when (current) {
                    PromptTab.SYSTEM -> SystemPromptTab(assistant, onUpdate = vm::update)
                    PromptTab.TEMPLATE -> TemplateTab(assistant, settings, onUpdate = vm::update)
                    PromptTab.PRESETS -> PresetMessagesTab(assistant, onUpdate = vm::update)
                    PromptTab.REGEX -> RegexTab(assistant, onUpdate = vm::update)
                }
            }
        }
    }
}

@Composable
private fun PromptTabBar(
    selected: PromptTab,
    assistant: Assistant,
    onSelect: (PromptTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PromptTab.entries.forEach { tab ->
            val count = when (tab) {
                PromptTab.SYSTEM -> assistant.systemPrompt.length.takeIf { it > 0 }
                PromptTab.TEMPLATE -> null
                PromptTab.PRESETS -> assistant.presetMessages.size.takeIf { it > 0 }
                PromptTab.REGEX -> assistant.regexes.size.takeIf { it > 0 }
            }
            val active = tab == selected
            val container by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                label = "promptTabContainer",
            )
            val content by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "promptTabContent",
            )
            Surface(
                onClick = { onSelect(tab) },
                shape = CircleShape,
                color = container,
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(tab.icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = content)
                    Text(
                        text = stringResource(tab.shortTitleRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = content,
                        maxLines = 1,
                    )
                    if (count != null) {
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = content.copy(alpha = 0.75f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemPromptTab(
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
) {
    val promptState = rememberTextFieldState(initialText = assistant.systemPrompt)
    LaunchedEffect(Unit) {
        snapshotFlow { promptState.text }.collect {
            onUpdate(assistant.copy(systemPrompt = it.toString()))
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PromptCard {
            TextArea(
                state = promptState,
                label = stringResource(R.string.assistant_page_system_prompt),
                minLines = 8,
                maxLines = 24,
            )
            Text(
                text = stringResource(R.string.assistant_page_available_variables),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DefaultPlaceholderProvider.placeholders.forEach { (key, info) ->
                    Tag(onClick = { promptState.insertAtCursor("{{$key}}") }) {
                        info.displayName()
                        Text(": {{$key}}")
                    }
                }
            }
        }

        PromptCard {
            PromptSwitchRow(
                title = stringResource(R.string.assistant_page_allow_conversation_system_prompt),
                description = stringResource(R.string.assistant_page_allow_conversation_system_prompt_desc),
                checked = assistant.allowConversationSystemPrompt,
                onCheckedChange = { onUpdate(assistant.copy(allowConversationSystemPrompt = it)) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            PromptSwitchRow(
                title = stringResource(R.string.assistant_page_allow_conversation_prompt_injection),
                description = stringResource(R.string.assistant_page_allow_conversation_prompt_injection_desc),
                checked = assistant.allowConversationPromptInjection,
                onCheckedChange = { onUpdate(assistant.copy(allowConversationPromptInjection = it)) },
            )
        }
    }
}

@Composable
private fun TemplateTab(
    assistant: Assistant,
    settings: Settings,
    onUpdate: (Assistant) -> Unit,
) {
    val context = LocalContext.current
    val templateTransformer = koinInject<TemplateTransformer>()
    var showPreview by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val editor: @Composable (Modifier) -> Unit = { modifier ->
            TemplateEditor(assistant, onUpdate, modifier)
        }
        val preview: @Composable (Modifier) -> Unit = { modifier ->
            TemplatePreviewCard(
                assistant = assistant,
                settings = settings,
                context = context,
                transformer = templateTransformer,
                modifier = modifier,
            )
        }
        if (wide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                    editor(Modifier.fillMaxWidth())
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                    preview(Modifier.fillMaxWidth())
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                editor(Modifier.fillMaxWidth())
                PromptCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.assistant_page_template_preview),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showPreview = !showPreview }) {
                            Text(
                                stringResource(
                                    if (showPreview) R.string.assistant_page_template_preview_hide
                                    else R.string.assistant_page_template_preview_show
                                )
                            )
                        }
                    }
                    AnimatedVisibility(visible = showPreview) {
                        preview(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateEditor(
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
    modifier: Modifier = Modifier,
) {
    val missingMessage = "{{ message }}" !in assistant.messageTemplate
    PromptCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.assistant_page_message_template),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${assistant.messageTemplate.length}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(
                onClick = { onUpdate(assistant.copy(messageTemplate = "{{ message }}")) },
                enabled = assistant.messageTemplate != "{{ message }}",
                modifier = Modifier.size(32.dp),
            ) {
                Icon(HugeIcons.Refresh03, contentDescription = null, modifier = Modifier.size(17.dp))
            }
        }
        OutlinedTextField(
            value = assistant.messageTemplate,
            onValueChange = { onUpdate(assistant.copy(messageTemplate = it)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 10,
            maxLines = 24,
            isError = missingMessage,
            supportingText = if (missingMessage) {
                { Text(stringResource(R.string.assistant_page_message_template_missing_message)) }
            } else null,
            textStyle = LocalTextStyle.current.copy(
                fontSize = 12.sp,
                fontFamily = JetbrainsMono,
                lineHeight = 16.sp,
            ),
        )
        Text(
            text = buildAnnotatedString {
                append(stringResource(R.string.assistant_page_template_variables_label))
                append(" ")
                listOf(
                    R.string.assistant_page_template_variable_role to "{{ role }}",
                    R.string.assistant_page_template_variable_message to "{{ message }}",
                    R.string.assistant_page_template_variable_time to "{{ time }}",
                    R.string.assistant_page_template_variable_date to "{{ date }}",
                ).forEachIndexed { index, (labelRes, token) ->
                    if (index > 0) append(", ")
                    append(stringResource(labelRes))
                    append(": ")
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(token) }
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TemplatePreviewCard(
    assistant: Assistant,
    settings: Settings,
    context: android.content.Context,
    transformer: TemplateTransformer,
    modifier: Modifier = Modifier,
) {
    val rawMessages = remember {
        listOf(
            UIMessage.user("你好啊"),
            UIMessage.assistant("你好，有什么我可以帮你的吗？"),
        )
    }
    val preview by produceState<UiState<List<UIMessage>>>(UiState.Success(rawMessages), assistant) {
        value = runCatching {
            UiState.Success(
                transformer.transform(
                    ctx = TransformerContext(
                        context = context,
                        model = Model(modelId = "gpt-4o", displayName = "GPT-4o"),
                        assistant = assistant,
                        settings = settings,
                    ),
                    messages = rawMessages,
                )
            )
        }.getOrElse { UiState.Error(it) }
    }
    PromptCard(modifier) {
        Text(
            text = stringResource(R.string.assistant_page_template_preview),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        preview.onError {
            Text(text = it.message ?: it.javaClass.name, color = MaterialTheme.colorScheme.error)
        }
        preview.onSuccess {
            ChatFontProvider(displaySetting = settings.displaySetting) {
                it.fastForEach { message ->
                    ChatMessage(
                        node = message.toMessageNode(),
                        onRegenerate = {},
                        onEdit = {},
                        onDelete = {},
                        onUpdate = {},
                        lastMessage = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetMessagesTab(
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "preset_header") {
            Text(
                text = stringResource(R.string.assistant_page_preset_messages_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        if (assistant.presetMessages.isEmpty()) {
            item(key = "preset_empty") {
                PromptCard {
                    Text(
                        text = stringResource(R.string.assistant_page_preset_messages_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        itemsIndexed(assistant.presetMessages, key = { index, _ -> "preset_$index" }) { index, presetMessage ->
            PresetMessageCard(
                message = presetMessage,
                onRoleChange = { role ->
                    onUpdate(
                        assistant.copy(
                            presetMessages = assistant.presetMessages.mapIndexed { i, msg ->
                                if (i == index) msg.copy(role = role) else msg
                            }
                        )
                    )
                },
                onTextChange = { text ->
                    onUpdate(
                        assistant.copy(
                            presetMessages = assistant.presetMessages.mapIndexed { i, msg ->
                                if (i == index) {
                                    msg.copy(parts = listOf(UIMessagePart.Text(text)))
                                } else {
                                    msg
                                }
                            }
                        )
                    )
                },
                onDelete = {
                    onUpdate(
                        assistant.copy(
                            presetMessages = assistant.presetMessages.filterIndexed { i, _ -> i != index }
                        )
                    )
                },
            )
        }
        item(key = "preset_add") {
            Button(
                onClick = {
                    val lastRole = assistant.presetMessages.lastOrNull()?.role ?: MessageRole.ASSISTANT
                    val nextRole = if (lastRole == MessageRole.USER) MessageRole.ASSISTANT else MessageRole.USER
                    onUpdate(
                        assistant.copy(
                            presetMessages = assistant.presetMessages + UIMessage(
                                role = nextRole,
                                parts = listOf(UIMessagePart.Text("")),
                            )
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(R.string.assistant_page_preset_messages_add),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun PresetMessageCard(
    message: UIMessage,
    onRoleChange: (MessageRole) -> Unit,
    onTextChange: (String) -> Unit,
    onDelete: () -> Unit,
) {
    PromptCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Select(
                options = listOf(MessageRole.USER, MessageRole.ASSISTANT),
                selectedOption = message.role,
                onOptionSelected = onRoleChange,
                modifier = Modifier.width(120.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(HugeIcons.Cancel01, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        OutlinedTextField(
            value = message.toText(),
            onValueChange = onTextChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 6,
        )
    }
}

@Composable
private fun RegexTab(
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.assistant_page_regex_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    onUpdate(assistant.copy(regexes = assistant.regexes + AssistantRegex(id = Uuid.random())))
                },
            ) {
                Icon(HugeIcons.Add01, contentDescription = null)
            }
        }
        if (assistant.regexes.isEmpty()) {
            PromptCard {
                Text(
                    text = stringResource(R.string.assistant_page_regex_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ReorderableColumn(
            list = assistant.regexes,
            onSettle = { fromIndex, toIndex ->
                val regexes = assistant.regexes.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
                onUpdate(assistant.copy(regexes = regexes))
            },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { index, regex, isDragging ->
            key(regex.id) {
                ReorderableItem(modifier = Modifier.fillMaxWidth()) {
                    AssistantRegexCard(
                        regex = regex,
                        onUpdate = onUpdate,
                        assistant = assistant,
                        index = index,
                        modifier = Modifier.scale(if (isDragging) 0.97f else 1f),
                        dragHandleModifier = Modifier
                            .size(44.dp)
                            .longPressDraggableHandle(
                                enabled = assistant.regexes.size > 1,
                                onDragStarted = {
                                    haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                },
                                onDragStopped = {
                                    haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun PromptSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PromptCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CustomColors.groupedCardSurface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun AssistantRegexCard(
    regex: AssistantRegex,
    onUpdate: (Assistant) -> Unit,
    assistant: Assistant,
    index: Int,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CustomColors.groupedCardSurface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier
                .padding(10.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = dragHandleModifier, contentAlignment = Alignment.Center) {
                    Icon(HugeIcons.DragDropVertical, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                Text(
                    text = regex.name.ifBlank { stringResource(R.string.assistant_page_regex_new) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = regex.enabled,
                    onCheckedChange = { enabled ->
                        onUpdate(
                            assistant.copy(
                                regexes = assistant.regexes.mapIndexed { i, reg ->
                                    if (i == index) reg.copy(enabled = enabled) else reg
                                }
                            )
                        )
                    },
                    modifier = Modifier.padding(start = 6.dp),
                )
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (expanded) {
                OutlinedTextField(
                    value = regex.name,
                    onValueChange = { name ->
                        onUpdate(
                            assistant.copy(
                                regexes = assistant.regexes.mapIndexed { i, reg ->
                                    if (i == index) reg.copy(name = name) else reg
                                }
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.assistant_page_regex_name)) },
                )
                OutlinedTextField(
                    value = regex.findRegex,
                    onValueChange = { findRegex ->
                        onUpdate(
                            assistant.copy(
                                regexes = assistant.regexes.mapIndexed { i, reg ->
                                    if (i == index) reg.copy(findRegex = findRegex.trim()) else reg
                                }
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.assistant_page_regex_find_regex)) },
                    placeholder = { Text("e.g., \\b\\w+@\\w+\\.\\w+\\b") },
                )
                OutlinedTextField(
                    value = regex.replaceString,
                    onValueChange = { replaceString ->
                        onUpdate(
                            assistant.copy(
                                regexes = assistant.regexes.mapIndexed { i, reg ->
                                    if (i == index) reg.copy(replaceString = replaceString) else reg
                                }
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.assistant_page_regex_replace_string)) },
                    placeholder = { Text("e.g., [EMAIL]") },
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.assistant_page_regex_affecting_scopes),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AssistantAffectScope.entries.forEach { scope ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Checkbox(
                                    checked = scope in regex.affectingScope,
                                    onCheckedChange = { checked ->
                                        val newScopes = if (checked) {
                                            regex.affectingScope + scope
                                        } else {
                                            regex.affectingScope - scope
                                        }
                                        onUpdate(
                                            assistant.copy(
                                                regexes = assistant.regexes.mapIndexed { i, reg ->
                                                    if (i == index) reg.copy(affectingScope = newScopes) else reg
                                                }
                                            )
                                        )
                                    },
                                )
                                Text(
                                    text = scope.name.lowercase().replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Checkbox(
                        checked = regex.visualOnly,
                        onCheckedChange = { visualOnly ->
                            onUpdate(
                                assistant.copy(
                                    regexes = assistant.regexes.mapIndexed { i, reg ->
                                        if (i == index) reg.copy(visualOnly = visualOnly) else reg
                                    }
                                )
                            )
                        },
                    )
                    Text(
                        text = stringResource(R.string.assistant_page_regex_visual_only),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            onUpdate(
                                assistant.copy(
                                    regexes = assistant.regexes.filterIndexed { i, _ -> i != index }
                                )
                            )
                        },
                    ) {
                        Icon(HugeIcons.Delete01, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.delete), modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}
