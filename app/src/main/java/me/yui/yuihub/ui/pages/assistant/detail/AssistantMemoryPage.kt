package me.yui.yuihub.ui.pages.assistant.detail

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CancelCircle
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.FilterHorizontal
import me.rerere.hugeicons.stroke.Hourglass
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.MaskTheater01
import me.rerere.hugeicons.stroke.MoreHorizontal
import me.rerere.hugeicons.stroke.MoreHorizontalCircle01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.SourceCode
import me.rerere.hugeicons.stroke.Star
import me.rerere.hugeicons.stroke.Sun03
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.hugeicons.stroke.UserCircle02
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.yui.yuihub.R
import me.yui.yuihub.data.model.Assistant
import me.yui.yuihub.data.model.AssistantMemory
import me.yui.yuihub.data.model.MemoryCategory
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.ui.FormItem
import me.yui.yuihub.ui.components.ui.RikkaConfirmDialog
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.ui.theme.extendColors
import me.yui.yuihub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 记忆页：概览 + 按类别分组浏览 + 滑动管理。
 *
 * - 概览：记忆总数与类别构成的彩色分布条（点击跳转对应分组）
 * - 浏览：sticky 类别组头 + 卡片列表；卡片点击编辑、左滑删除、按压缩放的即时反馈
 * - 搜索：平铺结果 + 类别标签
 * - 开关等设置收进顶栏菜单的底部弹层
 */
@Composable
fun AssistantMemoryPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = { parametersOf(id) }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<AssistantMemory?>(null) }
    var pendingDelete by remember { mutableStateOf<AssistantMemory?>(null) }
    var consolidateState by remember { mutableStateOf<ConsolidateState?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val listItems = remember(memories, query, assistant.enableMemory, categoryFilter) {
        buildList {
            val searching = query.isNotBlank()
            val activeFilter = categoryFilter
            val visible = activeFilter
                ?.let { filter -> memories.filter { it.category == filter } }
                ?: memories
            if (memories.isNotEmpty()) {
                add(MemoryListItem.Summary)
                add(MemoryListItem.Search)
            }
            if (!assistant.enableMemory) {
                add(MemoryListItem.DisabledCard)
            }
            if (assistant.enableMemory && memories.isEmpty()) {
                add(MemoryListItem.EmptyState)
            }
            if (memories.isNotEmpty()) {
                if (searching) {
                    val keyword = query.trim()
                    val results = visible.filter { it.content.contains(keyword, ignoreCase = true) }
                    if (results.isEmpty()) {
                        add(MemoryListItem.NoResult)
                    } else {
                        add(MemoryListItem.ResultCount(results.size))
                        results.forEach { add(MemoryListItem.Entry(it, showCategory = activeFilter == null)) }
                    }
                } else if (activeFilter != null) {
                    if (visible.isEmpty()) {
                        add(MemoryListItem.EmptyCategory)
                    } else {
                        add(MemoryListItem.Header(activeFilter, visible.size))
                        visible.forEach { add(MemoryListItem.Entry(it, showCategory = false)) }
                    }
                } else {
                    MemoryCategory.ALL.forEach { category ->
                        val group = memories.filter { it.category == category }
                        if (group.isNotEmpty()) {
                            add(MemoryListItem.Header(category, group.size))
                            group.forEach { add(MemoryListItem.Entry(it, showCategory = false)) }
                        }
                    }
                }
            }
        }
    }

    val consolidating = consolidateState == ConsolidateState.Running

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.assistant_page_tab_memory))
                        Spacer(modifier = Modifier.weight(1f))
                        // 记忆总开关：放在标题右侧（放在顶栏 actions 里与其它图标不成组）
                        Switch(
                            checked = assistant.enableMemory,
                            onCheckedChange = { vm.update(assistant.copy(enableMemory = it)) },
                        )
                    }
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        enabled = assistant.enableMemory && memories.isNotEmpty() && !consolidating,
                        onClick = {
                            consolidateState = ConsolidateState.Running
                            vm.consolidateMemories { result ->
                                consolidateState = if (result == null) {
                                    ConsolidateState.Failed
                                } else {
                                    ConsolidateState.Done(result.first, result.second)
                                }
                            }
                        },
                    ) {
                        if (consolidating) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Icon(
                                imageVector = HugeIcons.MagicWand01,
                                contentDescription = stringResource(R.string.memory_page_consolidate),
                            )
                        }
                    }

                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = HugeIcons.MoreHorizontal,
                                contentDescription = stringResource(R.string.memory_page_more),
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.memory_page_settings)) },
                                leadingIcon = { Icon(HugeIcons.Settings02, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    showSettings = true
                                },
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = assistant.enableMemory,
                enter = scaleIn(initialScale = 0.8f) + fadeIn(),
                exit = scaleOut(targetScale = 0.8f) + fadeOut(),
            ) {
                FloatingActionButton(
                    onClick = { editing = AssistantMemory(id = 0) },
                ) {
                    Icon(
                        imageVector = HugeIcons.Add01,
                        contentDescription = stringResource(R.string.memory_page_add_title),
                    )
                }
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = innerPadding + PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 4.dp,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listItems.forEach { item ->
                when (item) {
                    is MemoryListItem.Summary -> item(key = "summary") {
                        MemorySummaryBlock(
                            memories = memories,
                            onJumpToCategory = { category ->
                                val index = listItems.indexOfFirst {
                                    it is MemoryListItem.Header && it.category == category
                                }
                                if (index >= 0) {
                                    scope.launch { listState.animateScrollToItem(index) }
                                }
                            },
                        )
                    }

                    is MemoryListItem.Search -> item(key = "search") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MemorySearchBar(
                                query = query,
                                onQueryChange = { query = it },
                                modifier = Modifier.weight(1f),
                            )
                            MemoryFilterButton(
                                selected = categoryFilter,
                                onSelect = { categoryFilter = it },
                            )
                        }
                    }

                    is MemoryListItem.DisabledCard -> item(key = "disabled") {
                        MemoryDisabledCard()
                    }

                    is MemoryListItem.EmptyState -> item(key = "empty") {
                        MemoryEmptyState()
                    }

                    is MemoryListItem.ResultCount -> item(key = "result_count") {
                        Text(
                            text = stringResource(R.string.memory_page_search_results, item.count),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        )
                    }

                    is MemoryListItem.NoResult -> item(key = "no_result") {
                        MemoryNoResultBlock()
                    }

                    is MemoryListItem.EmptyCategory -> item(key = "empty_category") {
                        MemoryNoResultBlock(
                            text = stringResource(R.string.memory_page_empty_category),
                        )
                    }

                    is MemoryListItem.Header -> stickyHeader(key = "header_${item.category}") {
                        CategorySectionHeader(
                            category = item.category,
                            count = item.count,
                        )
                    }

                    is MemoryListItem.Entry -> item(key = item.memory.id) {
                        MemoryRow(
                            memory = item.memory,
                            showCategory = item.showCategory,
                            onClick = { editing = item.memory },
                            onDelete = { pendingDelete = item.memory },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    editing?.let { target ->
        MemoryEditorSheet(
            initial = target,
            onDismiss = { editing = null },
            onSave = { updated ->
                if (updated.id == 0) {
                    vm.addMemory(updated)
                } else {
                    vm.updateMemory(updated)
                }
            },
            onDelete = { pendingDelete = it },
        )
    }

    consolidateState?.let { state ->
        ConsolidateSheet(
            state = state,
            onDismiss = { consolidateState = null },
        )
    }

    if (showSettings) {
        MemorySettingsSheet(
            assistant = assistant,
            onUpdate = { vm.update(it) },
            onDismiss = { showSettings = false },
        )
    }

    RikkaConfirmDialog(
        show = pendingDelete != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDelete?.let { vm.deleteMemory(it) }
            pendingDelete = null
        },
        onDismiss = { pendingDelete = null },
        text = {
            Text(
                text = pendingDelete?.content.orEmpty(),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

// ===== 列表项模型 =====

private sealed interface MemoryListItem {
    data object Summary : MemoryListItem
    data object Search : MemoryListItem
    data object DisabledCard : MemoryListItem
    data object EmptyState : MemoryListItem
    data object EmptyCategory : MemoryListItem
    data object NoResult : MemoryListItem
    data class ResultCount(val count: Int) : MemoryListItem
    data class Header(val category: String, val count: Int) : MemoryListItem
    data class Entry(val memory: AssistantMemory, val showCategory: Boolean) : MemoryListItem
}

private sealed interface ConsolidateState {
    data object Running : ConsolidateState
    data class Done(val created: Int, val removed: Int) : ConsolidateState
    data object Failed : ConsolidateState
}

// ===== 概览：总数 + 类别分布条 =====

@Composable
private fun MemorySummaryBlock(
    memories: List<AssistantMemory>,
    onJumpToCategory: (String) -> Unit,
) {
    val segments = remember(memories) {
        MemoryCategory.ALL
            .map { category -> category to memories.count { it.category == category } }
            .filter { it.second > 0 }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.memory_page_summary, memories.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.memory_page_summary_categories, segments.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MemoryDistributionBar(
            segments = segments,
            onSegmentClick = onJumpToCategory,
        )
    }
}

/**
 * 类别分布条：按数量占比分段的彩色条，点击某段跳转到对应分组。
 * 入场的生长动画带 stagger，宽度变化（增删记忆）由弹簧衔接。
 */
@Composable
private fun MemoryDistributionBar(
    segments: List<Pair<String, Int>>,
    onSegmentClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp),
    ) {
        val total = segments.sumOf { it.second }.coerceAtLeast(1)
        val gap = 2.dp
        val availWidth = (maxWidth - gap * (segments.size - 1).coerceAtLeast(0))
            .coerceAtLeast(0.dp)

        Row(
            modifier = Modifier.fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            segments.forEachIndexed { index, (category, count) ->
                var appeared by remember(category) { mutableStateOf(false) }
                LaunchedEffect(category) {
                    delay(index * 45L)
                    appeared = true
                }
                val target = count.toFloat() / total
                val fraction by animateFloatAsState(
                    targetValue = if (appeared) target else 0f,
                    animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
                    label = "distribution_$category",
                )
                if (fraction > 0.002f) {
                    Box(
                        modifier = Modifier
                            .width(availWidth * fraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(memoryCategoryColor(category))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onSegmentClick(category) },
                    )
                }
            }
        }
    }
}

// ===== 搜索栏 =====

@Composable
private fun MemorySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceBright,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Search01,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.memory_page_search),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = stringResource(R.string.memory_page_search_clear),
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onQueryChange("") },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ===== 类别筛选按钮（搜索框右侧）=====

@Composable
private fun MemoryFilterButton(
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val active = selected != null

    Box(modifier = modifier) {
        Surface(
            onClick = { menuOpen = true },
            shape = RoundedCornerShape(14.dp),
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceBright,
            border = if (active) {
                null
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            },
        ) {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = HugeIcons.FilterHorizontal,
                    contentDescription = stringResource(R.string.memory_page_filter),
                    modifier = Modifier.size(18.dp),
                    tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.memory_page_filter_all)) },
                trailingIcon = {
                    if (selected == null) {
                        Icon(
                            imageVector = HugeIcons.Tick02,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
                onClick = {
                    onSelect(null)
                    menuOpen = false
                },
            )
            MemoryCategory.ALL.forEach { category ->
                DropdownMenuItem(
                    text = { Text(memoryCategoryName(category)) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(memoryCategoryColor(category)),
                        )
                    },
                    trailingIcon = {
                        if (selected == category) {
                            Icon(
                                imageVector = HugeIcons.Tick02,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                    onClick = {
                        onSelect(category)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

// ===== 类别组头（sticky） =====

@Composable
private fun CategorySectionHeader(
    category: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CustomColors.topBarColors.containerColor)
            .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = memoryCategoryIcon(category),
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = memoryCategoryTextColor(category),
        )
        Text(
            text = memoryCategoryName(category),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ===== 记忆行：滑动删除包裹 + 卡片 =====

@Composable
private fun MemoryRow(
    memory: AssistantMemory,
    showCategory: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MemorySwipeReveal(
        onDelete = onDelete,
        modifier = modifier,
    ) { isRevealed, close ->
        MemoryCard(
            memory = memory,
            showCategory = showCategory,
            onClick = { if (isRevealed) close() else onClick() },
        )
    }
}

/**
 * 左滑露出删除按钮。
 *
 * 手势细节：水平 slop 后才接管；松手用「位移 + 速度」共同决定展开/收回，
 * 弹簧带释放速度衔接（松手不跳变）；越界拖动用橡皮筋衰减。
 */
@Composable
private fun MemorySwipeReveal(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (isRevealed: Boolean, close: () -> Unit) -> Unit,
) {
    val density = LocalDensity.current
    val revealPx = remember(density) { with(density) { 84.dp.toPx() } }
    var offsetX by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val isRevealed = offsetX < -1f

    val settleTo: (Float, Float) -> Unit = { target, velocity ->
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(
                initialValue = offsetX,
                targetValue = target,
                initialVelocity = velocity,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
            ) { value, _ ->
                offsetX = value
            }
        }
    }
    val close = { settleTo(0f, 0f) }

    Box(modifier = modifier.fillMaxWidth().clipToBounds()) {
        // 底层：删除按钮
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(end = 8.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp, 44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        close()
                        onDelete()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.assistant_page_delete),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        // 上层：卡片本身
        Box(
            modifier = Modifier
                .graphicsLayer { translationX = offsetX }
                .pointerInput(revealPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val slopChange = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                        } ?: return@awaitEachGesture
                        settleJob?.cancel()
                        val tracker = VelocityTracker()
                        tracker.addPosition(slopChange.uptimeMillis, slopChange.position)
                        var change = slopChange
                        while (change.pressed) {
                            val event = awaitPointerEvent()
                            val next = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!next.pressed) {
                                change = next
                                break
                            }
                            val delta = next.position.x - next.previousPosition.x
                            tracker.addPosition(next.uptimeMillis, next.position)
                            if (delta != 0f) {
                                next.consume()
                                val raw = offsetX + delta
                                offsetX = when {
                                    raw > 0f -> rubberband(raw, revealPx)
                                    raw < -revealPx -> -revealPx - rubberband(-raw - revealPx, revealPx)
                                    else -> raw
                                }
                            }
                            change = next
                        }
                        val velocity = tracker.calculateVelocity().x
                        val target = when {
                            velocity < -450f -> -revealPx
                            velocity > 450f -> 0f
                            offsetX < -revealPx * 0.4f -> -revealPx
                            else -> 0f
                        }
                        settleTo(target, velocity)
                    }
                },
        ) {
            content(isRevealed, close)
        }
    }
}

// 橡皮筋衰减：越界越深、跟手越少
private fun rubberband(overshoot: Float, dimension: Float, constant: Float = 0.55f): Float =
    (overshoot * dimension * constant) / (dimension + constant * abs(overshoot))

// ===== 记忆卡片 =====

@Composable
private fun MemoryCard(
    memory: AssistantMemory,
    showCategory: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 1500f),
        label = "memory_card_press",
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (showCategory) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(memoryCategoryColor(memory.category)),
                    )
                    Text(
                        text = memoryCategoryName(memory.category),
                        style = MaterialTheme.typography.labelSmall,
                        color = memoryCategoryTextColor(memory.category),
                    )
                }
            }
            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ImportanceStars(importance = memory.importance)
                Text(
                    text = relativeTimeText(memory.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ===== 星标 =====

@Composable
private fun ImportanceStars(
    importance: Float,
    modifier: Modifier = Modifier,
    starSize: Dp = 12.dp,
    interactive: Boolean = false,
    onRate: ((Float) -> Unit)? = null,
) {
    val filled = (importance.coerceIn(0f, 1f) * 5).roundToInt()
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (interactive) 0.dp else 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(5) { index ->
            val starTint = if (index < filled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
            if (interactive && onRate != null) {
                IconButton(
                    onClick = { onRate((index + 1) / 5f) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Star,
                        contentDescription = null,
                        modifier = Modifier.size(starSize),
                        tint = starTint,
                    )
                }
            } else {
                Icon(
                    imageVector = HugeIcons.Star,
                    contentDescription = null,
                    modifier = Modifier.size(starSize),
                    tint = starTint,
                )
            }
        }
    }
}

// ===== 关闭态 / 空态 / 无结果 =====

@Composable
private fun MemoryDisabledCard(
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Brain02,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.memory_page_off_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.memory_page_off_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MemoryEmptyState(modifier: Modifier = Modifier) {
    val badgeScale = remember { Animatable(0.85f) }
    LaunchedEffect(Unit) {
        badgeScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 44.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .scale(badgeScale.value)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = HugeIcons.Brain02,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.memory_page_empty_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.memory_page_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Text(
            text = stringResource(R.string.memory_page_empty_add_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun MemoryNoResultBlock(
    modifier: Modifier = Modifier,
    text: String = stringResource(R.string.memory_page_no_result),
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Search01,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ===== 编辑 / 新建弹层 =====

@Composable
private fun MemoryEditorSheet(
    initial: AssistantMemory,
    onDismiss: () -> Unit,
    onSave: (AssistantMemory) -> Unit,
    onDelete: (AssistantMemory) -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()
    val isNew = initial.id == 0

    var content by remember(initial.id) { mutableStateOf(initial.content) }
    var category by remember(initial.id) { mutableStateOf(initial.category) }
    var stars by remember(initial.id) {
        mutableStateOf((initial.importance.coerceIn(0f, 1f) * 5).roundToInt().coerceIn(1, 5))
    }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(initial.id) {
        if (isNew) {
            delay(300)
            runCatching { focusRequester.requestFocus() }
        }
    }

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(
                    if (isNew) R.string.memory_page_add_title else R.string.memory_page_edit_title
                ),
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text(stringResource(R.string.memory_page_content_hint)) },
                minLines = 3,
                maxLines = 8,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.memory_page_category),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MemoryCategory.ALL.forEach { item ->
                        FilterChip(
                            selected = category == item,
                            onClick = { category = item },
                            label = { Text(memoryCategoryName(item)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = memoryCategoryIcon(item),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.memory_page_importance),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    ImportanceStars(
                        importance = stars / 5f,
                        starSize = 26.dp,
                        interactive = true,
                        onRate = { stars = (it * 5).roundToInt().coerceIn(1, 5) },
                    )
                }
            }

            if (!isNew) {
                Text(
                    text = stringResource(
                        R.string.memory_page_updated,
                        relativeTimeText(initial.updatedAt),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            content = content.trim(),
                            category = category,
                            importance = stars / 5f,
                        )
                    )
                    close()
                },
                enabled = content.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.assistant_page_save))
            }

            if (!isNew) {
                TextButton(
                    onClick = {
                        close()
                        onDelete(initial)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.assistant_page_delete))
                }
            }
        }
    }
}

// ===== 设置弹层 =====

@Composable
private fun MemorySettingsSheet(
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.memory_page_settings),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            FormItem(
                label = { Text(stringResource(R.string.assistant_page_recent_chats)) },
                description = { Text(stringResource(R.string.assistant_page_recent_chats_desc)) },
                tail = {
                    Switch(
                        checked = assistant.enableRecentChatsReference,
                        onCheckedChange = { onUpdate(assistant.copy(enableRecentChatsReference = it)) },
                    )
                },
            )
        }
    }
}

// ===== 整理弹层 =====

@Composable
private fun ConsolidateSheet(
    state: ConsolidateState,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                (fadeIn(tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = tween(220)))
                    .togetherWith(fadeOut(tween(120)))
            },
            label = "consolidate_state",
        ) { current ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (current) {
                    is ConsolidateState.Running -> {
                        Spacer(Modifier.height(12.dp))
                        CircularWavyProgressIndicator(modifier = Modifier.size(44.dp))
                        Text(
                            text = stringResource(R.string.memory_page_consolidate_running),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.memory_page_consolidate_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    is ConsolidateState.Done -> {
                        ConsolidateBadge(
                            container = MaterialTheme.colorScheme.primaryContainer,
                            content = MaterialTheme.colorScheme.primary,
                            icon = HugeIcons.MagicWand01,
                        )
                        Text(
                            text = stringResource(R.string.memory_page_consolidate_done_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (current.created == 0 && current.removed == 0) {
                            Text(
                                text = stringResource(R.string.memory_page_consolidate_nothing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ConsolidateStatTile(
                                    value = current.created,
                                    label = stringResource(R.string.memory_page_consolidate_created_label),
                                    modifier = Modifier.weight(1f),
                                )
                                ConsolidateStatTile(
                                    value = current.removed,
                                    label = stringResource(R.string.memory_page_consolidate_removed_label),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Button(
                            onClick = { close() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.memory_page_done))
                        }
                    }

                    is ConsolidateState.Failed -> {
                        ConsolidateBadge(
                            container = MaterialTheme.colorScheme.errorContainer,
                            content = MaterialTheme.colorScheme.error,
                            icon = HugeIcons.CancelCircle,
                        )
                        Text(
                            text = stringResource(R.string.memory_page_consolidate_done_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.memory_page_consolidate_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(2.dp))
                        Button(
                            onClick = { close() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.memory_page_done))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsolidateBadge(
    container: Color,
    content: Color,
    icon: ImageVector,
) {
    val badgeScale = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) {
        badgeScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        )
    }
    Box(
        modifier = Modifier
            .size(64.dp)
            .scale(badgeScale.value)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = content,
        )
    }
}

@Composable
private fun ConsolidateStatTile(
    value: Int,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ===== 类别图标 / 颜色 / 名称 =====

private fun memoryCategoryIcon(category: String): ImageVector = when (category) {
    MemoryCategory.PROFILE -> HugeIcons.UserCircle02
    MemoryCategory.PREFERENCE -> HugeIcons.Favourite
    MemoryCategory.CODING -> HugeIcons.SourceCode
    MemoryCategory.ROLEPLAY -> HugeIcons.MaskTheater01
    MemoryCategory.DAILY -> HugeIcons.Sun03
    MemoryCategory.TEMPORARY -> HugeIcons.Hourglass
    else -> HugeIcons.MoreHorizontalCircle01
}

// 分布条 / 色点用的鲜明主色
@Composable
private fun memoryCategoryColor(category: String): Color {
    val colors = MaterialTheme.extendColors
    return when (category) {
        MemoryCategory.PROFILE -> colors.blue5
        MemoryCategory.PREFERENCE -> colors.red5
        MemoryCategory.CODING -> colors.green5
        MemoryCategory.ROLEPLAY -> colors.orange5
        MemoryCategory.DAILY -> colors.gray5
        MemoryCategory.TEMPORARY -> colors.orange3
        else -> colors.gray3
    }
}

// 组头 / 标签文字用的深浅色（亮色模式深、暗色模式亮）
@Composable
private fun memoryCategoryTextColor(category: String): Color {
    val colors = MaterialTheme.extendColors
    return when (category) {
        MemoryCategory.PROFILE -> colors.blue8
        MemoryCategory.PREFERENCE -> colors.red8
        MemoryCategory.CODING -> colors.green8
        MemoryCategory.ROLEPLAY -> colors.orange8
        MemoryCategory.DAILY -> colors.gray8
        MemoryCategory.TEMPORARY -> colors.orange8
        else -> colors.gray8
    }
}

@Composable
private fun memoryCategoryName(category: String): String = stringResource(
    when (category) {
        MemoryCategory.PROFILE -> R.string.memory_page_category_profile
        MemoryCategory.PREFERENCE -> R.string.memory_page_category_preference
        MemoryCategory.CODING -> R.string.memory_page_category_coding
        MemoryCategory.ROLEPLAY -> R.string.memory_page_category_roleplay
        MemoryCategory.DAILY -> R.string.memory_page_category_daily
        MemoryCategory.TEMPORARY -> R.string.memory_page_category_temporary
        else -> R.string.memory_page_category_other
    }
)

// ===== 相对时间 =====

@Composable
private fun relativeTimeText(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    val diff = (System.currentTimeMillis() - epochMillis).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> stringResource(R.string.memory_page_time_just_now)
        diff < 3_600_000L -> stringResource(
            R.string.memory_page_time_minutes_ago,
            (diff / 60_000L).toInt(),
        )

        diff < 86_400_000L -> stringResource(
            R.string.memory_page_time_hours_ago,
            (diff / 3_600_000L).toInt(),
        )

        diff < 7 * 86_400_000L -> stringResource(
            R.string.memory_page_time_days_ago,
            (diff / 86_400_000L).toInt(),
        )

        diff < 30 * 86_400_000L -> stringResource(
            R.string.memory_page_time_weeks_ago,
            (diff / (7 * 86_400_000L)).toInt(),
        )

        else -> Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    }
}
