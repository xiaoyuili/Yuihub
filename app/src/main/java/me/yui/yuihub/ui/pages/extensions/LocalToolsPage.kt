package me.yui.yuihub.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.yui.yuihub.R
import me.yui.yuihub.data.ai.tools.local.LocalToolOption
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.ui.CardGroup
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.utils.plus

internal data class LocalToolEntry(
    val option: LocalToolOption,
    val titleRes: Int,
    val descriptionRes: Int,
)

internal val LocalToolEntries = listOf(
    LocalToolEntry(
        LocalToolOption.JavascriptEngine,
        R.string.assistant_page_local_tools_javascript_engine_title,
        R.string.assistant_page_local_tools_javascript_engine_desc,
    ),
    LocalToolEntry(
        LocalToolOption.TimeInfo,
        R.string.assistant_page_local_tools_time_info_title,
        R.string.assistant_page_local_tools_time_info_desc,
    ),
    LocalToolEntry(
        LocalToolOption.Clipboard,
        R.string.assistant_page_local_tools_clipboard_title,
        R.string.assistant_page_local_tools_clipboard_desc,
    ),
    LocalToolEntry(
        LocalToolOption.AskUser,
        R.string.assistant_page_local_tools_ask_user_title,
        R.string.assistant_page_local_tools_ask_user_desc,
    ),
    LocalToolEntry(
        LocalToolOption.ScreenTime,
        R.string.assistant_page_local_tools_screen_time_title,
        R.string.assistant_page_local_tools_screen_time_desc,
    ),
    LocalToolEntry(
        LocalToolOption.Calendar,
        R.string.assistant_page_local_tools_calendar_title,
        R.string.assistant_page_local_tools_calendar_desc,
    ),
    LocalToolEntry(
        LocalToolOption.ChartDisplay,
        R.string.assistant_page_local_tools_chart_display_title,
        R.string.assistant_page_local_tools_chart_display_desc,
    ),
)

@Composable
fun LocalToolsPage() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.assistant_page_tab_local_tools),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                },
                navigationIcon = { BackButton() },
                colors = CustomColors.pageTopBarColors,
            )
        },
        containerColor = CustomColors.pageBackground,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.local_tools_page_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
            item {
                CardGroup {
                    LocalToolEntries.forEach { entry ->
                        item(
                            headlineContent = { Text(stringResource(entry.titleRes)) },
                            supportingContent = { Text(stringResource(entry.descriptionRes)) },
                        )
                    }
                }
            }
        }
    }
}
