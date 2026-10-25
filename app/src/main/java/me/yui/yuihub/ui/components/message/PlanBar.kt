package me.yui.yuihub.ui.components.message

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.TaskDone01
import me.yui.yuihub.R
import me.yui.yuihub.ui.theme.extendColors

/** 任务胶囊的图标状态 */
private enum class TaskIconState { LOADING, DONE, IDLE }

/**
 * 任务状态条：输入栏左上角外的一行「进度环图标 + 当前步骤 + 计数」。
 *
 * - 没有任务时不渲染（入口据此隐藏）
 * - 图标外圈是完成进度，内圈随进度在「加载中 / 完成 / 待机」之间动画切换；完成瞬间脉冲一下
 * - 右侧文字上下翻转到新的未完成步骤；全部完成后显示「任务已完成」
 * - 点击图标向上展开完整清单，进行中与已完成各自有标记
 * - 与输入栏共用同一条淡化动画（[alpha]），拖动消息列表时一起半透明
 *
 * @param running 当前是否有生成在进行；只有生成中才让图标转圈，中断后停在待机态，避免一直转
 */
@Composable
fun PlanBar(
    todos: List<TodoEntry>,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    running: Boolean = false,
) {
    if (todos.isEmpty()) return

    val completedCount = todos.count { it.status == "completed" }
    val total = todos.size
    val allDone = completedCount == total

    var expanded by remember { mutableStateOf(false) }

    // 模型不一定每次都会更新状态：生成中若一条 in_progress 都没有，
    // 就把第一个未完成步骤当作正在执行，避免面板上的点一直停在“待办”白点、胶囊也像没在跑
    val displayTodos = remember(todos, running) {
        if (running && todos.none { it.status == "in_progress" }) {
            val index = todos.indexOfFirst { it.status != "completed" }
            if (index < 0) {
                todos
            } else {
                todos.toMutableList().also { it[index] = it[index].copy(status = "in_progress") }
            }
        } else {
            todos
        }
    }
    // 当前进行中的那一步；没有进行中的则取第一个未完成的
    val current = displayTodos.firstOrNull { it.status == "in_progress" }
        ?: displayTodos.firstOrNull { it.status != "completed" }

    // 刚完成的步骤：图标先停在完成态一下，再翻转到下一步，
    // 这样连续完成多步时也能看到每一次的收尾，而不是从 A 直接跳到 C
    val completedNames = remember(todos) {
        todos.filter { it.status == "completed" }.map { it.content }
    }
    var previousCompletedNames by remember { mutableStateOf(completedNames) }
    var justCompletedName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(completedNames, allDone) {
        val added = completedNames.filterNot { it in previousCompletedNames }
        previousCompletedNames = completedNames
        if (added.isNotEmpty() && !allDone) {
            justCompletedName = added.last()
            delay(900)
            justCompletedName = null
        }
    }

    val iconState = when {
        allDone || justCompletedName != null -> TaskIconState.DONE
        running -> TaskIconState.LOADING
        else -> TaskIconState.IDLE
    }
    val activeName = justCompletedName ?: current?.content
    val label = when {
        allDone -> stringResource(R.string.chat_todo_bar_all_done)
        activeName != null -> activeName
        else -> stringResource(R.string.chat_message_todo_title)
    }
    val accent by animateColorAsState(
        targetValue = if (allDone) MaterialTheme.extendColors.green6 else MaterialTheme.colorScheme.primary,
        animationSpec = tween(320),
        label = "taskBarAccent",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                expandFrom = Alignment.Bottom,
            ) + fadeIn(tween(180)) + scaleIn(
                animationSpec = tween(200),
                initialScale = 0.94f,
                // 从贴近胶囊的一侧展开，像被图标“抽”出来
                transformOrigin = TransformOrigin(0.5f, 1f),
            ),
            exit = shrinkVertically(
                animationSpec = tween(180),
                shrinkTowards = Alignment.Bottom,
            ) + fadeOut(tween(120)),
        ) {
            TaskPanel(
                todos = displayTodos,
                completed = completedCount,
                total = total,
                accent = accent,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 2.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaskIconButton(
                state = iconState,
                progress = completedCount.toFloat() / total,
                expanded = expanded,
                onClick = { expanded = !expanded },
            )
            Spacer(Modifier.width(9.dp))
            AnimatedContent(
                targetState = label,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    // 新文案从下往上顶替旧文案
                    (slideInVertically(animationSpec = tween(220)) { it } +
                        fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.92f))
                        .togetherWith(
                            slideOutVertically(animationSpec = tween(160)) { -it } +
                                fadeOut(tween(120))
                        )
                        .using(SizeTransform(clip = false))
                },
                contentAlignment = Alignment.CenterStart,
                label = "taskBarLabel",
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (allDone) accent else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            AnimatedContent(
                targetState = completedCount,
                transitionSpec = {
                    (slideInVertically(animationSpec = tween(200)) { -it } + fadeIn(tween(200)))
                        .togetherWith(
                            slideOutVertically(animationSpec = tween(160)) { it } +
                                fadeOut(tween(120))
                        )
                        .using(SizeTransform(clip = false))
                },
                label = "taskBarCount",
            ) { done ->
                Text(
                    text = "$done/$total",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFeatureSettings = "tnum",
                    ),
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
            }
        }
    }
}

/** 展开后的清单面板：顶部一条进度线，下面是步骤列表 */
@Composable
private fun TaskPanel(
    todos: List<TodoEntry>,
    completed: Int,
    total: Int,
    accent: Color,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = completed.toFloat() / total,
        animationSpec = tween(520),
        label = "taskPanelProgress",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = accent,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
            TodoPlanContent(
                todos = todos,
                showHeader = false,
                modifier = Modifier
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun TaskIconButton(
    state: TaskIconState,
    progress: Float,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val description = stringResource(
        if (expanded) R.string.chat_message_todo_collapse else R.string.plan_bar_expand
    )
    val accent by animateColorAsState(
        targetValue = if (state == TaskIconState.DONE) {
            MaterialTheme.extendColors.green6
        } else {
            MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(320),
        label = "taskIconAccent",
    )
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(520),
        label = "taskIconProgress",
    )
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)

    // 完成瞬间轻微弹一下，作为“这一步收尾”的反馈
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(state) {
        if (state == TaskIconState.DONE) {
            pulse.animateTo(1.14f, tween(140))
            pulse.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        }
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(30.dp)
            .graphicsLayer {
                scaleX = pulse.value
                scaleY = pulse.value
            }
            .semantics { contentDescription = description },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize().padding(1.5.dp)) {
                val stroke = 2.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                if (animatedProgress > 0f) {
                    drawArc(
                        color = accent,
                        startAngle = -90f,
                        sweepAngle = 360f * animatedProgress,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }

            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    (fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.6f))
                        .togetherWith(fadeOut(tween(120)))
                },
                label = "taskIconState",
            ) { iconState ->
                Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                    when (iconState) {
                        TaskIconState.LOADING -> CircularProgressIndicator(
                            modifier = Modifier.fillMaxSize(),
                            strokeWidth = 1.8.dp,
                            color = accent,
                        )

                        TaskIconState.DONE -> Icon(
                            imageVector = HugeIcons.TaskDone01,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            tint = accent,
                        )

                        TaskIconState.IDLE -> Icon(
                            imageVector = HugeIcons.CheckList,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            tint = accent,
                        )
                    }
                }
            }
        }
    }
}
