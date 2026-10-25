package me.yui.yuihub.ui.pages.setting

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Clapping01
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Internet
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.UserMultiple
import me.yui.yuihub.R
import me.yui.yuihub.Screen
import me.yui.yuihub.ui.theme.EntryAccents

/**
 * 设置页搜索目录里的一条“具体设置项”（不是页面入口）。
 *
 * 页面入口只能把用户带到页面；设置项条目能让「聊天背景」「输入栏背景效果」这类
 * 藏在页面里的开关也能被直接搜到。路径用于结果里的面包屑。
 */
internal data class SettingsCatalogItem(
    val title: String,
    val path: String,
    val keywords: List<String>,
    val screen: Screen,
    val icon: ImageVector,
    val iconColor: Color?,
    /** 目标页面的行锚点；为 null 时只能跳到页面，不会高亮具体设置项 */
    val anchorId: String? = null,
)

/**
 * 具体设置项目录。新增设置项时同步在这里补一条，否则用户只能靠记忆翻页面。
 * 关键词尽量覆盖：俗称、旧叫法、英文名、常见错别字意图。
 */
@Composable
internal fun rememberSettingsSearchCatalog(): List<SettingsCatalogItem> {
    val context = LocalContext.current
    // 目录内容只跟语言有关：缓存住，避免每次输入都重建 80 条（搜索卡顿的主因）
    val localeKey = LocalConfiguration.current.locales.toLanguageTags()
    return remember(localeKey) { buildSettingsSearchCatalog(context) }
}

private fun buildSettingsSearchCatalog(context: Context): List<SettingsCatalogItem> {
    val root = context.getString(R.string.settings)
    val appearance = context.getString(R.string.setting_page_appearance)
    val preferences = context.getString(R.string.setting_page_preferences)
    val message = context.getString(R.string.setting_appearance_message_ui)
    val typography = context.getString(R.string.setting_appearance_fonts_and_code)
    val interaction = context.getString(R.string.setting_appearance_interaction)
    val theme = context.getString(R.string.setting_page_preferences_theme)
    val notification = context.getString(R.string.setting_page_preferences_notification)
    val network = context.getString(R.string.setting_page_preferences_network)
    val permissions = context.getString(R.string.setting_permissions_title)
    val models = context.getString(R.string.setting_page_default_model)
    val providers = context.getString(R.string.setting_page_providers)
    val prompts = context.getString(R.string.setting_page_prompts)
    val workspaces = context.getString(R.string.extensions_page_workspace)
    val searchService = context.getString(R.string.setting_page_search_service)
    val backup = context.getString(R.string.setting_page_data_backup)
    val about = context.getString(R.string.setting_page_about)

    fun path(vararg segments: String) = (listOf(root) + segments).joinToString(" › ")

    return listOf(
        // ── 外观 ─────────────────────────────────────────────
        SettingsCatalogItem(
            title = context.getString(R.string.setting_page_color_mode),
            path = path(appearance),
            keywords = listOf("深色", "浅色", "跟随系统", "暗色", "夜间", "color mode", "dark"),
            screen = Screen.SettingAppearance,
            icon = HugeIcons.Sun01,
            iconColor = EntryAccents.Blue,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_theme_page_preset_themes),
            path = path(appearance, theme),
            keywords = listOf("主题", "配色", "皮肤", "玻璃", "拟态", "theme", "preset"),
            screen = Screen.SettingPreferencesTheme,
            icon = HugeIcons.PaintBoard,
            iconColor = EntryAccents.Violet,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_page_dynamic_color),
            path = path(appearance, theme),
            keywords = listOf("动态取色", "壁纸取色", "monet", "material you"),
            screen = Screen.SettingPreferencesTheme,
            icon = HugeIcons.PaintBoard,
            iconColor = EntryAccents.Violet,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_amoled_dark_mode_title),
            path = path(appearance, theme),
            keywords = listOf("纯黑", "黑色", "省电", "amoled", "dark"),
            screen = Screen.SettingPreferencesTheme,
            icon = HugeIcons.PaintBoard,
            iconColor = EntryAccents.Violet,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_theme_page_custom_themes),
            path = path(appearance, theme),
            keywords = listOf("自定义主题", "主色", "辅色", "主题色", "导入主题"),
            screen = Screen.SettingTheme,
            icon = HugeIcons.PaintBoard,
            iconColor = EntryAccents.Violet,
        ),

        // ── 外观 · 消息界面 ─────────────────────────────────
        SettingsCatalogItem(
            title = context.getString(R.string.assistant_page_chat_background),
            anchorId = "appearance.message.chat_background",
            path = path(appearance, message),
            keywords = listOf("背景", "背景图", "壁纸", "聊天背景", "background", "wallpaper", "bg"),
            screen = Screen.SettingAppearanceMessage,
            icon = HugeIcons.Message02,
            iconColor = EntryAccents.Blue,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.assistant_page_background_opacity),
            anchorId = "appearance.message.background_opacity",
            path = path(appearance, message),
            keywords = listOf("背景", "透明度", "背景透明度", "opacity"),
            screen = Screen.SettingAppearanceMessage,
            icon = HugeIcons.Message02,
            iconColor = EntryAccents.Blue,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.assistant_page_gradient_background),
            anchorId = "appearance.message.gradient_background",
            path = path(appearance, message),
            keywords = listOf("渐变", "背景", "mesh", "gradient"),
            screen = Screen.SettingAppearanceMessage,
            icon = HugeIcons.Message02,
            iconColor = EntryAccents.Blue,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_show_user_avatar_title),
            anchorId = "appearance.message.user_avatar",
            path = path(appearance, message),
            keywords = listOf("头像", "用户头像", "avatar"),
            screen = Screen.SettingAppearanceMessage,
            icon = HugeIcons.Message02,
            iconColor = EntryAccents.Blue,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_show_assistant_bubble_title),
            anchorId = "appearance.message.assistant_bubble",
            path = path(appearance, message),
            keywords = listOf("气泡", "助手气泡", "bubble"),
            screen = Screen.SettingAppearanceMessage,
            icon = HugeIcons.Message02,
            iconColor = EntryAccents.Blue,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_bubble_opacity_title),
            anchorId = "appearance.message.bubble_opacity",
            path = path(appearance, message),
            keywords = listOf("气泡", "透明度", "bubble"),
            screen = Screen.SettingAppearanceMessage,
            icon = HugeIcons.Message02,
            iconColor = EntryAccents.Blue,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_show_model_name_title),
            anchorId = "appearance.message.model_name",
            path = path(appearance, message),
            keywords = listOf("模型名称", "模型名"),
            screen = Screen.SettingAppearanceMessage,
            icon = HugeIcons.Message02,
            iconColor = EntryAccents.Blue,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_chat_list_model_icon_title),
            anchorId = "appearance.message.model_icon",
            path = path(appearance, message),
            keywords = listOf("模型图标", "图标", "icon"),
            screen = Screen.SettingAppearanceMessage,
            icon = HugeIcons.Message02,
            iconColor = EntryAccents.Blue,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_show_datetime_in_message_title),
            anchorId = "appearance.message.datetime",
            path = path(appearance, message),
            keywords = listOf("时间", "日期", "datetime"),
            screen = Screen.SettingAppearanceMessage,
            icon = HugeIcons.Message02,
            iconColor = EntryAccents.Blue,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_show_token_usage_title),
            anchorId = "appearance.message.token_usage",
            path = path(appearance, message),
            keywords = listOf("token", "用量", "统计", "消耗"),
            screen = Screen.SettingAppearanceMessage,
            icon = HugeIcons.Message02,
            iconColor = EntryAccents.Blue,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_show_thinking_content_title),
            anchorId = "appearance.message.thinking",
            path = path(appearance, message),
            keywords = listOf("思考", "推理", "thinking", "reasoning"),
            screen = Screen.SettingAppearanceMessage,
            icon = HugeIcons.Message02,
            iconColor = EntryAccents.Blue,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_auto_collapse_thinking_title),
            anchorId = "appearance.message.auto_collapse_thinking",
            path = path(appearance, message),
            keywords = listOf("思考", "折叠", "自动收起", "thinking"),
            screen = Screen.SettingAppearanceMessage,
            icon = HugeIcons.Message02,
            iconColor = EntryAccents.Blue,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_enable_latex_rendering_title),
            anchorId = "appearance.message.latex",
            path = path(appearance, message),
            keywords = listOf("公式", "数学", "latex", "markdown"),
            screen = Screen.SettingAppearanceMessage,
            icon = HugeIcons.Message02,
            iconColor = EntryAccents.Blue,
        ),

        // ── 外观 · 字体与代码 ───────────────────────────────
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_font_size_title),
            anchorId = "appearance.typography.font_size",
            path = path(appearance, typography),
            keywords = listOf("字号", "字体大小", "font size"),
            screen = Screen.SettingAppearanceTypography,
            icon = HugeIcons.Code,
            iconColor = EntryAccents.Amber,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_chat_font_family_title),
            anchorId = "appearance.typography.font_family",
            path = path(appearance, typography),
            keywords = listOf("字体", "聊天字体", "font", "serif", "monospace"),
            screen = Screen.SettingAppearanceTypography,
            icon = HugeIcons.Code,
            iconColor = EntryAccents.Amber,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_custom_font_title),
            anchorId = "appearance.typography.custom_font",
            path = path(appearance, typography),
            keywords = listOf("自定义字体", "导入字体", "ttf", "otf", "font"),
            screen = Screen.SettingAppearanceTypography,
            icon = HugeIcons.Code,
            iconColor = EntryAccents.Amber,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_code_block_auto_wrap_title),
            anchorId = "appearance.typography.code_wrap",
            path = path(appearance, typography),
            keywords = listOf("代码块", "换行", "code", "wrap"),
            screen = Screen.SettingAppearanceTypography,
            icon = HugeIcons.Code,
            iconColor = EntryAccents.Amber,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_code_block_auto_collapse_title),
            anchorId = "appearance.typography.code_collapse",
            path = path(appearance, typography),
            keywords = listOf("代码块", "折叠", "code"),
            screen = Screen.SettingAppearanceTypography,
            icon = HugeIcons.Code,
            iconColor = EntryAccents.Amber,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_show_line_numbers_title),
            anchorId = "appearance.typography.line_numbers",
            path = path(appearance, typography),
            keywords = listOf("行号", "代码", "line number"),
            screen = Screen.SettingAppearanceTypography,
            icon = HugeIcons.Code,
            iconColor = EntryAccents.Amber,
        ),

        // ── 外观 · 交互 ─────────────────────────────────────
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_background_effect_title),
            anchorId = "appearance.interaction.background_effect",
            path = path(appearance, interaction),
            keywords = listOf("输入栏", "模糊", "玻璃", "毛玻璃", "blur", "glass"),
            screen = Screen.SettingAppearanceInteraction,
            icon = HugeIcons.Settings03,
            iconColor = EntryAccents.Teal,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_blur_radius),
            anchorId = "appearance.interaction.blur_radius",
            path = path(appearance, interaction),
            keywords = listOf("模糊度", "模糊半径", "blur"),
            screen = Screen.SettingAppearanceInteraction,
            icon = HugeIcons.Settings03,
            iconColor = EntryAccents.Teal,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_enable_auto_scroll_title),
            anchorId = "appearance.interaction.auto_scroll",
            path = path(appearance, interaction),
            keywords = listOf("滚动", "自动滚动", "scroll"),
            screen = Screen.SettingAppearanceInteraction,
            icon = HugeIcons.Settings03,
            iconColor = EntryAccents.Teal,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_paste_long_text_as_file_title),
            anchorId = "appearance.interaction.paste_as_file",
            path = path(appearance, interaction),
            keywords = listOf("粘贴", "长文本", "文件", "paste"),
            screen = Screen.SettingAppearanceInteraction,
            icon = HugeIcons.Settings03,
            iconColor = EntryAccents.Teal,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_paste_long_text_threshold_title),
            anchorId = "appearance.interaction.paste_threshold",
            path = path(appearance, interaction),
            keywords = listOf("长文本", "阈值", "粘贴"),
            screen = Screen.SettingAppearanceInteraction,
            icon = HugeIcons.Settings03,
            iconColor = EntryAccents.Teal,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_volume_key_scroll_title),
            anchorId = "appearance.interaction.volume_scroll",
            path = path(appearance, interaction),
            keywords = listOf("音量键", "滚动", "音量"),
            screen = Screen.SettingAppearanceInteraction,
            icon = HugeIcons.Settings03,
            iconColor = EntryAccents.Teal,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_show_message_jumper_title),
            anchorId = "appearance.interaction.message_jumper",
            path = path(appearance, interaction),
            keywords = listOf("跳转", "消息跳转", "快速定位", "jumper"),
            screen = Screen.SettingAppearanceInteraction,
            icon = HugeIcons.Settings03,
            iconColor = EntryAccents.Teal,
        ),

        // ── 偏好 · 通知 / 网络 / 权限 ───────────────────────
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_notification_message_generated),
            anchorId = "preferences.notification.message_generated",
            path = path(preferences, notification),
            keywords = listOf("通知", "生成完成", "提醒", "notification"),
            screen = Screen.SettingPreferencesNotification,
            icon = HugeIcons.Notification01,
            iconColor = EntryAccents.Cyan,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_display_page_live_update_notification),
            anchorId = "preferences.notification.live_update",
            path = path(preferences, notification),
            keywords = listOf("通知", "实时", "进度", "notification"),
            screen = Screen.SettingPreferencesNotification,
            icon = HugeIcons.Notification01,
            iconColor = EntryAccents.Cyan,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_page_preferences_network_proxy),
            path = path(preferences, network),
            keywords = listOf("代理", "proxy", "http", "socks"),
            screen = Screen.SettingPreferencesNetwork,
            icon = HugeIcons.Internet,
            iconColor = EntryAccents.Blue,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_page_preferences_network_user_agent),
            path = path(preferences, network),
            keywords = listOf("ua", "user agent", "请求头"),
            screen = Screen.SettingPreferencesNetwork,
            icon = HugeIcons.Internet,
            iconColor = EntryAccents.Blue,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_page_preferences_network_auto_retry),
            path = path(preferences, network),
            keywords = listOf("重试", "自动重试", "retry"),
            screen = Screen.SettingPreferencesNetwork,
            icon = HugeIcons.Internet,
            iconColor = EntryAccents.Blue,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_permissions_storage),
            anchorId = "preferences.permissions.storage",
            path = path(preferences, permissions),
            keywords = listOf("存储", "文件访问", "权限", "storage"),
            screen = Screen.SettingPermissions,
            icon = HugeIcons.Shield01,
            iconColor = EntryAccents.Green,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_permissions_notification),
            anchorId = "preferences.permissions.notification",
            path = path(preferences, permissions),
            keywords = listOf("通知权限", "权限"),
            screen = Screen.SettingPermissions,
            icon = HugeIcons.Shield01,
            iconColor = EntryAccents.Green,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_permissions_background),
            anchorId = "preferences.permissions.background",
            path = path(preferences, permissions),
            keywords = listOf("后台运行", "后台", "权限"),
            screen = Screen.SettingPermissions,
            icon = HugeIcons.Shield01,
            iconColor = EntryAccents.Green,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_permissions_battery_optimization),
            anchorId = "preferences.permissions.battery",
            path = path(preferences, permissions),
            keywords = listOf("电池优化", "省电", "后台"),
            screen = Screen.SettingPermissions,
            icon = HugeIcons.Shield01,
            iconColor = EntryAccents.Green,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_permissions_install_unknown_apps),
            anchorId = "preferences.permissions.install_apps",
            path = path(preferences, permissions),
            keywords = listOf("安装应用", "未知来源", "更新"),
            screen = Screen.SettingPermissions,
            icon = HugeIcons.Shield01,
            iconColor = EntryAccents.Green,
        ),

        // ── AI 配置 · 模型 / 供应商 / 提示词 ────────────────
        SettingsCatalogItem(
            title = context.getString(R.string.setting_model_page_default_chat_model),
            path = path(models),
            keywords = listOf("默认模型", "聊天模型", "model"),
            screen = Screen.SettingModels,
            icon = HugeIcons.AiMagic,
            iconColor = EntryAccents.Violet,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_model_page_title_model),
            anchorId = "models.title_model",
            path = path(models),
            keywords = listOf("标题模型", "生成标题", "title"),
            screen = Screen.SettingModels,
            icon = HugeIcons.AiMagic,
            iconColor = EntryAccents.Violet,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_model_page_vision_model),
            anchorId = "models.vision_model",
            path = path(models),
            keywords = listOf("视觉模型", "图片识别", "vision"),
            screen = Screen.SettingModels,
            icon = HugeIcons.AiMagic,
            iconColor = EntryAccents.Violet,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.assistant_page_thinking_budget),
            anchorId = "models.thinking_budget",
            path = path(models),
            keywords = listOf("思考预算", "推理强度", "reasoning"),
            screen = Screen.SettingModels,
            icon = HugeIcons.AiMagic,
            iconColor = EntryAccents.Violet,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_model_page_auto_compress_title),
            anchorId = "models.auto_compress",
            path = path(models),
            keywords = listOf("压缩", "自动压缩", "上下文", "compress"),
            screen = Screen.SettingModels,
            icon = HugeIcons.AiMagic,
            iconColor = EntryAccents.Violet,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_model_page_auto_compress_threshold),
            anchorId = "models.auto_compress_threshold",
            path = path(models),
            keywords = listOf("压缩阈值", "阈值", "80%", "compress"),
            screen = Screen.SettingModels,
            icon = HugeIcons.AiMagic,
            iconColor = EntryAccents.Violet,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_model_page_catalog_title),
            path = path(models),
            keywords = listOf("模型目录", "模型信息识别", "models.dev", "同步"),
            screen = Screen.SettingModels,
            icon = HugeIcons.AiMagic,
            iconColor = EntryAccents.Violet,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_provider_page_api_key),
            path = path(providers),
            keywords = listOf("密钥", "key", "token", "apikey", "API"),
            screen = Screen.SettingProvider,
            icon = HugeIcons.Brain02,
            iconColor = EntryAccents.Green,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_provider_page_api_base_url),
            path = path(providers),
            keywords = listOf("url", "地址", "接口地址", "base", "endpoint"),
            screen = Screen.SettingProvider,
            icon = HugeIcons.Brain02,
            iconColor = EntryAccents.Green,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_provider_page_api_type),
            path = path(providers),
            keywords = listOf("接口类型", "接口", "OpenAI", "Claude", "Gemini", "自定义"),
            screen = Screen.SettingProvider,
            icon = HugeIcons.Brain02,
            iconColor = EntryAccents.Green,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_provider_page_response_api),
            path = path(providers),
            keywords = listOf("response", "responses api", "接口"),
            screen = Screen.SettingProvider,
            icon = HugeIcons.Brain02,
            iconColor = EntryAccents.Green,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_provider_page_add_model),
            path = path(providers),
            keywords = listOf("模型列表", "添加模型", "model"),
            screen = Screen.SettingProvider,
            icon = HugeIcons.Brain02,
            iconColor = EntryAccents.Green,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_provider_page_balance_info),
            path = path(providers),
            keywords = listOf("余额", "充值", "balance", "额度"),
            screen = Screen.SettingProvider,
            icon = HugeIcons.Brain02,
            iconColor = EntryAccents.Green,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_model_page_prompt_title),
            path = path(prompts),
            keywords = listOf("标题提示词", "生成标题", "title prompt"),
            screen = Screen.SettingPrompts,
            icon = HugeIcons.AiEditing,
            iconColor = EntryAccents.Amber,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_prompts_agent_tool_style_title),
            path = path(prompts),
            keywords = listOf("工具风格", "tool style", "系统提示"),
            screen = Screen.SettingPrompts,
            icon = HugeIcons.AiEditing,
            iconColor = EntryAccents.Amber,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_prompts_child_agent_title),
            path = path(prompts),
            keywords = listOf("子代理", "subagent", "提示词"),
            screen = Screen.SettingPrompts,
            icon = HugeIcons.AiEditing,
            iconColor = EntryAccents.Amber,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_prompts_memory_extract_title),
            path = path(prompts),
            keywords = listOf("记忆", "提取", "memory"),
            screen = Screen.SettingPrompts,
            icon = HugeIcons.AiEditing,
            iconColor = EntryAccents.Amber,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_prompts_memory_consolidate_title),
            path = path(prompts),
            keywords = listOf("记忆", "整理", "合并", "memory"),
            screen = Screen.SettingPrompts,
            icon = HugeIcons.AiEditing,
            iconColor = EntryAccents.Amber,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_prompts_compaction_checkpoint_title),
            path = path(prompts),
            keywords = listOf("压缩", "检查点", "checkpoint"),
            screen = Screen.SettingPrompts,
            icon = HugeIcons.AiEditing,
            iconColor = EntryAccents.Amber,
        ),

        // ── 能力 · 工作区 ───────────────────────────────────
        SettingsCatalogItem(
            title = context.getString(R.string.workspace_detail_mount_dirs),
            path = path(workspaces),
            keywords = listOf("挂载", "挂载目录", "mount", "手机目录"),
            screen = Screen.Workspaces,
            icon = HugeIcons.Folder01,
            iconColor = EntryAccents.Blue,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.workspace_terminal),
            path = path(workspaces),
            keywords = listOf("终端", "shell", "命令行", "bash"),
            screen = Screen.Workspaces,
            icon = HugeIcons.Folder01,
            iconColor = EntryAccents.Blue,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_page_mcp),
            path = path(context.getString(R.string.setting_page_mcp)),
            keywords = listOf("mcp", "服务器", "工具协议", "server"),
            screen = Screen.SettingMcp,
            icon = HugeIcons.Package,
            iconColor = EntryAccents.Indigo,
        ),

        // ── 数据与关于 ─────────────────────────────────────
        SettingsCatalogItem(
            title = context.getString(R.string.about_page_check_update),
            path = path(about),
            keywords = listOf("更新", "检查更新", "版本", "update"),
            screen = Screen.SettingAbout,
            icon = HugeIcons.Clapping01,
            iconColor = EntryAccents.Rose,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.about_page_github),
            path = path(about),
            keywords = listOf("github", "源码", "仓库"),
            screen = Screen.SettingAbout,
            icon = HugeIcons.Clapping01,
            iconColor = EntryAccents.Rose,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.about_page_qq_group),
            path = path(about),
            keywords = listOf("qq", "群", "交流"),
            screen = Screen.SettingAbout,
            icon = HugeIcons.Clapping01,
            iconColor = EntryAccents.Rose,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.about_page_license),
            path = path(about),
            keywords = listOf("许可证", "开源协议", "license"),
            screen = Screen.SettingAbout,
            icon = HugeIcons.Clapping01,
            iconColor = EntryAccents.Rose,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_page_data_backup),
            path = path(backup),
            keywords = listOf("备份", "导出", "导入", "恢复", "backup"),
            screen = Screen.Backup,
            icon = HugeIcons.Database02,
            iconColor = EntryAccents.Blue,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_page_search_service),
            path = path(searchService),
            keywords = listOf("搜索", "联网", "Tavily", "Exa", "Bing", "Brave", "SearXNG", "智谱"),
            screen = Screen.SettingSearch,
            icon = HugeIcons.GlobalSearch,
            iconColor = EntryAccents.Violet,
        ),
        SettingsCatalogItem(
            title = context.getString(R.string.setting_page_subagent_roles),
            path = path(context.getString(R.string.setting_page_capabilities)),
            keywords = listOf("子代理", "角色", "persona", "subagent"),
            screen = Screen.SubagentPersonas,
            icon = HugeIcons.UserMultiple,
            iconColor = EntryAccents.Indigo,
        ),
    )
}
