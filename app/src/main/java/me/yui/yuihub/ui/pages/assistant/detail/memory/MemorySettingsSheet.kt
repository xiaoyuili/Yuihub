package me.yui.yuihub.ui.pages.assistant.detail.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Model
import me.yui.yuihub.R
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.data.model.Assistant
import me.yui.yuihub.ui.components.ai.ModelSelector

@Composable
fun MemorySettingsSheet(
    assistant: Assistant,
    settings: Settings,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateSettings: (Settings) -> Unit,
    onExport: () -> Unit,
    onClearArchived: () -> Unit,
    onClearAll: () -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.memory_page_settings),
                style = MaterialTheme.typography.titleLarge,
            )
            SettingSwitchRow(
                title = stringResource(R.string.memory_page_enable),
                description = stringResource(R.string.memory_page_enable_desc),
                checked = assistant.enableMemory,
                onCheckedChange = { onUpdateAssistant(assistant.copy(enableMemory = it)) },
            )
            SettingSwitchRow(
                title = stringResource(R.string.memory_page_auto_extract),
                description = stringResource(R.string.memory_page_auto_extract_desc),
                checked = assistant.memoryAutoExtract,
                onCheckedChange = { onUpdateAssistant(assistant.copy(memoryAutoExtract = it)) },
            )
            SettingSwitchRow(
                title = stringResource(R.string.memory_page_auto_organize),
                description = stringResource(R.string.memory_page_auto_organize_desc),
                checked = assistant.memoryAutoOrganize,
                onCheckedChange = { onUpdateAssistant(assistant.copy(memoryAutoOrganize = it)) },
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.memory_page_maintenance_model),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.memory_page_maintenance_model_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ModelSelector(
                    modelId = settings.memoryMaintenanceModelId,
                    providers = settings.providers,
                    type = ModelType.CHAT,
                    allowClear = true,
                    onSelect = { model: Model ->
                        onUpdateSettings(
                            settings.copy(
                                memoryMaintenanceModelId = model.id.takeIf { model.modelId.isNotBlank() },
                            )
                        )
                    },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            SettingChoices(
                title = stringResource(R.string.memory_page_hit_top_k),
                options = listOf(0 to "0", 1 to "1", 2 to "2", 3 to "3"),
                selected = settings.memoryHitTopK,
                onSelect = { onUpdateSettings(settings.copy(memoryHitTopK = it)) },
            )
            SettingChoices(
                title = stringResource(R.string.memory_page_core_budget),
                options = listOf(800, 1200, 1600, 2000).map { it to it.toString() },
                selected = settings.memoryCoreCharBudget,
                onSelect = { onUpdateSettings(settings.copy(memoryCoreCharBudget = it)) },
            )
            SettingChoices(
                title = stringResource(R.string.memory_page_hit_budget),
                options = listOf(320, 440, 600).map { it to it.toString() },
                selected = settings.memoryHitCharBudget,
                onSelect = { onUpdateSettings(settings.copy(memoryHitCharBudget = it)) },
            )
            SettingChoices(
                title = stringResource(R.string.memory_page_organize_growth),
                options = listOf(10, 20, 30, 50).map { it to it.toString() },
                selected = settings.memoryOrganizeGrowth,
                onSelect = { onUpdateSettings(settings.copy(memoryOrganizeGrowth = it)) },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onExport, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.memory_page_export))
                }
                TextButton(onClick = onClearArchived) {
                    Text(stringResource(R.string.memory_page_clear_archived))
                }
            }
            TextButton(onClick = onClearAll, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.memory_page_clear_all),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingChoices(
    title: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(label) },
                )
            }
        }
    }
}
