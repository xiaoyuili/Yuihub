package me.yui.yuihub.ui.pages.setting.components

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Connect
import me.yui.yuihub.R
import me.yui.yuihub.ui.components.ai.ModelSelector
import me.yui.yuihub.ui.components.ui.AutoAIIcon
import me.yui.yuihub.ui.theme.extendColors
import me.yui.yuihub.utils.UiState
import me.yui.yuihub.utils.toFixed
import kotlin.uuid.Uuid
import org.koin.compose.koinInject

@Composable
fun ProviderConnectionTester(
    internalProvider: ProviderSetting,
) {
    var showTestDialog by remember { mutableStateOf(false) }
    val providerManager = koinInject<ProviderManager>()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    IconButton(onClick = { showTestDialog = true }) {
        Icon(HugeIcons.Connect, null)
    }

    if (showTestDialog) {
        var model by remember(internalProvider) {
            mutableStateOf(internalProvider.models.firstOrNull { it.type == ModelType.CHAT })
        }
        var nonStreamingState: UiState<String> by remember { mutableStateOf(UiState.Idle) }
        var streamingState: UiState<String> by remember { mutableStateOf(UiState.Idle) }
        var toolsState: UiState<String> by remember { mutableStateOf(UiState.Idle) }
        var streamingText by remember { mutableStateOf("") }

        fun resetStates() {
            nonStreamingState = UiState.Idle
            streamingState = UiState.Idle
            toolsState = UiState.Idle
            streamingText = ""
        }

        AlertDialog(
            onDismissRequest = { showTestDialog = false },
            title = {
                Text(stringResource(R.string.setting_provider_page_test_connection))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModelSelector(
                        modelId = model?.id,
                        providers = listOf(internalProvider),
                        type = ModelType.CHAT,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        model = it
                    }

                    TestResultItem(
                        label = stringResource(R.string.setting_provider_page_test_non_streaming),
                        state = nonStreamingState,
                        resultText = (nonStreamingState as? UiState.Success)?.data ?: ""
                    )

                    TestResultItem(
                        label = stringResource(R.string.setting_provider_page_test_streaming),
                        state = streamingState,
                        resultText = streamingText
                    )

                    TestResultItem(
                        label = stringResource(R.string.setting_provider_page_test_tool_call),
                        state = toolsState,
                        resultText = (toolsState as? UiState.Success)?.data ?: ""
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showTestDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (model == null) return@TextButton
                        val provider = providerManager.getProviderByType(internalProvider)
                        resetStates()
                        scope.launch {
                            launch {
                                runCatching {
                                    nonStreamingState = UiState.Loading
                                    val result = provider.generateText(
                                        providerSetting = internalProvider,
                                        messages = listOf(
                                            UIMessage.system("You are a helpful assistant"),
                                            UIMessage.user("hello"),
                                        ),
                                        params = TextGenerationParams(
                                            model = model!!,
                                            customHeaders = model!!.customHeaders,
                                            customBody = model!!.customBodies
                                        )
                                    )
                                    val text = result.message.parts
                                        .filterIsInstance<UIMessagePart.Text>()
                                        .joinToString("") { it.text }
                                    nonStreamingState = UiState.Success(text)
                                }.onFailure { nonStreamingState = UiState.Error(it) }
                            }
                            launch {
                                runCatching {
                                    streamingState = UiState.Loading
                                    val flow = provider.streamText(
                                        providerSetting = internalProvider,
                                        messages = listOf(
                                            UIMessage.system("You are a helpful assistant"),
                                            UIMessage.user("hello"),
                                        ),
                                        params = TextGenerationParams(
                                            model = model!!,
                                            customHeaders = model!!.customHeaders,
                                            customBody = model!!.customBodies
                                        )
                                    )
                                    flow.collect { chunk ->
                                        if (chunk is StreamChunk.TextDelta) {
                                            streamingText += chunk.text
                                        }
                                    }
                                    streamingState = UiState.Success("")
                                }.onFailure { streamingState = UiState.Error(it) }
                            }
                            launch {
                                runCatching {
                                    toolsState = UiState.Loading
                                    val testTool = Tool(
                                        name = "get_current_time",
                                        description = "Get the current date and time.",
                                        execute = { emptyList() }
                                    )
                                    val result = provider.generateText(
                                        providerSetting = internalProvider,
                                        messages = listOf(
                                            UIMessage.system("You are a helpful assistant"),
                                            UIMessage.user("Use the get_current_time tool."),
                                        ),
                                        params = TextGenerationParams(
                                            model = model!!,
                                            tools = listOf(testTool),
                                            customHeaders = model!!.customHeaders,
                                            customBody = model!!.customBodies
                                        )
                                    )
                                    val message = result.message
                                    val toolCall = message.parts
                                        .filterIsInstance<UIMessagePart.Tool>()
                                        .firstOrNull()
                                    val resultText = if (toolCall != null) {
                                        context.getString(
                                            R.string.setting_provider_page_test_tool_called,
                                            toolCall.toolName,
                                            toolCall.input
                                        )
                                    } else {
                                        val text = message.parts
                                            .filterIsInstance<UIMessagePart.Text>()
                                            .joinToString("") { it.text }
                                        context.getString(
                                            R.string.setting_provider_page_test_tool_not_called,
                                            text
                                        )
                                    }
                                    toolsState = UiState.Success(resultText)
                                }.onFailure { toolsState = UiState.Error(it) }
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_provider_page_test))
                }
            }
        )
    }
}

@Composable
private fun TestResultItem(
    label: String,
    state: UiState<String>,
    resultText: String
) {
    var showErrorSheet by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(120.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        when (state) {
            is UiState.Idle -> Text(
                text = "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            is UiState.Loading -> LinearWavyProgressIndicator(modifier = Modifier.weight(1f))
            is UiState.Success -> Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extendColors.green6
                )
                if (resultText.isNotBlank()) {
                    Text(
                        text = resultText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            is UiState.Error -> Text(
                text = state.error.message ?: "Error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.extendColors.red6,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { showErrorSheet = true }
            )
        }
    }

    if (showErrorSheet && state is UiState.Error) {
        val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        val stackTrace = remember(state.error) {
            state.error.stackTraceToString()
        }
        ModalBottomSheet(
            onDismissRequest = { showErrorSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = state.error.message ?: "Error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extendColors.red6
                )
                Text(
                    text = stackTrace,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 顶部栏按钮：弹出全部已添加模型的连接测试弹窗
 */
@Composable
fun ModelConnectionTester(
    providerSetting: ProviderSetting,
) {
    var showSheet by remember { mutableStateOf(false) }

    IconButton(onClick = { showSheet = true }) {
        Icon(HugeIcons.Connect, null)
    }

    if (showSheet) {
        ModelConnectionTestSheet(
            providerSetting = providerSetting,
            onDismiss = { showSheet = false },
        )
    }
}

private sealed interface ModelTestResult {
    data object Testing : ModelTestResult

    data class Success(val latencyMs: Long) : ModelTestResult

    data object Timeout : ModelTestResult

    data class Failure(val message: String) : ModelTestResult
}

private const val CONNECTION_TEST_TIMEOUT_MILLIS = 30_000L

@Composable
private fun ModelConnectionTestSheet(
    providerSetting: ProviderSetting,
    onDismiss: () -> Unit,
) {
    val providerManager = koinInject<ProviderManager>()
    val results = remember(providerSetting.id) {
        mutableStateMapOf<Uuid, ModelTestResult>().apply {
            providerSetting.models.forEach { model ->
                put(model.id, ModelTestResult.Testing)
            }
        }
    }

    // 所有已添加模型同时开始测试
    LaunchedEffect(providerSetting.id) {
        val provider = providerManager.getProviderByType(providerSetting)
        coroutineScope {
            providerSetting.models.forEach { model ->
                launch {
                    results[model.id] = runModelConnectionTest(provider, providerSetting, model)
                }
            }
        }
    }

    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (providerSetting.models.isEmpty()) Modifier
                    else Modifier.fillMaxHeight(0.9f)
                )
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_provider_page_test_connection),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = providerSetting.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (providerSetting.models.isEmpty()) {
                Text(
                    text = stringResource(R.string.setting_provider_page_no_models),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(providerSetting.models, key = { it.id }) { model ->
                        ModelTestRow(
                            model = model,
                            result = results[model.id] ?: ModelTestResult.Testing,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelTestRow(
    model: Model,
    result: ModelTestResult,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small,
        ) {
            AutoAIIcon(
                name = model.modelId,
                modifier = Modifier.size(36.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (result is ModelTestResult.Failure && result.message.isNotBlank()) {
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.extendColors.red6,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        when (result) {
            ModelTestResult.Testing -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )

            is ModelTestResult.Success -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.CheckmarkCircle02,
                    contentDescription = null,
                    tint = MaterialTheme.extendColors.green6,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = formatTestLatency(result.latencyMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ModelTestResult.Timeout -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.AlertCircle,
                    contentDescription = null,
                    tint = MaterialTheme.extendColors.red6,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.setting_provider_page_test_timeout),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.extendColors.red6,
                )
            }

            is ModelTestResult.Failure -> Icon(
                imageVector = HugeIcons.AlertCircle,
                contentDescription = null,
                tint = MaterialTheme.extendColors.red6,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun formatTestLatency(latencyMs: Long): String =
    if (latencyMs < 1000) {
        "${latencyMs}ms"
    } else {
        "${(latencyMs / 1000f).toFixed(1)}s"
    }

// 只测到首字（首个响应）为止；超过 30 秒未拿到首字视为超时
private suspend fun runModelConnectionTest(
    provider: Provider<ProviderSetting>,
    providerSetting: ProviderSetting,
    model: Model,
): ModelTestResult = try {
    withTimeout(CONNECTION_TEST_TIMEOUT_MILLIS) {
        val start = SystemClock.elapsedRealtime()
        when (model.type) {
            ModelType.CHAT -> provider.streamText(
                providerSetting = providerSetting,
                messages = listOf(UIMessage.user("hi")),
                params = TextGenerationParams(
                    model = model,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            ).first()

            ModelType.EMBEDDING -> provider.generateEmbedding(
                providerSetting = providerSetting,
                params = EmbeddingGenerationParams(
                    model = model,
                    input = listOf("hi"),
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            )

            ModelType.IMAGE -> provider.generateImage(
                providerSetting = providerSetting,
                params = ImageGenerationParams(
                    model = model,
                    prompt = "hi",
                    numOfImages = 1,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            ).first()
        }
        ModelTestResult.Success(SystemClock.elapsedRealtime() - start)
    }
} catch (e: TimeoutCancellationException) {
    ModelTestResult.Timeout
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    ModelTestResult.Failure(e.message ?: e.javaClass.simpleName)
}
