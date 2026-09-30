package me.yui.yuihub.ui.pages.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit03
import me.rerere.hugeicons.stroke.UserMultiple
import me.yui.yuihub.R
import me.yui.yuihub.data.datastore.SettingsStore
import me.yui.yuihub.data.model.BuiltinSubagentPersonas
import me.yui.yuihub.data.model.SubagentPersona
import me.yui.yuihub.data.model.SubagentToolCatalog
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.ui.RikkaConfirmDialog
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.utils.plus
import org.koin.compose.koinInject
import kotlinx.coroutines.launch

/**
 * 子代理角色页：管理 spawn_agent 可用的专家角色（提示词 + 工具白名单）。
 * 内置探索者/审查者/规划者首次启动时写入，用户可编辑或删除。
 */
@Composable
fun SubagentPersonasPage() {
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val personas = settings.subagentPersonas
    var editing by remember { mutableStateOf<SubagentPersona?>(null) }
    var pendingDelete by remember { mutableStateOf<SubagentPersona?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.subagent_page_title)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = SubagentPersona() }) {
                Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.subagent_page_add))
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (personas.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HugeIcons.UserMultiple,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.subagent_page_empty_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.subagent_page_empty_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "intro") {
                    Text(
                        text = stringResource(R.string.subagent_page_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                items(personas, key = { it.id }) { persona ->
                    SubagentPersonaCard(
                        persona = persona,
                        onEdit = { editing = persona },
                        onDelete = { pendingDelete = persona },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    editing?.let { target ->
        SubagentPersonaEditSheet(
            initial = target,
            onDismiss = { editing = null },
            onSave = { updated ->
                val exists = personas.any { it.id == updated.id }
                scope.launch {
                    settingsStore.update { current ->
                        current.copy(
                            subagentPersonas = if (exists) {
                                current.subagentPersonas.map { if (it.id == updated.id) updated else it }
                            } else {
                                current.subagentPersonas + updated
                            }
                        )
                    }
                }
                editing = null
            },
        )
    }

    RikkaConfirmDialog(
        show = pendingDelete != null,
        title = stringResource(R.string.subagent_page_delete_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDelete?.let { target ->
                scope.launch {
                    settingsStore.update { current ->
                        current.copy(subagentPersonas = current.subagentPersonas.filterNot { it.id == target.id })
                    }
                }
            }
            pendingDelete = null
        },
        onDismiss = { pendingDelete = null },
    ) {
        Text(stringResource(R.string.subagent_page_delete_message, pendingDelete?.name.orEmpty()))
    }
}

@Composable
private fun SubagentPersonaCard(
    persona: SubagentPersona,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isBuiltin = persona.id in setOf(
        BuiltinSubagentPersonas.EXPLORER_ID,
        BuiltinSubagentPersonas.REVIEWER_ID,
        BuiltinSubagentPersonas.PLANNER_ID,
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HugeIcons.UserMultiple,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = persona.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isBuiltin) {
                        Text(
                            text = stringResource(R.string.subagent_page_builtin),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = HugeIcons.Edit03,
                        contentDescription = stringResource(R.string.subagent_page_edit),
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = HugeIcons.Delete01,
                        contentDescription = stringResource(R.string.delete),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Text(
                text = persona.systemPrompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = if (persona.allowedTools.isEmpty()) {
                    stringResource(R.string.subagent_page_tools_all)
                } else {
                    stringResource(
                        R.string.subagent_page_tools_count,
                        persona.allowedTools.size,
                        persona.allowedTools.sorted().take(3).joinToString(", ") +
                            if (persona.allowedTools.size > 3) "…" else "",
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
    }
}

/**
 * 角色编辑弹层：名称、提示词、工具白名单多选（不勾 = 不限制）。
 */
@Composable
private fun SubagentPersonaEditSheet(
    initial: SubagentPersona,
    onDismiss: () -> Unit,
    onSave: (SubagentPersona) -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var systemPrompt by remember(initial.id) { mutableStateOf(initial.systemPrompt) }
    var allowedTools by remember(initial.id) { mutableStateOf(initial.allowedTools) }
    var unrestricted by remember(initial.id) { mutableStateOf(initial.allowedTools.isEmpty()) }

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(
                    if (initial.name.isBlank()) R.string.subagent_page_create else R.string.subagent_page_edit
                ),
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.subagent_page_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text(stringResource(R.string.subagent_page_prompt)) },
                placeholder = { Text(stringResource(R.string.subagent_page_prompt_hint)) },
                minLines = 4,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.subagent_page_tools),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = unrestricted,
                        onClick = { unrestricted = !unrestricted },
                        label = { Text(stringResource(R.string.subagent_page_tools_all)) },
                    )
                }
                if (!unrestricted) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SubagentToolCatalog.ENTRIES.forEach { (toolName, _) ->
                            FilterChip(
                                selected = toolName in allowedTools,
                                onClick = {
                                    allowedTools = if (toolName in allowedTools) {
                                        allowedTools - toolName
                                    } else {
                                        allowedTools + toolName
                                    }
                                },
                                label = { Text(toolName, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
            }

            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            systemPrompt = systemPrompt.trim(),
                            allowedTools = if (unrestricted) emptySet() else allowedTools,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                },
                enabled = name.isNotBlank() && systemPrompt.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.automation_edit_save))
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}
