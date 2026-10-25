package me.yui.yuihub.ui.components.ui

import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable

/**
 * 统一的可滚动弹层状态入口。
 *
 * 不在 sheet 状态层拦截 Hidden：点击遮罩、系统返回都应一次关闭；
 * 列表滚动优先由内容自身处理。
 */
@Composable
fun rememberGuardedModalBottomSheetState(
    skipPartiallyExpanded: Boolean = true,
): SheetState = rememberModalBottomSheetState(
    skipPartiallyExpanded = skipPartiallyExpanded,
)
