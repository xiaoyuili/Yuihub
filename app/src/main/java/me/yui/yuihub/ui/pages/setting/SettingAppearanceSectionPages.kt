package me.yui.yuihub.ui.pages.setting

import androidx.compose.runtime.Composable
import me.yui.yuihub.R
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingAppearanceMessagePage(vm: SettingVM = koinViewModel()) {
    SettingPreferencesUIPage(vm = vm, section = UiSettingsSection.MESSAGE)
}

@Composable
fun SettingAppearanceTypographyPage(vm: SettingVM = koinViewModel()) {
    SettingPreferencesUIPage(vm = vm, section = UiSettingsSection.TYPOGRAPHY)
}

@Composable
fun SettingAppearanceInteractionPage(vm: SettingVM = koinViewModel()) {
    SettingPreferencesGeneralPage(
        vm = vm,
        titleRes = R.string.setting_appearance_interaction,
    )
}
