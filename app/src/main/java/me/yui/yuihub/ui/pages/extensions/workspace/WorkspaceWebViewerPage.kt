package me.yui.yuihub.ui.pages.extensions.workspace

import android.content.ClipData
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.yui.yuihub.R
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.webview.WebView
import me.yui.yuihub.ui.components.webview.rememberWebViewState
import me.yui.yuihub.ui.theme.CustomColors

/**
 * 应用内网页查看器: 展示工作区里 AI 起的本地服务 (http://127.0.0.1:<port>)。
 * proot 共享宿主网络栈, 127.0.0.1 就是手机本机端口, WebView 直接可达,
 * 无需用户在物理机上装环境。
 */
@Composable
fun WorkspaceWebViewerPage(
    url: String,
) {
    var addressBar by remember(url) { mutableStateOf(url) }
    var addressBarFocused by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    val state = rememberWebViewState(
        url = url,
        settings = {
            javaScriptEnabled = true
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        },
    )

    // 页面内跳转/重定向后回写地址栏 (编辑中不覆盖用户的输入)
    LaunchedEffect(state.currentUrl) {
        val currentPageUrl = state.currentUrl
        if (!addressBarFocused && !currentPageUrl.isNullOrBlank()) {
            addressBar = currentPageUrl
        }
    }

    fun submitAddress() {
        val trimmed = addressBar.trim()
        if (trimmed.isEmpty()) return
        val target = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
        state.loadUrl(target)
    }

    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton() },
                title = {
                    AddressBar(
                        value = addressBar,
                        onValueChange = { addressBar = it },
                        onFocusChanged = { addressBarFocused = it },
                        onSubmit = { submitAddress() },
                        onReload = {
                            // 地址栏改动过 → 当「前往」用; 未改动 → 刷新当前页
                            if (addressBar.trim() != state.currentUrl) {
                                submitAddress()
                            } else {
                                state.reload()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                    )
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(
                                imageVector = HugeIcons.MoreVertical,
                                contentDescription = stringResource(R.string.more_options),
                            )
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.workspace_web_viewer_open_in_browser)) },
                                leadingIcon = { Icon(HugeIcons.Earth, contentDescription = null) },
                                onClick = {
                                    showMoreMenu = false
                                    val target = state.currentUrl ?: addressBar
                                    if (target.isNotBlank()) {
                                        uriHandler.openUri(target)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.workspace_web_viewer_copy_url)) },
                                leadingIcon = { Icon(HugeIcons.Copy01, contentDescription = null) },
                                onClick = {
                                    showMoreMenu = false
                                    val target = state.currentUrl ?: addressBar
                                    if (target.isNotBlank()) {
                                        scope.launch {
                                            clipboard.setClipEntry(
                                                ClipEntry(clipData = ClipData.newPlainText("URL", target))
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        WebView(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

@Composable
private fun AddressBar(
    value: String,
    onValueChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    Surface(
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(50),
        color = CustomColors.listItemColors.containerColor,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp, end = 4.dp)
                    .onFocusChanged { onFocusChanged(it.isFocused) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        keyboardController?.hide()
                        onSubmit()
                    },
                ),
            )
            IconButton(
                onClick = onReload,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Refresh01,
                    contentDescription = stringResource(R.string.workspace_web_viewer_reload),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
