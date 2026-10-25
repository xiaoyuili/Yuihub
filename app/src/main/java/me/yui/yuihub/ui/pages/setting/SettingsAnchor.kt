package me.yui.yuihub.ui.pages.setting

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** 一次“搜索跳转定位”请求；带时间戳，避免页面没承接时下次进入又莫名闪一下 */
internal data class SettingsSearchFocusRequest(
    val anchorId: String,
    val requestedAt: Long = System.currentTimeMillis(),
)

internal object SettingsSearchFocus {
    /** 超过这个时间还没被任何页面承接就作废 */
    const val TTL_MS = 8_000L

    private val _pending = MutableStateFlow<SettingsSearchFocusRequest?>(null)
    val pending: StateFlow<SettingsSearchFocusRequest?> = _pending.asStateFlow()

    fun request(anchorId: String) {
        _pending.value = SettingsSearchFocusRequest(anchorId)
    }

    fun isActive(request: SettingsSearchFocusRequest?): Boolean =
        request != null && System.currentTimeMillis() - request.requestedAt < TTL_MS

    fun clear(anchorId: String) {
        _pending.compareAndSet(_pending.value?.takeIf { it.anchorId == anchorId }, null)
    }

    fun cancel() {
        _pending.value = null
    }
}

/**
 * 给设置行打锚点：被搜索结果命中时滚动到可见位置并闪一次高亮。
 *
 * 用法：`CardGroup { item(modifier = Modifier.settingsAnchor("appearance.message.chat_background"), ...) }`
 */
@Composable
internal fun Modifier.settingsAnchor(anchorId: String?): Modifier {
    if (anchorId == null) return this
    val requester = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    val highlightColor = MaterialTheme.colorScheme.primary
    var flash by remember { mutableStateOf(false) }
    var laidOut by remember { mutableStateOf(false) }

    val highlightAlpha by animateFloatAsState(
        targetValue = if (flash) 1f else 0f,
        animationSpec = tween(durationMillis = if (flash) 100 else 800),
        label = "settingsAnchorFlash",
    )

    LaunchedEffect(anchorId) {
        SettingsSearchFocus.pending.collect { pending ->
            if (pending?.anchorId != anchorId || !SettingsSearchFocus.isActive(pending)) return@collect
            // 等这一行完成布局再滚动，避免拿到旧的滚动容器尺寸
            withTimeoutOrNull(1_000) {
                while (!laidOut) delay(16)
            }
            runCatching { requester.bringIntoView() }
            SettingsSearchFocus.clear(anchorId)
            flash = true
            delay(1_000)
            flash = false
        }
    }

    return this
        .bringIntoViewRequester(requester)
        .onGloballyPositioned { laidOut = true }
        .drawWithContent {
            drawContent()
            if (highlightAlpha <= 0.01f) return@drawWithContent
            val radius = with(density) { 14.dp.toPx() }
            val inset = with(density) { 1.5.dp.toPx() }
            val stroke = with(density) { 2.dp.toPx() }
            val rectTopLeft = Offset(inset, inset)
            val rectSize = Size(
                width = (size.width - inset * 2).coerceAtLeast(0f),
                height = (size.height - inset * 2).coerceAtLeast(0f),
            )
            // 画在内容之上：卡片底色/行内容都是不透明的，drawBehind 会被整个盖住
            drawRoundRect(
                color = highlightColor.copy(alpha = 0.10f * highlightAlpha),
                topLeft = rectTopLeft,
                size = rectSize,
                cornerRadius = CornerRadius(radius, radius),
            )
            drawRoundRect(
                color = highlightColor.copy(alpha = 0.85f * highlightAlpha),
                topLeft = rectTopLeft,
                size = rectSize,
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = stroke),
            )
        }
}

/**
 * 懒列表页的定位辅助：目标行还没被组合出来时，逐项滚过去直到 [SettingsSearchFocus.pending] 被接住。
 * 设置页项目不多（一般 < 15 项），逐项探测的代价可以忽略；到底都没接住就放弃。
 *
 * @param pagePrefix 本页锚点前缀（如 `appearance.message.`），避免其它页面的 host 抢走待定位
 */
@Composable
internal fun SettingsAnchorHost(listState: LazyListState, pagePrefix: String) {
    val pending = SettingsSearchFocus.pending
    LaunchedEffect(listState, pagePrefix) {
        pending.collect { request ->
            if (request == null || !request.anchorId.startsWith(pagePrefix)) return@collect
            if (!SettingsSearchFocus.isActive(request)) {
                SettingsSearchFocus.cancel()
                return@collect
            }
            var index = 0
            while (SettingsSearchFocus.pending.value?.anchorId == request.anchorId) {
                val count = listState.layoutInfo.totalItemsCount
                if (count == 0) {
                    delay(32)
                    continue
                }
                if (index >= count) break
                listState.scrollToItem(index)
                // 给目标行一次组合与接住的机会
                delay(60)
                index++
            }
            if (SettingsSearchFocus.pending.value?.anchorId == request.anchorId) {
                SettingsSearchFocus.cancel()
            }
        }
    }
}
