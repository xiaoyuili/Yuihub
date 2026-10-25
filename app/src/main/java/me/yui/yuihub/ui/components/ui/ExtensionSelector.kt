package me.yui.yuihub.ui.components.ui

import androidx.activity.compose.BackHandler

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Tools
import me.yui.yuihub.R
import me.yui.yuihub.Screen
import me.yui.yuihub.data.ai.tools.local.LocalToolOption
import me.yui.yuihub.ui.context.LocalNavController
import me.yui.yuihub.ui.pages.assistant.detail.LocalToolSelectionList
import me.yui.yuihub.ui.pages.extensions.LocalToolEntries
import me.yui.yuihub.ui.pages.setting.SettingIcon
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.ui.theme.EntryAccents
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.data.files.SkillManager
import me.yui.yuihub.data.files.SkillMetadata
import me.yui.yuihub.data.model.Assistant
import me.yui.yuihub.data.model.Conversation
import me.yui.yuihub.data.repository.ScheduledTaskRepository
import me.yui.yuihub.ui.components.ai.ExtensionEmptyState
import me.yui.yuihub.ui.components.ai.LorebooksContent
import me.yui.yuihub.ui.components.ai.McpServersContent
import me.yui.yuihub.ui.components.ai.ModeInjectionsContent
import me.yui.yuihub.ui.components.ai.SkillsContent
import org.koin.compose.koinInject

private enum class ExtensionSection {
    MODE_INJECTIONS,
    LOREBOOKS,
    SKILLS,
    MCP,
    LOCAL_TOOLS,
    SCHEDULED_TASKS,
}

@Composable
fun ExtensionSelector(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    settings: Settings,
    onUpdate: (Assistant) -> Unit,
    conversation: Conversation? = null,
    onUpdateConversation: ((Conversation) -> Unit)? = null,
    onNavigateToModeInjections: () -> Unit = {},
    onNavigateToLorebooks: () -> Unit = {},
    onNavigateToSkills: () -> Unit = {},
    onNavigateToMcp: () -> Unit = {},
    onDismissAll: () -> Unit = {},
) {
    val skillManager: SkillManager = koinInject()
    val taskRepository: ScheduledTaskRepository = koinInject()
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    var skills by remember { mutableStateOf<List<SkillMetadata>>(emptyList()) }
    var section by remember { mutableStateOf<ExtensionSection?>(null) }
    BackHandler(enabled = section != null) {
        section = null
    }

    LaunchedEffect(Unit) {
        skills = skillManager.pruneOrphanedEnabledSkills()
    }

    val allTasks by taskRepository.tasksFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val tasks = if (conversation != null) {
        allTasks.filter { it.conversationId == conversation.id.toString() }
    } else {
        allTasks.filter { it.assistantId == assistant.id.toString() }
    }

    val useConversationInjections =
        assistant.allowConversationPromptInjection && conversation != null && onUpdateConversation != null
    val selectedModeIds = if (useConversationInjections) conversation!!.modeInjectionIds else assistant.modeInjectionIds
    val selectedLorebookIds = if (useConversationInjections) conversation!!.lorebookIds else assistant.lorebookIds
    val selectedSkills = conversation?.enabledSkillsOverride ?: assistant.enabledSkills
    val selectedMcp = conversation?.mcpServersOverride ?: assistant.mcpServers
    val selectedLocalTools = conversation?.localToolsOverride ?: assistant.localTools

    fun updateSkills(new: Set<String>) {
        if (conversation != null && onUpdateConversation != null) {
            onUpdateConversation(conversation.copy(enabledSkillsOverride = new))
        } else {
            onUpdate(assistant.copy(enabledSkills = new))
        }
    }

    fun updateMcp(new: Set<kotlin.uuid.Uuid>) {
        if (conversation != null && onUpdateConversation != null) {
            onUpdateConversation(conversation.copy(mcpServersOverride = new))
        } else {
            onUpdate(assistant.copy(mcpServers = new))
        }
    }

    fun updateLocalTools(new: List<LocalToolOption>) {
        if (conversation != null && onUpdateConversation != null) {
            onUpdateConversation(conversation.copy(localToolsOverride = new))
        } else {
            onUpdate(assistant.copy(localTools = new))
        }
    }

    val counts = listOf(
        ExtensionSection.MODE_INJECTIONS to (selectedModeIds.size to settings.modeInjections.size),
        ExtensionSection.LOREBOOKS to (selectedLorebookIds.size to settings.lorebooks.size),
        ExtensionSection.SKILLS to (selectedSkills.size to skills.size),
        ExtensionSection.MCP to (selectedMcp.size to settings.mcpServers.size),
        ExtensionSection.LOCAL_TOOLS to (selectedLocalTools.size to LocalToolEntries.size),
        ExtensionSection.SCHEDULED_TASKS to (tasks.count { it.enabled } to tasks.size),
    ).toMap()

    Column(modifier = modifier.fillMaxWidth()) {
        AnimatedContent<ExtensionSection?>(
            targetState = section,
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally { it / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 4 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { it / 4 } + fadeOut())
                }
            },
            label = "extensionSection",
        ) { currentSection ->
        Column(modifier = Modifier.fillMaxWidth()) {
        if (currentSection == null) {
            ExtensionListHeader(
                title = stringResource(R.string.assistant_page_tab_extensions),
                count = null,
            )
            val groups = listOf(
                R.string.extensions_page_section_prompt to listOf(
                    ExtensionSection.MODE_INJECTIONS,
                    ExtensionSection.LOREBOOKS,
                ),
                R.string.extensions_page_section_agent to listOf(
                    ExtensionSection.SKILLS,
                    ExtensionSection.MCP,
                    ExtensionSection.LOCAL_TOOLS,
                ),
                R.string.extensions_page_section_automation to listOf(
                    ExtensionSection.SCHEDULED_TASKS,
                ),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                groups.forEach { (groupTitle, entries) ->
                    Column {
                        Text(
                            text = stringResource(groupTitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                        )
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = CustomColors.groupedCardSurface,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            ),
                        ) {
                            Column {
                                entries.forEachIndexed { index, entry ->
                                    ExtensionSectionRow(
                                        section = entry,
                                        count = counts[entry] ?: (0 to 0),
                                        onClick = { section = entry },
                                    )
                                    if (index != entries.lastIndex) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val count = counts[currentSection] ?: (0 to 0)
            ExtensionListHeader(
                title = sectionTitle(currentSection),
                count = count,
                onBack = { section = null },
            )
            when (currentSection) {
                ExtensionSection.MODE_INJECTIONS -> if (settings.modeInjections.isNotEmpty()) {
                    ModeInjectionsContent(
                        modifier = Modifier.fillMaxSize(),
                        modeInjections = settings.modeInjections,
                        selectedIds = selectedModeIds,
                        onToggle = { id, checked ->
                            val newIds = if (checked) selectedModeIds + id else selectedModeIds - id
                            if (useConversationInjections) {
                                onUpdateConversation!!(conversation!!.copy(modeInjectionIds = newIds))
                            } else {
                                onUpdate(assistant.copy(modeInjectionIds = newIds))
                            }
                        },
                        onManage = onNavigateToModeInjections,
                    )
                } else {
                    ExtensionEmptyState(
                        message = stringResource(R.string.extension_selector_mode_injections_empty),
                        buttonText = stringResource(R.string.extension_selector_go_to_mode_injections),
                        onAction = onNavigateToModeInjections,
                    )
                }

                ExtensionSection.LOREBOOKS -> if (settings.lorebooks.isNotEmpty()) {
                    LorebooksContent(
                        modifier = Modifier.fillMaxSize(),
                        lorebooks = settings.lorebooks,
                        selectedIds = selectedLorebookIds,
                        onToggle = { id, checked ->
                            val newIds = if (checked) selectedLorebookIds + id else selectedLorebookIds - id
                            if (useConversationInjections) {
                                onUpdateConversation!!(conversation!!.copy(lorebookIds = newIds))
                            } else {
                                onUpdate(assistant.copy(lorebookIds = newIds))
                            }
                        },
                        onManage = onNavigateToLorebooks,
                    )
                } else {
                    ExtensionEmptyState(
                        message = stringResource(R.string.extension_selector_lorebooks_empty),
                        buttonText = stringResource(R.string.extension_selector_go_to_lorebooks),
                        onAction = onNavigateToLorebooks,
                    )
                }

                ExtensionSection.SKILLS -> if (skills.isNotEmpty()) {
                    SkillsContent(
                        modifier = Modifier.fillMaxSize(),
                        skills = skills,
                        enabledSkills = selectedSkills,
                        onToggle = { name, checked ->
                            updateSkills(if (checked) selectedSkills + name else selectedSkills - name)
                        },
                        onManage = onNavigateToSkills,
                    )
                } else {
                    ExtensionEmptyState(
                        message = stringResource(R.string.extension_selector_skills_empty),
                        buttonText = stringResource(R.string.extension_selector_go_to_skills),
                        onAction = onNavigateToSkills,
                    )
                }

                ExtensionSection.MCP -> if (settings.mcpServers.isNotEmpty()) {
                    McpServersContent(
                        modifier = Modifier.fillMaxSize(),
                        servers = settings.mcpServers,
                        selectedIds = selectedMcp,
                        onToggle = { id, checked ->
                            updateMcp(if (checked) selectedMcp + id else selectedMcp - id)
                        },
                        onManage = onNavigateToMcp,
                    )
                } else {
                    ExtensionEmptyState(
                        message = stringResource(R.string.extension_selector_mcp_empty),
                        buttonText = stringResource(R.string.extension_selector_go_to_mcp),
                        onAction = onNavigateToMcp,
                    )
                }

                ExtensionSection.LOCAL_TOOLS -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    LocalToolSelectionList(
                        selected = selectedLocalTools,
                        onChange = ::updateLocalTools,
                        usePermissionDialog = false,
                        compact = true,
                    )
                }

                ExtensionSection.SCHEDULED_TASKS -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    items(tasks, key = { it.id }) { task ->
                        ListItem(
                            headlineContent = { Text(task.name) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        if (task.enabled) R.string.extension_enabled
                                        else R.string.extension_disabled
                                    )
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = task.enabled,
                                    onCheckedChange = { enabled ->
                                        scope.launch {
                                            taskRepository.setEnabled(task.id, enabled, System.currentTimeMillis())
                                        }
                                    },
                                )
                            },
                        )
                    }
                    item {
                        TextButton(
                            onClick = {
                                onDismissAll()
                                if (conversation != null) {
                                    navController.navigate(
                                        Screen.ConversationScheduledTasks(
                                            assistant.id.toString(),
                                            conversation.id.toString(),
                                        )
                                    )
                                } else {
                                    navController.navigate(Screen.AssistantScheduledTasks(assistant.id.toString()))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.extensions_manage_scheduled_tasks))
                        }
                    }
                }
            }
        }
        }
        }
    }
}

@Composable
private fun ExtensionListHeader(
    title: String,
    count: Pair<Int, Int>?,
    onBack: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Text(
                text = "${count.first}/${count.second}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExtensionSectionRow(
    section: ExtensionSection,
    count: Pair<Int, Int>,
    onClick: () -> Unit,
) {
    val icon = when (section) {
        ExtensionSection.MODE_INJECTIONS -> HugeIcons.MagicWand01
        ExtensionSection.LOREBOOKS -> HugeIcons.Book01
        ExtensionSection.SKILLS -> HugeIcons.Puzzle
        ExtensionSection.MCP -> HugeIcons.McpServer
        ExtensionSection.LOCAL_TOOLS -> HugeIcons.Tools
        ExtensionSection.SCHEDULED_TASKS -> HugeIcons.Clock01
    }
    val title = when (section) {
        ExtensionSection.MODE_INJECTIONS -> stringResource(R.string.assistant_extensions_page_tab_mode_injections)
        ExtensionSection.LOREBOOKS -> stringResource(R.string.assistant_extensions_page_tab_lorebooks)
        ExtensionSection.SKILLS -> stringResource(R.string.assistant_extensions_page_tab_skills)
        ExtensionSection.MCP -> stringResource(R.string.assistant_page_tab_mcp)
        ExtensionSection.LOCAL_TOOLS -> stringResource(R.string.assistant_page_tab_local_tools)
        ExtensionSection.SCHEDULED_TASKS -> stringResource(R.string.assistant_page_tab_scheduled_tasks)
    }
    val accent = when (section) {
        ExtensionSection.MODE_INJECTIONS -> EntryAccents.Rose
        ExtensionSection.LOREBOOKS -> EntryAccents.Amber
        ExtensionSection.SKILLS -> EntryAccents.Violet
        ExtensionSection.MCP -> EntryAccents.Cyan
        ExtensionSection.LOCAL_TOOLS -> EntryAccents.Green
        ExtensionSection.SCHEDULED_TASKS -> EntryAccents.Indigo
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 50.dp)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingIcon(icon, accent)
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        )
        Text(
            text = "${count.first}/${count.second}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(99.dp),
                )
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        Icon(
            imageVector = HugeIcons.ArrowRight01,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 7.dp)
                .size(17.dp),
        )
    }
}

private fun groupFor(section: ExtensionSection): Int = when (section) {
    ExtensionSection.MODE_INJECTIONS,
    ExtensionSection.LOREBOOKS -> R.string.extensions_page_section_prompt

    ExtensionSection.SKILLS,
    ExtensionSection.MCP,
    ExtensionSection.LOCAL_TOOLS -> R.string.extensions_page_section_agent

    ExtensionSection.SCHEDULED_TASKS -> R.string.extensions_page_section_automation
}

@Composable
private fun sectionTitle(section: ExtensionSection): String = when (section) {
    ExtensionSection.MODE_INJECTIONS -> stringResource(R.string.assistant_extensions_page_tab_mode_injections)
    ExtensionSection.LOREBOOKS -> stringResource(R.string.assistant_extensions_page_tab_lorebooks)
    ExtensionSection.SKILLS -> stringResource(R.string.assistant_extensions_page_tab_skills)
    ExtensionSection.MCP -> stringResource(R.string.assistant_page_tab_mcp)
    ExtensionSection.LOCAL_TOOLS -> stringResource(R.string.assistant_page_tab_local_tools)
    ExtensionSection.SCHEDULED_TASKS -> stringResource(R.string.assistant_page_tab_scheduled_tasks)
}
