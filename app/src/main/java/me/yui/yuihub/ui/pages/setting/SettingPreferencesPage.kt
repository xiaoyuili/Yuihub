package me.yui.yuihub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Internet
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.Shield01
import me.yui.yuihub.R
import me.yui.yuihub.Screen
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.ui.CardGroup
import me.yui.yuihub.ui.context.LocalNavController
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.ui.theme.EntryAccents
import me.yui.yuihub.utils.plus

@Composable
fun SettingPreferencesPage() {
    val navController = LocalNavController.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences))
                },
                navigationIcon = {
                    BackButton()
                },
                colors = CustomColors.pageTopBarColors
            )
        },
        containerColor = CustomColors.pageBackground
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_preferences_notifications_and_permissions)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingPreferencesNotification) },
                        leadingContent = { SettingIcon(HugeIcons.Notification01, EntryAccents.Cyan) },
                        headlineContent = { Text(stringResource(R.string.setting_page_preferences_notification)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_preferences_notification_desc)) },
                        trailingContent = {
                            Icon(
                                imageVector = HugeIcons.ArrowRight01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingPermissions) },
                        leadingContent = { SettingIcon(HugeIcons.Shield01, EntryAccents.Green) },
                        headlineContent = { Text(stringResource(R.string.setting_permissions_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_permissions_desc)) },
                        trailingContent = {
                            Icon(
                                imageVector = HugeIcons.ArrowRight01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_preferences_network)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingPreferencesNetwork) },
                        leadingContent = { SettingIcon(HugeIcons.Internet, EntryAccents.Blue) },
                        headlineContent = { Text(stringResource(R.string.setting_page_preferences_network)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_preferences_network_desc)) },
                        trailingContent = {
                            Icon(
                                imageVector = HugeIcons.ArrowRight01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }
    }
}
