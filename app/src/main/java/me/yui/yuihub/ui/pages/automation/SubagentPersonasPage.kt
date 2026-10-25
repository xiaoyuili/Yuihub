package me.yui.yuihub.ui.pages.automation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit03
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.UserMultiple
import me.yui.yuihub.R
import me.yui.yuihub.data.datastore.SettingsStore
import me.yui.yuihub.data.model.BuiltinSubagentPersonas
import me.yui.yuihub.data.model.SubagentPersona
import me.yui.yuihub.data.model.SubagentToolCatalog
import me.yui.yuihub.ui.components.ai.ModelListSheet
import me.yui.yuihub.ui.components.ai.ReasoningLevelPanel
import me.yui.yuihub.ui.components.ai.rememberModelListState
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.ui.RikkaConfirmDialog
import me.yui.yuihub.ui.components.ui.Tag
import me.yui.yuihub.ui.components.ui.TagType
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.ui.theme.EntryAccents
import me.yui.yuihub.utils.plus
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * 子代理角色页：管理 spawn_agent 可用的专家角色（提示词 + 工具白名单）。
 * 内置探索者/审查者/规划者首次启动时写入，用户可编辑或删除。
 *
 * 新建入口在顶栏右上角（与页面「右上角添加」的交互一致）。
 */
@Composable
fun SubagentPersonasPage() {
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val personas = settings.subagentPersonas
    var editing by remember { mutableStateOf<SubagentPersona?>(null) }
    var pendingDelete by remember { mutableStateOf<SubagentPersona?>(null) }
    var query by remember { mutableStateOf("") }
    val visiblePersonas = remember(personas, query) {
        if (query.isBlank()) personas
        else personas.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.systemPrompt.contains(query, ignoreCase = true)
        }
    }
    val duplicateSuffix = stringResource(R.string.subagent_page_duplicate)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.subagent_page_title),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { editing = SubagentPersona() }) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = stringResource(R.string.subagent_page_add),
                        )
                    }
                },
                colors = CustomColors.pageTopBarColors,
            )
        },
        containerColor = CustomColors.pageBackground,
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
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(18.dp))
                Button(onClick = { editing = SubagentPersona() }) {
                    Text(stringResource(R.string.subagent_page_add))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 32.dp,
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
                item(key = "search") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.subagent_page_search_placeholder)) },
                        leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(HugeIcons.Cancel01, contentDescription = null)
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                    )
                }
                item(key = "count") {
                    Text(
                        text = stringResource(R.string.subagent_page_count, visiblePersonas.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                if (visiblePersonas.isEmpty()) {
                    item(key = "no-match") {
                        Text(
                            text = stringResource(R.string.subagent_page_no_match),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        )
                    }
                }
                items(visiblePersonas, key = { it.id }) { persona ->
                    SubagentPersonaCard(
                        persona = persona,
                        onEdit = { editing = persona },
                        onDelete = { pendingDelete = persona },
                        onDuplicate = {
                            val now = System.currentTimeMillis()
                            val copy = persona.copy(
                                id = Uuid.random(),
                                name = "${persona.name} · $duplicateSuffix",
                                createdAt = now,
                                updatedAt = now,
                            )
                            scope.launch {
                                settingsStore.update { current ->
                                    current.copy(subagentPersonas = current.subagentPersonas + copy)
                                }
                            }
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    editing?.let { target ->
        SubagentPersonaEditSheet(
            initial = target,
            providers = settings.providers,
            defaultModelId = settings.chatModelId,
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
    onDuplicate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isBuiltin = persona.id in setOf(
        BuiltinSubagentPersonas.EXPLORER_ID,
        BuiltinSubagentPersonas.REVIEWER_ID,
        BuiltinSubagentPersonas.PLANNER_ID,
    )
    var menuExpanded by remember { mutableStateOf(false) }
    val accent = if (isBuiltin) EntryAccents.Indigo else EntryAccents.Violet

    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onEdit,
        shape = RoundedCornerShape(18.dp),
        colors = CustomColors.cardColorsOnSurfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = persona.name.trim().take(1).ifBlank { "?" },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = accent,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = persona.name.ifBlank { stringResource(R.string.subagent_page_title) },
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isBuiltin) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            ) {
                                Text(
                                    text = stringResource(R.string.subagent_page_builtin),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tag(type = TagType.DEFAULT) {
                            Text(
                                if (persona.allowedTools.isEmpty()) {
                                    stringResource(R.string.subagent_page_tools_all)
                                } else {
                                    stringResource(R.string.subagent_page_tools_badge, persona.allowedTools.size)
                                }
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = HugeIcons.MoreVertical,
                            contentDescription = stringResource(R.string.subagent_page_edit),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.subagent_page_edit)) },
                            leadingIcon = { Icon(HugeIcons.Edit03, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.subagent_page_duplicate)) },
                            leadingIcon = { Icon(HugeIcons.Copy01, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onDuplicate()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Delete01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }

            if (persona.systemPrompt.isNotBlank()) {
                Text(
                    text = persona.systemPrompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (persona.allowedTools.isNotEmpty()) {
                Text(
                    text = persona.allowedTools.sorted().take(4)
                        .map { SubagentToolCatalog.displayName(it) }
                        .joinToString(", ") +
                        if (persona.allowedTools.size > 4) "…" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 角色编辑弹层：名称、提示词、工具权限。
 *
 * 工具权限两种模式：全部允许（不限制）与自定义白名单；自定义模式提供全选/清空快捷操作，
 * 每个工具以中文名展示，与设置页的工具命名保持一致。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubagentPersonaEditSheet(
    initial: SubagentPersona,
    providers: List<ProviderSetting>,
    defaultModelId: Uuid,
    onDismiss: () -> Unit,
    onSave: (SubagentPersona) -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var systemPrompt by remember(initial.id) { mutableStateOf(initial.systemPrompt) }
    var allowedTools by remember(initial.id) { mutableStateOf(initial.allowedTools) }
    var unrestricted by remember(initial.id) { mutableStateOf(initial.allowedTools.isEmpty()) }
    var toolQuery by remember { mutableStateOf("") }
    var modelId by remember(initial.id) { mutableStateOf(initial.modelId) }
    var reasoningLevel by remember(initial.id) { mutableStateOf(initial.reasoningLevel) }
    var showReasoningSheet by remember { mutableStateOf(false) }
    val modelState = rememberModelListState(
        modelId = modelId,
        providers = providers,
        type = ModelType.CHAT,
    )

    val filteredTools = SubagentToolCatalog.ENTRIES.filter { entry ->
        toolQuery.isBlank() ||
            entry.second.contains(toolQuery, ignoreCase = true) ||
            entry.first.contains(toolQuery, ignoreCase = true)
    }
    val canSave = name.isNotBlank() && systemPrompt.isNotBlank() &&
        (unrestricted || allowedTools.isNotEmpty())

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .imePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = name.trim().take(1).ifBlank { "?" },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (initial.name.isBlank()) R.string.subagent_page_create else R.string.subagent_page_edit
                        ),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.subagent_page_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.cancel))
                }
            }

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                SegmentedButton(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text(stringResource(R.string.subagent_page_tab_profile))
                }
                SegmentedButton(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text(stringResource(R.string.subagent_page_tab_tools))
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (tab == 0) {
                    Text(
                        text = stringResource(R.string.subagent_page_section_basic),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.subagent_page_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        text = stringResource(R.string.subagent_page_section_prompt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        label = { Text(stringResource(R.string.subagent_page_prompt)) },
                        placeholder = { Text(stringResource(R.string.subagent_page_prompt_hint)) },
                        minLines = 6,
                        maxLines = 14,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = CustomColors.groupedCardSurface,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.subagent_page_model),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { modelState.open() }) {
                                    Text(
                                        text = modelState.currentModel?.displayName
                                            ?: stringResource(R.string.subagent_page_follow_chat),
                                    )
                                    Icon(
                                        imageVector = HugeIcons.ArrowRight01,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 14.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.subagent_page_reasoning_short),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { showReasoningSheet = true }) {
                                    Text(
                                        text = if (reasoningLevel == null) {
                                            stringResource(R.string.subagent_page_follow_chat)
                                        } else {
                                            reasoningLevel!!.displayLabel()
                                        },
                                    )
                                    Icon(
                                        imageVector = HugeIcons.ArrowRight01,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = CustomColors.groupedCardSurface,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.subagent_page_section_tools),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = if (unrestricted) {
                                        stringResource(R.string.subagent_page_tools_all)
                                    } else {
                                        stringResource(
                                            R.string.subagent_page_tools_selected,
                                            allowedTools.size,
                                            SubagentToolCatalog.ENTRIES.size,
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { tab = 1 }) {
                                Text(stringResource(R.string.subagent_page_tools_manage))
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = CustomColors.groupedCardSurface,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.subagent_page_tools_all),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(R.string.subagent_page_tools_all_desc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = unrestricted,
                                onCheckedChange = { unrestricted = it },
                            )
                        }
                    }

                    if (!unrestricted) {
                        OutlinedTextField(
                            value = toolQuery,
                            onValueChange = { toolQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.subagent_page_tools_search)) },
                            leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.subagent_page_tools_selected,
                                    allowedTools.size,
                                    SubagentToolCatalog.ENTRIES.size,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    allowedTools = allowedTools + filteredTools.map { it.first }
                                },
                            ) {
                                Text(stringResource(R.string.subagent_page_tools_select_all))
                            }
                            TextButton(onClick = { allowedTools = emptySet() }) {
                                Text(stringResource(R.string.subagent_page_tools_clear))
                            }
                        }

                        if (filteredTools.isEmpty()) {
                            Text(
                                text = stringResource(R.string.subagent_page_tools_none),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        } else {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                filteredTools.forEach { entry ->
                                    val toolName = entry.first
                                    FilterChip(
                                        selected = toolName in allowedTools,
                                        onClick = {
                                            allowedTools = if (toolName in allowedTools) {
                                                allowedTools - toolName
                                            } else {
                                                allowedTools + toolName
                                            }
                                        },
                                        label = { Text(entry.second) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        onSave(
                            initial.copy(
                                name = name.trim(),
                                systemPrompt = systemPrompt.trim(),
                                allowedTools = if (unrestricted) emptySet() else allowedTools,
                                modelId = modelId,
                                reasoningLevel = reasoningLevel,
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                    },
                    enabled = canSave,
                    modifier = Modifier.weight(2f),
                ) {
                    Text(stringResource(R.string.automation_edit_save))
                }
            }
            ModelListSheet(
                state = modelState,
                onSelect = { modelId = it.id },
                followOptionLabel = stringResource(R.string.subagent_page_follow_chat),
                onSelectFollow = { modelId = null },
            )

            if (showReasoningSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showReasoningSheet = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.subagent_page_reasoning),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        TextButton(
                            onClick = {
                                reasoningLevel = null
                                showReasoningSheet = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.subagent_page_follow_chat),
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Start,
                            )
                        }
                        ReasoningLevelPanel(
                            reasoningLevel = reasoningLevel ?: ReasoningLevel.AUTO,
                            onUpdateReasoningLevel = {
                                reasoningLevel = it
                                showReasoningSheet = false
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}


@Composable
private fun ReasoningLevel.displayLabel(): String = when (this) {
    ReasoningLevel.OFF -> stringResource(R.string.reasoning_off)
    ReasoningLevel.AUTO -> stringResource(R.string.reasoning_auto)
    ReasoningLevel.LOW -> stringResource(R.string.reasoning_light)
    ReasoningLevel.MEDIUM -> stringResource(R.string.reasoning_medium)
    ReasoningLevel.HIGH -> stringResource(R.string.reasoning_heavy)
    ReasoningLevel.XHIGH -> stringResource(R.string.reasoning_xhigh)
    ReasoningLevel.MAX -> stringResource(R.string.reasoning_max)
}
