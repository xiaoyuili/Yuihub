package me.yui.yuihub.ui.components.nav

import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.yui.yuihub.R
import me.yui.yuihub.ui.context.LocalNavController
import me.yui.yuihub.ui.theme.CustomColors

@Composable
fun BackButton(modifier: Modifier = Modifier) {
    val navController = LocalNavController.current
    FilledTonalIconButton(
        onClick = {
            navController.popBackStack()
        },
        modifier = modifier,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = CustomColors.listItemColors.containerColor),
    ) {
        Icon(
            imageVector = HugeIcons.ArrowLeft01,
            contentDescription = stringResource(R.string.back)
        )
    }
}
