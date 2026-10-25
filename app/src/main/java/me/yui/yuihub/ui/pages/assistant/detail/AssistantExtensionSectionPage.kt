package me.yui.yuihub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.yui.yuihub.R
import me.yui.yuihub.Screen
import me.yui.yuihub.ui.components.ai.LorebooksContent
import me.yui.yuihub.ui.components.ai.McpServersContent
import me.yui.yuihub.ui.components.ai.ModeInjectionsContent
import me.yui.yuihub.ui.components.ai.SkillsContent
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.context.LocalNavController
import me.yui.yuihub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

const val ASSISTANT_SECTION_INJECTIONS = "injections"
const val ASSISTANT_SECTION_LOREBOOKS = "lorebooks"
const val ASSISTANT_SECTION_SKILLS = "skills"
const val ASSISTANT_SECTION_MCP = "mcp"

@Composable
fun AssistantExtensionSectionPage(id: String, section: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val skills by vm.skills.collectAsStateWithLifecycle()
    val mcpServerConfigs by vm.mcpServerConfigs.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    val title = when (section) {
        ASSISTANT_SECTION_INJECTIONS -> stringResource(R.string.extensions_page_mode_injection)
        ASSISTANT_SECTION_LOREBOOKS -> stringResource(R.string.extensions_page_lorebook)
        ASSISTANT_SECTION_SKILLS -> stringResource(R.string.extensions_page_agent_skills)
        else -> stringResource(R.string.setting_page_mcp)
    }
    val count = when (section) {
        ASSISTANT_SECTION_INJECTIONS -> assistant.modeInjectionIds.size to settings.modeInjections.size
        ASSISTANT_SECTION_LOREBOOKS -> assistant.lorebookIds.size to settings.lorebooks.size
        ASSISTANT_SECTION_SKILLS -> assistant.enabledSkills.size to skills.size
        else -> assistant.mcpServers.size to mcpServerConfigs.size
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        Text(
                            text = "${count.first}/${count.second}",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (section) {
                ASSISTANT_SECTION_INJECTIONS -> ModeInjectionsContent(
                    modifier = Modifier.fillMaxSize(),
                    modeInjections = settings.modeInjections,
                    selectedIds = assistant.modeInjectionIds,
                    onToggle = { injId, checked ->
                        val newIds = if (checked) assistant.modeInjectionIds + injId
                        else assistant.modeInjectionIds - injId
                        vm.update(assistant.copy(modeInjectionIds = newIds))
                    },
                    onManage = { navController.navigate(Screen.ModeInjections) },
                )

                ASSISTANT_SECTION_LOREBOOKS -> LorebooksContent(
                    modifier = Modifier.fillMaxSize(),
                    lorebooks = settings.lorebooks,
                    selectedIds = assistant.lorebookIds,
                    onToggle = { bookId, checked ->
                        val newIds = if (checked) assistant.lorebookIds + bookId
                        else assistant.lorebookIds - bookId
                        vm.update(assistant.copy(lorebookIds = newIds))
                    },
                    onManage = { navController.navigate(Screen.Lorebooks) },
                )

                ASSISTANT_SECTION_SKILLS -> SkillsContent(
                    modifier = Modifier.fillMaxSize(),
                    skills = skills,
                    enabledSkills = assistant.enabledSkills,
                    onToggle = { name, checked ->
                        val newSkills = if (checked) assistant.enabledSkills + name
                        else assistant.enabledSkills - name
                        vm.update(assistant.copy(enabledSkills = newSkills))
                    },
                    onManage = { navController.navigate(Screen.Skills) },
                )

                else -> McpServersContent(
                    modifier = Modifier.fillMaxSize(),
                    servers = mcpServerConfigs,
                    selectedIds = assistant.mcpServers,
                    onToggle = { serverId, checked ->
                        val newIds = if (checked) assistant.mcpServers + serverId
                        else assistant.mcpServers - serverId
                        vm.update(assistant.copy(mcpServers = newIds))
                    },
                    onManage = { navController.navigate(Screen.SettingMcp) },
                )
            }
        }
    }
}
