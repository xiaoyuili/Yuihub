package me.yui.yuihub.ui.pages.setting

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sun01
import me.yui.yuihub.R
import me.yui.yuihub.Screen
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.ui.CardGroup
import me.yui.yuihub.ui.context.LocalNavController
import me.yui.yuihub.ui.hooks.rememberColorMode
import me.yui.yuihub.ui.theme.ColorMode
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.ui.theme.EntryAccents
import me.yui.yuihub.utils.plus

@Composable
fun SettingAppearancePage() {
    val navController = LocalNavController.current
    var colorMode by rememberColorMode()
    val selectedColorModeText = when (colorMode) {
        ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
        ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
        ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_appearance))
                },
                navigationIcon = {
                    BackButton()
                },
                colors = CustomColors.pageTopBarColors
            )
        },
        containerColor = CustomColors.pageBackground
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                AppearanceCard(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_page_color_mode),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                    Text(
                        text = selectedColorModeText,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    SegmentedPill(
                        options = ColorMode.entries,
                        selectedOption = colorMode,
                        onOptionSelected = { colorMode = it },
                        optionToString = {
                            when (it) {
                                ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_follow)
                                ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                                ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingPreferencesTheme) },
                        leadingContent = { SettingIcon(HugeIcons.Sun01, color = EntryAccents.Violet) },
                        headlineContent = { Text(stringResource(R.string.setting_page_preferences_theme)) },
                        supportingContent = { Text(stringResource(R.string.setting_appearance_theme_desc)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingAppearanceMessage) },
                        leadingContent = { SettingIcon(HugeIcons.Message02, color = EntryAccents.Blue) },
                        headlineContent = { Text(stringResource(R.string.setting_appearance_message_ui)) },
                        supportingContent = { Text(stringResource(R.string.setting_appearance_message_ui_desc)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingAppearanceTypography) },
                        leadingContent = { SettingIcon(HugeIcons.Code, color = EntryAccents.Amber) },
                        headlineContent = { Text(stringResource(R.string.setting_appearance_fonts_and_code)) },
                        supportingContent = { Text(stringResource(R.string.setting_appearance_fonts_and_code_desc)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingAppearanceInteraction) },
                        leadingContent = { SettingIcon(HugeIcons.Settings03, color = EntryAccents.Teal) },
                        headlineContent = { Text(stringResource(R.string.setting_appearance_interaction)) },
                        supportingContent = { Text(stringResource(R.string.setting_appearance_interaction_desc)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.setting_appearance_preview),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 3.dp, bottom = 7.dp),
                )
                AppearanceCard(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = HugeIcons.PaintBoard,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PreviewBar(widthFraction = 0.56f, height = 9.dp, strong = true)
                            PreviewBar(widthFraction = 0.86f, height = 9.dp)
                            PreviewBar(widthFraction = 0.44f, height = 9.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = CustomColors.groupedCardSurface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            content = content,
        )
    }
}

@Composable
private fun PreviewBar(
    widthFraction: Float,
    height: androidx.compose.ui.unit.Dp,
    strong: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(
                if (strong) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
    )
}

/**
 * 分段胶囊控件：三等分轨道 + 滑块，点击某段后滑块动画滑到该位置。
 */
@Composable
internal fun <T> SegmentedPill(
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    optionToString: @Composable (T) -> String,
    modifier: Modifier = Modifier,
) {
    val trackShape = RoundedCornerShape(50)
    val knobShape = RoundedCornerShape(50)
    Surface(
        modifier = modifier.height(32.dp),
        shape = trackShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp),
        ) {
            val count = options.size.coerceAtLeast(1)
            val knobWidth = maxWidth / count
            val selectedIndex = options.indexOf(selectedOption).coerceAtLeast(0)
            val knobOffset by animateDpAsState(
                targetValue = knobWidth * selectedIndex,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 900f),
                label = "segmentedPillKnob",
            )

            Box(
                modifier = Modifier
                    .offset(x = knobOffset)
                    .width(knobWidth)
                    .fillMaxHeight()
                    .shadow(2.dp, knobShape)
                    .clip(knobShape)
                    .background(MaterialTheme.colorScheme.surfaceBright),
            )

            Row(modifier = Modifier.fillMaxSize()) {
                options.fastForEach { option ->
                    val selected = option == selectedOption
                    val contentColor by animateColorAsState(
                        targetValue = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        },
                        animationSpec = tween(150),
                        label = "segmentedPillColor",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(knobShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onOptionSelected(option) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = optionToString(option),
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
