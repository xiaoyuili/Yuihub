package me.yui.yuihub.ui.components.ai

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Idea
import me.rerere.hugeicons.stroke.Idea01
import me.yui.yuihub.R
import me.yui.yuihub.ui.components.ui.ToggleSurface
import me.yui.yuihub.ui.components.ui.icons.ReasoningHigh
import me.yui.yuihub.ui.components.ui.icons.ReasoningLow
import me.yui.yuihub.ui.components.ui.icons.ReasoningMedium
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

// 内嵌式推理强度面板：放在输入框上方的布局槽位里渲染，
// 宽度与输入框一致。无底色无边框的轻量层，避免与输入框形成双色块
@Composable
fun ReasoningLevelPanel(
    reasoningLevel: ReasoningLevel,
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentIndex = levels.indexOf(reasoningLevel).coerceAtLeast(0)
    var sliderValue by remember { mutableFloatStateOf(currentIndex.toFloat()) }

    LaunchedEffect(currentIndex) {
        sliderValue = currentIndex.toFloat()
    }

    // 拖动时实时预览档位
    val previewLevel = levels[sliderValue.roundToInt().coerceIn(0, levelCount - 1)]

    Column(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 2.dp),
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
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = {
                val snappedIndex = sliderValue.roundToInt().coerceIn(0, levelCount - 1)
                sliderValue = snappedIndex.toFloat()
                onUpdateReasoningLevel(levels[snappedIndex])
            },
            valueRange = 0f..(levelCount - 1).toFloat(),
            steps = 0,
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                )
            }
        )
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

                ReasoningSlider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = {
                        val snappedIndex = sliderValue.roundToInt().coerceIn(0, levelCount - 1)
                        sliderValue = snappedIndex.toFloat()
                        onUpdateReasoningLevel(levels[snappedIndex])
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                )
            }
        }
    }
}

@Composable
private fun ReasoningSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = 0f..(levelCount - 1).toFloat(),
        steps = levelCount - 2,
        modifier = modifier,
        thumb = {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onPrimary)
                )
            }
        },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                drawStopIndicator = null,
                thumbTrackGapSize = 0.dp,
            )
        }
    )
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
