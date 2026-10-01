package me.yui.yuihub.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.TaskDone01
import me.yui.yuihub.R
import me.yui.yuihub.ui.theme.extendColors

/**
 * 计划细条（方案 E）：常驻在输入栏正上方，收起时一行高度。
 *
 * 收起态一行显示「进行中的步骤 + 进度」，点击展开完整清单；再点收起。
 * 滚动消息列表时随输入栏一起淡化（[alpha] 与输入栏共用同一条动画）。
 */
@Composable
fun PlanBar(
    todos: List<TodoEntry>,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
) {
    if (todos.isEmpty()) return

    val completed = todos.count { it.status == "completed" }
    val total = todos.size
    val allDone = completed == total
    // 当前进行中的那一步；没有进行中的则取最后一个未完成的
    val current = todos.firstOrNull { it.status == "in_progress" }
        ?: todos.lastOrNull { it.status != "completed" }

    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200),
        label = "planBarArrow",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            // 与输入栏同步：拖动消息列表时一起进入半透明
            .graphicsLayer { this.alpha = alpha },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (allDone) HugeIcons.TaskDone01 else HugeIcons.CheckList,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = if (allDone) MaterialTheme.extendColors.green6 else MaterialTheme.colorScheme.primary,
                )

                // 单行摘要：进度已在右侧计数与下方进度条中体现，不再重复副标题
                Text(
                    text = if (allDone) {
                        stringResource(R.string.chat_message_todo_all_done)
                    } else {
                        current?.content ?: stringResource(R.string.chat_message_todo_title)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                Text(
                    text = "$completed/$total",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (allDone) MaterialTheme.extendColors.green6 else MaterialTheme.colorScheme.primary,
                )

                Icon(
                    imageVector = HugeIcons.ArrowDown01,
                    contentDescription = stringResource(
                        if (expanded) R.string.chat_message_todo_collapse else R.string.plan_bar_expand
                    ),
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = arrowRotation },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 2dp 细线贴在下沿；展开时同时充当与清单的分隔
            LinearProgressIndicator(
                progress = { completed.toFloat() / total },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = if (allDone) MaterialTheme.extendColors.green6 else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                drawStopIndicator = {},
                gapSize = 0.dp,
            )

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(tween(160)),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(tween(120)),
            ) {
                // 只渲染条目本身：状态与进度已在上方行里，不再重复渲染标题行
                TodoPlanContent(todos = todos, showHeader = false)
            }
        }
    }
}
