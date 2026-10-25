package me.yui.yuihub.ui.pages.assistant.detail.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.yui.yuihub.R
import me.yui.yuihub.data.ai.memory.MemoryText
import me.yui.yuihub.data.model.AssistantMemory
import me.yui.yuihub.data.model.MemoryCategory
import me.yui.yuihub.data.model.MemoryKind
import me.yui.yuihub.data.model.MemoryScope
import me.yui.yuihub.data.model.MemorySection
import me.yui.yuihub.data.model.MemoryStatus

@Composable
fun MemoryEditorSheet(
    initial: AssistantMemory,
    workspaceAvailable: Boolean,
    onDismiss: () -> Unit,
    onSave: (AssistantMemory) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isNew = initial.id == 0
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var content by remember(initial.id) { mutableStateOf(initial.content) }
    var kind by remember(initial.id) { mutableStateOf(MemoryKind.normalize(initial.kind)) }
    var section by remember(initial.id) { mutableStateOf(MemorySection.normalize(initial.section)) }
    var category by remember(initial.id) { mutableStateOf(MemoryCategory.normalize(initial.category)) }
    var weight by remember(initial.id) { mutableStateOf(initial.weight.coerceIn(1, 4)) }
    var keywords by remember(initial.id) { mutableStateOf(initial.keywords.joinToString(", ")) }
    var scope by remember(initial.id) { mutableStateOf(MemoryScope.normalize(initial.scope)) }
    var status by remember(initial.id) { mutableStateOf(MemoryStatus.normalize(initial.status)) }

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
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(if (isNew) R.string.memory_page_add_title else R.string.memory_page_edit_title),
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.memory_page_title_label)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.memory_page_content_hint)) },
                minLines = 3,
                maxLines = 8,
            )

            FormLabel(stringResource(R.string.memory_page_kind))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MemoryKind.ALL.forEach { item ->
                    FilterChip(
                        selected = kind == item,
                        onClick = { kind = item },
                        label = { Text(memoryKindLabel(item)) },
                    )
                }
            }

            if (kind == MemoryKind.CORE) {
                FormLabel(stringResource(R.string.memory_page_section))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MemorySection.ALL.forEach { item ->
                        FilterChip(
                            selected = section == item,
                            onClick = { section = item },
                            label = { Text(memorySectionLabel(item)) },
                        )
                    }
                }
            }

            FormLabel(stringResource(R.string.memory_page_category))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MemoryCategory.ALL.forEach { item ->
                    FilterChip(
                        selected = category == item,
                        onClick = { category = item },
                        label = { Text(memoryCategoryName(item)) },
                    )
                }
            }

            FormLabel(stringResource(R.string.memory_page_importance))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..4).forEach { value ->
                    FilterChip(
                        selected = weight == value,
                        onClick = { weight = value },
                        label = { Text(value.toString()) },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.memory_page_keywords)) },
                    placeholder = { Text(stringResource(R.string.memory_page_keywords_hint)) },
                    minLines = 1,
                )
                TextButton(
                    onClick = {
                        keywords = MemoryText.extractKeywords(title + " " + content)
                            .joinToString(", ")
                    }
                ) {
                    Text(stringResource(R.string.memory_page_keywords_auto))
                }
            }

            if (workspaceAvailable) {
                FormLabel(stringResource(R.string.memory_page_scope))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = scope == MemoryScope.ASSISTANT,
                        onClick = { scope = MemoryScope.ASSISTANT },
                        label = { Text(stringResource(R.string.memory_page_scope_assistant)) },
                    )
                    FilterChip(
                        selected = scope == MemoryScope.WORKSPACE,
                        onClick = { scope = MemoryScope.WORKSPACE },
                        label = { Text(stringResource(R.string.memory_page_scope_workspace)) },
                    )
                }
            }

            FormLabel(stringResource(R.string.memory_page_status))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MemoryStatus.ALL.forEach { item ->
                    FilterChip(
                        selected = status == item,
                        onClick = { status = item },
                        label = { Text(memoryStatusLabel(item)) },
                    )
                }
            }

            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            title = title.trim(),
                            content = content.trim(),
                            kind = kind,
                            section = if (kind == MemoryKind.CORE) section else "",
                            category = category,
                            weight = weight,
                            keywords = keywords.split(",", "，")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .distinct(),
                            scope = scope,
                            status = status,
                        )
                    )
                    onDismiss()
                },
                enabled = content.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.assistant_page_save))
            }
        }
    }
}

@Composable
internal fun FormLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
