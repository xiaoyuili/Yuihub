package me.yui.yuihub.ui.pages.setting

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clapping01
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Internet
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.UserMultiple
import me.yui.yuihub.R
import me.yui.yuihub.Screen
import me.yui.yuihub.data.datastore.isNotConfigured
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.ui.CardGroup
import me.yui.yuihub.ui.context.LocalNavController
import me.yui.yuihub.ui.context.Navigator
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.ui.theme.EntryAccents
import me.yui.yuihub.ui.theme.LocalDarkMode
import me.yui.yuihub.utils.plus
import org.koin.androidx.compose.koinViewModel

private data class SettingsEntry(
    val title: String,
    val description: String?,
    val icon: ImageVector,
    val iconColor: Color? = null,
    val keywords: List<String> = emptyList(),
    val onClick: () -> Unit,
)

private data class SettingsGroup(
    val title: String,
    val entries: List<SettingsEntry>,
)

/** 搜索结果指向的两种目标：页面入口、具体设置项 */
private sealed interface SettingsSearchTarget {
    data class Page(val entry: SettingsEntry) : SettingsSearchTarget
    data class Item(val item: SettingsCatalogItem) : SettingsSearchTarget
}

@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    val groups = listOf(
        SettingsGroup(
            title = stringResource(R.string.setting_page_general_settings),
            entries = listOf(
                SettingsEntry(
                    title = stringResource(R.string.setting_page_appearance),
                    description = stringResource(R.string.setting_page_appearance_desc),
                    icon = HugeIcons.PaintBoard,
                    iconColor = EntryAccents.Blue,
                    keywords = listOf("外观", "主题", "颜色", "深色", "浅色", "AMOLED", "主题色", "输入栏", "模糊", "玻璃", "气泡", "字体", "动画"),
                    onClick = { navController.navigate(Screen.SettingAppearance) },
                ),
                SettingsEntry(
                    title = stringResource(R.string.setting_page_preferences),
                    description = stringResource(R.string.setting_page_preferences_desc),
                    icon = HugeIcons.Settings03,
                    iconColor = EntryAccents.Violet,
                    keywords = listOf("通知", "权限", "网络", "主题", "语言", "启动页", "代理", "外观"),
                    onClick = { navController.navigate(Screen.SettingPreferences) },
                ),
            ),
        ),
        SettingsGroup(
            title = stringResource(R.string.setting_page_ai_config),
            entries = listOf(
                SettingsEntry(
                    title = stringResource(R.string.setting_page_default_model),
                    description = stringResource(R.string.setting_page_default_model_desc),
                    icon = HugeIcons.AiMagic,
                    iconColor = EntryAccents.Violet,
                    keywords = listOf("模型", "默认模型", "上下文压缩", "自动压缩", "视觉模型", "模型目录", "标题模型", "压缩阈值", "图像模型", "embedding"),
                    onClick = { navController.navigate(Screen.SettingModels) },
                ),
                SettingsEntry(
                    title = stringResource(R.string.setting_page_providers),
                    description = stringResource(R.string.setting_page_providers_desc),
                    icon = HugeIcons.Brain02,
                    iconColor = EntryAccents.Green,
                    keywords = listOf("供应商", "API", "API Key", "Base URL", "自定义请求", "模型", "接口类型", "密钥", "key", "url", "余额", "OpenAI", "Claude", "Gemini", "自定义"),
                    onClick = { navController.navigate(Screen.SettingProvider) },
                ),
                SettingsEntry(
                    title = stringResource(R.string.setting_page_prompts),
                    description = stringResource(R.string.setting_page_prompts_desc),
                    icon = HugeIcons.AiEditing,
                    iconColor = EntryAccents.Amber,
                    keywords = listOf("提示词", "标题生成", "上下文压缩", "记忆提取", "记忆整理", "工作区", "子代理", "技能", "定时任务", "视觉", "变量"),
                    onClick = { navController.navigate(Screen.SettingPrompts) },
                ),
            ),
        ),
        SettingsGroup(
            title = stringResource(R.string.setting_page_capabilities),
            entries = listOf(
                SettingsEntry(
                    title = stringResource(R.string.setting_page_assistant),
                    description = stringResource(R.string.setting_page_assistant_desc),
                    icon = HugeIcons.LookTop,
                    iconColor = EntryAccents.Indigo,
                    keywords = listOf("助手", "角色", "默认助手", "提示词", "记忆", "本地工具", "温度", "topP", "请求头"),
                    onClick = { navController.navigate(Screen.Assistant) },
                ),
                SettingsEntry(
                    title = stringResource(R.string.extensions_page_workspace),
                    description = stringResource(R.string.extensions_page_workspace_desc),
                    icon = HugeIcons.Folder01,
                    iconColor = EntryAccents.Blue,
                    keywords = listOf("工作区", "workspace", "Ubuntu", "终端", "文件", "rootfs", "挂载", "mount", "shell", "依赖"),
                    onClick = { navController.navigate(Screen.Workspaces) },
                ),
                SettingsEntry(
                    title = stringResource(R.string.setting_page_subagent_roles),
                    description = stringResource(R.string.setting_page_subagent_roles_desc),
                    icon = HugeIcons.UserMultiple,
                    iconColor = EntryAccents.Indigo,
                    keywords = listOf("子代理", "角色", "subagent", "并行", "推理强度"),
                    onClick = { navController.navigate(Screen.SubagentPersonas) },
                ),
                SettingsEntry(
                    title = stringResource(R.string.setting_page_search_service),
                    description = stringResource(R.string.setting_page_search_service_desc),
                    icon = HugeIcons.GlobalSearch,
                    iconColor = EntryAccents.Violet,
                    keywords = listOf("搜索", "搜索服务", "搜索引擎", "Tavily", "Exa", "Bing", "Brave", "联网", "web", "抓取"),
                    onClick = { navController.navigate(Screen.SettingSearch) },
                ),
                SettingsEntry(
                    title = stringResource(R.string.setting_page_extensions),
                    description = stringResource(R.string.setting_page_extensions_desc),
                    icon = HugeIcons.Package,
                    iconColor = EntryAccents.Amber,
                    keywords = listOf("扩展", "技能", "注入", "世界书", "模式注入", "lorebook"),
                    onClick = { navController.navigate(Screen.Extensions) },
                ),
            ),
        ),
        SettingsGroup(
            title = stringResource(R.string.setting_page_data_and_about),
            entries = listOf(
                SettingsEntry(
                    title = stringResource(R.string.setting_page_data_backup),
                    description = stringResource(R.string.setting_page_data_backup_desc),
                    icon = HugeIcons.Database02,
                    iconColor = EntryAccents.Blue,
                    keywords = listOf("备份", "恢复", "数据", "自动备份", "导出", "导入"),
                    onClick = { navController.navigate(Screen.Backup) },
                ),
                SettingsEntry(
                    title = stringResource(R.string.setting_page_about),
                    description = stringResource(R.string.setting_page_about_desc),
                    icon = HugeIcons.Clapping01,
                    iconColor = EntryAccents.Rose,
                    keywords = listOf("关于", "版本", "更新", "QQ", "分享", "GitHub", "许可证"),
                    onClick = { navController.navigate(Screen.SettingAbout) },
                ),
            ),
        ),
    )

    val extraSearchEntries = listOf(
        SettingsEntry(
            title = stringResource(R.string.setting_page_color_mode),
            description = null,
            icon = HugeIcons.Sun01,
            iconColor = EntryAccents.Blue,
            keywords = listOf("颜色模式", "跟随系统", "浅色", "深色"),
            onClick = { navController.navigate(Screen.SettingAppearance) },
        ),
        SettingsEntry(
            title = stringResource(R.string.setting_page_preferences_theme),
            description = stringResource(R.string.setting_page_preferences_theme_desc),
            icon = HugeIcons.Sun01,
            iconColor = EntryAccents.Violet,
            keywords = listOf("动态颜色", "预设主题", "AMOLED", "深色", "自定义主题", "主题色", "背景色", "玻璃", "拟态", "主题"),
            onClick = { navController.navigate(Screen.SettingPreferencesTheme) },
        ),
        SettingsEntry(
            title = stringResource(R.string.setting_page_preferences_general),
            description = stringResource(R.string.setting_page_preferences_general_desc),
            icon = HugeIcons.Settings03,
            iconColor = EntryAccents.Indigo,
            keywords = listOf("常规", "语言", "启动页", "动画"),
            onClick = { navController.navigate(Screen.SettingPreferencesGeneral) },
        ),
        SettingsEntry(
            title = stringResource(R.string.setting_appearance_message_ui),
            description = stringResource(R.string.setting_appearance_message_ui_desc),
            icon = HugeIcons.Message02,
            iconColor = EntryAccents.Blue,
            keywords = listOf("消息", "气泡", "头像", "模型名称", "时间", "思考", "LaTeX", "token", "输入栏", "模糊", "玻璃", "复制", "长按"),
            onClick = { navController.navigate(Screen.SettingAppearanceMessage) },
        ),
        SettingsEntry(
            title = stringResource(R.string.setting_appearance_fonts_and_code),
            description = stringResource(R.string.setting_appearance_fonts_and_code_desc),
            icon = HugeIcons.Code,
            iconColor = EntryAccents.Amber,
            keywords = listOf("字体", "字号", "字体大小", "代码块", "自动换行", "行号", "等宽字体"),
            onClick = { navController.navigate(Screen.SettingAppearanceTypography) },
        ),
        SettingsEntry(
            title = stringResource(R.string.setting_appearance_interaction),
            description = stringResource(R.string.setting_appearance_interaction_desc),
            icon = HugeIcons.Settings03,
            iconColor = EntryAccents.Teal,
            keywords = listOf("交互", "滚动", "背景", "粘贴", "长文本", "音量键", "跳转", "发送", "回车", "震动", "动画"),
            onClick = { navController.navigate(Screen.SettingAppearanceInteraction) },
        ),
        SettingsEntry(
            title = stringResource(R.string.setting_page_preferences_notification),
            description = stringResource(R.string.setting_page_preferences_notification_desc),
            icon = HugeIcons.Notification01,
            iconColor = EntryAccents.Cyan,
            keywords = listOf("通知", "推送", "提醒", "声音", "振动"),
            onClick = { navController.navigate(Screen.SettingPreferencesNotification) },
        ),
        SettingsEntry(
            title = stringResource(R.string.setting_permissions_title),
            description = stringResource(R.string.setting_permissions_desc),
            icon = HugeIcons.Shield01,
            iconColor = EntryAccents.Green,
            keywords = listOf("权限", "存储", "相机", "麦克风", "通知权限", "相册", "文件访问"),
            onClick = { navController.navigate(Screen.SettingPermissions) },
        ),
        SettingsEntry(
            title = stringResource(R.string.setting_page_preferences_network),
            description = stringResource(R.string.setting_page_preferences_network_desc),
            icon = HugeIcons.Internet,
            iconColor = EntryAccents.Blue,
            keywords = listOf("网络", "代理", "超时", "重试", "user agent", "ua"),
            onClick = { navController.navigate(Screen.SettingPreferencesNetwork) },
        ),
        SettingsEntry(
            title = stringResource(R.string.setting_page_search_service),
            description = stringResource(R.string.setting_page_search_service_desc),
            icon = HugeIcons.GlobalSearch,
            iconColor = EntryAccents.Violet,
            keywords = listOf("搜索", "搜索引擎", "Tavily", "Exa", "Bing", "Brave"),
            onClick = { navController.navigate(Screen.SettingSearch) },
        ),
        SettingsEntry(
            title = stringResource(R.string.setting_page_prompts),
            description = stringResource(R.string.setting_page_prompts_desc),
            icon = HugeIcons.AiEditing,
            iconColor = EntryAccents.Amber,
            keywords = listOf("提示词", "标题生成", "上下文压缩", "记忆提取", "记忆整理", "工作区", "子代理", "技能", "定时任务", "视觉", "变量"),
            onClick = { navController.navigate(Screen.SettingPrompts) },
        ),
        SettingsEntry(
            title = stringResource(R.string.extensions_page_workspace),
            description = stringResource(R.string.extensions_page_workspace_desc),
            icon = HugeIcons.Folder01,
            iconColor = EntryAccents.Blue,
            keywords = listOf("工作区", "workspace", "Ubuntu", "终端", "文件", "rootfs"),
            onClick = { navController.navigate(Screen.Workspaces) },
        ),
        SettingsEntry(
            title = stringResource(R.string.setting_page_subagent_roles),
            description = stringResource(R.string.setting_page_subagent_roles_desc),
            icon = HugeIcons.UserMultiple,
            iconColor = EntryAccents.Indigo,
            keywords = listOf("子代理", "角色"),
            onClick = { navController.navigate(Screen.SubagentPersonas) },
        ),
        SettingsEntry(
            title = stringResource(R.string.setting_page_share),
            description = stringResource(R.string.setting_page_share_desc),
            icon = HugeIcons.Share04,
            iconColor = EntryAccents.Amber,
            keywords = listOf("分享", "应用"),
            onClick = { navController.navigate(Screen.SettingAbout) },
        ),
        SettingsEntry(
            title = stringResource(R.string.about_page_qq_group),
            description = null,
            icon = HugeIcons.Message01,
            iconColor = EntryAccents.Green,
            keywords = listOf("QQ", "群"),
            onClick = { navController.navigate(Screen.SettingAbout) },
        ),
    )

    val searchEntries = extraSearchEntries
    val normalizedQuery = query.trim()
    val searchTokens = if (normalizedQuery.isEmpty()) {
        emptyList()
    } else {
        normalizedQuery.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    }
    // 索引 = 页面入口（主分组 + 子页面） + 具体设置项目录；同名时保留页面入口
    val pageEntryTitles = buildSet {
        groups.forEach { group -> group.entries.forEach { add(it.title) } }
        searchEntries.forEach { add(it.title) }
    }
    val catalogItems = rememberSettingsSearchCatalog()
    // 目录条目本身缓存住（含关键词小写、拼音懒值），避免每次输入都重建
    val catalogSearchItems: List<SettingsSearchItem<SettingsSearchTarget>> = remember(catalogItems) {
        catalogItems.mapIndexed { index, item ->
            SettingsSearchItem(
                title = item.title,
                description = null,
                keywords = item.keywords,
                group = item.path,
                order = index,
                payload = SettingsSearchTarget.Item(item),
            )
        }
    }
    val visibleCatalogItems = catalogSearchItems.filter { it.title !in pageEntryTitles }
    val searchIndex: List<SettingsSearchItem<SettingsSearchTarget>> =
        buildList<SettingsSearchItem<SettingsSearchTarget>> {
            addAll(visibleCatalogItems)
            var order = visibleCatalogItems.size
            groups.forEach { group ->
                group.entries.forEach { entry ->
                    add(
                        SettingsSearchItem(
                            title = entry.title,
                            description = entry.description,
                            keywords = entry.keywords,
                            group = group.title,
                            order = order++,
                            payload = SettingsSearchTarget.Page(entry),
                        )
                    )
                }
            }
            searchEntries.forEach { entry ->
                add(
                    SettingsSearchItem(
                        title = entry.title,
                        description = entry.description,
                        keywords = entry.keywords,
                        group = "",
                        order = order++,
                        payload = SettingsSearchTarget.Page(entry),
                    )
                )
            }
        }.distinctBy { "${it.title}|${it.group}|${it.description.orEmpty()}" }

    val searchHits: List<SettingsSearchHit<SettingsSearchTarget>> = if (searchTokens.isEmpty()) {
        emptyList()
    } else {
        searchSettings(searchIndex, normalizedQuery)
    }
    val isSearching = searchTokens.isNotEmpty()
    val keyboard = LocalSoftwareKeyboardController.current

    // 搜索时返回键先退出搜索，而不是直接退出设置页
    BackHandler(enabled = query.isNotEmpty()) {
        query = ""
        keyboard?.hide()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                },
                navigationIcon = {
                    BackButton()
                },
                colors = CustomColors.pageTopBarColors
            )
        },
        containerColor = CustomColors.pageBackground
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(key = "search") {
                SettingsSearchBar(
                    query = query,
                    onQueryChange = { query = it },
                )
            }

            if (isSearching) {
                if (searchHits.isEmpty()) {
                    item(key = "empty") {
                        Box(modifier = Modifier.animateItem()) {
                            SettingsSearchEmptyState(
                                onSuggestion = { suggestion -> query = suggestion },
                            )
                        }
                    }
                } else {
                    item(key = "count") {
                        AnimatedContent(
                            targetState = searchHits.size,
                            modifier = Modifier.animateItem(),
                            transitionSpec = {
                                (fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 })
                                    .togetherWith(
                                        fadeOut(tween(120)) +
                                            slideOutVertically(tween(120)) { -it / 2 }
                                    )
                            },
                            label = "settingsSearchCount",
                        ) { count ->
                            Text(
                                text = stringResource(R.string.setting_page_search_count, count),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
                            )
                        }
                    }
                    item(key = "results") {
                        CardGroup(
                            modifier = Modifier
                                .animateItem()
                                .animateContentSize(),
                            title = { Text(stringResource(R.string.setting_page_search_results)) },
                        ) {
                            searchHits.forEach { hit ->
                                val target = hit.item.payload
                                val icon: ImageVector
                                val iconColor: Color?
                                val onClick: () -> Unit
                                val description: String?
                                when (target) {
                                    is SettingsSearchTarget.Page -> {
                                        icon = target.entry.icon
                                        iconColor = target.entry.iconColor
                                        onClick = target.entry.onClick
                                        description = target.entry.description
                                    }

                                    is SettingsSearchTarget.Item -> {
                                        icon = target.item.icon
                                        iconColor = target.item.iconColor
                                        onClick = {
                                            target.item.anchorId?.let(SettingsSearchFocus::request)
                                            navController.navigate(target.item.screen)
                                        }
                                        description = null
                                    }
                                }
                                val supporting: (@Composable () -> Unit)? = when {
                                    hit.keyword != null -> {
                                        {
                                            Text(
                                                stringResource(
                                                    R.string.setting_page_search_matched,
                                                    hit.keyword,
                                                )
                                            )
                                        }
                                    }

                                    description != null -> {
                                        { Text(description) }
                                    }

                                    else -> null
                                }
                                item(
                                    onClick = onClick,
                                    leadingContent = {
                                        SettingIcon(icon, color = iconColor)
                                    },
                                    overlineContent = hit.item.group.takeIf { it.isNotBlank() }?.let { group ->
                                        { Text(group) }
                                    },
                                    headlineContent = {
                                        Text(
                                            highlightSettingsText(
                                                text = hit.item.title,
                                                tokens = searchTokens,
                                                style = SpanStyle(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.SemiBold,
                                                ),
                                            )
                                        )
                                    },
                                    supportingContent = supporting,
                                    trailingContent = {
                                        Icon(
                                            imageVector = HugeIcons.ArrowRight01,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            } else {
                if (settings.isNotConfigured()) {
                    item(key = "warning") {
                        ProviderConfigWarningCard(navController)
                    }
                }

                groups.forEach { group ->
                    item(key = group.title) {
                        CardGroup(
                            title = { Text(group.title) },
                        ) {
                            group.entries.forEach { entry ->
                                item(
                                    onClick = entry.onClick,
                                    leadingContent = {
                                        SettingIcon(entry.icon, color = entry.iconColor)
                                    },
                                    headlineContent = {
                                        Text(entry.title)
                                    },
                                    supportingContent = entry.description?.let { description ->
                                        { Text(description) }
                                    },
                                    trailingContent = {
                                        Icon(
                                            imageVector = HugeIcons.ArrowRight01,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = CustomColors.groupedCardSurface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.Search01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { keyboard?.hide() },
                ),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.setting_page_search_placeholder),
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** 搜不到时的空态：给几个高频入口，点一下直接搜，不用再手敲 */
@Composable
private fun SettingsSearchEmptyState(onSuggestion: (String) -> Unit) {
    val suggestions = listOf(
        stringResource(R.string.setting_page_providers),
        stringResource(R.string.setting_page_default_model),
        stringResource(R.string.setting_page_prompts),
        stringResource(R.string.setting_page_preferences_theme),
        stringResource(R.string.extensions_page_workspace),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = HugeIcons.Search01,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = stringResource(R.string.setting_page_search_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = stringResource(R.string.setting_page_search_try),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 18.dp, bottom = 10.dp),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.forEach { suggestion ->
                Surface(
                    onClick = { onSuggestion(suggestion) },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = suggestion,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * 设置入口统一图标底座：38dp 圆角容器，默认中性，仅重点入口使用强调色。
 */
@Composable
internal fun SettingIcon(icon: ImageVector, color: Color? = null) {
    val dark = LocalDarkMode.current
    val tint = color?.let { if (dark) lerp(it, Color.White, 0.25f) else it }
        ?: MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (color == null) MaterialTheme.colorScheme.surfaceContainerHigh
                else tint.copy(alpha = if (dark) 0.2f else 0.14f)
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ProviderConfigWarningCard(navController: Navigator) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = CustomColors.groupedCardSurface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.Alert01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_page_config_api_title),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Text(
                    text = stringResource(R.string.setting_page_config_api_desc),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            TextButton(
                onClick = { navController.navigate(Screen.SettingProvider) }
            ) {
                Text(stringResource(R.string.setting_page_config))
            }
        }
    }
}
