package me.yui.yuihub.ui.pages.assistant.detail

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.yui.yuihub.R
import me.yui.yuihub.data.ai.tools.local.LocalToolOption
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.ui.CardGroup
import me.yui.yuihub.ui.components.ui.permission.PermissionInfo
import me.yui.yuihub.ui.components.ui.permission.PermissionManager
import me.yui.yuihub.ui.components.ui.permission.findActivity
import me.yui.yuihub.ui.components.ui.permission.rememberPermissionState
import me.yui.yuihub.ui.context.LocalToaster
import me.yui.yuihub.ui.pages.extensions.LocalToolEntries
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.utils.hasUsageStatsPermission
import me.yui.yuihub.utils.openUsageAccessSettings
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantLocalToolPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.assistant_page_tab_local_tools)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(innerPadding)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LocalToolSelectionList(
                selected = assistant.localTools,
                onChange = { vm.update(assistant.copy(localTools = it)) },
            )
        }
    }
}

@Composable
fun LocalToolSelectionList(
    selected: List<LocalToolOption>,
    onChange: (List<LocalToolOption>) -> Unit,
    usePermissionDialog: Boolean = true,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val permissionRequiredText =
        stringResource(R.string.assistant_page_local_tools_screen_time_permission_required)
    val calendarPermissionRequiredText =
        stringResource(R.string.assistant_page_local_tools_calendar_permission_required)

    val calendarPermissionState = if (usePermissionDialog) {
        rememberPermissionState(
            permissions = setOf(
                PermissionInfo(
                    permission = Manifest.permission.READ_CALENDAR,
                    displayName = { Text(stringResource(R.string.permission_calendar_read)) },
                    usage = { Text(stringResource(R.string.permission_calendar_read_desc)) },
                    required = true
                ),
                PermissionInfo(
                    permission = Manifest.permission.WRITE_CALENDAR,
                    displayName = { Text(stringResource(R.string.permission_calendar_write)) },
                    usage = { Text(stringResource(R.string.permission_calendar_write_desc)) },
                    required = true
                ),
            )
        )
    } else {
        null
    }
    calendarPermissionState?.let { PermissionManager(permissionState = it) }

    fun toggleLocalTool(option: LocalToolOption, enabled: Boolean) {
        if (enabled && option == LocalToolOption.ScreenTime && !context.hasUsageStatsPermission()) {
            toaster.show(message = permissionRequiredText, type = ToastType.Warning)
            context.openUsageAccessSettings()
        }
        if (enabled && option == LocalToolOption.Calendar) {
            if (calendarPermissionState != null) {
                if (!calendarPermissionState.allPermissionsGranted) {
                    calendarPermissionState.requestPermissions()
                    return
                }
            } else if (!context.hasCalendarPermissions()) {
                toaster.show(message = calendarPermissionRequiredText, type = ToastType.Warning)
                context.findActivity()?.let { activity ->
                    ActivityCompat.requestPermissions(
                        activity,
                        arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                        CALENDAR_PERMISSION_REQUEST_CODE,
                    )
                }
                return
            }
        }
        onChange(if (enabled) selected + option else selected - option)
    }

    if (!compact) {
        CardGroup {
            LocalToolEntries.forEach { entry ->
                item(
                    headlineContent = { Text(stringResource(entry.titleRes)) },
                    supportingContent = { Text(stringResource(entry.descriptionRes)) },
                    trailingContent = {
                        Switch(
                            checked = selected.contains(entry.option),
                            onCheckedChange = { toggleLocalTool(entry.option, it) },
                        )
                    },
                )
            }
        }
    } else {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = CustomColors.groupedCardSurface,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            ),
        ) {
            Column {
                LocalToolEntries.forEachIndexed { index, entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 58.dp)
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            Text(
                                text = stringResource(entry.titleRes),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                            )
                            Text(
                                text = stringResource(entry.descriptionRes),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Switch(
                            checked = selected.contains(entry.option),
                            onCheckedChange = { toggleLocalTool(entry.option, it) },
                        )
                    }
                    if (index != LocalToolEntries.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }
}


private const val CALENDAR_PERMISSION_REQUEST_CODE = 0x0CA1

private fun android.content.Context.hasCalendarPermissions(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
