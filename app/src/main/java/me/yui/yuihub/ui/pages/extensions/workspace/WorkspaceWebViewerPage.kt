package me.yui.yuihub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Link01
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
    var loadUrl by remember(url) { mutableStateOf(url) }
    var reloadKey by remember { mutableStateOf(0) }

    val state = rememberWebViewState(
        url = loadUrl,
        settings = {
            javaScriptEnabled = true
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.workspace_web_viewer_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { reloadKey++ }) {
                        Icon(HugeIcons.Refresh01, contentDescription = stringResource(R.string.workspace_web_viewer_reload))
                    }
                    IconButton(onClick = { loadUrl = addressBar }) {
                        Icon(HugeIcons.Link01, contentDescription = stringResource(R.string.workspace_web_viewer_go))
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            OutlinedTextField(
                value = addressBar,
                onValueChange = { addressBar = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                singleLine = true,
                placeholder = { Text("http://127.0.0.1:8080") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { loadUrl = addressBar }),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            WebView(
                state = state,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
