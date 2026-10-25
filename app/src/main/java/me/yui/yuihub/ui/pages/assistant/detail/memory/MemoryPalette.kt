package me.yui.yuihub.ui.pages.assistant.detail.memory

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.hugeicons.stroke.Message02
import me.yui.yuihub.R
import me.yui.yuihub.data.model.MemoryCategory
import me.yui.yuihub.data.model.MemoryKind
import me.yui.yuihub.data.model.MemorySection
import me.yui.yuihub.data.model.MemoryStatus
import me.yui.yuihub.ui.theme.extendColors

@Composable
fun memoryKindLabel(kind: String): String = stringResource(
    when (kind) {
        MemoryKind.CORE -> R.string.memory_page_kind_core
        MemoryKind.PROJECT -> R.string.memory_page_kind_project
        MemoryKind.LESSON -> R.string.memory_page_kind_lesson
        MemoryKind.TOPIC -> R.string.memory_page_kind_topic
        else -> R.string.memory_page_kind_fact
    }
)

fun memoryKindIcon(kind: String): ImageVector = when (kind) {
    MemoryKind.CORE -> HugeIcons.Brain02
    MemoryKind.PROJECT -> HugeIcons.Folder01
    MemoryKind.LESSON -> HugeIcons.Alert01
    MemoryKind.TOPIC -> HugeIcons.Message02
    else -> HugeIcons.Idea01
}

@Composable
fun memoryKindColor(kind: String): Color {
    val colors = MaterialTheme.extendColors
    return when (kind) {
        MemoryKind.CORE -> MaterialTheme.colorScheme.primary
        MemoryKind.PROJECT -> colors.blue5
        MemoryKind.LESSON -> colors.orange5
        MemoryKind.TOPIC -> colors.red5
        else -> colors.green5
    }
}

@Composable
fun memoryKindTextColor(kind: String): Color {
    val colors = MaterialTheme.extendColors
    return when (kind) {
        MemoryKind.CORE -> MaterialTheme.colorScheme.primary
        MemoryKind.PROJECT -> colors.blue8
        MemoryKind.LESSON -> colors.orange8
        MemoryKind.TOPIC -> colors.red8
        else -> colors.green8
    }
}

@Composable
fun memoryStatusLabel(status: String): String = stringResource(
    when (MemoryStatus.normalize(status)) {
        MemoryStatus.STALE -> R.string.memory_page_status_stale
        MemoryStatus.ARCHIVED -> R.string.memory_page_status_archived
        else -> R.string.memory_page_status_active
    }
)

@Composable
fun memoryStatusColor(status: String): Color = when (MemoryStatus.normalize(status)) {
    MemoryStatus.STALE -> MaterialTheme.colorScheme.tertiary
    MemoryStatus.ARCHIVED -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.primary
}

@Composable
fun memorySectionLabel(section: String): String = stringResource(
    when (MemorySection.normalize(section)) {
        MemorySection.RULES -> R.string.memory_page_section_rules
        MemorySection.AI -> R.string.memory_page_section_ai
        else -> R.string.memory_page_section_user
    }
)

@Composable
fun memoryCategoryName(category: String): String = stringResource(
    when (MemoryCategory.normalize(category)) {
        MemoryCategory.PROFILE -> R.string.memory_page_category_profile
        MemoryCategory.PREFERENCE -> R.string.memory_page_category_preference
        MemoryCategory.CODING -> R.string.memory_page_category_coding
        MemoryCategory.ROLEPLAY -> R.string.memory_page_category_roleplay
        MemoryCategory.DAILY -> R.string.memory_page_category_daily
        MemoryCategory.TEMPORARY -> R.string.memory_page_category_temporary
        else -> R.string.memory_page_category_other
    }
)

@Composable
fun memoryCategoryColor(category: String): Color {
    val colors = MaterialTheme.extendColors
    return when (MemoryCategory.normalize(category)) {
        MemoryCategory.PROFILE -> colors.blue5
        MemoryCategory.PREFERENCE -> colors.red5
        MemoryCategory.CODING -> colors.green5
        MemoryCategory.ROLEPLAY -> colors.orange5
        MemoryCategory.DAILY -> colors.gray5
        MemoryCategory.TEMPORARY -> colors.orange3
        else -> colors.gray3
    }
}
