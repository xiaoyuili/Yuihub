package me.yui.yuihub.ui.pages.assistant.detail

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.yui.yuihub.R
import me.yui.yuihub.data.model.Assistant
import me.yui.yuihub.data.model.EvolutionLesson
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.ui.CardGroup
import me.yui.yuihub.ui.components.ui.RikkaConfirmDialog
import me.yui.yuihub.ui.hooks.EditStateContent
import me.yui.yuihub.ui.hooks.useEditState
import me.yui.yuihub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun AssistantEvolutionPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = { parametersOf(id) }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val lessons by vm.lessons.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { EvolutionLesson.KINDS.size })
    var consolidating by remember { mutableStateOf(false) }
    var consolidateSummary by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_tab_evolution)) },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(
                        onClick = {
                            if (!consolidating) {
                                consolidating = true
                                vm.consolidateLessons { created, removed ->
                                    consolidateSummary = "$created / $removed"
                                    consolidating = false
                                }
                            }
                        },
                        enabled = assistant.enableEvolution && !consolidating,
                    ) {
                        if (consolidating) {
                            Text(stringResource(R.string.assistant_evolution_consolidating))
                        } else {
                            Text(stringResource(R.string.assistant_evolution_consolidate))
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            CardGroup(
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_evolution_enable)) },
                    supportingContent = {
                        Text(stringResource(R.string.assistant_evolution_enable_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = assistant.enableEvolution,
                            onCheckedChange = {
                                vm.update(assistant.copy(enableEvolution = it))
                            },
                        )
                    },
                )
            }

            SecondaryTabRow(
                selectedTabIndex = pagerState.currentPage,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                EvolutionLesson.KINDS.forEachIndexed { index, kind ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = {
                            Text(
                                when (kind) {
                                    EvolutionLesson.KIND_CODING -> stringResource(R.string.assistant_evolution_kind_coding)
                                    EvolutionLesson.KIND_ROLEPLAY -> stringResource(R.string.assistant_evolution_kind_roleplay)
                                    else -> stringResource(R.string.assistant_evolution_kind_chat)
                                }
                            )
                        },
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                val kind = EvolutionLesson.KINDS[page]
                val kindLessons = lessons.filter { it.kind == kind }
                EvolutionLessonList(
                    lessons = kindLessons,
                    enabled = assistant.enableEvolution,
                    onAdd = { vm.addLesson(kind, it.first, it.second) },
                    onEdit = { vm.updateLesson(it) },
                    onDelete = { vm.deleteLesson(it) },
                )
            }
        }
    }

    consolidateSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = { consolidateSummary = null },
            title = { Text(stringResource(R.string.assistant_evolution_consolidate_done_title)) },
            text = {
                val created = summary.substringBefore('/').trim()
                val removed = summary.substringAfter('/').trim()
                Text(stringResource(R.string.assistant_evolution_consolidate_done, created, removed))
            },
            confirmButton = {
                TextButton(onClick = { consolidateSummary = null }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }
}

@Composable
private fun EvolutionLessonList(
    lessons: List<EvolutionLesson>,
    enabled: Boolean,
    onAdd: (Pair<String, String>) -> Unit,
    onEdit: (EvolutionLesson) -> Unit,
    onDelete: (EvolutionLesson) -> Unit,
) {
    val dialogState = useEditState<EvolutionLesson> { lesson ->
        if (lesson.id == 0) {
            onAdd(lesson.title to lesson.content)
        } else {
            onEdit(lesson)
        }
    }
    var pendingDelete by remember { mutableStateOf<EvolutionLesson?>(null) }

    dialogState.EditStateContent { lesson, update ->
        AlertDialog(
            onDismissRequest = { dialogState.dismiss() },
            title = { Text(stringResource(R.string.assistant_evolution_edit_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = lesson.title,
                        onValueChange = { update(lesson.copy(title = it)) },
                        label = { Text(stringResource(R.string.assistant_evolution_title_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = lesson.content,
                        onValueChange = { update(lesson.copy(content = it)) },
                        label = { Text(stringResource(R.string.assistant_evolution_content_label)) },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { dialogState.confirm() },
                    enabled = lesson.title.isNotBlank() && lesson.content.isNotBlank(),
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogState.dismiss() }) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!enabled) {
            item {
                Text(
                    text = stringResource(R.string.assistant_evolution_disabled_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.assistant_evolution_list_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = lessons.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = {
                        dialogState.open(EvolutionLesson(kind = lessons.firstOrNull()?.kind ?: "chat"))
                    },
                ) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                }
            }
        }

        if (lessons.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.assistant_evolution_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        lessons.fastForEach { lesson ->
            item(key = lesson.id) {
                LessonItem(
                    lesson = lesson,
                    onEdit = { dialogState.open(it) },
                    onDelete = { pendingDelete = it },
                )
            }
        }
    }

    RikkaConfirmDialog(
        show = pendingDelete != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDelete?.let(onDelete)
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

@Composable
private fun LessonItem(
    lesson: EvolutionLesson,
    onEdit: (EvolutionLesson) -> Unit,
    onDelete: (EvolutionLesson) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = lesson.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = lesson.content,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = formatLessonTime(lesson.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onEdit(lesson) }) {
                Icon(HugeIcons.PencilEdit01, contentDescription = null)
            }
            IconButton(onClick = { onDelete(lesson) }) {
                Icon(HugeIcons.Delete01, contentDescription = null)
            }
        }
    }
}

private fun formatLessonTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}