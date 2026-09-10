package me.yui.yuihub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextButton
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Puzzle
import me.yui.yuihub.R
import me.yui.yuihub.Screen
import me.yui.yuihub.ui.components.ai.ExtensionEmptyState
import me.yui.yuihub.ui.components.ai.LorebooksContent
import me.yui.yuihub.ui.components.ai.McpPicker
import me.yui.yuihub.ui.components.ai.ModeInjectionsContent
import me.yui.yuihub.ui.components.ai.SkillsContent
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.nav.FloatingBottomBar
import me.yui.yuihub.ui.components.nav.FloatingBottomBarDefaults
import me.yui.yuihub.ui.components.nav.FloatingBottomBarTab
import me.yui.yuihub.ui.context.LocalNavController
import me.yui.yuihub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantExtensionsPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val skills by vm.skills.collectAsStateWithLifecycle()
    val mcpServerConfigs by vm.mcpServerConfigs.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pagerState = rememberPagerState { 4 }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_extensions_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
            ) { page ->
                when (page) {
                    0 -> {
                        if (settings.modeInjections.isEmpty()) {
                            ExtensionEmptyState(
                                message = stringResource(R.string.assistant_extensions_page_empty_mode_injections),
                                buttonText = stringResource(R.string.assistant_extensions_page_goto_prompts),
                                onAction = { navController.navigate(Screen.Prompts) },
                            )
                        } else {
                            Column(
                                modifier = Modifier.padding(bottom = FloatingBottomBarDefaults.ContentBottom),
                            ) {
                                ModeInjectionsContent(
                                    modifier = Modifier.weight(1f),
                                    modeInjections = settings.modeInjections,
                                    selectedIds = assistant.modeInjectionIds,
                                    onToggle = { injId, checked ->
                                        val newIds = if (checked) assistant.modeInjectionIds + injId
                                        else assistant.modeInjectionIds - injId
                                        vm.update(assistant.copy(modeInjectionIds = newIds))
                                    },
                                )
                                TextButton(
                                    onClick = { navController.navigate(Screen.Prompts) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.assistant_extensions_page_goto_prompts))
                                }
                            }
                        }
                    }

                    1 -> {
                        if (settings.lorebooks.isEmpty()) {
                            ExtensionEmptyState(
                                message = stringResource(R.string.assistant_extensions_page_empty_lorebooks),
                                buttonText = stringResource(R.string.assistant_extensions_page_goto_prompts),
                                onAction = { navController.navigate(Screen.Prompts) },
                            )
                        } else {
                            Column(
                                modifier = Modifier.padding(bottom = FloatingBottomBarDefaults.ContentBottom),
                            ) {
                                LorebooksContent(
                                    modifier = Modifier.weight(1f),
                                    lorebooks = settings.lorebooks,
                                    selectedIds = assistant.lorebookIds,
                                    onToggle = { injId, checked ->
                                        val newIds = if (checked) assistant.lorebookIds + injId
                                        else assistant.lorebookIds - injId
                                        vm.update(assistant.copy(lorebookIds = newIds))
                                    },
                                )
                                TextButton(
                                    onClick = { navController.navigate(Screen.Prompts) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.assistant_extensions_page_goto_prompts))
                                }
                            }
                        }
                    }

                    2 -> {
                        if (skills.isEmpty()) {
                            ExtensionEmptyState(
                                message = stringResource(R.string.assistant_extensions_page_empty_skills),
                                buttonText = stringResource(R.string.assistant_extensions_page_goto_extensions),
                                onAction = { navController.navigate(Screen.Skills) },
                            )
                        } else {
                            Column(
                                modifier = Modifier.padding(bottom = FloatingBottomBarDefaults.ContentBottom),
                            ) {
                                SkillsContent(
                                    modifier = Modifier.weight(1f),
                                    skills = skills,
                                    enabledSkills = assistant.enabledSkills,
                                    onToggle = { name, checked ->
                                        val newSkills = if (checked) assistant.enabledSkills + name
                                        else assistant.enabledSkills - name
                                        vm.update(assistant.copy(enabledSkills = newSkills))
                                    },
                                )
                                TextButton(
                                    onClick = { navController.navigate(Screen.Skills) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.assistant_extensions_page_goto_extensions))
                                }
                            }
                        }
                    }

                    3 -> {
                        if (mcpServerConfigs.isEmpty()) {
                            ExtensionEmptyState(
                                message = stringResource(R.string.assistant_extensions_page_empty_mcp),
                                buttonText = stringResource(R.string.assistant_extensions_page_goto_mcp),
                                onAction = { navController.navigate(Screen.SettingMcp) },
                            )
                        } else {
                            Column(
                                modifier = Modifier.padding(bottom = FloatingBottomBarDefaults.ContentBottom),
                            ) {
                                McpPicker(
                                    modifier = Modifier.weight(1f),
                                    assistant = assistant,
                                    servers = mcpServerConfigs,
                                    onUpdateAssistant = { vm.update(it) },
                                )
                                TextButton(
                                    onClick = { navController.navigate(Screen.SettingMcp) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.assistant_extensions_page_goto_mcp))
                                }
                            }
                        }
                    }
                }
            }
            FloatingBottomBar(
                pagerState = pagerState,
                tabs = listOf(
                    FloatingBottomBarTab(
                        icon = HugeIcons.MagicWand01,
                        label = stringResource(R.string.assistant_extensions_page_tab_mode_injections),
                    ),
                    FloatingBottomBarTab(
                        icon = HugeIcons.Book01,
                        label = stringResource(R.string.assistant_extensions_page_tab_lorebooks),
                    ),
                    FloatingBottomBarTab(
                        icon = HugeIcons.Puzzle,
                        label = stringResource(R.string.assistant_extensions_page_tab_skills),
                    ),
                    FloatingBottomBarTab(
                        icon = HugeIcons.McpServer,
                        label = stringResource(R.string.assistant_page_tab_mcp),
                    ),
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = innerPadding.calculateBottomPadding()),
            )
        }
    }
}
