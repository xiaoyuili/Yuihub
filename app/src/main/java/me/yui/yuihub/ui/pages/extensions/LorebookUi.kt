package me.yui.yuihub.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import me.rerere.hugeicons.stroke.Search01
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.yui.yuihub.R
import me.yui.yuihub.data.model.PromptInjection
import me.yui.yuihub.data.model.SelectiveLogic
import me.yui.yuihub.ui.components.ui.Tag
import me.yui.yuihub.ui.components.ui.TagType
import me.yui.yuihub.ui.theme.CustomColors

/** 世界书列表 / 条目列表共用的搜索框 */
@Composable
internal fun LorebookSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(HugeIcons.Search01, contentDescription = null, modifier = Modifier.size(20.dp))
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(HugeIcons.Cancel01, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(50),
        modifier = modifier.fillMaxWidth(),
    )
}

/** 世界书页面共用的分组卡片：小标题在卡片外（iOS 分组列表做法），内容在卡内 */
@Composable
internal fun LorebookSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CustomColors.cardColorsOnSurfaceContainer,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun getSelectiveLogicLabel(logic: SelectiveLogic): String = when (logic) {
    SelectiveLogic.AND_ANY -> stringResource(R.string.prompt_page_selective_logic_and_any)
    SelectiveLogic.AND_ALL -> stringResource(R.string.prompt_page_selective_logic_and_all)
    SelectiveLogic.NOT_ANY -> stringResource(R.string.prompt_page_selective_logic_not_any)
    SelectiveLogic.NOT_ALL -> stringResource(R.string.prompt_page_selective_logic_not_all)
}

/** 关键词列表编辑器（主键与次级键共用） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeywordEditor(
    label: String,
    keywords: List<String>,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    var draft by remember { mutableStateOf("") }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (keywords.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                keywords.forEach { keyword ->
                    InputChip(
                        selected = false,
                        onClick = {},
                        label = { Text(keyword) },
                        trailingIcon = {
                            IconButton(
                                onClick = { onChange(keywords - keyword) },
                                modifier = Modifier.size(16.dp),
                            ) {
                                Icon(HugeIcons.Cancel01, null, modifier = Modifier.size(12.dp))
                            }
                        },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.prompt_page_new_keyword)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            IconButton(
                onClick = {
                    if (draft.isNotBlank()) {
                        onChange(keywords + draft.trim())
                        draft = ""
                    }
                },
            ) {
                Icon(HugeIcons.Add01, stringResource(R.string.prompt_page_add))
            }
        }
    }
}

/** 条目标签行：一眼看出该条目启用了哪些高级特性 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LorebookEntryTags(
    entry: PromptInjection.RegexInjection,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (entry.constantActive) {
            Tag(type = TagType.INFO) { Text(stringResource(R.string.prompt_page_constant_active)) }
        }
        if (!entry.enabled) {
            Tag(type = TagType.WARNING) { Text(stringResource(R.string.prompt_page_disabled)) }
        }
        if (entry.useProbability && entry.probability < 100) {
            Tag(type = TagType.DEFAULT) { Text("${entry.probability}%") }
        }
        if (entry.group.isNotBlank()) {
            Tag(type = TagType.DEFAULT) { Text(entry.group) }
        }
        if (entry.sticky > 0) {
            Tag(type = TagType.DEFAULT) { Text("sticky ${entry.sticky}") }
        }
        if (entry.cooldown > 0) {
            Tag(type = TagType.DEFAULT) { Text("cd ${entry.cooldown}") }
        }
        if (entry.delay > 0) {
            Tag(type = TagType.DEFAULT) { Text("delay ${entry.delay}") }
        }
        if (entry.excludeRecursion || entry.preventRecursion || entry.delayUntilRecursion) {
            Tag(type = TagType.DEFAULT) { Text(stringResource(R.string.prompt_page_section_advanced)) }
        }
    }
}
