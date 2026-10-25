package me.yui.yuihub.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.yui.yuihub.R
import me.yui.yuihub.Screen
import me.yui.yuihub.data.model.ModelCatalogService
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.ui.AutoAIIcon
import me.yui.yuihub.ui.components.ui.RikkaFormDialog
import me.yui.yuihub.ui.context.LocalNavController
import me.yui.yuihub.ui.hooks.useEditState
import me.yui.yuihub.ui.pages.setting.components.ProviderConfigure
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.ui.theme.EntryAccents
import me.yui.yuihub.utils.plus
import me.yui.yuihub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.time.Instant

/** 供应商列表的表头项数（汇总 + 模型目录行），拖拽下标需要减去它 */
private const val PROVIDER_LIST_HEADER_ITEMS = 1

@Composable
fun SettingProviderPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    var searchQuery by remember { mutableStateOf("") }
    var showCatalogDialog by remember { mutableStateOf(false) }
    val lazyListState = rememberLazyListState()
    val filteredProviders = remember(settings.providers, searchQuery) {
        if (searchQuery.isBlank()) {
            settings.providers
        } else {
            settings.providers.filter { provider ->
                provider.name.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    // 懒列表第 0 项是汇总/模型目录行，供应商从下标 1 开始；
    // 用 id 映射而不是直接位移，避免头部占位和搜索过滤导致下标错位（曾因此越界闪退）
    val providersNow by rememberUpdatedState(settings.providers)
    val settingsNow by rememberUpdatedState(settings)
    val visibleProvidersNow by rememberUpdatedState(filteredProviders)
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val visible = visibleProvidersNow
        val fromId = visible.getOrNull(from.index - PROVIDER_LIST_HEADER_ITEMS)?.id
        val toId = visible.getOrNull(to.index - PROVIDER_LIST_HEADER_ITEMS)?.id
        if (fromId != null && toId != null && fromId != toId) {
            val newProviders = providersNow.toMutableList()
            val fromPos = newProviders.indexOfFirst { it.id == fromId }
            val toPos = newProviders.indexOfFirst { it.id == toId }
            if (fromPos >= 0 && toPos >= 0) {
                newProviders.add(toPos, newProviders.removeAt(fromPos))
                vm.updateSettings(settingsNow.copy(providers = newProviders))
            }
        }
    }
    val enabledCount = remember(settings.providers) { settings.providers.count { it.enabled } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(R.string.setting_provider_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    AddButton {
                        vm.updateSettings(
                            settings.copy(
                                providers = listOf(it) + settings.providers
                            )
                        )
                    }
                },
                colors = CustomColors.pageTopBarColors
            )
        },
        containerColor = CustomColors.pageBackground,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            ProviderSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .imePadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp) +
                    PaddingValues(bottom = innerPadding.calculateBottomPadding() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                state = lazyListState,
            ) {
                item(key = "summary") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 2.dp, top = 2.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.setting_provider_page_summary,
                                enabledCount,
                                settings.providers.size,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showCatalogDialog = true }) {
                            Icon(
                                imageVector = HugeIcons.Settings03,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = stringResource(R.string.setting_model_page_catalog_title),
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }

                if (filteredProviders.isEmpty()) {
                    item(key = "empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = if (searchQuery.isBlank()) {
                                    stringResource(R.string.setting_provider_page_empty)
                                } else {
                                    stringResource(R.string.setting_provider_page_no_models)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(filteredProviders, key = { it.id }) { provider ->
                        ReorderableItem(
                            state = reorderableState,
                            key = provider.id
                        ) { isDragging ->
                            val haptic = LocalHapticFeedback.current
                            ProviderItem(
                                modifier = Modifier
                                    .scale(if (isDragging) 0.97f else 1f)
                                    .fillMaxWidth()
                                    // 长按卡片任意位置即可拖动排序；不再单独占用一个拖动手柄槽位
                                    .longPressDraggableHandle(
                                        onDragStarted = {
                                            haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                        },
                                        onDragStopped = {
                                            haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                        }
                                    ),
                                provider = provider,
                                onToggleEnabled = { enabled ->
                                    vm.updateSettings(
                                        settings.copy(
                                            providers = settings.providers.map {
                                                if (it.id == provider.id) {
                                                    it.copyProvider(enabled = enabled)
                                                } else {
                                                    it
                                                }
                                            }
                                        )
                                    )
                                },
                                onClick = {
                                    navController.navigate(Screen.SettingProviderDetail(providerId = provider.id.toString()))
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCatalogDialog) {
        RikkaFormDialog(
            title = stringResource(R.string.setting_model_page_catalog_title),
            confirmText = stringResource(R.string.confirm),
            onConfirm = { showCatalogDialog = false },
            onDismiss = { showCatalogDialog = false },
        ) {
            ModelCatalogSettingsContent(settings = settings, vm = vm)
        }
    }
}

/** 紧凑搜索框：44dp 胶囊，比 OutlinedTextField 少一半垂直留白 */
@Composable
private fun ProviderSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textState = rememberTextFieldState(query)
    LaunchedEffect(textState.text) {
        val text = textState.text.toString()
        if (text != query) onQueryChange(text)
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.Search01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (textState.text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_search_providers),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    state = textState,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (textState.text.isNotEmpty()) {
                IconButton(
                    onClick = { textState.edit { replace(0, length, "") } },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.cancel),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelCatalogSettingsContent(
    settings: me.yui.yuihub.data.datastore.Settings,
    vm: SettingVM,
) {
    val catalog = koinInject<ModelCatalogService>()
    val lastSyncTime by catalog.lastSyncTime.collectAsStateWithLifecycle()
    val entryCount by catalog.entryCount.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var syncing by remember { mutableStateOf(false) }
    var syncFailed by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.setting_model_page_catalog_enable),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = settings.modelCatalogEnabled,
                onCheckedChange = { enabled ->
                    vm.updateSettings(settings.copy(modelCatalogEnabled = enabled))
                    if (enabled) {
                        scope.launch { catalog.ensureLoaded() }
                    }
                },
            )
        }

        if (settings.modelCatalogEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (lastSyncTime == 0L) {
                        stringResource(R.string.setting_model_page_catalog_never_synced)
                    } else {
                        stringResource(
                            R.string.setting_model_page_catalog_last_sync,
                            Instant.ofEpochMilli(lastSyncTime).toLocalDateTime(),
                            entryCount,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }

            TextButton(
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
                enabled = !syncing,
            ) {
                Text(stringResource(R.string.setting_model_page_catalog_sync_now))
            }

            if (syncFailed) {
                Text(
                    text = stringResource(R.string.setting_model_page_catalog_sync_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AddButton(onAdd: (ProviderSetting) -> Unit) {
    val dialogState = useEditState<ProviderSetting> {
        onAdd(it.copyProvider(name = it.name.trim()))
    }

    IconButton(
        onClick = {
            dialogState.open(ProviderSetting.OpenAI())
        }
    ) {
        Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.setting_provider_page_add_provider))
    }

    if (dialogState.isEditing) {
        dialogState.currentState?.let { editing ->
            ModalBottomSheet(
                onDismissRequest = { dialogState.dismiss() },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_add_provider),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 520.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        ProviderConfigure(
                            provider = editing,
                            onEdit = { dialogState.currentState = it },
                        )
                    }
                    Button(
                        onClick = { dialogState.confirm() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(stringResource(R.string.setting_provider_page_add))
                    }
                }
            }
        }
    }
}

/** 底部弹层里的可滚动内容（避免新增依赖：复用 verticalScroll） */
@Composable
private fun ProviderItem(
    provider: ProviderSetting,
    onToggleEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val contentAlpha = if (provider.enabled) 1f else 0.55f
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = CustomColors.groupedCardSurface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        tonalElevation = 0.dp,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AutoAIIcon(
                name = provider.name,
                modifier = Modifier
                    .size(40.dp)
                    .alpha(contentAlpha),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .alpha(contentAlpha),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!provider.enabled) {
                        StatusPill(
                            text = stringResource(R.string.setting_provider_page_disabled),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (provider.name == "AiHubMix") {
                        StatusPill(text = "10%", color = EntryAccents.Amber)
                    }
                }
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    ProvideTextStyle(
                        MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
                    ) {
                        provider.shortDescription()
                    }
                }
                Text(
                    text = stringResource(
                        R.string.setting_provider_page_model_count,
                        provider.models.size
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                )
            }
            Switch(
                checked = provider.enabled,
                onCheckedChange = onToggleEnabled,
            )
        }
    }
}

@Composable
private fun StatusPill(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
