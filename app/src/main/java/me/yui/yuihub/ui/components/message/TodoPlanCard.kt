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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.TaskDone01
import me.yui.yuihub.R
import me.yui.yuihub.ui.theme.extendColors
import me.yui.yuihub.utils.JsonInstant

/**
 * 计划（todo_write）卡片：在正文中展示模型的执行清单与实时进度。
 *
 * 展示的是「这一次调用时的清单」——同一轮里模型多次更新会渲染成多张卡片，
 * 形成计划演进的时间线（与消息历史一致，天然持久化）。
 */
@Composable
fun TodoPlanCard(
    toolInput: String,
    modifier: Modifier = Modifier,
) {
    val todos = remember(toolInput) { parseTodos(toolInput) }
    if (todos.isEmpty()) return

    val completedCount = todos.count { it.status == "completed" }
    val total = todos.size
    val allDone = completedCount == total

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
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

            todos.forEach { item ->
                TodoRow(item)
            }
        }
    }
}

@Composable
private fun TodoRow(item: TodoItem) {
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

private data class TodoItem(val content: String, val status: String)

private fun parseTodos(rawJson: String): List<TodoItem> {
    return runCatching {
        val root = JsonInstant.parseToJsonElement(rawJson).jsonObject
        (root["todos"] as? JsonArray).orEmpty().mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "pending"
                TodoItem(content, status)
            }.getOrNull()
        }
    }.getOrDefault(emptyList())
}
