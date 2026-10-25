package me.yui.yuihub.ui.pages.extensions

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
import me.yui.yuihub.data.repository.ScheduledTaskRepository
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.ui.CardGroup
import me.yui.yuihub.ui.context.LocalNavController
import me.yui.yuihub.ui.pages.extensions.skills.SkillsVM
import me.yui.yuihub.ui.pages.setting.SettingIcon
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.ui.theme.EntryAccents
import me.yui.yuihub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun ExtensionsPage(
    promptVM: PromptVM = koinViewModel(),
    skillsVM: SkillsVM = koinViewModel(),
) {
    val navController = LocalNavController.current
    val settings by promptVM.settings.collectAsStateWithLifecycle()
    val skills by skillsVM.skills.collectAsStateWithLifecycle()
    val scheduledTaskRepository = koinInject<ScheduledTaskRepository>()
    val scheduledTasks by scheduledTaskRepository.tasksFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val injections = settings.modeInjections
    val enabledInjections = injections.count { it.enabled }
    val lorebooks = settings.lorebooks

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.extensions_page_title),
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
                CardGroup(
                    title = { Text(stringResource(R.string.extensions_page_section_prompt)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.ModeInjections) },
                        leadingContent = { SettingIcon(HugeIcons.MagicWand01, EntryAccents.Rose) },
                        headlineContent = { Text(stringResource(R.string.extensions_page_mode_injection)) },
                        supportingContent = { Text("$enabledInjections/${injections.size}") },
                        trailingContent = { EntryChevron() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Lorebooks) },
                        leadingContent = { SettingIcon(HugeIcons.Book01, EntryAccents.Amber) },
                        headlineContent = { Text(stringResource(R.string.extensions_page_lorebook)) },
                        supportingContent = {
                            Text("${lorebooks.count { it.enabled }}/${lorebooks.size}")
                        },
                        trailingContent = { EntryChevron() },
                    )
                }
            }

            item {
                CardGroup(
                    title = { Text(stringResource(R.string.extensions_page_section_agent)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.Skills) },
                        leadingContent = { SettingIcon(HugeIcons.Puzzle, EntryAccents.Violet) },
                        headlineContent = { Text(stringResource(R.string.extensions_page_agent_skills)) },
                        supportingContent = { Text("${skills.size}") },
                        trailingContent = { EntryChevron() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.LocalTools) },
                        leadingContent = { SettingIcon(HugeIcons.Tools, EntryAccents.Green) },
                        headlineContent = { Text(stringResource(R.string.assistant_page_tab_local_tools)) },
                        supportingContent = { Text("${LocalToolEntries.size}") },
                        trailingContent = { EntryChevron() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingMcp) },
                        leadingContent = { SettingIcon(HugeIcons.McpServer, EntryAccents.Cyan) },
                        headlineContent = { Text(stringResource(R.string.setting_page_mcp)) },
                        supportingContent = {
                            Text("${settings.mcpServers.count { it.commonOptions.enable }}/${settings.mcpServers.size}")
                        },
                        trailingContent = { EntryChevron() },
                    )
                }
            }
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.extensions_page_section_automation)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.ScheduledTasks) },
                        leadingContent = { SettingIcon(HugeIcons.Clock01, EntryAccents.Indigo) },
                        headlineContent = { Text(stringResource(R.string.assistant_page_tab_scheduled_tasks)) },
                        supportingContent = {
                            Text("${scheduledTasks.count { it.enabled }}/${scheduledTasks.size}")
                        },
                        trailingContent = { EntryChevron() },
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryChevron() {
    Icon(
        imageVector = HugeIcons.ArrowRight01,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
