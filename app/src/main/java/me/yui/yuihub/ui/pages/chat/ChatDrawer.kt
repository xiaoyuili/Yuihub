package me.yui.yuihub.ui.pages.chat

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.FileAudio
import me.rerere.hugeicons.stroke.FileCode
import me.rerere.hugeicons.stroke.FileImage
import me.rerere.hugeicons.stroke.FileVideo
import me.rerere.hugeicons.stroke.FileZip
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.LanguageCircle
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import me.yui.yuihub.R
import me.yui.yuihub.Screen
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.data.datastore.getCurrentAssistant
import me.yui.yuihub.data.model.Assistant
import me.yui.yuihub.data.model.Conversation
import me.yui.yuihub.data.model.Folder
import me.yui.yuihub.data.repository.ConversationRepository
import me.yui.yuihub.data.repository.WorkspaceRepository
import me.yui.yuihub.ui.components.ai.AssistantPicker
import me.yui.yuihub.ui.components.ui.BackupReminderCard
import me.yui.yuihub.ui.components.ui.Greeting
import me.yui.yuihub.ui.components.ui.Tooltip
import me.yui.yuihub.ui.components.ui.UIAvatar
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import me.yui.yuihub.ui.context.LocalToaster
import me.yui.yuihub.ui.context.Navigator
import com.dokar.sonner.ToastType
import me.yui.yuihub.ui.hooks.EditStateContent
import me.yui.yuihub.ui.hooks.readBooleanPreference
import me.yui.yuihub.ui.hooks.useEditState
import me.yui.yuihub.ui.modifier.onClick
import me.yui.yuihub.ui.pages.extensions.workspace.WorkspaceFileType
import me.yui.yuihub.ui.pages.extensions.workspace.detectFileType
import me.yui.yuihub.utils.fileSizeToString
import me.yui.yuihub.utils.navigateToChatPage
import me.yui.yuihub.utils.toDp
import me.yui.yuihub.utils.writeClipboardText
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import androidx.core.content.FileProvider
import java.io.File
import kotlin.uuid.Uuid

@Composable
fun ChatDrawerContent(
    navController: Navigator,
    vm: ChatVM,
    settings: Settings,
    current: Conversation,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val repo = koinInject<ConversationRepository>()

    val activity = context as ComponentActivity
    val drawerVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = activity)

    val conversations = drawerVm.conversations.collectAsLazyPagingItems()
    val folders by drawerVm.folders.collectAsStateWithLifecycle()
    val selectedFolderId by drawerVm.selectedFolderId.collectAsStateWithLifecycle()
    val conversationListState = rememberLazyListState(
        initialFirstVisibleItemIndex = drawerVm.scrollIndex,
        initialFirstVisibleItemScrollOffset = drawerVm.scrollOffset,
    )

    LaunchedEffect(conversationListState) {
        snapshotFlow {
            conversationListState.firstVisibleItemIndex to
                conversationListState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collectLatest { (index, offset) ->
                drawerVm.saveScrollPosition(index, offset)
            }
    }

    val conversationJobs by vm.conversationJobs.collectAsStateWithLifecycle(
        initialValue = emptyMap(),
    )

    // 侧滑页面板：当前助手绑定工作区时提供「会话 / 文件」切换（状态存 VM，从详情页返回时保留现场）
    val workspaceId = settings.getCurrentAssistant().workspaceId
    val workspaceIdStr = workspaceId?.toString()
    val activePanel by drawerVm.drawerPanel.collectAsStateWithLifecycle()
    val filesPath by drawerVm.filesPath.collectAsStateWithLifecycle()
    val workspaceRepository: WorkspaceRepository = koinInject()
    val focusManager = LocalFocusManager.current
    var filesRefreshKey by remember { mutableStateOf(0) }
    // 未绑定工作区时始终按会话面板呈现
    val effectivePanel = if (workspaceIdStr != null) activePanel else DrawerPanel.CHATS

    LaunchedEffect(workspaceId) {
        drawerVm.syncWorkspaceState(workspaceId)
        // 预加载文件面板根目录：切到「文件」时即时呈现
        val wsId = workspaceIdStr ?: return@LaunchedEffect
        runCatching {
            workspaceRepository.listFiles(id = wsId, area = WorkspaceStorageArea.FILES, path = "")
        }.onSuccess { drawerVm.filesCache[""] = it }
    }

    // 文件名搜索：防抖 250ms，实时显示在文件列表区域
    var filesSearchQuery by remember(workspaceId) { mutableStateOf("") }
    var filesSearchResults by remember { mutableStateOf<List<WorkspaceFileEntry>?>(null) }
    var filesSearching by remember { mutableStateOf(false) }
    LaunchedEffect(workspaceId, filesSearchQuery, filesRefreshKey) {
        val query = filesSearchQuery.trim()
        if (workspaceId == null || query.isEmpty()) {
            filesSearchResults = null
            filesSearching = false
            return@LaunchedEffect
        }
        filesSearching = true
        delay(250)
        filesSearchResults = runCatching {
            workspaceRepository.searchFilesByName(workspaceId.toString(), query)
        }.getOrDefault(emptyList())
        filesSearching = false
    }

    // 昵称编辑状态
    val nicknameEditState = useEditState<String> { newNickname ->
        vm.updateSettings(
            settings.copy(
                displaySetting = settings.displaySetting.copy(
                    userNickname = newNickname
                )
            )
        )
    }

    // 移动对话状态
    var showMoveToAssistantSheet by remember { mutableStateOf(false) }
    var conversationToMove by remember { mutableStateOf<Conversation?>(null) }
    val bottomSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    // 文件夹相关状态
    var showMoveToFolderSheet by remember { mutableStateOf(false) }
    var conversationToMoveFolder by remember { mutableStateOf<Conversation?>(null) }
    val folderSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<Folder?>(null) }
    var folderToDelete by remember { mutableStateOf<Folder?>(null) }

    ModalDrawerSheet(
        modifier = Modifier.width(300.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BackupReminderCard(
                settings = settings,
                onClick = { navController.navigate(Screen.Backup) },
            )

            // 用户头像和昵称自定义区域
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                UIAvatar(
                    name = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                    value = settings.displaySetting.userAvatar,
                    onUpdate = { newAvatar ->
                        vm.updateSettings(
                            settings.copy(
                                displaySetting = settings.displaySetting.copy(
                                    userAvatar = newAvatar
                                )
                            )
                        )
                    },
                    modifier = Modifier.size(50.dp),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable {
                                nicknameEditState.open(settings.displaySetting.userNickname)
                            }
                        )

                        Icon(
                            imageVector = HugeIcons.PencilEdit01,
                            contentDescription = "Edit",
                            modifier = Modifier
                                .onClick {
                                    nicknameEditState.open(settings.displaySetting.userNickname)
                                }
                                .size(LocalTextStyle.current.fontSize.toDp())
                        )
                    }
                    Greeting(
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            // 工作区面板：助手绑定工作区时展示「会话 / 文件」切换（展开动画）
            androidx.compose.animation.AnimatedVisibility(
                visible = workspaceId != null,
                enter = expandVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(),
                exit = shrinkVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeOut(),
            ) {
                DrawerPanelToggle(
                    selected = activePanel,
                    onSelect = {
                        focusManager.clearFocus()
                        drawerVm.setDrawerPanel(it)
                    },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            // 面板内容：会话（含聊天/新建行）/ 文件浏览，方向感交叉淡入切换
            AnimatedContent(
                targetState = effectivePanel,
                transitionSpec = {
                    val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                    (
                        fadeIn(tween(210, easing = FastOutSlowInEasing)) +
                            slideInHorizontally(tween(210, easing = FastOutSlowInEasing)) { direction * it / 16 }
                        ) togetherWith (
                        fadeOut(tween(130)) +
                            slideOutHorizontally(tween(130)) { -direction * it / 16 }
                        )
                },
                label = "drawerPanelContent",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { panel ->
                if (panel == DrawerPanel.FILES && workspaceIdStr != null) {
                    WorkspaceFilesPanel(
                        workspaceId = workspaceIdStr,
                        path = filesPath,
                        onPathChange = { drawerVm.setFilesPath(it) },
                        onOpenFile = { entry ->
                            drawerVm.requestDrawerReopen()
                            navController.navigate(
                                Screen.WorkspaceFileEditor(
                                    id = workspaceIdStr,
                                    area = WorkspaceStorageArea.FILES.name,
                                    path = entry.path,
                                )
                            )
                        },
                        refreshKey = filesRefreshKey,
                        onFilesChanged = {
                            drawerVm.invalidateFilesCache()
                            filesRefreshKey++
                        },
                        cache = drawerVm.filesCache,
                        searchQuery = filesSearchQuery,
                        searchResults = filesSearchResults,
                        searching = filesSearching,
                        onDismissFocus = { focusManager.clearFocus() },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FolderBar(
                            folders = folders,
                            selectedFolderId = selectedFolderId,
                            onSelect = { drawerVm.selectFolder(it) },
                            onCreate = { showCreateFolderDialog = true },
                            onRename = { folderToRename = it },
                            onDelete = { folderToDelete = it },
                        )
                        ConversationList(
                            current = current,
                            conversations = conversations,
                            conversationJobs = conversationJobs.keys,
                            listState = conversationListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            onClick = {
                                navigateToChatPage(navController, it.id)
                            },
                            onRegenerateTitle = {
                                vm.generateTitle(it, true)
                            },
                            onDelete = {
                                scope.launch {
                                    vm.deleteConversation(it).join()
                                    conversations.refresh()
                                    if (it.id == current.id) {
                                        navigateToChatPage(navController)
                                    }
                                }
                            },
                            onPin = {
                                vm.updatePinnedStatus(it)
                            },
                            onMoveToAssistant = {
                                conversationToMove = it
                                showMoveToAssistantSheet = true
                            },
                            onMoveToFolder = {
                                conversationToMoveFolder = it
                                showMoveToFolderSheet = true
                            }
                        )
                    }
                }
            }

            // 底部：文件面板显示文件名搜索框；会话面板显示助手选择器
            if (effectivePanel == DrawerPanel.FILES) {
                WorkspaceFilesSearchBar(
                    query = filesSearchQuery,
                    onQueryChange = { filesSearchQuery = it },
                )
            } else {
                AssistantPicker(
                    settings = settings,
                    onUpdateSettings = {
                        val updateJob = vm.updateSettings(it)
                        scope.launch {
                            updateJob.join()
                            val id = if (context.readBooleanPreference("create_new_conversation_on_start", true)) {
                                Uuid.random()
                            } else {
                                repo.getConversationsOfAssistant(it.assistantId)
                                    .first()
                                    .firstOrNull()
                                    ?.id ?: Uuid.random()
                            }
                            navigateToChatPage(navigator = navController, chatId = id)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    onClickSetting = {
                        val currentAssistantId = settings.assistantId
                        navController.navigate(Screen.AssistantDetail(id = currentAssistantId.toString()))
                    }
                )
            }

            Row(
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                DrawerAction(
                    icon = {
                        Icon(
                            imageVector = HugeIcons.LookTop,
                            contentDescription = stringResource(R.string.assistant_page_title)
                        )
                    },
                    label = {
                        Text(stringResource(R.string.assistant_page_title))
                    },
                    onClick = {
                        navController.navigate(Screen.Assistant)
                    },
                )

                DrawerAction(
                    icon = {
                        Icon(
                            imageVector = HugeIcons.Image02,
                            contentDescription = stringResource(R.string.chat_page_menu_image_generation)
                        )
                    },
                    label = {
                        Text(stringResource(R.string.chat_page_menu_image_generation))
                    },
                    onClick = {
                        navController.navigate(Screen.ImageGen)
                    },
                )

                DrawerAction(
                    icon = {
                        Icon(HugeIcons.InLove, stringResource(R.string.favorite_page_title))
                    },
                    label = {
                        Text(stringResource(R.string.favorite_page_title))
                    },
                    onClick = {
                        navController.navigate(Screen.Favorite)
                    },
                )

                DrawerAction(
                    icon = {
                        Icon(HugeIcons.ChartColumn, "统计数据")
                    },
                    label = {
                        Text("统计数据")
                    },
                    onClick = {
                        navController.navigate(Screen.Stats(chatId = current.id.toString()))
                    },
                )

                Spacer(Modifier.weight(1f))

                DrawerAction(
                    icon = {
                        Icon(HugeIcons.Settings03, null)
                    },
                    label = { Text(stringResource(R.string.settings)) },
                    onClick = {
                        navController.navigate(Screen.Setting)
                    },
                )
            }
        }
    }

    // 昵称编辑对话框
    nicknameEditState.EditStateContent { nickname, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                nicknameEditState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_nickname))
            },
            text = {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_page_nickname_placeholder)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 移动到文件夹 Bottom Sheet
    if (showMoveToFolderSheet) {
        val doMove: (Uuid?) -> Unit = { folderId ->
            conversationToMoveFolder?.let { conversation ->
                drawerVm.moveConversationToFolder(conversation.id, folderId)
                scope.launch {
                    folderSheetState.hide()
                    showMoveToFolderSheet = false
                    conversationToMoveFolder = null
                    conversations.refresh()
                }
            }
        }
        ModalBottomSheet(
            onDismissRequest = {
                showMoveToFolderSheet = false
                conversationToMoveFolder = null
            },
            sheetState = folderSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_page_move_to_folder),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // 移出文件夹（未归类）
                Surface(
                    onClick = { doMove(null) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = if (conversationToMoveFolder?.folderId == null) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(HugeIcons.Folder01, null)
                        Text(
                            text = stringResource(R.string.chat_page_remove_from_folder),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(folders, key = { it.id }) { folder ->
                        val isCurrent = folder.id == conversationToMoveFolder?.folderId
                        Surface(
                            onClick = { doMove(folder.id) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            tonalElevation = if (isCurrent) 2.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(HugeIcons.Folder01, null)
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 新建文件夹对话框
    if (showCreateFolderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text(stringResource(R.string.chat_page_create_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_page_folder_name)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        drawerVm.createFolder(name)
                        showCreateFolderDialog = false
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 重命名文件夹对话框
    folderToRename?.let { folder ->
        var name by remember(folder.id) { mutableStateOf(folder.name) }
        AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text(stringResource(R.string.chat_page_rename_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        drawerVm.renameFolder(folder.id, name)
                        folderToRename = null
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToRename = null }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 删除文件夹确认
    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(stringResource(R.string.chat_page_delete_folder)) },
            text = { Text(stringResource(R.string.chat_page_delete_folder_confirm, folder.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (drawerVm.deleteFolder(folder.id)) {
                            folderToDelete = null
                            conversations.refresh()
                        } else {
                            toaster.show(context.getString(R.string.chat_page_delete_folder_generating), type = ToastType.Warning)
                        }
                    }
                ) { Text(stringResource(R.string.chat_page_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 移动到助手 Bottom Sheet
    if (showMoveToAssistantSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showMoveToAssistantSheet = false
                conversationToMove = null
            },
            sheetState = bottomSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_page_move_to_assistant),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(settings.assistants, key = { it.id }) { assistant ->
                        AssistantItem(
                            assistant = assistant,
                            isCurrentAssistant = assistant.id == conversationToMove?.assistantId,
                            onClick = {
                                conversationToMove?.let { conversation ->
                                    vm.moveConversationToAssistant(conversation, assistant.id)
                                    scope.launch {
                                        bottomSheetState.hide()
                                        showMoveToAssistantSheet = false
                                        conversationToMove = null
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerAction(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = CircleShape,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Tooltip(
            tooltip = {
                label()
            }
        ) {
            Box(
                modifier = Modifier
                    .padding(10.dp)
                    .size(20.dp),
            ) {
                icon()
            }
        }
    }
}

@Composable
private fun FolderBar(
    folders: List<Folder>,
    selectedFolderId: Uuid?,
    onSelect: (Uuid?) -> Unit,
    onCreate: () -> Unit,
    onRename: (Folder) -> Unit,
    onDelete: (Folder) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            FolderChip(
                label = stringResource(R.string.chat_page_folder_default),
                selected = selectedFolderId == null,
                onClick = { onSelect(null) },
                onLongClick = {},
            )
        }
        items(folders, key = { it.id }) { folder ->
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                FolderChip(
                    label = folder.name,
                    icon = HugeIcons.Folder01,
                    selected = selectedFolderId == folder.id,
                    onClick = { onSelect(folder.id) },
                    onLongClick = { menuExpanded = true },
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_page_rename)) },
                        leadingIcon = { Icon(HugeIcons.PencilEdit01, null) },
                        onClick = {
                            onRename(folder)
                            menuExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_page_delete)) },
                        leadingIcon = { Icon(HugeIcons.Delete01, null) },
                        onClick = {
                            onDelete(folder)
                            menuExpanded = false
                        }
                    )
                }
            }
        }
        item {
            FolderChip(
                label = stringResource(R.string.chat_page_folder_add),
                icon = HugeIcons.FolderAdd,
                selected = false,
                onClick = onCreate,
                onLongClick = {},
            )
        }
    }
}

@Composable
private fun FolderChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    icon: ImageVector? = null,
) {
    Surface(
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) {
                Icon(icon, null, modifier = Modifier.size(14.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AssistantItem(
    assistant: Assistant,
    isCurrentAssistant: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isCurrentAssistant) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (isCurrentAssistant) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            UIAvatar(
                name = assistant.name,
                value = assistant.avatar,
                onUpdate = {},
                modifier = Modifier.size(40.dp),
            )
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isCurrentAssistant) {
                    Text(
                        text = stringResource(R.string.assistant_page_current_assistant),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

enum class DrawerPanel {
    CHATS,
    FILES,
}

/** 侧滑页「会话 / 文件」圆角滑块分段控件。 */
@Composable
private fun DrawerPanelToggle(
    selected: DrawerPanel,
    onSelect: (DrawerPanel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackShape = RoundedCornerShape(14.dp)
    val knobShape = RoundedCornerShape(10.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = trackShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
        ) {
            val knobWidth = maxWidth / 2
            val knobOffset by animateDpAsState(
                targetValue = if (selected == DrawerPanel.FILES) knobWidth else 0.dp,
                animationSpec = spring(dampingRatio = 0.78f, stiffness = 1150f),
                label = "drawerPanelKnob",
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
                DrawerPanelTab(
                    label = stringResource(R.string.chat_page_drawer_panel_chats),
                    selected = selected == DrawerPanel.CHATS,
                    onClick = { onSelect(DrawerPanel.CHATS) },
                    modifier = Modifier.weight(1f),
                )
                DrawerPanelTab(
                    label = stringResource(R.string.chat_page_drawer_panel_files),
                    selected = selected == DrawerPanel.FILES,
                    onClick = { onSelect(DrawerPanel.FILES) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DrawerPanelTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        },
        animationSpec = tween(150),
        label = "drawerPanelTabColor",
    )
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/** 侧滑页工作区文件浏览：目录导航 + 长按操作 + 文件名搜索结果。 */
@Composable
private fun WorkspaceFilesPanel(
    workspaceId: String,
    path: String,
    onPathChange: (String) -> Unit,
    onOpenFile: (WorkspaceFileEntry) -> Unit,
    refreshKey: Int,
    onFilesChanged: () -> Unit,
    cache: MutableMap<String, List<WorkspaceFileEntry>>,
    searchQuery: String,
    searchResults: List<WorkspaceFileEntry>?,
    searching: Boolean,
    onDismissFocus: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val repository: WorkspaceRepository = koinInject()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val searchingMode = searchQuery.trim().isNotEmpty()

    var rootName by remember(workspaceId) { mutableStateOf("") }
    // 目录内容：同步读取缓存作为初始值，命中时首帧即有内容（无空态闪烁）
    var entries by remember(workspaceId, path) { mutableStateOf(cache[path].orEmpty()) }
    var loading by remember(workspaceId, path) { mutableStateOf(cache[path] == null) }
    // 每个目录独立滚动位置：切换目录从顶部开始，避免沿用上一目录的滚动偏移
    val listState = remember(workspaceId, path) { LazyListState() }

    // 长按操作状态
    var actionTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var renameTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var exportTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }

    LaunchedEffect(workspaceId) {
        rootName = runCatching { repository.getById(workspaceId)?.name }.getOrNull().orEmpty()
    }

    // 目录加载：命中缓存立即呈现，后台再刷新一次并回写缓存；
    // 仅当无缓存可显时才展示加载指示，避免列表跳动
    LaunchedEffect(workspaceId, path, refreshKey) {
        runCatching {
            repository.listFiles(id = workspaceId, area = WorkspaceStorageArea.FILES, path = path)
        }.onSuccess { result ->
            entries = result
            cache[path] = result
        }
        loading = false
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val entry = exportTarget.also { exportTarget = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val output = context.contentResolver.openOutputStream(uri) ?: error("无法写入所选位置")
                output.use {
                    repository.exportFile(
                        id = workspaceId,
                        area = WorkspaceStorageArea.FILES,
                        path = entry.path,
                        outputStream = it,
                    )
                }
            }.onFailure {
                toaster.show(it.message ?: "导出失败", type = ToastType.Error)
            }
        }
    }

    fun shareEntry(entry: WorkspaceFileEntry) {
        scope.launch {
            runCatching {
                val dir = File(context.cacheDir, "workspace_share").apply { mkdirs() }
                val file = File(dir, entry.name)
                file.outputStream().use { output ->
                    repository.exportFile(
                        id = workspaceId,
                        area = WorkspaceStorageArea.FILES,
                        path = entry.path,
                        outputStream = output,
                    )
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, null))
            }.onFailure {
                toaster.show(it.message ?: "分享失败", type = ToastType.Error)
            }
        }
    }

    Column(modifier = modifier) {
        if (!searchingMode) {
            // 顶部路径栏：显示工作区与当前目录
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (path.isNotBlank()) {
                    IconButton(
                        onClick = {
                            onDismissFocus()
                            onPathChange(path.substringBeforeLast('/', missingDelimiterValue = ""))
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.ArrowLeft01,
                            contentDescription = stringResource(R.string.back),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Text(
                    text = buildString {
                        append(rootName.ifBlank { "workspace" })
                        if (path.isNotBlank()) {
                            append(" / ")
                            append(path)
                        }
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                )
            }
        } else {
            // 搜索结果提示行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (searching) {
                    CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.5.dp)
                }
                Text(
                    text = when {
                        searching -> ""
                        searchResults.isNullOrEmpty() ->
                            stringResource(R.string.chat_page_drawer_files_search_empty)
                        else ->
                            stringResource(R.string.chat_page_drawer_files_search_count, searchResults.orEmpty().size)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (searchingMode) {
                items(searchResults.orEmpty(), key = { "search_${it.path}" }) { entry ->
                    WorkspaceSearchResultRow(
                        entry = entry,
                        onOpen = {
                            onDismissFocus()
                            if (entry.isDirectory) {
                                onPathChange(entry.path)
                            } else {
                                onOpenFile(entry)
                            }
                        },
                        onCopyPath = {
                            context.writeClipboardText(entry.path)
                            toaster.show(context.getString(R.string.copied), type = ToastType.Success)
                        },
                        onLongClick = { actionTarget = entry },
                    )
                }
            } else {
                if (loading && entries.isEmpty()) {
                    item(key = "loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                if (!loading && entries.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(R.string.workspace_detail_empty_directory),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                        )
                    }
                }
                items(entries, key = { it.path }) { entry ->
                    WorkspaceFileRow(
                        entry = entry,
                        onClick = {
                            onDismissFocus()
                            if (entry.isDirectory) {
                                onPathChange(entry.path)
                            } else {
                                onOpenFile(entry)
                            }
                        },
                        onLongClick = { actionTarget = entry },
                    )
                }
            }
        }
    }

    // 长按操作弹层：重命名 / 删除 / 导出 / 分享
    actionTarget?.let { entry ->
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
        ModalBottomSheet(
            onDismissRequest = { actionTarget = null },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                FileActionItem(icon = HugeIcons.PencilEdit01, label = stringResource(R.string.chat_page_rename)) {
                    actionTarget = null
                    renameTarget = entry
                }
                FileActionItem(icon = HugeIcons.Delete01, label = stringResource(R.string.chat_page_delete)) {
                    actionTarget = null
                    deleteTarget = entry
                }
                FileActionItem(icon = HugeIcons.Download04, label = stringResource(R.string.export_title)) {
                    actionTarget = null
                    exportTarget = entry
                    exportLauncher.launch(entry.name)
                }
                FileActionItem(icon = HugeIcons.Share01, label = stringResource(R.string.share)) {
                    actionTarget = null
                    shareEntry(entry)
                }
            }
        }
    }

    // 重命名对话框
    renameTarget?.let { entry ->
        var name by remember(entry.path) { mutableStateOf(entry.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.chat_page_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && name.trim() != entry.name,
                    onClick = {
                        val newName = name.trim()
                        renameTarget = null
                        scope.launch {
                            val parent = entry.path.substringBeforeLast('/', missingDelimiterValue = "")
                            val target = if (parent.isEmpty()) newName else "$parent/$newName"
                            runCatching {
                                repository.moveFile(workspaceId, entry.path, target, overwrite = false)
                            }.onSuccess {
                                onFilesChanged()
                            }.onFailure {
                                toaster.show(it.message ?: "重命名失败", type = ToastType.Error)
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // 删除确认
    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = { Text(entry.name) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            val deleted = runCatching {
                                repository.deleteFile(
                                    id = workspaceId,
                                    area = WorkspaceStorageArea.FILES,
                                    path = entry.path,
                                    recursive = entry.isDirectory,
                                )
                            }.getOrDefault(false)
                            if (deleted) {
                                onFilesChanged()
                            } else {
                                toaster.show("删除失败", type = ToastType.Error)
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun WorkspaceFileRow(
    entry: WorkspaceFileEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = workspaceFileIcon(entry),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (entry.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!entry.isDirectory) {
            Text(
                text = entry.sizeBytes.fileSizeToString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 搜索结果行：小字路径（点击复制）+ 文件名。 */
@Composable
private fun WorkspaceSearchResultRow(
    entry: WorkspaceFileEntry,
    onOpen: () -> Unit,
    onCopyPath: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val parent = entry.path.substringBeforeLast('/', missingDelimiterValue = "")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(onClick = onOpen, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onCopyPath)
                .padding(horizontal = 2.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = if (parent.isEmpty()) "/" else "/$parent",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                imageVector = HugeIcons.Copy01,
                contentDescription = stringResource(R.string.copy),
                modifier = Modifier.size(11.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = workspaceFileIcon(entry),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = entry.sizeBytes.fileSizeToString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 底部文件名搜索框（文件面板时替代助手选择器）。 */
@Composable
private fun WorkspaceFilesSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceBright,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Search01,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.chat_page_drawer_files_search_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = stringResource(R.string.cancel),
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onQueryChange("") },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FileActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun workspaceFileIcon(entry: WorkspaceFileEntry): ImageVector = when {
    entry.isDirectory -> HugeIcons.Folder01
    else -> when (entry.detectFileType()) {
        WorkspaceFileType.IMAGE -> HugeIcons.FileImage
        WorkspaceFileType.AUDIO -> HugeIcons.FileAudio
        WorkspaceFileType.VIDEO -> HugeIcons.FileVideo
        WorkspaceFileType.ARCHIVE -> HugeIcons.FileZip
        WorkspaceFileType.TEXT -> HugeIcons.FileCode
        WorkspaceFileType.OTHER -> HugeIcons.File01
    }
}
