package me.yui.yuihub.ui.components.ai

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Idea
import me.rerere.hugeicons.stroke.Idea01
import me.yui.yuihub.R
import me.yui.yuihub.ui.components.ui.ToggleSurface
import me.yui.yuihub.ui.components.ui.icons.ReasoningHigh
import me.yui.yuihub.ui.components.ui.icons.ReasoningLow
import me.yui.yuihub.ui.components.ui.icons.ReasoningMedium
import me.yui.yuihub.ui.theme.LocalDarkMode
import kotlin.math.abs
import kotlin.math.roundToInt

private val levels = ReasoningLevel.entries
private val levelCount = levels.size

@Composable
fun ReasoningButton(
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    reasoningLevel: ReasoningLevel,
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
    showExternalPopup: Boolean = false,
    onShowExternalPopup: () -> Unit = {},
) {
    if (showExternalPopup) {
        // 外部面板模式：点击只切换外部状态，面板由调用方内嵌在输入框上方渲染
        ToggleSurface(
            checked = reasoningLevel.isEnabled,
            onClick = onShowExternalPopup,
            modifier = modifier,
        ) {
            ReasoningButtonContent(onlyIcon = onlyIcon, reasoningLevel = reasoningLevel)
        }
        return
    }

    var showPicker by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        ToggleSurface(
            checked = reasoningLevel.isEnabled,
            onClick = { showPicker = true },
        ) {
            ReasoningButtonContent(onlyIcon = onlyIcon, reasoningLevel = reasoningLevel)
        }

        ReasoningLevelPopup(
            expanded = showPicker,
            onDismissRequest = { showPicker = false },
            reasoningLevel = reasoningLevel,
            onUpdateReasoningLevel = onUpdateReasoningLevel,
        )
    }
}

@Composable
private fun ReasoningButtonContent(
    onlyIcon: Boolean,
    reasoningLevel: ReasoningLevel,
) {
    Row(
        modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center
        ) {
            ReasoningIcon(reasoningLevel)
        }
        if (!onlyIcon) Text(stringResource(R.string.setting_provider_page_reasoning))
    }
}

// 内嵌式推理强度面板：放在计划条与输入框之间渲染，宽度与输入框一致。
// 卡片样式与计划条统一（圆角 18 + surfaceContainerLow + 细描边）；
// [alpha] 与输入栏共用同一条淡化动画，拖动消息列表时一起半透明
@Composable
fun ReasoningLevelPanel(
    reasoningLevel: ReasoningLevel,
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
) {
    val currentIndex = levels.indexOf(reasoningLevel).coerceAtLeast(0)
    var sliderValue by remember { mutableFloatStateOf(currentIndex.toFloat()) }

    LaunchedEffect(currentIndex) {
        sliderValue = currentIndex.toFloat()
    }

    // 拖动时实时预览档位
    val previewLevel = levels[sliderValue.roundToInt().coerceIn(0, levelCount - 1)]

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.reasoning_picker_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = previewLevel.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            ReasoningTickSlider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    val snappedIndex = sliderValue.roundToInt().coerceIn(0, levelCount - 1)
                    sliderValue = snappedIndex.toFloat()
                    onUpdateReasoningLevel(levels[snappedIndex])
                },
                modifier = Modifier
                    .fillMaxWidth(),
            )
        }
    }
}

/**
 * 方案 07「聚焦字号」刻度滑块：
 * 轨道 + 渐变填充（primaryContainer → primary）+ 白芯圆形手柄；
 * 下方每个档位一个标签，按与当前档的距离呈现字号/颜色梯度（近大远小）。
 * 拖动跟手、松手吸附到最近档位；按下即跳档。颜色全部取自 MaterialTheme，深浅主题自适应。
 */
@Composable
private fun ReasoningTickSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = LocalDarkMode.current
    val trackColor = colorScheme.surfaceContainerHighest
    val fillStart = colorScheme.primaryContainer
    val fillEnd = colorScheme.primary
    val thumbFill = if (isDark) colorScheme.onSurface else colorScheme.surface
    val thumbRing = if (isDark) colorScheme.onSurface.copy(alpha = 0.35f) else colorScheme.outlineVariant
    // 刻度标签固定英文（与档位的参数值一致，None/Auto/Low/…/Max）；
    // 右上角的当前档位预览仍用本地化文案
    val labels = listOf("None", "Auto", "Low", "Medium", "High", "XHigh", "Max")
    val fraction = (value / (levelCount - 1)).coerceIn(0f, 1f)
    val currentIndex = value.roundToInt().coerceIn(0, levelCount - 1)
    val inset = 12.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                val insetPx = inset.toPx()
                fun commit(posX: Float) {
                    val usable = (size.width - insetPx * 2).coerceAtLeast(1f)
                    val raw = ((posX - insetPx) / usable).coerceIn(0f, 1f)
                    onValueChange(raw * (levelCount - 1))
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    commit(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        commit(change.position.x)
                        change.consume()
                    }
                    onValueChangeFinished()
                }
            }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value,
                    range = 0f..(levelCount - 1).toFloat(),
                    steps = levelCount - 2,
                )
                setProgress { target ->
                    onValueChange(target)
                    onValueChangeFinished()
                    true
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .drawBehind {
                    val insetPx = inset.toPx()
                    val trackHeight = 4.dp.toPx()
                    val top = (size.height - trackHeight) / 2f
                    val left = insetPx
                    val right = size.width - insetPx
                    val corner = CornerRadius(trackHeight / 2f)

                    drawRoundRect(
                        color = trackColor,
                        topLeft = Offset(left, top),
                        size = Size(right - left, trackHeight),
                        cornerRadius = corner,
                    )
                    // 渐变范围限于填充段自身：起点→头部，两端颜色与填充段一致
                    val fillWidth = (right - left) * fraction
                    if (fillWidth > 0f) {
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(fillStart, fillEnd),
                                startX = left,
                                endX = left + fillWidth,
                            ),
                            topLeft = Offset(left, top),
                            size = Size(fillWidth, trackHeight),
                            cornerRadius = corner,
                        )
                    }

                    // 手柄：柔影 + 圆芯 + 细描边（深色主题下圆芯用亮色保证对比）
                    val center = Offset(left + (right - left) * fraction, size.height / 2f)
                    val thumbRadius = 9.dp.toPx()
                    if (!isDark) {
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.10f),
                            radius = thumbRadius,
                            center = Offset(center.x, center.y + 1.dp.toPx()),
                        )
                    }
                    drawCircle(color = thumbFill, radius = thumbRadius, center = center)
                    drawCircle(color = thumbRing, radius = thumbRadius, center = center, style = Stroke(1.dp.toPx()))
                },
        )
        Layout(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            content = {
                labels.forEachIndexed { index, label ->
                    val distance = abs(index - currentIndex)
                    Text(
                        text = label,
                        color = when (distance) {
                            0 -> colorScheme.primary
                            1 -> colorScheme.onSurfaceVariant
                            else -> colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        },
                        fontSize = when (distance) {
                            0 -> 12.5.sp
                            1 -> 10.5.sp
                            else -> 9.5.sp
                        },
                        fontWeight = when (distance) {
                            0 -> FontWeight.ExtraBold
                            1 -> FontWeight.SemiBold
                            else -> FontWeight.Medium
                        },
                        maxLines = 1,
                    )
                }
            },
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(Constraints()) }
            val width = constraints.maxWidth
            val insetPx = inset.roundToPx()
            val rowHeight = placeables.maxOfOrNull { it.height } ?: 0
            layout(width, rowHeight) {
                placeables.forEachIndexed { index, placeable ->
                    val center = insetPx + (index / (levelCount - 1f)) * (width - 2 * insetPx)
                    placeable.place(
                        x = (center - placeable.width / 2f).roundToInt(),
                        y = (rowHeight - placeable.height) / 2,
                    )
                }
            }
        }
    }
}

// 弹窗形态（设置页/助手页等中部按钮用）：底部抽屉 + 滑块。
// 旧版用 DropdownMenu 锚定按钮，宽度随内容收缩，在设置页会遮挡卡片、
// 被屏幕边缘裁切；改用 ModalBottomSheet 全宽展示，滑块交互不变
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReasoningLevelPopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    reasoningLevel: ReasoningLevel,
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
) {
    val currentIndex = levels.indexOf(reasoningLevel).coerceAtLeast(0)
    var sliderValue by remember { mutableFloatStateOf(currentIndex.toFloat()) }

    LaunchedEffect(currentIndex) {
        sliderValue = currentIndex.toFloat()
    }

    if (expanded) {
        ModalBottomSheet(onDismissRequest = onDismissRequest) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val iconColor by animateColorAsState(
                    if (reasoningLevel.isEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = when (reasoningLevel) {
                            ReasoningLevel.OFF -> HugeIcons.Idea
                            ReasoningLevel.AUTO -> HugeIcons.Idea01
                            ReasoningLevel.LOW -> ReasoningLow
                            ReasoningLevel.MEDIUM -> ReasoningMedium
                            ReasoningLevel.HIGH -> ReasoningHigh
                            ReasoningLevel.XHIGH -> ReasoningHigh
                            ReasoningLevel.MAX -> ReasoningHigh
                        },
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = iconColor,
                    )
                    Text(
                        text = reasoningLevel.label(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                ReasoningTickSlider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = {
                        val snappedIndex = sliderValue.roundToInt().coerceIn(0, levelCount - 1)
                        sliderValue = snappedIndex.toFloat()
                        onUpdateReasoningLevel(levels[snappedIndex])
                    },
                    modifier = Modifier
                        .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ReasoningIcon(level: ReasoningLevel) {
    when (level) {
        ReasoningLevel.OFF -> Icon(HugeIcons.Idea, null)
        ReasoningLevel.AUTO -> Icon(HugeIcons.Idea01, null)
        ReasoningLevel.LOW -> Icon(ReasoningLow, null)
        ReasoningLevel.MEDIUM -> Icon(ReasoningMedium, null)
        ReasoningLevel.HIGH -> Icon(ReasoningHigh, null)
        ReasoningLevel.XHIGH -> Icon(ReasoningHigh, null)
        ReasoningLevel.MAX -> Icon(ReasoningHigh, null)
    }
}

@Composable
private fun ReasoningLevel.label(): String = when (this) {
    ReasoningLevel.OFF -> stringResource(R.string.reasoning_off)
    ReasoningLevel.AUTO -> stringResource(R.string.reasoning_auto)
    ReasoningLevel.LOW -> stringResource(R.string.reasoning_light)
    ReasoningLevel.MEDIUM -> stringResource(R.string.reasoning_medium)
    ReasoningLevel.HIGH -> stringResource(R.string.reasoning_heavy)
    ReasoningLevel.XHIGH -> stringResource(R.string.reasoning_xhigh)
    ReasoningLevel.MAX -> stringResource(R.string.reasoning_max)
}
