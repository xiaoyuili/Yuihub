package me.yui.yuihub.ui.components.richtext

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import java.net.URI

private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")

/**
 * 判断链接是否指向手机本机端口 (工作区里 AI 起的服务)。
 * proot 与宿主共享网络栈, 这类地址在应用内 WebView 可直接打开。
 */
fun isLoopbackUrl(url: String): Boolean = runCatching {
    val uri = URI(url.trim())
    val host = uri.host ?: return@runCatching false
    val scheme = uri.scheme?.lowercase()
    (scheme == "http" || scheme == "https") && host in LOOPBACK_HOSTS
}.getOrDefault(false)

/**
 * 聊天内链接点击的导航桥: RouteActivity 在组合时把导航回调注入这里。
 * Markdown 渲染处于非 Composable 的 AST 递归里, 无法直接读 CompositionLocal,
 * 用进程级回调绕开, 回调体内仍走 [Screen.WorkspaceWebViewer] 路由。
 */
object LocalLinkHandler {
    @Volatile
    var navigateToViewer: ((String) -> Unit)? = null
}

/**
 * 聊天内链接:
 * - loopback 地址 → 应用内 WorkspaceWebViewer
 * - 其它 → 默认系统浏览器
 *
 * 用法: withLink(markdownLink(url, style)) { ... }
 */
fun markdownLink(url: String, style: TextLinkStyles): LinkAnnotation =
    if (isLoopbackUrl(url)) {
        LinkAnnotation.Url(
            url = url,
            styles = style,
            linkInteractionListener = { _ ->
                LocalLinkHandler.navigateToViewer?.invoke(url)
            },
        )
    } else {
        LinkAnnotation.Url(url, style)
    }
