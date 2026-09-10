package me.yui.yuihub.ui.components.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.ui.theme.LocalDarkMode
import kotlin.math.roundToInt

object FloatingBottomBarDefaults {
    val Height = 56.dp
    val Margin = 12.dp

    /** 列表内容底部为悬浮栏预留的间距 */
    val ContentBottom = 80.dp

    /** 悬浮工具条 (HorizontalFloatingToolbar) 需上移的偏移量，使其位于悬浮栏上方 */
    val ToolbarOffset = 84.dp

    /** 与悬浮工具条同屏时，列表底部预留的间距（84 偏移 + 64 工具条高 + 20 间隙） */
    val ToolbarContentBottom = 168.dp
}

data class FloatingBottomBarTab(
    val icon: ImageVector,
    val label: String,
)

/**
 * iOS 风格悬浮底部导航栏：圆角矩形悬浮于内容之上。
 * 高亮块连续跟随 pager 滑动（点按与手势滑页都保持同步），选中图标轻微上浮放大。
 */
@Composable
fun FloatingBottomBar(
    pagerState: PagerState,
    tabs: List<FloatingBottomBarTab>,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val highlightAlpha = if (LocalDarkMode.current) 0.28f else 0.15f
    val scope = rememberCoroutineScope()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = FloatingBottomBarDefaults.Margin)
            .height(FloatingBottomBarDefaults.Height),
        shape = RoundedCornerShape(18.dp),
        color = CustomColors.cardColorsOnSurfaceContainer.containerColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        shadowElevation = 6.dp,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(5.dp),
        ) {
            val tabWidth = maxWidth / tabs.size
            val knobShape = RoundedCornerShape(13.dp)

            // 高亮块连续跟随 pager 滑动，点按与手势滑动都保持同步
            Box(
                modifier = Modifier
                    .offset {
                        val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
                        val clamped = position.coerceIn(0f, (tabs.size - 1).toFloat())
                        IntOffset(x = (tabWidth.toPx() * clamped).roundToInt(), y = 0)
                    }
                    .width(tabWidth)
                    .fillMaxHeight()
                    .clip(knobShape)
                    .background(accent.copy(alpha = highlightAlpha))
                    .border(1.dp, accent.copy(alpha = 0.18f), knobShape),
            )

            Row(modifier = Modifier.fillMaxSize()) {
                tabs.forEachIndexed { index, tab ->
                    FloatingBottomBarItem(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        icon = tab.icon,
                        label = tab.label,
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.FloatingBottomBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
) {
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "tabColor",
    )
    val lift by animateDpAsState(
        targetValue = if (selected) (-2).dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "tabLift",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "tabScale",
    )

    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(13.dp))
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    translationY = lift.toPx()
                    scaleX = scale
                    scaleY = scale
                },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
        )
    }
}
