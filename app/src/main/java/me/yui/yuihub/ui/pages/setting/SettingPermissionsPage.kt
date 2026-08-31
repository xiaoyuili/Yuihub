package me.yui.yuihub.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.first
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.Zap
import me.yui.yuihub.R
import me.yui.yuihub.data.datastore.SettingsStore
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.ui.CardGroup
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.utils.SystemPermissions
import me.yui.yuihub.utils.plus
import org.koin.compose.koinInject

/**
 * 系统权限总览：通知 / 后台运行 / 存储。状态每次回到前台自动重测，
 * 用户从系统设置返回即可看到最新结果，无需手动刷新。
 */
@Composable
fun SettingPermissionsPage() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settingsStore: SettingsStore = koinInject()

    var refreshKey by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationEnabled = remember(refreshKey) {
        SystemPermissions.isNotificationEnabled(context)
    }
    val batteryExempt = remember(refreshKey) {
        SystemPermissions.isIgnoringBatteryOptimizations(context)
    }
    val storageEnabled = remember(refreshKey) {
        SystemPermissions.hasAllFilesAccess()
    }
    // 与另两项保持同构：不设单独开关，豁免电池优化后即视为开启保活
    LaunchedEffect(batteryExempt) {
        if (batteryExempt != settingsStore.settingsFlowRaw.first().keepAwakeEnabled) {
            settingsStore.update { it.copy(keepAwakeEnabled = batteryExempt) }
        }
    }
    // 保活需同时满足：已豁免电池优化 + 通知可用（前台服务要有可见通知）
    val backgroundEnabled = batteryExempt && notificationEnabled

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_permissions_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_permissions_title)) },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.Notification01, null) },
                        headlineContent = {
                            Text(stringResource(R.string.setting_permissions_notification))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_permissions_notification_desc))
                        },
                        trailingContent = {
                            PermissionBadge(enabled = notificationEnabled)
                        },
                        onClick = {
                            SystemPermissions.openSettings(
                                context,
                                SystemPermissions.notificationSettingsIntent(context),
                            )
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.Zap, null) },
                        headlineContent = {
                            Text(stringResource(R.string.setting_permissions_background))
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.setting_permissions_background_desc),
                                maxLines = 2,
                            )
                        },
                        trailingContent = {
                            PermissionBadge(enabled = backgroundEnabled)
                        },
                        onClick = {
                            SystemPermissions.openSettings(
                                context,
                                SystemPermissions.batteryOptimizationIntent(context),
                            )
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.Folder01, null) },
                        headlineContent = {
                            Text(stringResource(R.string.setting_permissions_storage))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_permissions_storage_desc))
                        },
                        trailingContent = {
                            PermissionBadge(enabled = storageEnabled)
                        },
                        onClick = {
                            SystemPermissions.openSettings(
                                context,
                                SystemPermissions.allFilesAccessIntent(context),
                            )
                        },
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.setting_permissions_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}

/** 绿色「已开启」/ 红色「未启用」胶囊徽章。 */
@Composable
private fun PermissionBadge(enabled: Boolean) {
    val container = if (enabled) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val content = if (enabled) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Text(
        text = stringResource(
            if (enabled) R.string.setting_permissions_enabled else R.string.setting_permissions_disabled,
        ),
        style = MaterialTheme.typography.labelSmall,
        color = content,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .size(
                width = 68.dp,
                height = 24.dp,
            )
            .background(container, RoundedCornerShape(12.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}
