package me.yui.yuihub.di

import me.yui.yuihub.ui.pages.assistant.AssistantVM
import me.yui.yuihub.ui.pages.assistant.detail.AssistantDetailVM
import me.yui.yuihub.ui.pages.backup.BackupVM
import me.yui.yuihub.ui.pages.chat.ChatDrawerVM
import me.yui.yuihub.ui.pages.chat.ChatVM
import me.yui.yuihub.ui.pages.debug.DebugVM
import me.yui.yuihub.ui.pages.favorite.FavoriteVM
import me.yui.yuihub.ui.pages.search.SearchVM
import me.yui.yuihub.ui.pages.history.HistoryVM
import me.yui.yuihub.ui.pages.stats.StatsVM
import me.yui.yuihub.ui.pages.imggen.ImgGenVM
import me.yui.yuihub.ui.pages.extensions.PromptVM
import me.yui.yuihub.ui.pages.extensions.skills.SkillDetailVM
import me.yui.yuihub.ui.pages.extensions.skills.SkillsVM
import me.yui.yuihub.ui.pages.extensions.workspace.WorkspaceDetailVM
import me.yui.yuihub.ui.pages.extensions.workspace.WorkspaceVM
import me.yui.yuihub.ui.pages.setting.SettingVM
import me.yui.yuihub.ui.pages.share.handler.ShareHandlerVM
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule = module {
    viewModel<ChatVM> { params ->
        ChatVM(
            id = params.get(),
            context = get(),
            settingsStore = get(),
            conversationRepo = get(),
            chatService = get(),
            analytics = get(),
            filesManager = get(),
            favoriteRepository = get(),
        )
    }
    viewModelOf(::ChatDrawerVM)
    viewModelOf(::SettingVM)
    viewModelOf(::DebugVM)
    viewModelOf(::HistoryVM)
    viewModelOf(::AssistantVM)
    viewModel<AssistantDetailVM> {
        AssistantDetailVM(
            id = it.get(),
            settingsStore = get(),
            memoryRepository = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
        )
    }
    viewModel<ShareHandlerVM> {
        ShareHandlerVM(
            text = it.get(),
            settingsStore = get(),
        )
    }
    viewModelOf(::BackupVM)
    viewModelOf(::ImgGenVM)
    viewModelOf(::PromptVM)
    viewModelOf(::SkillsVM)
    viewModelOf(::SkillDetailVM)
    viewModelOf(::WorkspaceVM)
    viewModel<WorkspaceDetailVM> {
        WorkspaceDetailVM(
            id = it.get(),
            repository = get(),
            terminalSessionManager = get(),
        )
    }
    viewModelOf(::FavoriteVM)
    viewModelOf(::SearchVM)
    viewModelOf(::StatsVM)
}
