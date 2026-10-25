package me.yui.yuihub.ui.components.message

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.yui.yuihub.R
import me.yui.yuihub.data.model.CompressionSummary
import me.yui.yuihub.ui.components.richtext.MarkdownBlock
import me.yui.yuihub.ui.context.LocalToaster

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualCompressionDialog(
    summary: CompressionSummary,
    onDismiss: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.manual_compress_copied)
    val exportedMessage = stringResource(R.string.manual_compress_exported)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var pendingExport by remember { mutableStateOf(false) }
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri: Uri? ->
        if (uri != null && pendingExport) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(summary.content.toByteArray())
                        }
                    }.onSuccess {
                        withContext(Dispatchers.Main) { toaster.show(exportedMessage) }
                    }
                }
            }
        }
        pendingExport = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.manual_compress_dialog_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = compressionMeta(summary),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                MarkdownBlock(
                    content = summary.content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        pendingExport = true
                        createDocumentLauncher.launch("context-compression.md")
                    },
                ) {
                    Text(stringResource(R.string.manual_compress_export))
                }
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(summary.content))
                        toaster.show(copiedMessage)
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.copy))
                }
            }
        }
    }
}

@Composable
private fun compressionMeta(summary: CompressionSummary): String {
    val parts = buildList {
        if (summary.durationMs > 0) {
            add(
                stringResource(
                    R.string.compression_duration,
                    "%.1f".format(summary.durationMs / 1000.0),
                )
            )
        }
        if (summary.originalTokens > 0 && summary.summaryTokens > 0) {
            add(
                stringResource(
                    R.string.compression_tokens,
                    formatTokens(summary.originalTokens),
                    formatTokens(summary.summaryTokens),
                )
            )
        }
    }
    return parts.joinToString(" · ")
}

private fun formatTokens(tokens: Int): String = when {
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000 -> "%.1fK".format(tokens / 1_000.0)
    else -> tokens.toString()
}
