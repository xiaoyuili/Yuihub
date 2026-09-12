package me.yui.yuihub.ui.components.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import kotlin.math.roundToInt

private val SwipeActionWidth = 88.dp

/** 强 ease-out：起手快、收尾稳（Apple 风格退出曲线） */
private val EaseOutExpo = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/** 快速轻扫判定速度（px/s）：超过它即使位移不大也吸附到展开位 */
private const val FlickVelocity = 800f

/** 划过容器宽度的这个比例直接提交删除，不必再点按钮 */
private const val CommitRatio = 0.55f

/** 越过边界后的跟随比例：越小越"拉不动"，模拟橡皮筋 */
private const val OverdragDamping = 0.32f

/**
 * iOS 风格左滑操作行。
 *
 * - 内容 1:1 跟手位移，松手按「位移 + 松手速度」决定吸附到展开位还是收回
 * - 弹簧衔接松手速度、可中途反向拖，不会撞墙
 * - 越过边界是渐进阻尼（橡皮筋），不是硬停
 * - 划过 [CommitRatio] 宽度直接提交删除，并给一次触觉反馈
 * - 已展开时点击内容先收回，不误触原有点击
 */
@Composable
fun SwipeToReveal(
    onDelete: () -> Unit,
    deleteLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val actionWidthPx = with(density) { SwipeActionWidth.toPx() }
    var containerWidth by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }

    // 只在布尔翻转时重组，避免每帧重建修饰符链
    val revealed by remember { derivedStateOf { offset < -1f } }

    fun clampDrag(delta: Float): Float {
        val next = offset + delta
        return when {
            // 往右拉过静止位：轻微橡皮筋
            next > 0f -> next * OverdragDamping
            // 往左拉过展开位：越拉越沉
            -next <= actionWidthPx -> next
            else -> {
                val over = -next - actionWidthPx
                -(actionWidthPx + over * OverdragDamping)
            }
        }
    }

    fun settleTo(target: Float, velocity: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(
                initialValue = offset,
                targetValue = target,
                initialVelocity = velocity,
                animationSpec = spring(
                    dampingRatio = 0.85f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ) { value, _ -> offset = value }
        }
    }

    fun commitDelete() {
        settleJob?.cancel()
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        settleJob = scope.launch {
            animate(
                initialValue = offset,
                targetValue = -(containerWidth + actionWidthPx).toFloat(),
                initialVelocity = 0f,
                animationSpec = tween(durationMillis = 200, easing = EaseOutExpo),
            ) { value, _ -> offset = value }
            onDelete()
        }
    }

    Box(modifier = modifier.onSizeChanged { containerWidth = it.width }) {
        // 露出的操作区（右侧红色实底）
        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(SwipeActionWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.error)
                    .clickable(enabled = enabled) { commitDelete() },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Delete01,
                        contentDescription = deleteLabel,
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = deleteLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onError,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                // 位移在布局阶段读取，滑动过程零重组
                .offset { IntOffset(offset.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    state = rememberDraggableState { delta -> offset = clampDrag(delta) },
                    onDragStarted = {
                        settleJob?.cancel()
                        settleJob = null
                    },
                    onDragStopped = { velocity ->
                        val commitThreshold = containerWidth * CommitRatio
                        when {
                            containerWidth > 0 && -offset >= commitThreshold -> commitDelete()
                            velocity < -FlickVelocity || -offset > actionWidthPx / 2f ->
                                settleTo(-actionWidthPx, velocity)

                            else -> settleTo(0f, velocity)
                        }
                    },
                )
                .then(
                    if (revealed) {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures { settleTo(0f, 0f) }
                        }
                    } else {
                        Modifier
                    }
                ),
        ) {
            content()
        }
    }
}
