package me.yui.yuihub.ui.pages.chat

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Forward02
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.yui.yuihub.R
import me.yui.yuihub.data.model.Conversation
import me.yui.yuihub.ui.theme.extendColors
import me.yui.yuihub.utils.toLocalString
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * Represents different types of items in the conversation list
 */
sealed class ConversationListItem {
    data class DateHeader(
        val date: LocalDate,
        val label: String
    ) : ConversationListItem()
    data object PinnedHeader : ConversationListItem()
    data class Item(
        val conversation: Conversation
    ) : ConversationListItem()
}

/** 子代理行的运行状态：运行中（绿色呼吸竖条） / 已结束（灰色竖条） */
enum class SubagentRunState { RUNNING, DONE }

@Composable
fun ColumnScope.ConversationList(
    current: Conversation,
    conversations: LazyPagingItems<ConversationListItem>,
    conversationJobs: Collection<Uuid>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    subconversationCounts: Map<Uuid, Int> = emptyMap(),
    expandedSubagentIds: Set<Uuid> = emptySet(),
    runningSubagentIds: Set<Uuid> = emptySet(),
    subconversationsLoader: (Uuid) -> Flow<List<Conversation>> = { flowOf(emptyList()) },
    onToggleSubagentExpand: (Uuid) -> Unit = {},
    onClickSubagent: (Conversation) -> Unit = {},
    onRequestDeleteSubagent: (Conversation) -> Unit = {},
    onClick: (Conversation) -> Unit = {},
    onDelete: (Conversation) -> Unit = {},
    onRegenerateTitle: (Conversation) -> Unit = {},
    onPin: (Conversation) -> Unit = {},
    onMoveToAssistant: (Conversation) -> Unit = {},
    onMoveToFolder: (Conversation) -> Unit = {}
) {
    var hasScrolledToCurrent by remember(current.id) { mutableStateOf(false) }

    LaunchedEffect(current.id, conversations.itemCount, hasScrolledToCurrent) {
        if (hasScrolledToCurrent) return@LaunchedEffect
        val currentIndex = conversations.itemSnapshotList.items.indexOfFirst {
            (it as? ConversationListItem.Item)?.conversation?.id == current.id
        }
        if (currentIndex >= 0) {
            val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == currentIndex }
            if (!isVisible) {
                listState.scrollToItem(currentIndex)
            }
            hasScrolledToCurrent = true
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (conversations.itemCount == 0) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Text(
                        text = stringResource(id = R.string.chat_page_no_conversations),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        items(
            count = conversations.itemCount,
            key = conversations.itemKey { item ->
                when (item) {
                    is ConversationListItem.DateHeader -> "date_${item.date}"
                    is ConversationListItem.PinnedHeader -> "pinned_header"
                    is ConversationListItem.Item -> item.conversation.id.toString()
                }
            }
        ) { index ->
            when (val item = conversations[index]) {
                is ConversationListItem.DateHeader -> {
                    DateHeaderItem(
                        label = item.label,
                        modifier = Modifier.animateItem()
                    )
                }

                is ConversationListItem.PinnedHeader -> {
                    PinnedHeader(
                        modifier = Modifier.animateItem()
                    )
                }

                is ConversationListItem.Item -> {
                    ConversationItem(
                        conversation = item.conversation,
                        selected = item.conversation.id == current.id,
                        loading = item.conversation.id in conversationJobs,
                        showDivider = index < conversations.itemCount - 1 &&
                            conversations[index + 1] is ConversationListItem.Item,
                        subagentCount = subconversationCounts[item.conversation.id] ?: 0,
                        subagentExpanded = item.conversation.id in expandedSubagentIds,
                        runningSubagentIds = runningSubagentIds,
                        currentId = current.id,
                        subconversationsLoader = subconversationsLoader,
                        onToggleSubagentExpand = onToggleSubagentExpand,
                        onClickSubagent = onClickSubagent,
                        onRequestDeleteSubagent = onRequestDeleteSubagent,
                        onClick = onClick,
                        onDelete = onDelete,
                        onRegenerateTitle = onRegenerateTitle,
                        onPin = onPin,
                        onMoveToAssistant = onMoveToAssistant,
                        onMoveToFolder = onMoveToFolder,
                        modifier = Modifier.animateItem()
                    )
                }

                null -> {
                    // Placeholder for loading state
                }
            }
        }
    }
}

@Composable
private fun DateHeaderItem(
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun PinnedHeader(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = HugeIcons.Pin,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.pinned_chats),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    selected: Boolean,
    loading: Boolean,
    showDivider: Boolean,
    subagentCount: Int,
    subagentExpanded: Boolean,
    runningSubagentIds: Set<Uuid>,
    currentId: Uuid,
    modifier: Modifier = Modifier,
    subconversationsLoader: (Uuid) -> Flow<List<Conversation>> = { flowOf(emptyList()) },
    onToggleSubagentExpand: (Uuid) -> Unit = {},
    onClickSubagent: (Conversation) -> Unit = {},
    onRequestDeleteSubagent: (Conversation) -> Unit = {},
    onDelete: (Conversation) -> Unit = {},
    onRegenerateTitle: (Conversation) -> Unit = {},
    onPin: (Conversation) -> Unit = {},
    onMoveToAssistant: (Conversation) -> Unit = {},
    onMoveToFolder: (Conversation) -> Unit = {},
    onClick: (Conversation) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focusManager = LocalFocusManager.current
    // 只有选中的对话显示卡片（实底浮起）；未选中的透明、靠分隔线区隔。
    // 选中底色用中性 surface 而非 surfaceColorAtElevation——后者叠加 M3 的主色 elevation tint，
    // 在浅色主题下呈蓝紫；shadow 必须在 background 之前声明，否则阴影画在底色之上成灰环。
    val cardShape = RoundedCornerShape(14.dp)
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        Color.Transparent
    }
    var showDropdownMenu by remember {
        mutableStateOf(false)
    }
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (selected) Modifier.shadow(2.dp, cardShape) else Modifier)
                .clip(cardShape)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = { onClick(conversation) },
                    onLongClick = {
                        // Also clear chat input focus when the drawer is permanently visible.
                        focusManager.clearFocus(force = true)
                        showDropdownMenu = true
                    }
                )
                .background(backgroundColor, cardShape),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conversation.title.ifBlank { stringResource(id = R.string.chat_page_new_message) },
                    style = if (selected) {
                        MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))

                // 置顶图标
                AnimatedVisibility(conversation.isPinned) {
                    Icon(
                        imageVector = HugeIcons.Pin,
                        contentDescription = "Pinned",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                AnimatedVisibility(loading) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.extendColors.green6)
                            .size(4.dp)
                            .semantics {
                                contentDescription = "Loading"
                            }
                    )
                }
                // 子代理展开箭头：仅有子代理时显示，裸图标无按钮底，旋转过渡 折叠朝右→展开朝下
                AnimatedVisibility(subagentCount > 0) {
                    val rotation by animateFloatAsState(
                        targetValue = if (subagentExpanded) 90f else 0f,
                        animationSpec = tween(
                            durationMillis = 280,
                            easing = FastOutSlowInEasing
                        ),
                        label = "subagentChevron",
                    )
                    Icon(
                        imageVector = HugeIcons.ArrowRight01,
                        contentDescription = "Subagents",
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onToggleSubagentExpand(conversation.id) }
                            .padding(1.dp)
                            .graphicsLayer { rotationZ = rotation },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = showDropdownMenu,
                    onDismissRequest = { showDropdownMenu = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (conversation.isPinned) stringResource(R.string.unpin_chat) else stringResource(R.string.pin_chat)
                            )
                        },
                        onClick = {
                            onPin(conversation)
                            showDropdownMenu = false
                        },
                        leadingIcon = {
                            Icon(
                                if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                                null
                            )
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(stringResource(id = R.string.chat_page_regenerate_title))
                        },
                        onClick = {
                            onRegenerateTitle(conversation)
                            showDropdownMenu = false
                        },
                        leadingIcon = {
                            Icon(HugeIcons.Refresh01, null)
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.chat_page_move_to_assistant))
                        },
                        onClick = {
                            onMoveToAssistant(conversation)
                            showDropdownMenu = false
                        },
                        leadingIcon = {
                            Icon(HugeIcons.Forward02, null)
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.chat_page_move_to_folder))
                        },
                        onClick = {
                            onMoveToFolder(conversation)
                            showDropdownMenu = false
                        },
                        leadingIcon = {
                            Icon(HugeIcons.Folder01, null)
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(stringResource(id = R.string.chat_page_delete))
                        },
                        onClick = {
                            onDelete(conversation)
                            showDropdownMenu = false
                        },
                        leadingIcon = {
                            Icon(HugeIcons.Delete01, null)
                        }
                    )
                }
            }
        }

        // 子代理列表：展开时随高度过渡渐次滑出，字号小于主行以示层级
        AnimatedVisibility(
            visible = subagentExpanded && subagentCount > 0,
            enter = expandVertically(
                animationSpec = tween(280, easing = FastOutSlowInEasing)
            ) + fadeIn(tween(220)),
            exit = shrinkVertically(
                animationSpec = tween(240, easing = FastOutSlowInEasing)
            ) + fadeOut(tween(160)),
        ) {
            Column(
                modifier = Modifier.padding(start = 18.dp, bottom = 4.dp),
            ) {
                var subs by remember(conversation.id) { mutableStateOf<List<Conversation>>(emptyList()) }
                LaunchedEffect(conversation.id, subagentCount) {
                    subconversationsLoader(conversation.id).collect { subs = it }
                }
                subs.forEach { sub ->
                    SubagentRow(
                        conversation = sub,
                        running = sub.id in runningSubagentIds,
                        selected = sub.id == currentId,
                        onClick = { onClickSubagent(sub) },
                        onLongClick = { onRequestDeleteSubagent(sub) },
                    )
                }
            }
        }

        // 对话间分隔线：短横线（不全宽），只在下一个条目仍是对话时显示
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier
                    .padding(start = 24.dp, end = 56.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            )
        }
    }
}

@Composable
private fun SubagentRow(
    conversation: Conversation,
    running: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp)
    } else {
        Color.Transparent
    }
    // 运行中竖条呼吸；结束后固定不透明度
    val pulseAlpha: Float by if (running) {
        rememberInfiniteTransition(label = "subagentPulse").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(600),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "subagentPulseAlpha",
        )
    } else {
        remember { mutableStateOf(1f) }
    }
    val barColor = if (running) {
        MaterialTheme.extendColors.green6
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(background)
            .padding(start = 10.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .padding(end = 8.dp)
                .size(width = 2.5.dp, height = 16.dp)
                .background(
                    color = barColor.copy(alpha = barColor.alpha * pulseAlpha),
                    shape = RoundedCornerShape(50),
                )
        )
        Text(
            text = conversation.title.ifBlank { stringResource(id = R.string.chat_page_new_message) },
            style = MaterialTheme.typography.labelMedium,
            color = if (running) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
