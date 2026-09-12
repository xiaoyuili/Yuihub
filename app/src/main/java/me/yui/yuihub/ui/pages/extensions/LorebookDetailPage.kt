package me.yui.yuihub.ui.pages.extensions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Share03
import me.yui.yuihub.R
import me.yui.yuihub.Screen
import me.yui.yuihub.data.export.LorebookSerializer
import me.yui.yuihub.data.export.rememberExporter
import me.yui.yuihub.data.model.Lorebook
import me.yui.yuihub.data.model.PromptInjection
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.nav.FloatingBottomBar
import me.yui.yuihub.ui.components.nav.FloatingBottomBarDefaults
import me.yui.yuihub.ui.components.nav.FloatingBottomBarTab
import me.yui.yuihub.ui.components.ui.ExportDialog
import me.yui.yuihub.ui.components.ui.FormItem
import me.yui.yuihub.ui.components.ui.SwipeToReveal
import me.yui.yuihub.ui.context.LocalNavController
import me.yui.yuihub.ui.context.Navigator
import me.yui.yuihub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid

private const val TAB_OVERVIEW = 0
private const val TAB_ENTRIES = 1
private const val TAB_SETTINGS = 2

/**
 * 世界书详情。三个分页 + 底部悬浮导航：
 * 概览（基本信息、统计、操作）/ 条目（列表）/ 设置（匹配行为）。
 */
@OptIn(FlowPreview::class)
@Composable
fun LorebookDetailPage(bookId: String, vm: LorebookVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pagerState = rememberPagerState { 3 }

    val id = remember(bookId) { runCatching { Uuid.parse(bookId) }.getOrNull() }
    val book = settings.lorebooks.firstOrNull { it.id == id }

    // 书被删除（或 id 非法）时退出页面
    LaunchedEffect(book == null) {
        if (book == null) navController.popBackStack()
    }
    if (book == null) return

    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(book.name.ifBlank { stringResource(R.string.prompt_page_unnamed_lorebook) }) },
                navigationIcon = { BackButton() },
                actions = {
                    // 条目页给一个新建入口；其它页不需要（避免与底部导航抢位置）
                    if (pagerState.currentPage == TAB_ENTRIES) {
                        IconButton(onClick = {
                            navController.navigate(Screen.LorebookEntryEdit(book.id.toString(), ""))
                        }) {
                            Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.prompt_page_add_entry))
                        }
                    }
                },
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
                    .padding(innerPadding),
            ) { page ->
                when (page) {
                    TAB_ENTRIES -> EntriesTab(book = book, vm = vm, navController = navController)
                    TAB_SETTINGS -> SettingsTab(book = book, vm = vm)
                    else -> OverviewTab(
                        book = book,
                        vm = vm,
                        onDelete = { showDeleteDialog = true },
                    )
                }
            }

            FloatingBottomBar(
                pagerState = pagerState,
                tabs = listOf(
                    FloatingBottomBarTab(
                        icon = HugeIcons.ChartColumn,
                        label = stringResource(R.string.lorebook_tab_overview),
                    ),
                    FloatingBottomBarTab(
                        icon = HugeIcons.LeftToRightListBullet,
                        label = stringResource(R.string.lorebook_tab_entries),
                    ),
                    FloatingBottomBarTab(
                        icon = HugeIcons.Settings02,
                        label = stringResource(R.string.lorebook_tab_settings),
                    ),
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = innerPadding.calculateBottomPadding()),
            )
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.lorebook_delete_title)) },
            text = { Text(stringResource(R.string.lorebook_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    vm.deleteBook(book.id)
                }) {
                    Text(stringResource(R.string.prompt_page_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.prompt_page_cancel))
                }
            },
        )
    }
}

// ==================== 条目 ====================

@OptIn(FlowPreview::class)
@Composable
private fun EntriesTab(
    book: Lorebook,
    vm: LorebookVM,
    navController: Navigator,
) {
    val lazyListState = rememberLazyListState()
    var query by rememberSaveable(book.id) { mutableStateOf("") }
    val entries = book.entries
    val visibleEntries = remember(entries, query) {
        if (query.isBlank()) entries
        else entries.filter { entry ->
            entry.name.contains(query, ignoreCase = true) ||
                entry.keywords.any { it.contains(query, ignoreCase = true) } ||
                entry.secondaryKeywords.any { it.contains(query, ignoreCase = true) }
        }
    }
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        vm.reorderEntries(book.id, from.index, to.index)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 4.dp,
            end = 16.dp,
            bottom = FloatingBottomBarDefaults.ContentBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        state = lazyListState,
    ) {
        item(key = "search") {
            AnimatedVisibility(
                visible = entries.size > 4,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Column {
                    LorebookSearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = stringResource(R.string.lorebook_detail_search_entries),
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }

        if (entries.isEmpty()) {
            item(key = "empty") {
                LorebookEmptyState(
                    title = stringResource(R.string.lorebook_detail_no_entries),
                    hint = stringResource(R.string.prompt_page_empty_hint),
                )
            }
        } else if (visibleEntries.isEmpty()) {
            item(key = "no-match") {
                LorebookEmptyState(
                    title = stringResource(R.string.lorebook_detail_no_match),
                    hint = stringResource(R.string.lorebook_detail_no_match_hint),
                )
            }
        }

        items(visibleEntries, key = { it.id }) { entry ->
            ReorderableItem(state = reorderableState, key = entry.id) { isDragging ->
                SwipeToReveal(
                    onDelete = { vm.deleteEntry(book.id, entry.id) },
                    deleteLabel = stringResource(R.string.prompt_page_delete),
                    modifier = Modifier.graphicsLayer {
                        if (isDragging) {
                            scaleX = 1.02f
                            scaleY = 1.02f
                        }
                    },
                ) {
                    EntryCard(
                        entry = entry,
                        modifier = Modifier.longPressDraggableHandle(),
                        onOpen = {
                            navController.navigate(
                                Screen.LorebookEntryEdit(book.id.toString(), entry.id.toString())
                            )
                        },
                        onToggleEnabled = { vm.setEntryEnabled(book.id, entry.id, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryCard(
    entry: PromptInjection.RegexInjection,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = entry.name.ifBlank {
                        entry.keywords.firstOrNull() ?: stringResource(R.string.prompt_page_unnamed_entry)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.keywords.isNotEmpty()) {
                    Text(
                        text = entry.keywords.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                LorebookEntryTags(entry = entry)
            }
            Switch(
                checked = entry.enabled,
                onCheckedChange = onToggleEnabled,
            )
        }
    }
}

// ==================== 设置 ====================

@Composable
private fun SettingsTab(book: Lorebook, vm: LorebookVM) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 4.dp,
            end = 16.dp,
            bottom = FloatingBottomBarDefaults.ContentBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "matching") {
            LorebookSectionCard(title = stringResource(R.string.lorebook_settings_matching)) {
                FormItem(
                    label = { Text(stringResource(R.string.lorebook_page_recursive_scanning)) },
                    description = { Text(stringResource(R.string.lorebook_page_recursive_scanning_desc)) },
                    tail = {
                        Switch(
                            checked = book.recursiveScanning,
                            onCheckedChange = { vm.updateBook(book.copy(recursiveScanning = it)) },
                        )
                    },
                )
                AnimatedVisibility(visible = book.recursiveScanning) {
                    IntField(
                        value = book.maxRecursionSteps,
                        onValueChange = { vm.updateBook(book.copy(maxRecursionSteps = it.coerceIn(1, 20))) },
                        label = stringResource(R.string.lorebook_page_max_recursion_steps),
                        min = 1,
                    )
                }
                FormItem(
                    label = { Text(stringResource(R.string.lorebook_page_scan_assistant_prompt)) },
                    description = { Text(stringResource(R.string.lorebook_page_scan_assistant_prompt_desc)) },
                    tail = {
                        Switch(
                            checked = book.scanAssistantPrompt,
                            onCheckedChange = { vm.updateBook(book.copy(scanAssistantPrompt = it)) },
                        )
                    },
                )
            }
        }

        item(key = "budget") {
            LorebookSectionCard(title = stringResource(R.string.lorebook_settings_budget)) {
                IntField(
                    value = book.tokenBudget,
                    onValueChange = { vm.updateBook(book.copy(tokenBudget = it.coerceAtLeast(0))) },
                    label = stringResource(R.string.lorebook_page_token_budget),
                    supporting = stringResource(R.string.lorebook_page_token_budget_desc),
                    min = 0,
                )
                IntField(
                    value = book.minActivations,
                    onValueChange = { vm.updateBook(book.copy(minActivations = it.coerceAtLeast(0))) },
                    label = stringResource(R.string.lorebook_page_min_activations),
                    supporting = stringResource(R.string.lorebook_page_min_activations_desc),
                    min = 0,
                )
            }
        }
    }
}

// ==================== 概览 ====================

@OptIn(FlowPreview::class)
@Composable
private fun OverviewTab(
    book: Lorebook,
    vm: LorebookVM,
    onDelete: () -> Unit,
) {
    var showExportDialog by remember { mutableStateOf(false) }
    val exporter = rememberExporter(book, LorebookSerializer)

    var nameDraft by remember(book.id) { mutableStateOf(book.name) }
    var descriptionDraft by remember(book.id) { mutableStateOf(book.description) }
    LaunchedEffect(book.id) {
        snapshotFlow { nameDraft }.drop(1).debounce(400).collect { vm.renameBook(book.id, it) }
    }
    LaunchedEffect(book.id) {
        snapshotFlow { descriptionDraft }.drop(1).debounce(400).collect { vm.setBookDescription(book.id, it) }
    }

    val entries = book.entries
    val enabledCount = entries.count { it.enabled }
    val constantCount = entries.count { it.constantActive }
    val groupCount = entries.mapNotNull { it.group.takeIf { name -> name.isNotBlank() } }.distinct().size
    val timedCount = entries.count { it.sticky > 0 || it.cooldown > 0 || it.delay > 0 }
    val totalChars = entries.sumOf { it.content.length }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 4.dp,
            end = 16.dp,
            bottom = FloatingBottomBarDefaults.ContentBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "basic") {
            LorebookSectionCard(title = stringResource(R.string.lorebook_overview_basic)) {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    label = { Text(stringResource(R.string.prompt_page_name)) },
                    placeholder = { Text(stringResource(R.string.prompt_page_unnamed_lorebook)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = descriptionDraft,
                    onValueChange = { descriptionDraft = it },
                    label = { Text(stringResource(R.string.prompt_page_description)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_enabled)) },
                    tail = {
                        Switch(
                            checked = book.enabled,
                            onCheckedChange = { vm.updateBook(book.copy(enabled = it)) },
                        )
                    },
                )
            }
        }

        item(key = "stats") {
            LorebookSectionCard(title = stringResource(R.string.lorebook_overview_stats)) {
                val stats = listOf(
                    stringResource(R.string.lorebook_stat_total) to entries.size.toString(),
                    stringResource(R.string.lorebook_stat_enabled) to enabledCount.toString(),
                    stringResource(R.string.lorebook_stat_constant) to constantCount.toString(),
                    stringResource(R.string.lorebook_stat_groups) to groupCount.toString(),
                    stringResource(R.string.lorebook_stat_timed) to timedCount.toString(),
                    stringResource(R.string.lorebook_stat_chars) to totalChars.toString(),
                )
                stats.forEachIndexed { index, (label, value) ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        )
                    }
                    StatRow(label = label, value = value)
                }
            }
        }

        item(key = "actions") {
            LorebookSectionCard(title = stringResource(R.string.lorebook_overview_actions)) {
                ActionRow(
                    icon = HugeIcons.Share03,
                    label = stringResource(R.string.lorebook_action_export),
                    onClick = { showExportDialog = true },
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                )
                ActionRow(
                    icon = HugeIcons.Delete01,
                    label = stringResource(R.string.lorebook_action_delete),
                    onClick = onDelete,
                    destructive = true,
                )
            }
        }
    }

    if (showExportDialog) {
        ExportDialog(
            exporter = exporter,
            onDismiss = { showExportDialog = false },
        )
    }
}

/** 概览页的操作行（紧凑的单行，而不是大块卡片） */
@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val contentColor = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = HugeIcons.ArrowRight01,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 统计行：标签左、数值右，数值用等宽数字避免跳动 */
@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun IntField(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    min: Int,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text -> text.toIntOrNull()?.let { onValueChange(it.coerceAtLeast(min)) } },
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}
