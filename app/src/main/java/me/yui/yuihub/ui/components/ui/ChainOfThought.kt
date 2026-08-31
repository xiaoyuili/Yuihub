package me.yui.yuihub.ui.components.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronDown
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Sparkles
import me.yui.yuihub.R
import me.yui.yuihub.ui.context.LocalSettings

// 流程行的共享度量：单行 24dp 内容高 + 4dp 触摸余量，16dp 前导图标盒内放 14dp 字形，
// 展开体缩进 22dp（前导 16dp + 间距 6dp），使正文与标题文字左缘对齐。
private val FlowLeadingSize = 16.dp
private val FlowGlyphSize = 14.dp
private val FlowLeadingGap = 6.dp
private val FlowBodyIndent = 22.dp
private val FlowRowPaddingVertical = 4.dp

/**
 * 流程行标题层级：比正文小一档、24dp 行高、次要文本色，字重不加粗。
 *
 * 思考行与工具行共用该层级，保证折叠态下一串步骤读起来是同一种密度。
 */
@Composable
fun flowRowTitleStyle(): TextStyle {
    val ratio = LocalSettings.current.displaySetting.fontSizeRatio
    return LocalTextStyle.current.copy(
        fontSize = 13.sp * ratio,
        lineHeight = 24.sp * ratio,
        fontWeight = FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 流程行附加信息层级：与标题同字号，再暗一档，用于耗时、计数等次要值。 */
@Composable
fun flowRowMetaStyle(): TextStyle = flowRowTitleStyle().copy(
    color = flowRowMetaColor(),
)

/** [flowRowMetaStyle] 对应的颜色，供直接设置 `Icon.tint` 等场景复用。 */
@Composable
fun flowRowMetaColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)

/** 折叠控制条的文字层级：与正文同号，仅取次要文本色。 */
@Composable
private fun flowRowHeaderStyle(): TextStyle {
    val ratio = LocalSettings.current.displaySetting.fontSizeRatio
    return LocalTextStyle.current.copy(
        fontSize = 14.sp * ratio,
        lineHeight = 24.sp * ratio,
        fontWeight = FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 以扁平流程行的形式展示一组思考过程。
 *
 * 适用于承载推理步骤、工具调用步骤，或两者混合的链式内容。组件本身不绘制卡片背景，
 * 也不画时间线连线——步骤就是一串单行摘要，靠字号与颜色层级与正文区分：
 * - 在步骤较多时自动折叠，仅展示最后若干步
 * - 顶部控制条带一条分隔线，点击展开/收起全部步骤
 *
 * @param modifier 外层容器的修饰符
 * @param steps 需要渲染的步骤数据列表
 * @param collapsedVisibleCount 折叠时保留可见的尾部步骤数
 * @param content 每个步骤的具体 UI，由 [ChainOfThoughtScope] 提供步骤构建能力
 */
@Composable
fun <T> ChainOfThought(
    modifier: Modifier = Modifier,
    steps: List<T>,
    collapsedVisibleCount: Int = 2,
    content: @Composable ChainOfThoughtScope.(T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val canCollapse = steps.size > collapsedVisibleCount
    val visibleSteps = if (expanded || !canCollapse) steps else steps.takeLast(collapsedVisibleCount)
    val scope = remember { ChainOfThoughtScopeImpl() }

    Column(modifier = modifier.fillMaxWidth()) {
        if (canCollapse) {
            ChainOfThoughtHeader(
                expanded = expanded,
                hiddenCount = steps.size - collapsedVisibleCount,
                onToggle = { expanded = !expanded },
            )
        }
        visibleSteps.fastForEach { step ->
            scope.content(step)
        }
    }
}

@Composable
private fun ChainOfThoughtHeader(
    expanded: Boolean,
    hiddenCount: Int,
    onToggle: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(durationMillis = 100),
        label = "chain_of_thought_chevron",
    )
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onToggle)
                .padding(start = FlowLeadingGap, top = FlowRowPaddingVertical, bottom = FlowRowPaddingVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.ChevronDown,
                contentDescription = null,
                modifier = Modifier
                    .size(FlowLeadingSize)
                    .graphicsLayer { rotationZ = rotation },
                tint = flowRowMetaColor(),
            )
            Text(
                modifier = Modifier.padding(start = FlowLeadingGap),
                text = if (expanded) {
                    stringResource(R.string.chain_of_thought_collapse)
                } else {
                    stringResource(R.string.chain_of_thought_show_more_steps, hiddenCount)
                },
                style = flowRowHeaderStyle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * 标题与附加信息之间的分隔点，对应 Harness 流程行里那颗 2dp 小圆点。
 *
 * 只在附加信息是文本时使用；附加信息是按钮时不需要它。
 */
@Composable
fun FlowRowSeparator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(horizontal = 8.dp)
            .size(width = 2.dp, height = 2.dp)
            .clip(CircleShape)
            .background(flowRowMetaColor()),
    )
}

/**
 * [ChainOfThought] 内部使用的步骤渲染作用域。
 *
 * 通过该作用域可以声明单个步骤的图标、标题、附加信息以及可展开内容，
 * 并复用统一的单行布局与交互行为。
 */
interface ChainOfThoughtScope {
    /**
     * 声明一个非受控步骤，由组件内部管理展开/折叠状态。
     *
     * @param icon 步骤图标
     * @param label 步骤标题区域
     * @param extra 标题右侧的附加信息
     * @param onClick 自定义点击行为；设置后优先于展开/折叠逻辑
     * @param content 步骤展开后显示的内容；为 `null` 时步骤不可展开
     */
    @Composable
    fun ChainOfThoughtStep(
        icon: (@Composable () -> Unit)? = null,
        label: (@Composable () -> Unit),
        extra: (@Composable () -> Unit)? = null,
        onClick: (() -> Unit)? = null,
        content: (@Composable () -> Unit)? = null,
    )

    /**
     * 声明一个受控步骤，由外部传入展开状态。
     *
     * 适合需要与外部状态联动的场景，例如“推理中预览 / 完成后收起”。
     *
     * @param expanded 当前是否处于展开状态
     * @param onExpandedChange 展开状态变化回调
     * @param icon 步骤图标
     * @param label 步骤标题区域
     * @param extra 标题右侧的附加信息
     * @param onClick 自定义点击行为；设置后优先于展开/折叠逻辑
     * @param contentVisible 是否展示内容区域，可与 [expanded] 解耦
     * @param content 步骤内容；为 `null` 时步骤不可展开
     */
    @Composable
    fun ControlledChainOfThoughtStep(
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        icon: (@Composable () -> Unit)? = null,
        label: (@Composable () -> Unit),
        extra: (@Composable () -> Unit)? = null,
        onClick: (() -> Unit)? = null,
        contentVisible: Boolean = expanded,
        content: (@Composable () -> Unit)? = null,
    )
}

private class ChainOfThoughtScopeImpl : ChainOfThoughtScope {
    @Composable
    override fun ChainOfThoughtStep(
        icon: @Composable (() -> Unit)?,
        label: @Composable (() -> Unit),
        extra: @Composable (() -> Unit)?,
        onClick: (() -> Unit)?,
        content: @Composable (() -> Unit)?,
    ) {
        var expanded by remember { mutableStateOf(false) }
        ChainOfThoughtStepContent(
            icon = icon,
            label = label,
            extra = extra,
            onClick = onClick,
            expanded = expanded,
            onExpandedChange = { expanded = it },
            contentVisible = expanded,
            content = content,
        )
    }

    @Composable
    override fun ControlledChainOfThoughtStep(
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        icon: @Composable (() -> Unit)?,
        label: @Composable (() -> Unit),
        extra: @Composable (() -> Unit)?,
        onClick: (() -> Unit)?,
        contentVisible: Boolean,
        content: @Composable (() -> Unit)?,
    ) {
        ChainOfThoughtStepContent(
            icon = icon,
            label = label,
            extra = extra,
            onClick = onClick,
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            contentVisible = contentVisible,
            content = content,
        )
    }

    @Composable
    private fun ChainOfThoughtStepContent(
        icon: @Composable (() -> Unit)?,
        label: @Composable (() -> Unit),
        extra: @Composable (() -> Unit)?,
        onClick: (() -> Unit)?,
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        contentVisible: Boolean,
        content: @Composable (() -> Unit)?,
    ) {
        val hasContent = content != null
        val rowShape = MaterialTheme.shapes.small
        val chevronRotation by animateFloatAsState(
            targetValue = if (expanded) 0f else -90f,
            animationSpec = tween(durationMillis = 100),
            label = "chain_of_thought_step_chevron",
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onClick != null) {
                            Modifier
                                .clip(rowShape)
                                .clickable { onClick() }
                        } else if (hasContent) {
                            Modifier
                                .clip(rowShape)
                                .clickable { onExpandedChange(!expanded) }
                        } else {
                            Modifier
                        }
                    )
                    .padding(vertical = FlowRowPaddingVertical),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(FlowLeadingSize),
                    contentAlignment = Alignment.Center,
                ) {
                    if (icon != null) {
                        Box(
                            modifier = Modifier.size(FlowGlyphSize),
                            contentAlignment = Alignment.Center,
                        ) {
                            icon()
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(flowRowMetaColor()),
                        )
                    }
                }

                Box(modifier = Modifier.padding(start = FlowLeadingGap).weight(1f)) {
                    label()
                }

                if (extra != null) {
                    Box(modifier = Modifier.padding(start = 8.dp)) {
                        extra()
                    }
                }

                // 展开指示：可展开的行用旋转 chevron，只跳转详情的行用向右 chevron。
                // 前导图标始终保留工具身份（Harness 在展开时才把图标换成 chevron，
                // 而这里的工具行默认就是展开态，换掉会长期丢掉工具图标）。
                if (onClick != null) {
                    Icon(
                        imageVector = HugeIcons.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(FlowGlyphSize),
                        tint = flowRowMetaColor(),
                    )
                } else if (hasContent) {
                    Icon(
                        imageVector = HugeIcons.ChevronDown,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(FlowGlyphSize)
                            .graphicsLayer { rotationZ = chevronRotation },
                        tint = flowRowMetaColor(),
                    )
                }
            }

            if (contentVisible && hasContent) {
                Box(
                    modifier = Modifier.padding(
                        start = FlowBodyIndent,
                        top = FlowRowPaddingVertical,
                        bottom = FlowRowPaddingVertical,
                    ),
                ) {
                    content()
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChainOfThoughtPreview() {
    data class StepData(
        val label: String,
        val icon: ImageVector?,
        val status: String?,
        val hasContent: Boolean = false,
        val hasOnClick: Boolean = false,
        val controlled: Boolean = false,
    )

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text("Chain of thought")
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier.padding(innerPadding),
            ) {
                var controlledExpanded by remember { mutableStateOf(false) }

                ChainOfThought(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    steps = listOf(
                        StepData("Searching the web", HugeIcons.Search01, "3 results", hasContent = true),
                        StepData("Reading documents", HugeIcons.Sparkles, "Completed", hasOnClick = true),
                        StepData(
                            "Analyzing results (controlled)",
                            HugeIcons.Sparkles,
                            "In progress",
                            hasContent = true,
                            controlled = true,
                        ),
                        StepData("Step without icon", null, null),
                        StepData("Final step", HugeIcons.Sparkles, "Done"),
                    ),
                    collapsedVisibleCount = 2,
                ) { step ->
                    val iconComposable: (@Composable () -> Unit)? = step.icon?.let {
                        {
                            Icon(
                                imageVector = it,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                tint = flowRowMetaColor(),
                            )
                        }
                    }
                    val labelComposable: @Composable () -> Unit = {
                        Text(
                            text = step.label,
                            style = flowRowTitleStyle(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val extraComposable: (@Composable () -> Unit)? = step.status?.let {
                        {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FlowRowSeparator()
                                Text(
                                    text = it,
                                    style = flowRowMetaStyle(),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    val onClickHandler: (() -> Unit)? = if (step.hasOnClick) {
                        { /* Open detail sheet */ }
                    } else {
                        null
                    }
                    val contentComposable: (@Composable () -> Unit)? = if (step.hasContent) {
                        {
                            Text(
                                text = "Expanded body, indented to line up with the step title.",
                                style = flowRowMetaStyle(),
                            )
                        }
                    } else {
                        null
                    }

                    if (step.controlled) {
                        ControlledChainOfThoughtStep(
                            expanded = controlledExpanded,
                            onExpandedChange = { controlledExpanded = it },
                            icon = iconComposable,
                            label = labelComposable,
                            extra = extraComposable,
                            onClick = onClickHandler,
                            content = contentComposable,
                        )
                    } else {
                        ChainOfThoughtStep(
                            icon = iconComposable,
                            label = labelComposable,
                            extra = extraComposable,
                            onClick = onClickHandler,
                            content = contentComposable,
                        )
                    }
                }
            }
        }
    }
}
