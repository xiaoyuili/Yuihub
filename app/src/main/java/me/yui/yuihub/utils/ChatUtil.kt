package me.yui.yuihub.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import me.rerere.ai.ui.UIMessage
import me.yui.yuihub.Screen
import me.yui.yuihub.ui.context.Navigator
import kotlin.uuid.Uuid

private const val TAG = "ChatUtil"

fun navigateToChatPage(
    navigator: Navigator,
    chatId: Uuid = Uuid.random(),
    initText: String? = null,
    initFiles: List<Uri> = emptyList(),
    nodeId: Uuid? = null,
) {
    Log.i(TAG, "navigateToChatPage: navigate to $chatId")
    navigator.clearAndNavigate(
        Screen.Chat(
            id = chatId.toString(),
            text = initText,
            files = initFiles.map { it.toString() },
            nodeId = nodeId?.toString(),
        )
    )
}

fun Context.copyMessageToClipboard(message: UIMessage) {
    this.writeClipboardText(message.toText())
}

/**
 * 附件类型白名单。产品已定位为 agent 客户端：任何类型都接受（AI 可通过工具链处理任意文件），
 * 因此此函数恒返回 true，保留函数与签名是为了兼容测试与调用点。
 */
fun isAllowedFileType(fileName: String, mime: String): Boolean = true
