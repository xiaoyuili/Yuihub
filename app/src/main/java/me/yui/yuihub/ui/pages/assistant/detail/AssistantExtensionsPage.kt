package me.yui.yuihub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Tools
import me.yui.yuihub.R
import me.yui.yuihub.Screen
import me.yui.yuihub.data.db.entity.ScheduledTaskEntity
import me.yui.yuihub.data.repository.ScheduledTaskRepository
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.ui.CardGroup
import me.yui.yuihub.ui.context.LocalNavController
import me.yui.yuihub.ui.pages.extensions.LocalToolEntries
import me.yui.yuihub.ui.pages.setting.SettingIcon
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.ui.theme.EntryAccents
import me.yui.yuihub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantExtensionsPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val skills by vm.skills.collectAsStateWithLifecycle()
    val mcpServerConfigs by vm.mcpServerConfigs.collectAsStateWithLifecycle()
    val taskRepository: ScheduledTaskRepository = koinInject()
    val allTasks by taskRepository.tasksFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val tasks = allTasks.filter { it.assistantId == assistant.id.toString() }
    val navController = LocalNavController.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.assistant_extensions_page_title),
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
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                CardGroup(title = { Text(stringResource(R.string.extensions_page_section_prompt)) }) {
                    item(
                        onClick = { navController.navigate(Screen.AssistantExtensionSection(id, "injections")) },
                        leadingContent = { SettingIcon(HugeIcons.MagicWand01, EntryAccents.Rose) },
                        headlineContent = { Text(stringResource(R.string.extensions_page_mode_injection)) },
                        supportingContent = { CountText(assistant.modeInjectionIds.size, settings.modeInjections.size) },
                        trailingContent = { Chevron() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.AssistantExtensionSection(id, "lorebooks")) },
                        leadingContent = { SettingIcon(HugeIcons.Book01, EntryAccents.Amber) },
                        headlineContent = { Text(stringResource(R.string.extensions_page_lorebook)) },
                        supportingContent = { CountText(assistant.lorebookIds.size, settings.lorebooks.size) },
                        trailingContent = { Chevron() },
                    )
                }
            }

            item {
                CardGroup(title = { Text(stringResource(R.string.extensions_page_section_agent)) }) {
                    item(
                        onClick = { navController.navigate(Screen.AssistantExtensionSection(id, "skills")) },
                        leadingContent = { SettingIcon(HugeIcons.Puzzle, EntryAccents.Violet) },
                        headlineContent = { Text(stringResource(R.string.extensions_page_agent_skills)) },
                        supportingContent = { CountText(assistant.enabledSkills.size, skills.size) },
                        trailingContent = { Chevron() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.AssistantExtensionSection(id, "mcp")) },
                        leadingContent = { SettingIcon(HugeIcons.McpServer, EntryAccents.Cyan) },
                        headlineContent = { Text(stringResource(R.string.setting_page_mcp)) },
                        supportingContent = { CountText(assistant.mcpServers.size, mcpServerConfigs.size) },
                        trailingContent = { Chevron() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.AssistantLocalTool(id)) },
                        leadingContent = { SettingIcon(HugeIcons.Tools, EntryAccents.Green) },
                        headlineContent = { Text(stringResource(R.string.assistant_page_tab_local_tools)) },
                        supportingContent = { CountText(assistant.localTools.size, LocalToolEntries.size) },
                        trailingContent = { Chevron() },
                    )
                }
            }

            item {
                CardGroup(title = { Text(stringResource(R.string.extensions_page_section_automation)) }) {
                    item(
                        onClick = { navController.navigate(Screen.AssistantScheduledTasks(id)) },
                        leadingContent = { SettingIcon(HugeIcons.Clock01, EntryAccents.Indigo) },
                        headlineContent = { Text(stringResource(R.string.assistant_page_tab_scheduled_tasks)) },
                        supportingContent = { CountText(tasks.count { it.enabled }, tasks.size) },
                        trailingContent = { Chevron() },
                    )
                }
            }
        }
    }
}

@Composable
private fun CountText(enabled: Int, total: Int) {
    Text("$enabled/$total")
}

@Composable
private fun Chevron() {
    Icon(
        imageVector = HugeIcons.ArrowRight01,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
