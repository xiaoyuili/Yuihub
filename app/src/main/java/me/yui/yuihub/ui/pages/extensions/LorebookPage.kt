package me.yui.yuihub.ui.pages.extensions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.FileImport
import me.yui.yuihub.R
import me.yui.yuihub.Screen
import me.yui.yuihub.data.export.LorebookSerializer
import me.yui.yuihub.data.export.rememberImporter
import me.yui.yuihub.data.model.Lorebook
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.ui.SwipeToReveal
import me.yui.yuihub.ui.components.ui.Tag
import me.yui.yuihub.ui.components.ui.TagType
import me.yui.yuihub.ui.context.LocalNavController
import me.yui.yuihub.ui.context.LocalToaster
import me.yui.yuihub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 世界书列表。iOS 式分组卡片列表：拖拽排序、左滑删除、行内启用开关、导入。
 * 点卡片进入详情（条目 / 设置 / 概览 三个分页）。
 */
@Composable
fun LorebookPage(vm: LorebookVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val lazyListState = rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }

    val books = settings.lorebooks
    val currentBooks by rememberUpdatedState(books)
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        vm.reorderBooks(from.index, to.index)
    }
    val importSuccessMsg = stringResource(R.string.export_import_success)
    val importFailedTemplate = stringResource(R.string.export_import_failed)
    val importer = rememberImporter(LorebookSerializer) { result ->
        result.onSuccess { imported ->
            vm.updateBook(imported)
            toaster.show(importSuccessMsg)
        }.onFailure { error ->
            toaster.show(importFailedTemplate.format(error.message.orEmpty()))
        }
    }

    val visibleBooks = remember(currentBooks, query) {
        if (query.isBlank()) currentBooks
        else currentBooks.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.description.contains(query, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.lorebook_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { importer.importFromFile() }) {
                        Icon(
                            imageVector = HugeIcons.FileImport,
                            contentDescription = stringResource(R.string.lorebook_page_import),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val id = vm.addBook()
                navController.navigate(Screen.LorebookDetail(id.toString()))
            }) {
                Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.prompt_page_add_lorebook))
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            state = lazyListState,
        ) {
            // 书少的时候不占地方，多了才出现（出现/消失有展开动画，不是突然蹦出来）
            item(key = "search") {
                AnimatedVisibility(
                    visible = currentBooks.size > 3,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    Column {
                        LorebookSearchField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = stringResource(R.string.lorebook_page_search),
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            if (currentBooks.isEmpty()) {
                item(key = "empty") {
                    LorebookEmptyState(
                        title = stringResource(R.string.lorebook_page_empty),
                        hint = stringResource(R.string.prompt_page_empty_hint),
                    )
                }
            }

            items(visibleBooks, key = { it.id }) { book ->
                ReorderableItem(state = reorderableState, key = book.id) { isDragging ->
                    SwipeToReveal(
                        onDelete = { vm.deleteBook(book.id) },
                        deleteLabel = stringResource(R.string.prompt_page_delete),
                        modifier = Modifier.graphicsLayer {
                            if (isDragging) {
                                scaleX = 1.02f
                                scaleY = 1.02f
                            }
                        },
                    ) {
                        LorebookCard(
                            book = book,
                            modifier = Modifier.longPressDraggableHandle(),
                            onOpen = { navController.navigate(Screen.LorebookDetail(book.id.toString())) },
                            onToggleEnabled = { vm.updateBook(book.copy(enabled = it)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LorebookCard(
    book: Lorebook,
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
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.Book01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = book.name.ifBlank { stringResource(R.string.prompt_page_unnamed_lorebook) },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.description.isNotBlank()) {
                    Text(
                        text = book.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Tag(type = TagType.INFO) {
                        Text(stringResource(R.string.prompt_page_entries_count_format, book.entries.size))
                    }
                    if (book.recursiveScanning) {
                        Tag(type = TagType.DEFAULT) {
                            Text(stringResource(R.string.lorebook_page_recursive_scanning))
                        }
                    }
                    if (book.tokenBudget > 0) {
                        Tag(type = TagType.DEFAULT) { Text("${book.tokenBudget}") }
                    }
                }
            }
            Switch(
                checked = book.enabled,
                onCheckedChange = onToggleEnabled,
            )
        }
    }
}

/** 列表与条目页共用的空态 */
@Composable
internal fun LorebookEmptyState(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}
