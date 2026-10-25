package me.yui.yuihub.ui.pages.setting

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.AiFile01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.UserMultiple
import me.rerere.hugeicons.stroke.View
import me.yui.yuihub.R
import me.yui.yuihub.Screen
import me.yui.yuihub.data.ai.AGENT_TOOL_STYLE_PROMPT
import me.yui.yuihub.data.ai.prompts.COMPACTION_CHECKPOINT_PREAMBLE
import me.yui.yuihub.data.ai.prompts.DEFAULT_CHILD_AGENT_PROMPT
import me.yui.yuihub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.yui.yuihub.data.ai.prompts.DEFAULT_MEMORY_CONSOLIDATE_PROMPT
import me.yui.yuihub.data.ai.prompts.DEFAULT_MEMORY_EXTRACT_PROMPT
import me.yui.yuihub.data.ai.prompts.DEFAULT_MEMORY_SNAPSHOT_PROMPT
import me.yui.yuihub.data.ai.prompts.DEFAULT_SCHEDULED_TASK_PROMPT
import me.yui.yuihub.data.ai.prompts.DEFAULT_SKILLS_PROMPT
import me.yui.yuihub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.yui.yuihub.data.ai.prompts.PromptId
import me.yui.yuihub.data.ai.prompts.isPlainChatMode
import me.yui.yuihub.data.ai.prompts.isPromptEnabled
import me.yui.yuihub.data.ai.prompts.DEFAULT_VISION_PROMPT
import me.yui.yuihub.data.ai.prompts.DEFAULT_WORKSPACE_PROMPT
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.context.LocalNavController
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.ui.theme.EntryAccents
import me.yui.yuihub.utils.plus
import org.koin.androidx.compose.koinViewModel

private typealias PromptKind = PromptId

private enum class PromptCategory(
    @StringRes val titleRes: Int,
    val icon: ImageVector,
    val accent: Color,
) {
    CONVERSATION(R.string.setting_prompts_category_conversation, HugeIcons.AiEditing, EntryAccents.Violet),
    MEMORY(R.string.setting_prompts_category_memory, HugeIcons.AiBrain01, EntryAccents.Teal),
    WORKSPACE(R.string.setting_prompts_category_workspace, HugeIcons.Folder01, EntryAccents.Blue),
    TOOLS(R.string.setting_prompts_category_tools, HugeIcons.Tools, EntryAccents.Amber),
}

private data class PromptVariable(
    val token: String,
    @StringRes val descriptionRes: Int,
)

private data class PromptSpec(
    val kind: PromptKind,
    val category: PromptCategory,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val icon: ImageVector,
    val accent: Color,
    val defaultPrompt: String,
    val variables: List<PromptVariable>,
)

private fun specOf(kind: PromptKind): PromptSpec = when (kind) {
    PromptKind.TITLE -> PromptSpec(
        kind = kind,
        category = PromptCategory.CONVERSATION,
        titleRes = R.string.setting_model_page_prompt_title,
        descriptionRes = R.string.setting_prompts_title_card_desc,
        icon = HugeIcons.AiEditing,
        accent = EntryAccents.Violet,
        defaultPrompt = DEFAULT_TITLE_PROMPT,
        variables = listOf(
            PromptVariable("{content}", R.string.setting_prompts_var_content_desc),
            PromptVariable("{locale}", R.string.setting_prompts_var_locale_desc),
        ),
    )

    PromptKind.COMPRESS -> PromptSpec(
        kind = kind,
        category = PromptCategory.CONVERSATION,
        titleRes = R.string.setting_model_page_prompt_compress,
        descriptionRes = R.string.setting_prompts_compress_card_desc,
        icon = HugeIcons.AiFile01,
        accent = EntryAccents.Amber,
        defaultPrompt = DEFAULT_COMPRESS_PROMPT,
        variables = listOf(
            PromptVariable("{content}", R.string.setting_prompts_var_content_desc),
            PromptVariable("{target_tokens}", R.string.setting_prompts_var_target_tokens_desc),
            PromptVariable("{additional_context}", R.string.setting_prompts_var_additional_context_desc),
            PromptVariable("{locale}", R.string.setting_prompts_var_locale_desc),
        ),
    )

    PromptKind.COMPACTION_CHECKPOINT -> PromptSpec(
        kind = kind,
        category = PromptCategory.CONVERSATION,
        titleRes = R.string.setting_prompts_compaction_checkpoint_title,
        descriptionRes = R.string.setting_prompts_compaction_checkpoint_card_desc,
        icon = HugeIcons.AiFile01,
        accent = EntryAccents.Blue,
        defaultPrompt = COMPACTION_CHECKPOINT_PREAMBLE,
        variables = emptyList(),
    )

    PromptKind.MEMORY_EXTRACT -> PromptSpec(
        kind = kind,
        category = PromptCategory.MEMORY,
        titleRes = R.string.setting_prompts_memory_extract_title,
        descriptionRes = R.string.setting_prompts_memory_extract_card_desc,
        icon = HugeIcons.AiBrain01,
        accent = EntryAccents.Teal,
        defaultPrompt = DEFAULT_MEMORY_EXTRACT_PROMPT,
        variables = listOf(
            PromptVariable("{date}", R.string.setting_prompts_var_date_desc),
            PromptVariable("{existing_memories}", R.string.setting_prompts_var_existing_memories_desc),
            PromptVariable("{recent_conversation}", R.string.setting_prompts_var_recent_conversation_desc),
        ),
    )

    PromptKind.MEMORY_CONSOLIDATE -> PromptSpec(
        kind = kind,
        category = PromptCategory.MEMORY,
        titleRes = R.string.setting_prompts_memory_consolidate_title,
        descriptionRes = R.string.setting_prompts_memory_consolidate_card_desc,
        icon = HugeIcons.AiBrain01,
        accent = EntryAccents.Cyan,
        defaultPrompt = DEFAULT_MEMORY_CONSOLIDATE_PROMPT,
        variables = listOf(
            PromptVariable("{memories}", R.string.setting_prompts_var_memories_desc),
        ),
    )

    PromptKind.MEMORY_SNAPSHOT -> PromptSpec(
        kind = kind,
        category = PromptCategory.MEMORY,
        titleRes = R.string.setting_prompts_memory_snapshot_title,
        descriptionRes = R.string.setting_prompts_memory_snapshot_card_desc,
        icon = HugeIcons.Brain02,
        accent = EntryAccents.Indigo,
        defaultPrompt = DEFAULT_MEMORY_SNAPSHOT_PROMPT,
        variables = emptyList(),
    )

    PromptKind.WORKSPACE -> PromptSpec(
        kind = kind,
        category = PromptCategory.WORKSPACE,
        titleRes = R.string.setting_prompts_workspace_title,
        descriptionRes = R.string.setting_prompts_workspace_card_desc,
        icon = HugeIcons.Folder01,
        accent = EntryAccents.Blue,
        defaultPrompt = DEFAULT_WORKSPACE_PROMPT,
        variables = listOf(
            PromptVariable("{workspace_name}", R.string.setting_prompts_var_workspace_name_desc),
            PromptVariable("{workspace_mounts}", R.string.setting_prompts_var_workspace_mounts_desc),
            PromptVariable("{workspace_cwd}", R.string.setting_prompts_var_workspace_cwd_desc),
        ),
    )

    PromptKind.CHILD_AGENT -> PromptSpec(
        kind = kind,
        category = PromptCategory.WORKSPACE,
        titleRes = R.string.setting_prompts_child_agent_title,
        descriptionRes = R.string.setting_prompts_child_agent_card_desc,
        icon = HugeIcons.UserMultiple,
        accent = EntryAccents.Rose,
        defaultPrompt = DEFAULT_CHILD_AGENT_PROMPT,
        variables = emptyList(),
    )

    PromptKind.SKILLS -> PromptSpec(
        kind = kind,
        category = PromptCategory.TOOLS,
        titleRes = R.string.setting_prompts_skills_title,
        descriptionRes = R.string.setting_prompts_skills_card_desc,
        icon = HugeIcons.Puzzle,
        accent = EntryAccents.Violet,
        defaultPrompt = DEFAULT_SKILLS_PROMPT,
        variables = emptyList(),
    )

    PromptKind.SCHEDULED_TASK -> PromptSpec(
        kind = kind,
        category = PromptCategory.TOOLS,
        titleRes = R.string.setting_prompts_scheduled_task_title,
        descriptionRes = R.string.setting_prompts_scheduled_task_card_desc,
        icon = HugeIcons.Clock01,
        accent = EntryAccents.Indigo,
        defaultPrompt = DEFAULT_SCHEDULED_TASK_PROMPT,
        variables = emptyList(),
    )

    PromptKind.VISION -> PromptSpec(
        kind = kind,
        category = PromptCategory.TOOLS,
        titleRes = R.string.setting_prompts_vision_title,
        descriptionRes = R.string.setting_prompts_vision_card_desc,
        icon = HugeIcons.View,
        accent = EntryAccents.Green,
        defaultPrompt = DEFAULT_VISION_PROMPT,
        variables = emptyList(),
    )

    PromptKind.AGENT_TOOL_STYLE -> PromptSpec(
        kind = kind,
        category = PromptCategory.TOOLS,
        titleRes = R.string.setting_prompts_agent_tool_style_title,
        descriptionRes = R.string.setting_prompts_agent_tool_style_card_desc,
        icon = HugeIcons.AiMagic,
        accent = EntryAccents.Violet,
        defaultPrompt = AGENT_TOOL_STYLE_PROMPT,
        variables = emptyList(),
    )
}

private fun Settings.promptValue(kind: PromptKind): String = when (kind) {
    PromptKind.TITLE -> titlePrompt
    PromptKind.COMPRESS -> compressPrompt
    PromptKind.COMPACTION_CHECKPOINT -> compactionCheckpointPrompt
    PromptKind.MEMORY_EXTRACT -> memoryExtractPrompt
    PromptKind.MEMORY_CONSOLIDATE -> memoryConsolidatePrompt
    PromptKind.MEMORY_SNAPSHOT -> memorySnapshotPrompt
    PromptKind.WORKSPACE -> workspacePrompt
    PromptKind.CHILD_AGENT -> childAgentPrompt
    PromptKind.SKILLS -> skillsPrompt
    PromptKind.SCHEDULED_TASK -> scheduledTaskPrompt
    PromptKind.VISION -> visionPrompt
    PromptKind.AGENT_TOOL_STYLE -> agentToolStylePrompt
}

private fun Settings.withPrompt(kind: PromptKind, value: String): Settings = when (kind) {
    PromptKind.TITLE -> copy(titlePrompt = value)
    PromptKind.COMPRESS -> copy(compressPrompt = value)
    PromptKind.COMPACTION_CHECKPOINT -> copy(compactionCheckpointPrompt = value)
    PromptKind.MEMORY_EXTRACT -> copy(memoryExtractPrompt = value)
    PromptKind.MEMORY_CONSOLIDATE -> copy(memoryConsolidatePrompt = value)
    PromptKind.MEMORY_SNAPSHOT -> copy(memorySnapshotPrompt = value)
    PromptKind.WORKSPACE -> copy(workspacePrompt = value)
    PromptKind.CHILD_AGENT -> copy(childAgentPrompt = value)
    PromptKind.SKILLS -> copy(skillsPrompt = value)
    PromptKind.SCHEDULED_TASK -> copy(scheduledTaskPrompt = value)
    PromptKind.VISION -> copy(visionPrompt = value)
    PromptKind.AGENT_TOOL_STYLE -> copy(agentToolStylePrompt = value)
}

@Composable
fun SettingPromptsPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.setting_page_prompts),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                },
                navigationIcon = { BackButton() },
                colors = CustomColors.pageTopBarColors,
            )
        },
        containerColor = CustomColors.pageBackground,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "plain_mode") {
                PromptMasterSwitch(
                    enabled = settings.isPlainChatMode(),
                    onEnabledChange = { enabled ->
                        vm.updateSettings(
                            settings.copy(
                                disabledPromptIds = if (enabled) {
                                    PromptId.entries.map { it.name }.toSet()
                                } else {
                                    emptySet()
                                },
                            )
                        )
                    },
                )
            }

            item(key = "tool_schema_compact") {
                PromptBudgetCard(
                    level = settings.toolSchemaCompactLevel,
                    onSelect = { level ->
                        vm.updateSettings(settings.copy(toolSchemaCompactLevel = level))
                    },
                )
            }

            items(PromptCategory.entries, key = { it.name }) { category ->
                val specs = PromptKind.entries.map(::specOf).filter { it.category == category }
                PromptCategoryCard(
                    category = category,
                    enabled = specs.count { settings.isPromptEnabled(it.kind) },
                    total = specs.size,
                    onClick = {
                        navController.navigate(Screen.SettingPromptCategory(category.name))
                    },
                )
            }

            item(key = "warning") {
                PromptWarningCard()
            }
        }
    }
}

@Composable
private fun PromptBudgetCard(
    level: Int,
    onSelect: (Int) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CustomColors.groupedCardSurface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_tool_compact_title),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            )
            Text(
                text = stringResource(R.string.setting_tool_compact_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    0 to R.string.setting_tool_compact_off,
                    1 to R.string.setting_tool_compact_balanced,
                    2 to R.string.setting_tool_compact_aggressive,
                ).forEach { (value, labelRes) ->
                    FilterChip(
                        selected = level == value,
                        onClick = { onSelect(value) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PromptCategoryCard(
    category: PromptCategory,
    enabled: Int,
    total: Int,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CustomColors.groupedCardSurface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingIcon(category.icon, category.accent)
            Text(
                text = stringResource(category.titleRes),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$enabled/$total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = HugeIcons.ArrowRight01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
fun SettingPromptCategoryPage(categoryId: String, vm: SettingVM = koinViewModel()) {
    val category = PromptCategory.entries.firstOrNull { it.name == categoryId } ?: PromptCategory.CONVERSATION
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val specs = PromptKind.entries.map(::specOf).filter { it.category == category }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(category.titleRes))
                        Text(
                            text = "${specs.count { settings.isPromptEnabled(it.kind) }}/${specs.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { BackButton() },
                colors = CustomColors.pageTopBarColors,
            )
        },
        containerColor = CustomColors.pageBackground,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(specs, key = { it.kind.name }) { spec ->
                PromptCard(
                    spec = spec,
                    enabled = settings.isPromptEnabled(spec.kind),
                    onEnabledChange = { enabled ->
                        val disabled = settings.disabledPromptIds.toMutableSet()
                        if (enabled) disabled.remove(spec.kind.name) else disabled.add(spec.kind.name)
                        vm.updateSettings(settings.copy(disabledPromptIds = disabled))
                    },
                    onClick = {
                        navController.navigate(Screen.SettingPromptEdit(spec.kind.name))
                    },
                )
            }
            item(key = "warning") {
                PromptWarningCard()
            }
        }
    }
}

@Composable
private fun PromptMasterSwitch(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CustomColors.groupedCardSurface),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_prompts_plain_mode),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                )
                Text(
                    text = stringResource(R.string.setting_prompts_plain_mode_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
        }
    }
}

@Composable
private fun PromptWarningCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = HugeIcons.Alert01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = stringResource(R.string.setting_prompts_warning_title),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                )
                Text(
                    text = stringResource(R.string.setting_prompts_warning_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PromptCard(
    spec: PromptSpec,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CustomColors.groupedCardSurface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingIcon(spec.icon, spec.accent)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = stringResource(spec.titleRes),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Text(
                    text = stringResource(spec.descriptionRes),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
        }
    }
}

@Composable
fun SettingPromptEditPage(promptId: String, vm: SettingVM = koinViewModel()) {
    val kind = PromptKind.entries.firstOrNull { it.name == promptId } ?: PromptKind.TITLE
    val spec = specOf(kind)
    val settings by vm.settings.collectAsStateWithLifecycle()
    val initialPrompt = settings.promptValue(kind)
    val navController = LocalNavController.current

    var draft by remember(spec, initialPrompt) { mutableStateOf(TextFieldValue(initialPrompt)) }
    var showVariableHelp by remember { mutableStateOf(false) }
    var showVariableInsert by remember { mutableStateOf(false) }

    fun insertVariable(token: String) {
        val text = draft.text
        val start = minOf(draft.selection.start, draft.selection.end).coerceIn(0, text.length)
        val end = maxOf(draft.selection.start, draft.selection.end).coerceIn(start, text.length)
        val newText = text.replaceRange(start, end, token)
        draft = TextFieldValue(
            text = newText,
            selection = TextRange(start + token.length),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(spec.titleRes),
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                        if (spec.variables.isNotEmpty()) {
                            IconButton(
                                onClick = { showVariableHelp = true },
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(
                                    imageVector = HugeIcons.InformationCircle,
                                    contentDescription = stringResource(R.string.setting_prompts_variables_help),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                navigationIcon = { BackButton() },
                colors = CustomColors.pageTopBarColors,
            )
        },
        bottomBar = {
            Surface(color = CustomColors.pageBackground) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = { draft = TextFieldValue(spec.defaultPrompt) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.setting_model_page_reset_to_default))
                    }
                    TextButton(onClick = { navController.popBackStack() }) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = {
                            vm.updateSettings(settings.withPrompt(kind, draft.text))
                            navController.popBackStack()
                        },
                        enabled = draft.text.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.common_save))
                    }
                }
            }
        },
        containerColor = CustomColors.pageBackground,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = { Text(stringResource(R.string.setting_model_page_prompt_edit)) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (spec.variables.isNotEmpty()) {
                    OutlinedButton(onClick = { showVariableInsert = true }) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(R.string.setting_prompts_insert_variable),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.setting_model_page_prompt_chars, draft.text.length),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }

    if (showVariableHelp) {
        PromptVariablesSheet(
            variables = spec.variables,
            insertMode = false,
            onDismiss = { showVariableHelp = false },
        )
    }

    if (showVariableInsert) {
        PromptVariablesSheet(
            variables = spec.variables,
            insertMode = true,
            onDismiss = { showVariableInsert = false },
            onInsert = { token ->
                insertVariable(token)
                showVariableInsert = false
            },
        )
    }
}

@Composable
private fun PromptVariablesSheet(
    variables: List<PromptVariable>,
    insertMode: Boolean,
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    val describedVariables = variables.map { it to stringResource(it.descriptionRes) }
    val normalizedQuery = query.trim()
    val filtered = if (normalizedQuery.isEmpty()) {
        describedVariables
    } else {
        describedVariables.filter { (variable, description) ->
            variable.token.contains(normalizedQuery, ignoreCase = true) ||
                description.contains(normalizedQuery, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(if (insertMode) 0.62f else 0.48f)
                .imePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (insertMode) R.string.setting_prompts_insert_variable
                            else R.string.setting_prompts_variables_help
                        ),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(
                            if (insertMode) R.string.setting_prompts_insert_desc
                            else R.string.setting_prompts_variables_desc
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.cancel))
                }
            }

            if (insertMode && variables.size > 4) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.setting_prompts_variable_search)) },
                )
            }

            if (filtered.isEmpty()) {
                Text(
                    text = stringResource(R.string.setting_prompts_no_variable_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.first.token }) { (variable, description) ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = variable.token,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            trailingContent = if (insertMode) {
                                {
                                    Icon(
                                        imageVector = HugeIcons.Add01,
                                        contentDescription = stringResource(
                                            R.string.setting_prompts_insert_variable
                                        ),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            } else null,
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = if (insertMode) {
                                Modifier.clickable { onInsert(variable.token) }
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
        }
    }
}
