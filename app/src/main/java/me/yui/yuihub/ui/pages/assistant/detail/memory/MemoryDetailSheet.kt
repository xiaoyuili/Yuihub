package me.yui.yuihub.ui.pages.assistant.detail.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.yui.yuihub.R
import me.yui.yuihub.data.model.AssistantMemory
import me.yui.yuihub.data.model.MemoryKind
import me.yui.yuihub.data.model.MemorySection
import me.yui.yuihub.data.model.MemoryStatus

@Composable
fun MemoryDetailSheet(
    memory: AssistantMemory,
    sourceConversationId: String?,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onPromote: (String) -> Unit,
    onDemote: () -> Unit,
    onOpenSource: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KindBadge(memory.kind, memory.section)
                if (memory.status != MemoryStatus.ACTIVE) {
                    Text(
                        text = memoryStatusLabel(memory.status),
                        style = MaterialTheme.typography.labelMedium,
                        color = memoryStatusColor(memory.status),
                    )
                }
            }
            Text(
                text = memory.displayTitle,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (memory.keywords.isNotEmpty()) {
                FormLabel(stringResource(R.string.memory_page_keywords))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    memory.keywords.forEach { keyword ->
                        Surface(
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Text(
                                text = "#$keyword",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MetaColumn(stringResource(R.string.memory_page_category), memoryCategoryName(memory.category))
                MetaColumn(stringResource(R.string.memory_page_importance), memory.weight.toString())
                MetaColumn(stringResource(R.string.memory_page_hits), memory.hitCount.toString())
                MetaColumn(
                    stringResource(R.string.memory_page_updated_label),
                    relativeTime(memory.updatedAt),
                )
            }

            if (sourceConversationId != null) {
                TextButton(onClick = { onOpenSource(sourceConversationId) }) {
                    Text(stringResource(R.string.memory_page_source_conversation))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) {
                    Text(stringResource(R.string.memory_page_edit))
                }
                if (memory.kind == MemoryKind.CORE) {
                    TextButton(onClick = onDemote) {
                        Text(stringResource(R.string.memory_page_demote))
                    }
                } else {
                    TextButton(onClick = { onPromote(MemorySection.USER) }) {
                        Text(stringResource(R.string.memory_page_promote_core))
                    }
                }
                if (memory.status == MemoryStatus.ARCHIVED) {
                    TextButton(onClick = onRestore) {
                        Text(stringResource(R.string.memory_page_restore))
                    }
                } else {
                    TextButton(onClick = onArchive) {
                        Text(stringResource(R.string.memory_page_archive))
                    }
                }
                TextButton(onClick = onDelete) {
                    Text(
                        text = stringResource(R.string.memory_page_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun KindBadge(kind: String, section: String) {
    Surface(
        shape = androidx.compose.foundation.shape.CircleShape,
        color = memoryKindColor(kind).copy(alpha = 0.14f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = memoryKindLabel(kind),
                style = MaterialTheme.typography.labelMedium,
                color = memoryKindTextColor(kind),
            )
            if (kind == MemoryKind.CORE) {
                Text(
                    text = "· ${memorySectionLabel(section)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = memoryKindTextColor(kind).copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun MetaColumn(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun relativeTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "-"
    val diff = (System.currentTimeMillis() - epochMillis).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> stringResource(R.string.memory_page_time_just_now)
        diff < 3_600_000L -> stringResource(R.string.memory_page_time_minutes_ago, (diff / 60_000L).toInt())
        diff < 86_400_000L -> stringResource(R.string.memory_page_time_hours_ago, (diff / 3_600_000L).toInt())
        diff < 7 * 86_400_000L -> stringResource(R.string.memory_page_time_days_ago, (diff / 86_400_000L).toInt())
        else -> stringResource(R.string.memory_page_time_weeks_ago, (diff / (7 * 86_400_000L)).toInt())
    }
}
