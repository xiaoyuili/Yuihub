package me.yui.yuihub.ui.pages.backup.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.yui.yuihub.R
import me.yui.yuihub.data.datastore.BackupReminderConfig
import me.yui.yuihub.ui.components.ui.CardGroup
import me.yui.yuihub.ui.components.ui.StickyHeader
import me.yui.yuihub.ui.pages.backup.BackupVM
import me.yui.yuihub.utils.toLocalDateTime
import java.time.Instant

@Composable
fun ReminderTab(vm: BackupVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.backupReminderConfig

    fun updateConfig(update: BackupReminderConfig) {
        vm.updateSettings(settings.copy(backupReminderConfig = update))
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StickyHeader {
            Text(stringResource(R.string.backup_page_reminder))
        }

        CardGroup(
            modifier = Modifier.fillMaxWidth(),
        ) {
            item(
                trailingContent = {
                    Switch(
                        checked = config.enabled,
                        onCheckedChange = { updateConfig(config.copy(enabled = it)) },
                    )
                },
                headlineContent = { Text(stringResource(R.string.backup_page_reminder_enable)) },
            )

            if (config.enabled) {
                item(
                    headlineContent = { Text(stringResource(R.string.backup_page_reminder_interval)) },
                    supportingContent = {
                        val intervals = listOf(1, 3, 7, 14, 30)
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            intervals.forEachIndexed { index, days ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = intervals.size,
                                    ),
                                    onClick = { updateConfig(config.copy(intervalDays = days)) },
                                    selected = config.intervalDays == days,
                                ) {
                                    Text(stringResource(R.string.backup_page_reminder_interval_days, days))
                                }
                            }
                        }
                    },
                )

                item(
                    headlineContent = {
                        Text(
                            if (config.lastBackupTime == 0L) {
                                stringResource(R.string.backup_page_reminder_no_record)
                            } else {
                                stringResource(
                                    R.string.backup_page_reminder_last_time,
                                    Instant.ofEpochMilli(config.lastBackupTime).toLocalDateTime()
                                )
                            }
                        )
                    },
                )
            }
        }
    }
}
