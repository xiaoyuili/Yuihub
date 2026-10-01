package me.yui.yuihub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.ArrowRight01
import me.yui.yuihub.R
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.data.model.ModelCatalogService
import me.yui.yuihub.ui.components.ai.ModelListSheet
import me.yui.yuihub.ui.components.ai.ReasoningButton
import me.yui.yuihub.ui.components.ai.rememberModelListState
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.nav.FloatingBottomBar
import me.yui.yuihub.ui.components.nav.FloatingBottomBarDefaults
import me.yui.yuihub.ui.components.nav.FloatingBottomBarTab
import me.yui.yuihub.ui.components.ui.CardGroup
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.utils.plus
import me.yui.yuihub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.time.Instant
import kotlin.uuid.Uuid

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { 2 }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_model_page_title)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> ModelSettingsPage(settings = settings, vm = vm, contentPadding = contentPadding)
                    1 -> PromptSettingsPage(settings = settings, vm = vm, contentPadding = contentPadding)
                }
            }
            FloatingBottomBar(
                pagerState = pagerState,
                tabs = listOf(
                    FloatingBottomBarTab(
                        icon = HugeIcons.AiBrain01,
                        label = stringResource(R.string.setting_model_page_tab_model),
                    ),
                    FloatingBottomBarTab(
                        icon = HugeIcons.AiEditing,
                        label = stringResource(R.string.setting_model_page_tab_prompt),
                    ),
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = contentPadding.calculateBottomPadding()),
            )
        }
    }
}

@Composable
private fun ModelSettingsPage(settings: Settings, vm: SettingVM, contentPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(start = 16.dp, end = 16.dp, bottom = FloatingBottomBarDefaults.ContentBottom),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_title_model),
                description = stringResource(R.string.setting_model_page_title_model_desc),
                modelId = settings.titleModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(titleModelId = it.id)) },
                reasoningLevel = settings.titleModelReasoningLevel,
                onUpdateReasoningLevel = {
                    vm.updateSettings(settings.copy(titleModelReasoningLevel = it))
                },
                unselectedText = stringResource(R.string.setting_model_page_default_chat_model),
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_vision_model),
                description = stringResource(R.string.setting_model_page_vision_model_desc),
                modelId = settings.visionModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(visionModelId = it.id)) },
            )
        }
        item {
            ModelCatalogSettingItem(settings = settings, vm = vm)
        }
    }
}

/** 模型信息自动识别：models.dev 目录开关与同步状态 */
@Composable
private fun ModelCatalogSettingItem(settings: Settings, vm: SettingVM) {
    val catalog = koinInject<ModelCatalogService>()
    val lastSyncTime by catalog.lastSyncTime.collectAsStateWithLifecycle()
    val entryCount by catalog.entryCount.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var syncing by remember { mutableStateOf(false) }
    var syncFailed by remember { mutableStateOf(false) }

    Column {
        CardGroup(title = { Text(stringResource(R.string.setting_model_page_catalog_title)) }) {
            item(
                trailingContent = {
                    Switch(
                        checked = settings.modelCatalogEnabled,
                        onCheckedChange = { enabled ->
                            vm.updateSettings(settings.copy(modelCatalogEnabled = enabled))
                            if (enabled) {
                                // 启动时关闭的话当时没加载，开启后立即加载一次
                                scope.launch { catalog.ensureLoaded() }
                            }
                        },
                    )
                },
                headlineContent = { Text(stringResource(R.string.setting_model_page_catalog_enable)) },
                supportingContent = { Text(stringResource(R.string.setting_model_page_catalog_enable_desc)) },
            )

            if (settings.modelCatalogEnabled) {
                item(
                    headlineContent = {
                        Text(
                            if (lastSyncTime == 0L) {
                                stringResource(R.string.setting_model_page_catalog_never_synced)
                            } else {
                                stringResource(
                                    R.string.setting_model_page_catalog_last_sync,
                                    Instant.ofEpochMilli(lastSyncTime).toLocalDateTime(),
                                    entryCount,
                                )
                            }
                        )
                    },
                    supportingContent = {
                        Text(
                            if (syncFailed) {
                                stringResource(R.string.setting_model_page_catalog_sync_failed)
                            } else {
                                stringResource(R.string.setting_model_page_catalog_sync_desc)
                            }
                        )
                    },
                )

                item(
                    onClick = {
                        if (!syncing) {
                            syncing = true
                            syncFailed = false
                            scope.launch {
                                val ok = catalog.syncNow()
                                syncing = false
                                syncFailed = !ok
                            }
                        }
                    },
                    headlineContent = { Text(stringResource(R.string.setting_model_page_catalog_sync_now)) },
                    trailingContent = {
                        if (syncing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelSettingItem(
    title: String,
    description: String,
    modelId: Uuid?,
    providers: List<ProviderSetting>,
    onSelect: (Model) -> Unit,
    reasoningLevel: ReasoningLevel? = null,
    onUpdateReasoningLevel: ((ReasoningLevel) -> Unit)? = null,
    unselectedText: String? = null,
) {
    val state = rememberModelListState(
        modelId = modelId,
        providers = providers,
        type = ModelType.CHAT,
    )

    Column {
        CardGroup(title = { Text(title) }) {
            item(
                onClick = { state.open() },
                headlineContent = { Text(title) },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = state.currentModel?.displayName
                                ?: unselectedText
                                ?: stringResource(R.string.model_list_select_model),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            HugeIcons.ArrowRight01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
            )
            if (reasoningLevel != null && onUpdateReasoningLevel != null) {
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_thinking_budget)) },
                    trailingContent = {
                        ReasoningButton(
                            reasoningLevel = reasoningLevel,
                            onUpdateReasoningLevel = onUpdateReasoningLevel,
                        )
                    },
                )
            }
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }

    ModelListSheet(state = state, onSelect = onSelect)
}
