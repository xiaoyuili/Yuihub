package me.yui.yuihub.ui.pages.assistant.detail

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Puzzle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.yui.yuihub.R
import me.yui.yuihub.Screen
import me.yui.yuihub.data.model.Assistant
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.ui.UIAvatar
import me.yui.yuihub.ui.context.LocalNavController
import me.yui.yuihub.ui.hooks.heroAnimation
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.ui.theme.LocalDarkMode
import me.yui.yuihub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

// 功能入口图标配色，无对应主题色可用时按页面局部定义；深色模式下自动降透明、提亮
private val BasicAccent = Color(0xFF2563EB)
private val PromptAccent = Color(0xFF7A4DF0)
private val ExtensionsAccent = Color(0xFFD98A0C)
private val MemoryAccent = Color(0xFF0E9E85)
private val RequestAccent = Color(0xFFE0455F)
private val LocalToolsAccent = Color(0xFF5560D8)

@Composable
fun AssistantDetailPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = assistant.name.ifBlank {
                            stringResource(R.string.assistant_page_default_assistant)
                        },
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    BackButton()
                },
                colors = CustomColors.topBarColors
            )
        },
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AssistantHeader(
                    assistant = assistant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                )
            }

            item {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AssistantFeatureCard(
                            title = stringResource(R.string.assistant_page_tab_basic),
                            description = stringResource(R.string.assistant_detail_basic_desc),
                            icon = HugeIcons.Settings03,
                            accent = BasicAccent,
                            onClick = { navController.navigate(Screen.AssistantBasic(id)) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        AssistantFeatureCard(
                            title = stringResource(R.string.assistant_page_tab_prompt),
                            description = stringResource(R.string.assistant_detail_prompt_desc),
                            icon = HugeIcons.Message02,
                            accent = PromptAccent,
                            onClick = { navController.navigate(Screen.AssistantPrompt(id)) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AssistantFeatureCard(
                            title = stringResource(R.string.assistant_page_tab_extensions),
                            description = stringResource(R.string.assistant_detail_extensions_desc),
                            icon = HugeIcons.Puzzle,
                            accent = ExtensionsAccent,
                            onClick = { navController.navigate(Screen.AssistantInjections(id)) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        AssistantFeatureCard(
                            title = stringResource(R.string.assistant_page_tab_memory),
                            description = stringResource(R.string.assistant_detail_memory_desc),
                            icon = HugeIcons.Brain02,
                            accent = MemoryAccent,
                            onClick = { navController.navigate(Screen.AssistantMemory(id)) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AssistantFeatureCard(
                            title = stringResource(R.string.assistant_page_tab_request),
                            description = stringResource(R.string.assistant_detail_request_desc),
                            icon = HugeIcons.Code,
                            accent = RequestAccent,
                            onClick = { navController.navigate(Screen.AssistantRequest(id)) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        AssistantFeatureCard(
                            title = stringResource(R.string.assistant_page_tab_local_tools),
                            description = stringResource(R.string.assistant_detail_local_tools_desc),
                            icon = HugeIcons.BookOpen01,
                            accent = LocalToolsAccent,
                            onClick = { navController.navigate(Screen.AssistantLocalTool(id)) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantFeatureCard(
    title: String,
    description: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalDarkMode.current
    val iconBackground = accent.copy(alpha = if (dark) 0.22f else 0.14f)
    val iconTint = if (dark) lerp(accent, Color.White, 0.35f) else accent

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(iconBackground),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AssistantHeader(
    assistant: Assistant,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            UIAvatar(
                value = assistant.avatar,
                name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                onUpdate = null,
                modifier = Modifier
                    .size(72.dp)
                    .heroAnimation("assistant_${assistant.id}")
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (assistant.systemPrompt.isNotBlank()) {
                    Text(
                        text = assistant.systemPrompt.take(100) + if (assistant.systemPrompt.length > 100) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
