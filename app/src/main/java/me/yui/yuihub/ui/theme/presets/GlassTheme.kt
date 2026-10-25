package me.yui.yuihub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.yui.yuihub.R
import me.yui.yuihub.ui.theme.PresetTheme

/**
 * 拟态玻璃主题：页面本身提供柔和渐变底色，表面、卡片和气泡使用半透明色，
 * 输入栏的模糊开关仍然只控制输入栏。
 */
val GlassThemePreset by lazy {
    PresetTheme(
        id = "glass",
        name = {
            Text(stringResource(id = R.string.theme_name_glass))
        },
        standardLight = lightScheme,
        standardDark = darkScheme,
    )
}

private val primaryLight = Color(0xFF2563EB)
private val onPrimaryLight = Color(0xFFFFFFFF)
private val primaryContainerLight = Color(0xFFE3ECFF)
private val onPrimaryContainerLight = Color(0xFF173B82)
private val secondaryLight = Color(0xFF7C5CFC)
private val onSecondaryLight = Color(0xFFFFFFFF)
private val secondaryContainerLight = Color(0xFFEEE8FF)
private val onSecondaryContainerLight = Color(0xFF3E2A99)
private val tertiaryLight = Color(0xFF0F9F9A)
private val onTertiaryLight = Color(0xFFFFFFFF)
private val tertiaryContainerLight = Color(0xFFD9F7F5)
private val onTertiaryContainerLight = Color(0xFF0A5F5C)
private val errorLight = Color(0xFFD93A32)
private val onErrorLight = Color(0xFFFFFFFF)
private val errorContainerLight = Color(0xFFFDE7E5)
private val onErrorContainerLight = Color(0xFF8B1C17)
private val backgroundLight = Color.Transparent
private val onBackgroundLight = Color(0xFF111827)
private val surfaceLight = Color(0xFFFFFFFF)
private val onSurfaceLight = Color(0xFF111827)
private val surfaceVariantLight = Color(0xFFEDF1F8)
private val onSurfaceVariantLight = Color(0xFF475467)
private val outlineLight = Color(0xFFC7D2E5)
private val outlineVariantLight = Color(0xFFE2E8F2)
private val scrimLight = Color(0x66000000)
private val inverseSurfaceLight = Color(0xFF1F2937)
private val inverseOnSurfaceLight = Color(0xFFF9FAFB)
private val inversePrimaryLight = Color(0xFF93C5FD)
private val surfaceDimLight = Color(0xFFEAF2FF)
private val surfaceBrightLight = Color(0xFFFFFFFF)
private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
private val surfaceContainerLowLight = Color(0xFFF7F9FE)
private val surfaceContainerLight = Color(0xFFF1F5FC)
private val surfaceContainerHighLight = Color(0xFFE9EFF9)
private val surfaceContainerHighestLight = Color(0xFFDFE8F5)

private val primaryDark = Color(0xFF8AB4FF)
private val onPrimaryDark = Color(0xFF0A2A66)
private val primaryContainerDark = Color(0xFF243B66)
private val onPrimaryContainerDark = Color(0xFFDCE9FF)
private val secondaryDark = Color(0xFFB8A6FF)
private val onSecondaryDark = Color(0xFF281B58)
private val secondaryContainerDark = Color(0xFF2D2550)
private val onSecondaryContainerDark = Color(0xFFEDE6FF)
private val tertiaryDark = Color(0xFF5EEAD4)
private val onTertiaryDark = Color(0xFF073B38)
private val tertiaryContainerDark = Color(0xFF17413E)
private val onTertiaryContainerDark = Color(0xFFC9FFF9)
private val errorDark = Color(0xFFFFB4AB)
private val onErrorDark = Color(0xFF690005)
private val errorContainerDark = Color(0xFF4A1F1C)
private val onErrorContainerDark = Color(0xFFFFDAD6)
private val backgroundDark = Color.Transparent
private val onBackgroundDark = Color(0xFFF4F7FF)
private val surfaceDark = Color(0xFF151B26)
private val onSurfaceDark = Color(0xFFF4F7FF)
private val surfaceVariantDark = Color(0xFF26303F)
private val onSurfaceVariantDark = Color(0xFFC7D2E5)
private val outlineDark = Color(0xFF4A566B)
private val outlineVariantDark = Color(0xFF2B3545)
private val scrimDark = Color(0xFF000000)
private val inverseSurfaceDark = Color(0xFFF4F7FF)
private val inverseOnSurfaceDark = Color(0xFF1F2937)
private val inversePrimaryDark = Color(0xFF2563EB)
private val surfaceDimDark = Color(0xFF0B1220)
private val surfaceBrightDark = Color(0xFF33415A)
private val surfaceContainerLowestDark = Color(0xFF101620)
private val surfaceContainerLowDark = Color(0xFF161E2A)
private val surfaceContainerDark = Color(0xFF1B2432)
private val surfaceContainerHighDark = Color(0xFF222D3D)
private val surfaceContainerHighestDark = Color(0xFF2A3648)

private val lightScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
)

private val darkScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
)
