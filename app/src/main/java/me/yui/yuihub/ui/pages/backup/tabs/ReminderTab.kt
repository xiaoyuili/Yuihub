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

        StickyHeader {
            Text(stringResource(R.string.backup_page_auto_backup))
        }

        CardGroup(
            modifier = Modifier.fillMaxWidth(),
        ) {
            item(
                trailingContent = {
                    Switch(
                        checked = config.autoBackupEnabled,
                        onCheckedChange = { enabled ->
                            // 关闭时同时取消已排队的任务，避免关掉后还跑一次
                            updateConfig(
                                config.copy(
                                    autoBackupEnabled = enabled,
                                    // 重新开启时重置计时，从现在算下一个间隔
                                    autoBackupLastTime = if (enabled) 0L else config.autoBackupLastTime,
                                )
                            )
                            vm.onAutoBackupConfigChanged()
                        },
                    )
                },
                headlineContent = { Text(stringResource(R.string.backup_page_auto_backup_enable)) },
                supportingContent = { Text(stringResource(R.string.backup_page_auto_backup_desc)) },
            )

            if (config.autoBackupEnabled) {
                item(
                    headlineContent = { Text(stringResource(R.string.backup_page_auto_backup_interval)) },
                    supportingContent = {
                        val intervals = listOf(1, 3, 7)
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            intervals.forEachIndexed { index, days ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = intervals.size,
                                    ),
                                    onClick = {
                                        updateConfig(config.copy(autoBackupIntervalDays = days))
                                        vm.onAutoBackupConfigChanged()
                                    },
                                    selected = config.autoBackupIntervalDays == days,
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
                            if (config.autoBackupLastTime == 0L) {
                                stringResource(R.string.backup_page_auto_backup_never)
                            } else {
                                stringResource(
                                    R.string.backup_page_auto_backup_last_time,
                                    Instant.ofEpochMilli(config.autoBackupLastTime).toLocalDateTime()
                                )
                            }
                        )
                    },
                    supportingContent = {
                        Text(stringResource(R.string.backup_page_auto_backup_retain_note))
                    },
                )
            }

            item(
                headlineContent = { Text(stringResource(R.string.backup_page_auto_backup_scope)) },
                supportingContent = {
                    Text(stringResource(R.string.backup_page_auto_backup_scope_note))
                },
            )
        }
    }
}
