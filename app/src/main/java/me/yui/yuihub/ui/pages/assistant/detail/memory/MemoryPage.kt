package me.yui.yuihub.ui.pages.assistant.detail.memory

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.MoreHorizontal
import me.rerere.hugeicons.stroke.Settings02
import me.yui.yuihub.R
import me.yui.yuihub.data.model.AssistantMemory
import me.yui.yuihub.data.model.MemoryCategory
import me.yui.yuihub.data.model.MemoryKind
import me.yui.yuihub.data.model.MemoryScope
import me.yui.yuihub.data.model.MemorySection
import me.yui.yuihub.data.model.MemoryStatus
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.ui.RikkaConfirmDialog
import me.yui.yuihub.ui.context.LocalNavController
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.utils.navigateToChatPage
import me.yui.yuihub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

private enum class MemorySort(val labelRes: Int) {
    UPDATED(R.string.memory_page_sort_updated),
    WEIGHT(R.string.memory_page_sort_weight),
    HITS(R.string.memory_page_sort_hits),
}

@Composable
fun AssistantMemoryPage(id: String) {
    val vm: MemoryVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val organize by vm.organize.collectAsStateWithLifecycle()
    val extracting by vm.extracting.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val resources = LocalResources.current
    val navigator = LocalNavController.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var query by remember { mutableStateOf("") }
    var kindFilter by remember { mutableStateOf<String?>(null) }
    var statusFilter by remember { mutableStateOf(MemoryStatus.ACTIVE) }
    var scopeFilter by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(MemorySort.UPDATED) }
    var selectedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var editorTarget by remember { mutableStateOf<AssistantMemory?>(null) }
    var detailId by remember { mutableStateOf<Int?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showCoreEditor by remember { mutableStateOf(false) }
    var showOrganizer by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<List<Int>?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val workspaceAvailable = assistant.workspaceId != null
    val visible = remember(memories, query, kindFilter, statusFilter, scopeFilter, sort) {
        memories.asSequence()
            .filter { it.status == statusFilter }
            .filter { kindFilter == null || it.kind == kindFilter }
            .filter { scopeFilter == null || it.scope == scopeFilter }
            .filter {
                query.isBlank() ||
                    it.title.contains(query, true) ||
                    it.content.contains(query, true) ||
                    it.keywords.any { keyword -> keyword.contains(query, true) }
            }
            .sortedWith(
                when (sort) {
                    MemorySort.WEIGHT -> compareByDescending<AssistantMemory> { it.weight }
                        .thenByDescending { it.updatedAt }
                    MemorySort.HITS -> compareByDescending<AssistantMemory> { it.hitCount }
                        .thenByDescending { it.updatedAt }
                    MemorySort.UPDATED -> compareByDescending { it.updatedAt }
                }
            )
            .toList()
    }
    val tabCounts = remember(memories, statusFilter, scopeFilter) {
        memories.filter { it.status == statusFilter }
            .filter { scopeFilter == null || it.scope == scopeFilter }
            .groupingBy { it.kind }
            .eachCount()
    }
    val grouped = remember(visible, kindFilter) {
        if (kindFilter != null) {
            listOf(kindFilter!! to visible)
        } else {
            MemoryKind.ALL.mapNotNull { kind ->
                val items = visible.filter { it.kind == kind }
                if (items.isEmpty()) null else kind to items
            }
        }
    }
    val coreMemories = remember(memories) {
        memories.filter { it.kind == MemoryKind.CORE && it.status == MemoryStatus.ACTIVE }
    }
    val coreSuggestions = remember(memories) {
        memories.filter {
            it.status == MemoryStatus.ACTIVE && it.kind != MemoryKind.CORE && it.weight >= 2 &&
                it.category in setOf(MemoryCategory.PROFILE, MemoryCategory.PREFERENCE)
        }.sortedByDescending { it.updatedAt }.take(6)
    }
    val detailMemory = detailId?.let { target -> memories.firstOrNull { it.id == target } }
    val selectionMode = selectedIds.isNotEmpty()
    BackHandler(enabled = selectionMode) { selectedIds = emptySet() }
    val canMerge = selectedIds.size >= 2 &&
        memories.filter { it.id in selectedIds }.map { it.kind }.distinct().size == 1

    fun undoArchive(ids: List<Int>) {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = resources.getString(R.string.memory_page_archived_toast, ids.size),
                actionLabel = resources.getString(R.string.memory_page_undo),
            )
            if (result == SnackbarResult.ActionPerformed) vm.restoreMemories(ids)
        }
    }

    fun shareExport() {
        vm.exportMarkdown { markdown ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, markdown)
            }
            context.startActivity(Intent.createChooser(intent, null))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.memory_page_cancel_selection))
                        }
                    } else {
                        BackButton()
                    }
                },
                title = {
                    if (selectionMode) {
                        Text(stringResource(R.string.memory_page_selected_count, selectedIds.size))
                    } else {
                        Column {
                            Text(stringResource(R.string.assistant_page_tab_memory))
                            Text(
                                text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (selectionMode) {
                        IconButton(onClick = {
                            val all = visible.map { it.id }.toSet()
                            selectedIds = if (selectedIds.containsAll(all)) emptySet() else all
                        }) {
                            Icon(HugeIcons.CheckmarkCircle02, contentDescription = stringResource(R.string.memory_page_select_all))
                        }
                    } else {
                        IconButton(onClick = { editorTarget = AssistantMemory() }) {
                            Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.memory_page_add_title))
                        }
                        IconButton(onClick = { showOrganizer = true }) {
                            Icon(HugeIcons.MagicWand01, contentDescription = stringResource(R.string.memory_page_organize))
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(HugeIcons.MoreHorizontal, contentDescription = stringResource(R.string.memory_page_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.memory_page_settings)) },
                                    leadingIcon = { Icon(HugeIcons.Settings02, null) },
                                    onClick = {
                                        menuOpen = false
                                        showSettings = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.memory_page_export)) },
                                    onClick = {
                                        menuOpen = false
                                        shareExport()
                                    },
                                )
                            }
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 4.dp,
                    bottom = 110.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MemoryCoreCard(
                            stats = stats,
                            coreBudget = settings.memoryCoreCharBudget,
                            onEdit = { showCoreEditor = true },
                            onOrganize = { showOrganizer = true },
                        )
                        MemoryHealthRow(
                            stats = stats,
                            selectedStatus = statusFilter,
                            onStatusSelected = {
                                statusFilter = it
                                selectedIds = emptySet()
                            },
                        )
                        MemorySearchField(query = query, onQueryChange = { query = it })
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (workspaceAvailable) {
                                FilterChipSmall(
                                    label = stringResource(R.string.memory_page_scope_all),
                                    selected = scopeFilter == null,
                                    onClick = { scopeFilter = null },
                                )
                                FilterChipSmall(
                                    label = stringResource(R.string.memory_page_scope_assistant),
                                    selected = scopeFilter == MemoryScope.ASSISTANT,
                                    onClick = { scopeFilter = MemoryScope.ASSISTANT },
                                )
                                FilterChipSmall(
                                    label = stringResource(R.string.memory_page_scope_workspace),
                                    selected = scopeFilter == MemoryScope.WORKSPACE,
                                    onClick = { scopeFilter = MemoryScope.WORKSPACE },
                                )
                            }
                            androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
                            Surface(
                                onClick = {
                                    sort = when (sort) {
                                        MemorySort.UPDATED -> MemorySort.WEIGHT
                                        MemorySort.WEIGHT -> MemorySort.HITS
                                        MemorySort.HITS -> MemorySort.UPDATED
                                    }
                                },
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Text(
                                    text = stringResource(sort.labelRes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                }
                stickyHeader(key = "tabs") {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f)) {
                        MemoryKindTabs(
                            selected = kindFilter,
                            counts = tabCounts,
                            onSelect = {
                                kindFilter = it
                                selectedIds = emptySet()
                            },
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }

                if (visible.isEmpty()) {
                    item(key = "empty") {
                        AnimatedVisibility(
                            visible = true,
                            enter = androidx.compose.animation.fadeIn(),
                            exit = androidx.compose.animation.fadeOut(),
                        ) {
                            if (memories.isEmpty()) {
                                MemoryEmptyState(
                                    enabled = assistant.enableMemory,
                                    extracting = extracting,
                                    onAdd = { editorTarget = AssistantMemory() },
                                    onExtract = { vm.extractNow() },
                                    onEnable = { vm.updateAssistant(assistant.copy(enableMemory = true)) },
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.memory_page_no_result),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 32.dp),
                                )
                            }
                        }
                    }
                }

                grouped.forEach { (kind, items) ->
                    stickyHeader(key = "header_$kind") {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f)) {
                            MemorySectionHeader(kind = kind, count = items.size)
                        }
                    }
                    items(items, key = { it.id }) { memory ->
                        MemoryCard(
                            memory = memory,
                            query = query,
                            selectionMode = selectionMode,
                            selected = memory.id in selectedIds,
                            onClick = {
                                if (selectionMode) {
                                    selectedIds = selectedIds.toggle(memory.id)
                                } else {
                                    detailId = memory.id
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) selectedIds = setOf(memory.id)
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }

            MemorySelectionDock(
                visible = selectionMode,
                selectedCount = selectedIds.size,
                canMerge = canMerge,
                onPromote = {
                    val targets = memories.filter { it.id in selectedIds && it.kind != MemoryKind.CORE }
                    targets.forEach { vm.promoteToCore(it.id, MemorySection.USER) }
                    selectedIds = emptySet()
                },
                onArchive = {
                    val ids = selectedIds.toList()
                    vm.archiveMemories(ids)
                    selectedIds = emptySet()
                    undoArchive(ids)
                },
                onMerge = {
                    val selected = memories.filter { it.id in selectedIds }
                    if (selected.isNotEmpty()) {
                        val content = selected.joinToString("\n") { "- ${it.content}" }
                        vm.mergeMemories(selected.map { it.id }, content, selected.first().displayTitle)
                        selectedIds = emptySet()
                    }
                },
                onDelete = { pendingDelete = selectedIds.toList() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }

    editorTarget?.let { target ->
        MemoryEditorSheet(
            initial = target,
            workspaceAvailable = workspaceAvailable,
            onDismiss = { editorTarget = null },
            onSave = { updated ->
                if (updated.id == 0) vm.createMemory(updated) else vm.updateMemory(updated)
            },
        )
    }

    detailMemory?.let { memory ->
        MemoryDetailSheet(
            memory = memory,
            sourceConversationId = memory.sourceConversationId,
            onDismiss = { detailId = null },
            onEdit = {
                detailId = null
                editorTarget = memory
            },
            onArchive = {
                vm.archiveMemories(listOf(memory.id))
                detailId = null
                undoArchive(listOf(memory.id))
            },
            onRestore = {
                vm.restoreMemories(listOf(memory.id))
                detailId = null
            },
            onDelete = {
                detailId = null
                pendingDelete = listOf(memory.id)
            },
            onPromote = { section ->
                vm.promoteToCore(memory.id, section)
                detailId = null
            },
            onDemote = {
                vm.demoteToFact(memory.id)
                detailId = null
            },
            onOpenSource = { conversationId ->
                runCatching { Uuid.parse(conversationId) }.getOrNull()?.let {
                    detailId = null
                    navigateToChatPage(navigator, it)
                }
            },
        )
    }

    if (showCoreEditor) {
        MemoryCoreEditorSheet(
            core = coreMemories,
            suggestions = coreSuggestions,
            budget = settings.memoryCoreCharBudget,
            onDismiss = { showCoreEditor = false },
            onEdit = { memory ->
                showCoreEditor = false
                editorTarget = memory
            },
            onAdd = { section ->
                showCoreEditor = false
                editorTarget = AssistantMemory(kind = MemoryKind.CORE, section = section, weight = 3)
            },
            onPromote = { memoryId ->
                vm.promoteToCore(memoryId, MemorySection.USER)
            },
            onDemote = { memoryId -> vm.demoteToFact(memoryId) },
            onOrganize = {
                showCoreEditor = false
                showOrganizer = true
            },
        )
    }

    if (showOrganizer) {
        MemoryOrganizeSheet(
            state = organize,
            events = events,
            onStart = { vm.organizeNow() },
            onDismiss = { showOrganizer = false },
        )
    }

    if (showSettings) {
        MemorySettingsSheet(
            assistant = assistant,
            settings = settings,
            onUpdateAssistant = { vm.updateAssistant(it) },
            onUpdateSettings = { vm.updateSettings { _ -> it } },
            onExport = {
                showSettings = false
                shareExport()
            },
            onClearArchived = { vm.clearArchived() },
            onClearAll = { pendingDelete = listOf(-1) },
            onDismiss = { showSettings = false },
        )
    }

    RikkaConfirmDialog(
        show = pendingDelete != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            val target = pendingDelete
            pendingDelete = null
            selectedIds = emptySet()
            if (target == listOf(-1)) {
                vm.clearAll()
            } else {
                target?.let { vm.deleteMemories(it) }
            }
        },
        onDismiss = { pendingDelete = null },
        text = {
            Text(
                text = if (pendingDelete == listOf(-1)) {
                    stringResource(R.string.memory_page_clear_all_confirm)
                } else {
                    stringResource(R.string.memory_page_delete_confirm)
                }
            )
        },
    )
}

@Composable
private fun FilterChipSmall(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

private fun Set<Int>.toggle(id: Int): Set<Int> =
    if (id in this) this - id else this + id
