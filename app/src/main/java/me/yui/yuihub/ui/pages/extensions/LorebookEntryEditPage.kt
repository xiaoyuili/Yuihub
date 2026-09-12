package me.yui.yuihub.ui.pages.extensions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.yui.yuihub.R
import me.yui.yuihub.data.model.InjectionPosition
import me.yui.yuihub.data.model.PromptInjection
import me.yui.yuihub.data.model.SelectiveLogic
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.ui.FormItem
import me.yui.yuihub.ui.components.ui.Select
import me.yui.yuihub.ui.context.LocalNavController
import me.yui.yuihub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

/**
 * 世界书条目编辑页（独立整页，非弹层）。
 *
 * 字段较多，按「基础 / 触发 / 注入 / 高级」分区；用本地草稿编辑、显式保存，
 * 避免每次击键都写 DataStore。
 */
@Composable
fun LorebookEntryEditPage(
    bookId: String,
    entryId: String,
    vm: LorebookVM = koinViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val parsedBookId = remember(bookId) { runCatching { Uuid.parse(bookId) }.getOrNull() }
    val parsedEntryId = remember(entryId) { entryId.takeIf { it.isNotBlank() }?.let { runCatching { Uuid.parse(it) }.getOrNull() } }
    val book = settings.lorebooks.firstOrNull { it.id == parsedBookId }
    val existing = book?.entries?.firstOrNull { it.id == parsedEntryId }

    // 草稿：等设置加载完成后再初始化一次（设置流首值是占位值，未加载时 book 为 null）
    var draft by remember(bookId, entryId) { mutableStateOf<PromptInjection.RegexInjection?>(null) }
    LaunchedEffect(book, existing) {
        if (draft == null && book != null) {
            draft = existing?.copy() ?: PromptInjection.RegexInjection()
        }
    }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    val current = draft
    val canSave = current != null && (current.constantActive || current.keywords.isNotEmpty())

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        if (existing == null) stringResource(R.string.lorebook_entry_new_title)
                        else stringResource(R.string.prompt_page_edit_entry)
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.prompt_page_delete))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            Surface(color = CustomColors.topBarColors.containerColor) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            vm.upsertEntry(parsedBookId ?: return@Button, current ?: return@Button)
                            navController.popBackStack()
                        },
                        enabled = book != null && canSave,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.lorebook_entry_save))
                    }
                }
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (book == null || current == null) return@Scaffold

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- 基础 ----------
            LorebookSectionCard(title = stringResource(R.string.prompt_page_section_basic)) {
                OutlinedTextField(
                    value = current.name,
                    onValueChange = { draft = current.copy(name = it) },
                    label = { Text(stringResource(R.string.prompt_page_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_enabled)) },
                    tail = {
                        Switch(
                            checked = current.enabled,
                            onCheckedChange = { draft = current.copy(enabled = it) },
                        )
                    },
                )
            }

            // ---------- 触发 ----------
            LorebookSectionCard(title = stringResource(R.string.prompt_page_section_trigger)) {
                KeywordEditor(
                    label = stringResource(R.string.prompt_page_keywords_label),
                    keywords = current.keywords,
                    onChange = { draft = current.copy(keywords = it) },
                )
                KeywordEditor(
                    label = stringResource(R.string.prompt_page_secondary_keywords),
                    description = stringResource(R.string.prompt_page_secondary_keywords_desc),
                    keywords = current.secondaryKeywords,
                    onChange = { draft = current.copy(secondaryKeywords = it) },
                )
                AnimatedVisibility(visible = current.secondaryKeywords.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.prompt_page_selective_logic),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Select(
                            options = SelectiveLogic.entries,
                            selectedOption = current.selectiveLogic,
                            onOptionSelected = { draft = current.copy(selectiveLogic = it) },
                            optionToString = { getSelectiveLogicLabel(it) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_constant_active)) },
                    description = { Text(stringResource(R.string.prompt_page_constant_active_desc)) },
                    tail = {
                        Switch(
                            checked = current.constantActive,
                            onCheckedChange = { draft = current.copy(constantActive = it) },
                        )
                    },
                )
                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_use_regex)) },
                    tail = {
                        Switch(
                            checked = current.useRegex,
                            onCheckedChange = { draft = current.copy(useRegex = it) },
                        )
                    },
                )
                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_case_sensitive)) },
                    tail = {
                        Switch(
                            checked = current.caseSensitive,
                            onCheckedChange = { draft = current.copy(caseSensitive = it) },
                        )
                    },
                )
                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_match_whole_words)) },
                    description = { Text(stringResource(R.string.prompt_page_match_whole_words_desc)) },
                    tail = {
                        Switch(
                            checked = current.matchWholeWords,
                            onCheckedChange = { draft = current.copy(matchWholeWords = it) },
                        )
                    },
                )
                NumberField(
                    value = current.scanDepth,
                    onValueChange = { draft = current.copy(scanDepth = it) },
                    label = stringResource(R.string.prompt_page_scan_depth),
                    min = 1,
                )
            }

            // ---------- 注入 ----------
            LorebookSectionCard(title = stringResource(R.string.prompt_page_section_injection)) {
                Text(
                    stringResource(R.string.prompt_page_injection_position),
                    style = MaterialTheme.typography.titleSmall,
                )
                InjectionPositionSelector(
                    position = current.position,
                    onSelect = { draft = current.copy(position = it) },
                )
                AnimatedVisibility(visible = current.position == InjectionPosition.AT_DEPTH) {
                    NumberField(
                        value = current.injectDepth,
                        onValueChange = { draft = current.copy(injectDepth = it) },
                        label = stringResource(R.string.prompt_page_inject_depth),
                        min = 0,
                    )
                }
                AnimatedVisibility(visible = current.position.usesStandaloneMessage()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.prompt_page_injection_role),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        InjectionRoleSelector(
                            role = current.role,
                            onSelect = { draft = current.copy(role = it) },
                        )
                    }
                }
                NumberField(
                    value = current.priority,
                    onValueChange = { draft = current.copy(priority = it) },
                    label = stringResource(R.string.prompt_page_priority_label),
                    min = Int.MIN_VALUE,
                )
                OutlinedTextField(
                    value = current.content,
                    onValueChange = { draft = current.copy(content = it) },
                    label = { Text(stringResource(R.string.prompt_page_injection_content)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                )
            }

            // ---------- 高级 ----------
            LorebookSectionCard(title = stringResource(R.string.prompt_page_section_advanced)) {
                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_section_advanced)) },
                    description = { Text(stringResource(R.string.lorebook_entry_advanced_desc)) },
                    tail = {
                        Switch(checked = showAdvanced, onCheckedChange = { showAdvanced = it })
                    },
                )
                AnimatedVisibility(visible = showAdvanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FormItem(
                            label = { Text(stringResource(R.string.prompt_page_use_probability)) },
                            tail = {
                                Switch(
                                    checked = current.useProbability,
                                    onCheckedChange = { draft = current.copy(useProbability = it) },
                                )
                            },
                        )
                        AnimatedVisibility(visible = current.useProbability) {
                            NumberField(
                                value = current.probability,
                                onValueChange = { draft = current.copy(probability = it) },
                                label = stringResource(R.string.prompt_page_probability),
                                min = 0,
                                max = 100,
                            )
                        }

                        Text(stringResource(R.string.prompt_page_group), style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = current.group,
                            onValueChange = { draft = current.copy(group = it) },
                            label = { Text(stringResource(R.string.prompt_page_group)) },
                            supportingText = { Text(stringResource(R.string.prompt_page_group_desc)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        AnimatedVisibility(visible = current.group.isNotBlank()) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                NumberField(
                                    value = current.groupWeight,
                                    onValueChange = { draft = current.copy(groupWeight = it) },
                                    label = stringResource(R.string.prompt_page_group_weight),
                                    min = 0,
                                )
                                FormItem(
                                    label = { Text(stringResource(R.string.prompt_page_group_override)) },
                                    description = { Text(stringResource(R.string.prompt_page_group_override_desc)) },
                                    tail = {
                                        Switch(
                                            checked = current.groupOverride,
                                            onCheckedChange = { draft = current.copy(groupOverride = it) },
                                        )
                                    },
                                )
                                FormItem(
                                    label = { Text(stringResource(R.string.prompt_page_use_group_scoring)) },
                                    description = { Text(stringResource(R.string.prompt_page_use_group_scoring_desc)) },
                                    tail = {
                                        Switch(
                                            checked = current.useGroupScoring,
                                            onCheckedChange = { draft = current.copy(useGroupScoring = it) },
                                        )
                                    },
                                )
                            }
                        }

                        FormItem(
                            label = { Text(stringResource(R.string.prompt_page_exclude_recursion)) },
                            tail = {
                                Switch(
                                    checked = current.excludeRecursion,
                                    onCheckedChange = { draft = current.copy(excludeRecursion = it) },
                                )
                            },
                        )
                        FormItem(
                            label = { Text(stringResource(R.string.prompt_page_prevent_recursion)) },
                            tail = {
                                Switch(
                                    checked = current.preventRecursion,
                                    onCheckedChange = { draft = current.copy(preventRecursion = it) },
                                )
                            },
                        )
                        FormItem(
                            label = { Text(stringResource(R.string.prompt_page_delay_until_recursion)) },
                            tail = {
                                Switch(
                                    checked = current.delayUntilRecursion,
                                    onCheckedChange = { draft = current.copy(delayUntilRecursion = it) },
                                )
                            },
                        )

                        NumberField(
                            value = current.sticky,
                            onValueChange = { draft = current.copy(sticky = it) },
                            label = stringResource(R.string.prompt_page_sticky),
                            supporting = stringResource(R.string.prompt_page_sticky_desc),
                            min = 0,
                        )
                        NumberField(
                            value = current.cooldown,
                            onValueChange = { draft = current.copy(cooldown = it) },
                            label = stringResource(R.string.prompt_page_cooldown),
                            supporting = stringResource(R.string.prompt_page_cooldown_desc),
                            min = 0,
                        )
                        NumberField(
                            value = current.delay,
                            onValueChange = { draft = current.copy(delay = it) },
                            label = stringResource(R.string.prompt_page_delay),
                            supporting = stringResource(R.string.prompt_page_delay_desc),
                            min = 0,
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog && existing != null && parsedBookId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.lorebook_entry_delete_title)) },
            text = { Text(stringResource(R.string.lorebook_entry_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    vm.deleteEntry(parsedBookId, existing.id)
                    navController.popBackStack()
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

@Composable
private fun NumberField(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    min: Int,
    max: Int = Int.MAX_VALUE,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text ->
            text.toIntOrNull()?.let { onValueChange(it.coerceIn(min, max)) }
        },
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}
