package me.yui.yuihub.ui.pages.assistant.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.Puzzle
import me.yui.yuihub.R
import me.yui.yuihub.Screen
import me.yui.yuihub.data.model.Assistant
import me.yui.yuihub.data.model.Avatar
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.ui.UIAvatar
import me.yui.yuihub.ui.context.LocalNavController
import me.yui.yuihub.ui.hooks.heroAnimation
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.ui.theme.EntryAccents
import me.yui.yuihub.ui.theme.LocalDarkMode
import me.yui.yuihub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf


@Composable
fun AssistantDetailPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    var showNameDialog by remember(assistant.id) { mutableStateOf(false) }
    var nameInput by remember(assistant.id) { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_detail_title))
                },
                navigationIcon = {
                    BackButton()
                },
                colors = CustomColors.pageTopBarColors
            )
        },
        containerColor = CustomColors.pageBackground
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                AssistantHeader(
                    assistant = assistant,
                    onAvatarUpdate = { avatar ->
                        vm.update(
                            assistant.copy(
                                avatar = if (avatar == Avatar.Dummy) Avatar.Logo else avatar
                            )
                        )
                    },
                    onNameClick = {
                        nameInput = assistant.name
                        showNameDialog = true
                    },
                )
            }

            item {
                Text(
                    text = stringResource(R.string.assistant_detail_section_manage),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                )
            }

            item {
                AssistantEntryCard(
                    title = stringResource(R.string.assistant_page_tab_prompt),
                    description = stringResource(R.string.assistant_detail_prompt_desc),
                    icon = HugeIcons.Message02,
                    accent = EntryAccents.Violet,
                    onClick = { navController.navigate(Screen.AssistantPrompt(id)) },
                )
            }

            item {
                AssistantEntryCard(
                    title = stringResource(R.string.assistant_page_tab_extensions),
                    description = stringResource(R.string.assistant_detail_extensions_desc),
                    icon = HugeIcons.Puzzle,
                    accent = EntryAccents.Amber,
                    onClick = { navController.navigate(Screen.AssistantInjections(id)) },
                )
            }

            item {
                AssistantEntryCard(
                    title = stringResource(R.string.assistant_page_tab_memory),
                    description = stringResource(R.string.assistant_detail_memory_desc),
                    icon = HugeIcons.Brain02,
                    accent = EntryAccents.Teal,
                    onClick = { navController.navigate(Screen.AssistantMemory(id)) },
                )
            }

        }
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text(stringResource(R.string.assistant_page_name)) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.assistant_page_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.update(assistant.copy(name = nameInput.trim()))
                        showNameDialog = false
                    },
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            },
        )
    }
}

@Composable
private fun AssistantHeader(
    assistant: Assistant,
    onAvatarUpdate: (Avatar) -> Unit,
    onNameClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        UIAvatar(
            value = assistant.avatar,
            name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
            onUpdate = onAvatarUpdate,
            modifier = Modifier
                .size(96.dp)
                .heroAnimation("assistant_${assistant.id}")
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onNameClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun AssistantEntryCard(
    title: String,
    description: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
) {
    val dark = LocalDarkMode.current
    val iconTint = if (dark) lerp(accent, Color.White, 0.25f) else accent
    val iconBackground = iconTint.copy(alpha = if (dark) 0.2f else 0.14f)

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
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
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

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Icon(
                imageVector = HugeIcons.ArrowRight01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
