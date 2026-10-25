package me.yui.yuihub.ui.pages.setting.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dokar.sonner.ToastType
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.ProviderSetting
import me.yui.yuihub.R
import me.yui.yuihub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.yui.yuihub.ui.context.LocalToaster
import me.yui.yuihub.ui.pages.setting.SegmentedPill
import me.yui.yuihub.ui.theme.JetbrainsMono
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.reflect.KClass

/**
 * 供应商配置表单：接口类型（下拉弹层）+ 基础/连接/行为三段卡片。
 *
 * 只负责编辑 [provider] 内容，不包含保存/删除按钮——列表页的新增弹层、
 * 配置页、模型级「供应商重写」弹层都复用它。
 */
@Composable
fun ProviderConfigure(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
    onEdit: (provider: ProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        ProviderTypeField(provider = provider, onEdit = onEdit)

        when (provider) {
            is ProviderSetting.OpenAI -> ProviderConfigureOpenAI(provider, onEdit)
            is ProviderSetting.Google -> ProviderConfigureGoogle(provider, onEdit)
            is ProviderSetting.Claude -> ProviderConfigureClaude(provider, onEdit)
        }
    }
}

fun ProviderSetting.convertTo(type: KClass<out ProviderSetting>): ProviderSetting {
    if (this::class == type) return this

    val apiKey = when (this) {
        is ProviderSetting.OpenAI -> this.apiKey
        is ProviderSetting.Google -> this.apiKey
        is ProviderSetting.Claude -> this.apiKey
    }
    val sourceBaseUrl = when (this) {
        is ProviderSetting.OpenAI -> this.baseUrl
        is ProviderSetting.Google -> this.baseUrl
        is ProviderSetting.Claude -> this.baseUrl
    }
    val targetDefaultBaseUrl = when (type) {
        ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI().baseUrl
        ProviderSetting.Google::class -> ProviderSetting.Google().baseUrl
        ProviderSetting.Claude::class -> ProviderSetting.Claude().baseUrl
        else -> error("Unsupported provider type: $type")
    }
    val convertedBaseUrl = sourceBaseUrl.convertToTargetBaseUrl(targetDefaultBaseUrl)

    return when (type) {
        ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            balanceOption = this.balanceOption, customHeaders = this.customHeaders, builtIn = this.builtIn,
            description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl
        )
        ProviderSetting.Google::class -> ProviderSetting.Google(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            balanceOption = this.balanceOption, customHeaders = this.customHeaders, builtIn = this.builtIn,
            description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl
        )
        ProviderSetting.Claude::class -> ProviderSetting.Claude(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            balanceOption = this.balanceOption, customHeaders = this.customHeaders, builtIn = this.builtIn,
            description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl
        )
        else -> error("Unsupported provider type: $type")
    }
}

internal fun ProviderSetting.defaultBaseUrlForReset(): String {
    val defaultProvider = DEFAULT_PROVIDERS.find { it.id == id }
    // DEFAULT_PROVIDERS 静态类型是 List<ProviderSetting>（baseUrl 不在基类上），
    // 必须在同类型分支内完成收窄与取值；同 id 但类型不同的默认项视为不存在
    val matchedBaseUrl = when (this) {
        is ProviderSetting.OpenAI ->
            (defaultProvider as? ProviderSetting.OpenAI)?.baseUrl ?: ProviderSetting.OpenAI().baseUrl
        is ProviderSetting.Google ->
            (defaultProvider as? ProviderSetting.Google)?.baseUrl ?: ProviderSetting.Google().baseUrl
        is ProviderSetting.Claude ->
            (defaultProvider as? ProviderSetting.Claude)?.baseUrl ?: ProviderSetting.Claude().baseUrl
    }
    return matchedBaseUrl
}

internal fun ProviderSetting.resetBaseUrlToDefault(): ProviderSetting {
    val defaultBaseUrl = defaultBaseUrlForReset()
    return when (this) {
        is ProviderSetting.OpenAI -> this.copy(baseUrl = defaultBaseUrl)
        is ProviderSetting.Google -> this.copy(baseUrl = defaultBaseUrl)
        is ProviderSetting.Claude -> this.copy(baseUrl = defaultBaseUrl)
    }
}

internal fun ProviderSetting.isUsingDefaultBaseUrl(): Boolean {
    val baseUrl = when (this) {
        is ProviderSetting.OpenAI -> this.baseUrl
        is ProviderSetting.Google -> this.baseUrl
        is ProviderSetting.Claude -> this.baseUrl
    }
    return baseUrl == defaultBaseUrlForReset()
}

private fun String.convertToTargetBaseUrl(targetDefaultBaseUrl: String): String {
    val sourceUrl = this.toHttpUrlOrNull() ?: return this
    val sourceHost = sourceUrl.host.lowercase()
    if (sourceHost in OFFICIAL_PROVIDER_HOSTS) return targetDefaultBaseUrl
    val targetUrl = targetDefaultBaseUrl.toHttpUrlOrNull() ?: return this
    val convertedPath = sourceUrl.encodedPath.convertToTargetPath(targetUrl.encodedPath)
    return sourceUrl.newBuilder().encodedPath(convertedPath).build().toString()
}

private fun String.convertToTargetPath(targetPath: String): String {
    val source = this.normalizePath()
    val target = targetPath.normalizePath()
    val replaced = when {
        source.lowercase().endsWith(V1_BETA_SUFFIX) -> source.dropLast(V1_BETA_SUFFIX.length) + target
        source.lowercase().endsWith(V1_SUFFIX) -> source.dropLast(V1_SUFFIX.length) + target
        source.isBlank() -> target
        else -> source + target
    }
    return replaced.normalizePath()
}

private fun String.normalizePath(): String {
    val value = this.trim()
    if (value.isEmpty() || value == "/") return ""
    val path = if (value.startsWith("/")) value else "/$value"
    return path.trimEnd('/')
}

private fun String.isValidBaseUrl(): Boolean = this.toHttpUrlOrNull() != null

private const val OPENAI_OFFICIAL_HOST = "api.openai.com"
private const val GOOGLE_OFFICIAL_HOST = "generativelanguage.googleapis.com"
private const val CLAUDE_OFFICIAL_HOST = "api.anthropic.com"
private const val V1_SUFFIX = "/v1"
private const val V1_BETA_SUFFIX = "/v1beta"
private val OFFICIAL_PROVIDER_HOSTS = setOf(
    OPENAI_OFFICIAL_HOST,
    GOOGLE_OFFICIAL_HOST,
    CLAUDE_OFFICIAL_HOST
)

/** 表单里的单行输入框：统一的圆角与密度，避免各页各一套 */
@Composable
private fun ConfigTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    minLines: Int = 1,
    maxLines: Int = 1,
    textStyle: androidx.compose.ui.text.TextStyle? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        minLines = minLines,
        maxLines = maxLines,
        singleLine = maxLines == 1 && minLines == 1,
        shape = RoundedCornerShape(14.dp),
        textStyle = textStyle ?: LocalTextStyle.current,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
    )
}

@Composable
private fun BasicInfoCard(
    name: String,
    onNameChange: (String) -> Unit,
    description: (@Composable () -> Unit)? = null,
) {
    FormCard(title = stringResource(R.string.setting_provider_page_basic_settings)) {
        description?.let {
            it()
        }
        ConfigTextField(
            value = name,
            onValueChange = onNameChange,
            label = stringResource(R.string.setting_provider_page_name),
        )
    }
}

@Composable
private fun SecretKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    maxLines: Int = 3,
) {
    ConfigTextField(
        value = value,
        onValueChange = onValueChange,
        label = stringResource(R.string.setting_provider_page_api_key),
        maxLines = maxLines,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(if (visible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
            }
        },
    )
}

@Composable
private fun ProviderConfigureOpenAI(
    provider: ProviderSetting.OpenAI,
    onEdit: (provider: ProviderSetting.OpenAI) -> Unit
) {
    val toaster = LocalToaster.current
    var keyVisible by remember { mutableStateOf(false) }
    val responseApiWarning = stringResource(R.string.setting_provider_page_response_api_warning)

    BasicInfoCard(
        name = provider.name,
        onNameChange = { onEdit(provider.copy(name = it)) },
        description = provider.description,
    )

    FormCard(title = stringResource(R.string.setting_provider_page_connection)) {
        ConfigTextField(
            value = provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
            label = stringResource(R.string.setting_provider_page_api_base_url),
            isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl(),
        )
        SecretKeyField(
            value = provider.apiKey,
            onValueChange = { onEdit(provider.copy(apiKey = it)) },
            visible = keyVisible,
            onToggleVisible = { keyVisible = !keyVisible },
        )
        if (provider.customApiType || !provider.useResponseApi) {
            ConfigTextField(
                value = provider.chatCompletionsPath,
                onValueChange = { onEdit(provider.copy(chatCompletionsPath = it.trim())) },
                label = stringResource(R.string.setting_provider_page_api_path),
                supportingText = if (provider.customApiType) {
                    stringResource(R.string.setting_provider_page_api_path_hint)
                } else null,
            )
        }
    }

    FormCard(title = stringResource(R.string.setting_provider_page_behavior)) {
        if (!provider.customApiType) {
            SettingToggleRow(
                title = stringResource(R.string.setting_provider_page_response_api),
                checked = provider.useResponseApi,
                onCheckedChange = {
                    onEdit(provider.copy(useResponseApi = it))
                    if (it && provider.baseUrl.toHttpUrlOrNull()?.host != "api.openai.com") {
                        toaster.show(message = responseApiWarning, type = ToastType.Warning)
                    }
                },
            )
        }
        SettingToggleRow(
            title = stringResource(R.string.setting_provider_page_include_history_reasoning),
            checked = provider.includeHistoryReasoning,
            onCheckedChange = { onEdit(provider.copy(includeHistoryReasoning = it)) },
        )
    }
}

@Composable
private fun ProviderConfigureClaude(
    provider: ProviderSetting.Claude,
    onEdit: (provider: ProviderSetting.Claude) -> Unit
) {
    var keyVisible by remember { mutableStateOf(false) }

    BasicInfoCard(
        name = provider.name,
        onNameChange = { onEdit(provider.copy(name = it)) },
        description = provider.description,
    )

    FormCard(title = stringResource(R.string.setting_provider_page_connection)) {
        ConfigTextField(
            value = provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
            label = stringResource(R.string.setting_provider_page_api_base_url),
            isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl(),
        )
        SecretKeyField(
            value = provider.apiKey,
            onValueChange = { onEdit(provider.copy(apiKey = it)) },
            visible = keyVisible,
            onToggleVisible = { keyVisible = !keyVisible },
        )
    }

    FormCard(title = stringResource(R.string.setting_provider_page_behavior)) {
        SettingToggleRow(
            title = stringResource(R.string.setting_provider_page_claude_prompt_caching),
            checked = provider.promptCaching,
            onCheckedChange = { onEdit(provider.copy(promptCaching = it)) },
        )
        if (provider.promptCaching) {
            Text(
                text = stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SegmentedPill(
                options = ClaudePromptCacheTtl.entries,
                selectedOption = provider.promptCacheTtl,
                onOptionSelected = { onEdit(provider.copy(promptCacheTtl = it)) },
                optionToString = {
                    stringResource(
                        when (it) {
                            ClaudePromptCacheTtl.FIVE_MINUTES -> R.string.setting_provider_page_claude_prompt_cache_ttl_5m
                            ClaudePromptCacheTtl.ONE_HOUR -> R.string.setting_provider_page_claude_prompt_cache_ttl_1h
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ProviderConfigureGoogle(
    provider: ProviderSetting.Google,
    onEdit: (provider: ProviderSetting.Google) -> Unit
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    var keyVisible by remember { mutableStateOf(false) }
    var privateKeyVisible by remember { mutableStateOf(false) }
    val serviceAccountJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val content = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.readText()
                ?: return@rememberLauncherForActivityResult
            val json = Json.parseToJsonElement(content).jsonObject
            onEdit(
                provider.copy(
                    projectId = json["project_id"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: provider.projectId,
                    serviceAccountEmail = json["client_email"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: provider.serviceAccountEmail,
                    privateKey = json["private_key"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: provider.privateKey,
                )
            )
            toaster.show("Service account imported", type = ToastType.Success)
        } catch (e: Exception) {
            toaster.show("Failed to import: ${e.message}", type = ToastType.Error)
        }
    }

    BasicInfoCard(
        name = provider.name,
        onNameChange = { onEdit(provider.copy(name = it)) },
        description = provider.description,
    )

    FormCard(title = stringResource(R.string.setting_provider_page_connection)) {
        if (!provider.vertexAI) {
            ConfigTextField(
                value = provider.baseUrl,
                onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
                label = stringResource(R.string.setting_provider_page_api_base_url),
                isError = provider.baseUrl.isNotBlank() && (
                    !provider.baseUrl.isValidBaseUrl() || !provider.baseUrl.endsWith("/v1beta")
                    ),
                supportingText = if (!provider.baseUrl.endsWith("/v1beta")) {
                    "The base URL usually ends with `/v1beta`"
                } else null,
            )
        }
        if (!(provider.vertexAI && provider.useServiceAccount)) {
            SecretKeyField(
                value = provider.apiKey,
                onValueChange = { onEdit(provider.copy(apiKey = it)) },
                visible = keyVisible,
                onToggleVisible = { keyVisible = !keyVisible },
            )
        }
    }

    FormCard(title = stringResource(R.string.setting_provider_page_behavior)) {
        SettingToggleRow(
            title = stringResource(R.string.setting_provider_page_vertex_ai),
            checked = provider.vertexAI,
            onCheckedChange = { onEdit(provider.copy(vertexAI = it)) },
        )
        if (!provider.vertexAI) {
            SettingToggleRow(
                title = stringResource(R.string.setting_provider_page_interactions_api),
                checked = provider.useInteractionsApi,
                onCheckedChange = { onEdit(provider.copy(useInteractionsApi = it)) },
            )
        }
        if (provider.vertexAI) {
            SettingToggleRow(
                title = stringResource(R.string.setting_provider_page_use_service_account),
                checked = provider.useServiceAccount,
                onCheckedChange = { onEdit(provider.copy(useServiceAccount = it)) },
            )
        }
    }

    if (provider.vertexAI && provider.useServiceAccount) {
        FormCard(title = stringResource(R.string.setting_provider_page_service_account_email)) {
            OutlinedButton(
                onClick = { serviceAccountJsonLauncher.launch(arrayOf("application/json", "*/*")) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(stringResource(R.string.setting_provider_page_import_service_account_json))
            }
            ConfigTextField(
                value = provider.serviceAccountEmail,
                onValueChange = { onEdit(provider.copy(serviceAccountEmail = it.trim())) },
                label = stringResource(R.string.setting_provider_page_service_account_email),
            )
            ConfigTextField(
                value = provider.privateKey,
                onValueChange = { onEdit(provider.copy(privateKey = it.trim())) },
                label = stringResource(R.string.setting_provider_page_private_key),
                minLines = 3,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                visualTransformation = if (privateKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { privateKeyVisible = !privateKeyVisible }) {
                        Icon(if (privateKeyVisible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
                    }
                },
            )
            ConfigTextField(
                value = provider.location,
                onValueChange = { onEdit(provider.copy(location = it.trim())) },
                label = stringResource(R.string.setting_provider_page_location),
            )
            ConfigTextField(
                value = provider.projectId,
                onValueChange = { onEdit(provider.copy(projectId = it.trim())) },
                label = stringResource(R.string.setting_provider_page_project_id),
            )
        }
    }
}
