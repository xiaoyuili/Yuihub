package me.yui.yuihub.ui.pages.assistant.detail.memory

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.CancelCircle
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.SourceCode
import me.yui.yuihub.R
import me.yui.yuihub.data.ai.memory.MemoryConsolidator
import me.yui.yuihub.data.model.MemoryEvent
import me.yui.yuihub.data.model.MemoryEventType

@Composable
fun MemoryOrganizeSheet(
    state: OrganizeUiState,
    events: List<MemoryEvent>,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.memory_page_organize_title),
                style = MaterialTheme.typography.titleLarge,
            )
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    (fadeIn() + scaleIn(initialScale = 0.98f)).togetherWith(fadeOut())
                },
                label = "organizeState",
            ) { current ->
                when (current) {
                    OrganizeUiState.Idle -> OrganizeIdle(onStart)
                    is OrganizeUiState.Running -> OrganizeRunning(current)
                    is OrganizeUiState.Done -> OrganizeDone(current)
                    OrganizeUiState.Failed -> OrganizeFailed(onStart)
                }
            }

            if (events.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.memory_page_activity),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                events.take(12).forEach { event -> MemoryEventRow(event) }
            }
        }
    }
}

@Composable
private fun OrganizeIdle(onStart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.memory_page_organize_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Icon(HugeIcons.MagicWand01, null, modifier = Modifier.size(17.dp))
            Text(
                text = stringResource(R.string.memory_page_organize_now),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun OrganizeRunning(progress: OrganizeUiState.Running) {
    val stageLabel = when (progress.stage) {
        MemoryConsolidator.OrganizeProgress.Stage.MERGE -> stringResource(R.string.memory_page_stage_merge)
        MemoryConsolidator.OrganizeProgress.Stage.ARCHIVE -> stringResource(R.string.memory_page_stage_archive)
        MemoryConsolidator.OrganizeProgress.Stage.CORE -> stringResource(R.string.memory_page_stage_core)
        MemoryConsolidator.OrganizeProgress.Stage.DONE -> stringResource(R.string.memory_page_stage_done)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        Column {
            Text(text = stageLabel, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.memory_page_organize_running),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OrganizeDone(result: OrganizeUiState.Done) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(HugeIcons.CheckmarkCircle02, null, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(R.string.memory_page_organize_done),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                text = stringResource(
                    R.string.memory_page_organize_result,
                    result.result.created,
                    result.result.archived,
                    result.result.coreRewritten,
                    result.result.before,
                    result.result.after,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun OrganizeFailed(onRetry: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(HugeIcons.CancelCircle, null, modifier = Modifier.size(18.dp))
                Text(text = stringResource(R.string.memory_page_organize_failed), style = MaterialTheme.typography.titleSmall)
            }
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.memory_page_retry))
            }
        }
    }
}

@Composable
private fun MemoryEventRow(event: MemoryEvent) {
    val icon = when (event.type) {
        MemoryEventType.ORGANIZE -> HugeIcons.MagicWand01
        MemoryEventType.EXTRACT -> HugeIcons.Brain02
        else -> HugeIcons.SourceCode
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, modifier = Modifier.size(15.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.summary,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = relativeTime(event.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
