package me.yui.yuihub.ui.components.ai

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import me.yui.yuihub.R
import me.yui.yuihub.data.ai.RequestBreakdown
import me.yui.yuihub.ui.context.LocalToaster
import me.yui.yuihub.utils.writeClipboardText
import kotlin.math.max

@Composable
internal fun RequestBreakdownSheet(
    usage: ContextUsage,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val breakdown = usage.breakdown
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
                text = stringResource(R.string.chat_request_breakdown_title),
                style = MaterialTheme.typography.titleLarge,
            )
            if (breakdown == null) {
                Text(
                    text = stringResource(R.string.chat_request_breakdown_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Text(
                text = stringResource(
                    R.string.chat_request_breakdown_summary,
                    formatCompactTokens(breakdown.totalEstimatedTokens.toLong()),
                    breakdown.messageCount,
                    breakdown.toolCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            BreakdownStackBar(breakdown)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            val total = max(1, breakdown.totalEstimatedTokens)
            BreakdownRow(
                label = stringResource(R.string.chat_request_breakdown_system),
                tokens = breakdown.systemPromptTokens,
                total = total,
                color = MaterialTheme.colorScheme.primary,
            )
            BreakdownRow(
                label = stringResource(R.string.chat_request_breakdown_tools),
                tokens = breakdown.toolSchemaTokens,
                total = total,
                color = MaterialTheme.colorScheme.tertiary,
            )
            BreakdownRow(
                label = stringResource(R.string.chat_request_breakdown_memory),
                tokens = breakdown.memoryTokens,
                total = total,
                color = MaterialTheme.colorScheme.secondary,
            )
            if (breakdown.memoryTokens > 0) {
                Column(
                    modifier = Modifier.padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MiniBreakdownRow(
                        stringResource(R.string.chat_request_breakdown_memory_core),
                        breakdown.memoryCoreTokens,
                        total,
                    )
                    MiniBreakdownRow(
                        stringResource(R.string.chat_request_breakdown_memory_hit),
                        breakdown.memoryHitTokens,
                        total,
                    )
                    MiniBreakdownRow(
                        stringResource(R.string.chat_request_breakdown_memory_rebase),
                        breakdown.memoryRebaseTokens,
                        total,
                    )
                }
            }
            BreakdownRow(
                label = stringResource(R.string.chat_request_breakdown_history),
                tokens = breakdown.historyTokens,
                total = total,
                color = MaterialTheme.colorScheme.outline,
            )

            if (breakdown.topTools.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(
                    text = stringResource(R.string.chat_request_breakdown_top_tools),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                val maxTool = breakdown.topTools.maxOf { it.tokens }.coerceAtLeast(1)
                breakdown.topTools.forEach { tool ->
                    ToolBreakdownRow(tool, maxTool)
                }
            }

            if (usage.lastPromptTokens > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(
                    text = stringResource(
                        R.string.chat_request_breakdown_actual,
                        formatCompactTokens(usage.lastPromptTokens.toLong()),
                        formatCompactTokens(usage.lastCachedTokens.toLong()),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = stringResource(R.string.chat_request_breakdown_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val context = LocalContext.current
            val resources = LocalResources.current
            val toaster = LocalToaster.current
            TextButton(
                onClick = {
                    val text = buildBreakdownText(breakdown, usage)
                    context.writeClipboardText(text)
                    toaster.show(resources.getString(R.string.chat_request_breakdown_copied), type = ToastType.Success)
                },
            ) {
                Text(stringResource(R.string.chat_request_breakdown_copy))
            }
        }
    }
}

@Composable
private fun BreakdownStackBar(breakdown: RequestBreakdown) {
    val segments = listOf(
        breakdown.systemPromptTokens to MaterialTheme.colorScheme.primary,
        breakdown.toolSchemaTokens to MaterialTheme.colorScheme.tertiary,
        breakdown.memoryTokens to MaterialTheme.colorScheme.secondary,
        breakdown.historyTokens to MaterialTheme.colorScheme.outline,
    ).filter { it.first > 0 }
    if (segments.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape),
    ) {
        segments.forEach { (tokens, color) ->
            val fraction by animateFloatAsState(
                targetValue = tokens.toFloat() / breakdown.totalEstimatedTokens.coerceAtLeast(1),
                animationSpec = tween(450),
                label = "breakdownSegment",
            )
            Box(
                modifier = Modifier
                    .weight(fraction.coerceAtLeast(0.0001f))
                    .fillMaxHeight()
                    .background(color),
            )
        }
    }
}

@Composable
private fun BreakdownRow(
    label: String,
    tokens: Int,
    total: Int,
    color: Color,
) {
    val fraction by animateFloatAsState(
        targetValue = tokens.toFloat() / total,
        animationSpec = tween(450),
        label = "breakdownRow",
    )
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${formatCompactTokens(tokens.toLong())} · ${(fraction * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}

@Composable
private fun MiniBreakdownRow(label: String, tokens: Int, total: Int) {
    if (tokens <= 0) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatCompactTokens(tokens.toLong()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToolBreakdownRow(tool: RequestBreakdown.ToolUsage, maxTokens: Int) {
    val fraction by animateFloatAsState(
        targetValue = tool.tokens.toFloat() / maxTokens,
        animationSpec = tween(450),
        label = "toolBreakdown",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = tool.name,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
        )
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.tertiary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        Text(
            text = formatCompactTokens(tool.tokens.toLong()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun buildBreakdownText(breakdown: RequestBreakdown, usage: ContextUsage): String = buildString {
    appendLine("Request breakdown (estimated)")
    appendLine("total=${breakdown.totalEstimatedTokens} messages=${breakdown.messageCount} tools=${breakdown.toolCount}")
    appendLine("system=${breakdown.systemPromptTokens}")
    appendLine("tools=${breakdown.toolSchemaTokens}")
    appendLine("memory=${breakdown.memoryTokens} (core=${breakdown.memoryCoreTokens}, hit=${breakdown.memoryHitTokens}, rebase=${breakdown.memoryRebaseTokens})")
    appendLine("history=${breakdown.historyTokens}")
    if (usage.lastPromptTokens > 0) {
        appendLine("actual prompt=${usage.lastPromptTokens} cached=${usage.lastCachedTokens}")
    }
    breakdown.topTools.forEach { tool ->
        appendLine("tool ${tool.name}=${tool.tokens}")
    }
}
