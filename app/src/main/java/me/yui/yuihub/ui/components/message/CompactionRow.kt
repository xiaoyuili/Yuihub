package me.yui.yuihub.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.yui.yuihub.ui.components.ui.flowRowMetaColor
import me.yui.yuihub.ui.components.ui.flowRowMetaStyle
import me.yui.yuihub.ui.components.ui.flowRowTitleStyle

// 自动压缩检查点的可见流程行（对齐 deepseek-harness：压缩在会话中留一行检查点记录）：
// [16dp 前导盒 14dp 字形] 6dp [标题 13sp 次要色] 8dp [2dp 分隔点] 8dp [摘要 13sp 三级色 单行 ellipsis]
@Composable
fun CompactionRow(title: String, summary: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = flowRowTitleStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .size(2.dp)
                .clip(CircleShape)
                .background(flowRowMetaColor()),
        )
        Text(
            text = summary,
            style = flowRowMetaStyle(),
            color = flowRowMetaColor(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
