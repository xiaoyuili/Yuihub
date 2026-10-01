package me.yui.yuihub.ui.components.message

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.TaskDone01
import me.yui.yuihub.R
import me.yui.yuihub.data.ai.tools.TODO_TOOL_NAME
import me.yui.yuihub.ui.theme.extendColors
import me.yui.yuihub.utils.JsonInstant

/**
 * 从消息列表里取「当前计划」：最后一条已执行的 todo_write 调用。
 * 用于顶栏计划入口与面板——同一轮多次更新时只展示最新那份清单。
 *
 * @return 计划条目列表；从未调用过或不合法时返回空列表（入口据此隐藏）
 */
fun findActivePlan(messages: List<UIMessage>): List<TodoEntry> =
    messages.asReversed()
        .asSequence()
        .flatMap { message -> message.parts.asReversed().asSequence() }
        .filterIsInstance<UIMessagePart.Tool>()
        .firstOrNull { it.toolName == TODO_TOOL_NAME && it.isExecuted }
        ?.let { parseTodos(it.input) }
        .orEmpty()

/** 计划条目（对外暴露给聊天页顶栏使用） */
data class TodoEntry(val content: String, val status: String)

/**
 * 计划卡片内容（列表 + 进度头）。顶栏面板与正文卡片共用，保证两处观感一致。
 *
 * @param showHeader 是否渲染顶部的「计划 + 进度」标题行。
 *   嵌入在输入栏细条内时为 false（状态与进度已在细条上体现，再重复会很啰嗦）。
 */
@Composable
fun TodoPlanContent(
    todos: List<TodoEntry>,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
) {
    if (todos.isEmpty()) return
    val completedCount = todos.count { it.status == "completed" }
    val total = todos.size
    val allDone = completedCount == total

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showHeader) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (allDone) HugeIcons.TaskDone01 else HugeIcons.CheckList,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (allDone) MaterialTheme.extendColors.green6 else MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.chat_message_todo_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(modifier = Modifier.weight(1f))
                Text(
                    text = "$completedCount/$total",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (allDone) MaterialTheme.extendColors.green6 else MaterialTheme.colorScheme.primary,
                )
            }
        }

        todos.forEach { item ->
            TodoRow(item)
        }
    }
}

@Composable
private fun TodoRow(item: TodoEntry) {
    val done = item.status == "completed"
    val inProgress = item.status == "in_progress"
    val markerColor by animateColorAsState(
        targetValue = when {
            done -> MaterialTheme.extendColors.green6
            inProgress -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        },
        animationSpec = tween(220),
        label = "todoMarkerColor",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (done) 0.55f else 1f,
        animationSpec = tween(220),
        label = "todoContentAlpha",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // 状态标记：完成=实心勾，进行中=实心点，待办=空心圈
        Box(
            modifier = Modifier.size(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                done -> Icon(
                    imageVector = HugeIcons.TaskDone01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = markerColor,
                )

                inProgress -> Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(markerColor),
                )

                else -> Box(
                    modifier = Modifier
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(1.dp),
                ) { }
            }
        }

        Text(
            text = item.content,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            textDecoration = if (done) TextDecoration.LineThrough else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun parseTodos(rawJson: String): List<TodoEntry> {
    return runCatching {
        val root = JsonInstant.parseToJsonElement(rawJson).jsonObject
        (root["todos"] as? JsonArray).orEmpty().mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "pending"
                TodoEntry(content, status)
            }.getOrNull()
        }
    }.getOrDefault(emptyList())
}
