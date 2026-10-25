package me.yui.yuihub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight02
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Github
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Share04
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import androidx.core.net.toUri
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.launch
import me.yui.yuihub.BuildConfig
import me.yui.yuihub.R
import me.yui.yuihub.Screen
import me.yui.yuihub.data.update.AppUpdateInfo
import me.yui.yuihub.data.update.UpdateChecker
import me.yui.yuihub.data.update.UpdateDownloader
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.easteregg.EmojiBurstHost
import me.yui.yuihub.ui.components.ui.CardGroup
import me.yui.yuihub.ui.components.update.UpdateDialog
import me.yui.yuihub.ui.context.LocalNavController
import me.yui.yuihub.ui.context.LocalToaster
import com.dokar.sonner.ToastType
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.ui.theme.EntryAccents
import me.yui.yuihub.utils.openUrl
import me.yui.yuihub.utils.writeClipboardText
import me.yui.yuihub.utils.plus
import org.koin.compose.koinInject

@Composable
fun SettingAboutPage() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val updateChecker = koinInject<UpdateChecker>()
    val toaster = LocalToaster.current

    // 更新检查状态：latestVersion 非空即发现新版本，气泡提示持续显示到升级
    var checking by remember { mutableStateOf(false) }
    var latestVersion by remember { mutableStateOf<String?>(null) }
    var latestVersionCode by remember { mutableStateOf<String?>(null) }
    var pendingUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var showQqHelp by remember { mutableStateOf(false) }
    val downloader = koinInject<UpdateDownloader>()
    val hasUpdate = latestVersion != null

    fun checkUpdate() {
        if (checking) return
        checking = true
        scope.launch {
            val result = runCatching { updateChecker.check() }.getOrNull()
            checking = false
            when (result) {
                is UpdateChecker.CheckResult.UpdateAvailable -> {
                    latestVersion = result.update.versionName
                    latestVersionCode = result.update.versionCode?.toString()
                    pendingUpdate = result.update
                }
                UpdateChecker.CheckResult.UpToDate ->
                    toaster.show(resources.getString(R.string.about_page_no_new_version), type = ToastType.Success)
                null, UpdateChecker.CheckResult.Error ->
                    toaster.show(resources.getString(R.string.about_page_check_update_failed), type = ToastType.Error)
            }
        }
    }

    val emojiOptions = remember {
        listOf(
            "🎉", "✨", "🌟", "💫", "🎊", "🥳", "🎈", "🎆", "🎇", "🧨",
            "🌈", "🧧", "🎁", "🍬", "🍭", "🍉", "🍓", "🍒", "🍍", "🥭",
            "🐱", "🐶", "🦊", "🐼", "🦁", "🐯", "🐵", "🦄",
            "❤️", "🧡", "💛", "💚", "💙", "💜",
            "🇨🇳", "🌏", "🌍", "🌎",
            "🤗", "🤩", "😆", "😺", "😸", "🤡",
            "💡", "🔥", "💥", "🚀", "⭐", "🌙"
        )
    }
    var logoCenterPx by remember { mutableStateOf(Offset.Zero) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.about_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                colors = CustomColors.pageTopBarColors,
            )
        },
        containerColor = CustomColors.pageBackground,
    ) { innerPadding ->
        EmojiBurstHost(
            modifier = Modifier.fillMaxSize(),
            emojiOptions = emojiOptions,
            burstCount = 12
        ) { onBurst ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp, bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AsyncImage(
                            model = R.mipmap.ic_launcher,
                            contentDescription = "Logo",
                            modifier = Modifier
                                .clip(CircleShape)
                                .size(104.dp)
                                .onGloballyPositioned { coordinates ->
                                    val position = coordinates.positionInParent()
                                    val size = coordinates.size
                                    logoCenterPx = Offset(
                                        position.x + size.width / 2f,
                                        position.y + size.height / 2f
                                    )
                                }
                                .clickable { onBurst(logoCenterPx) }
                        )
                        Text(
                            text = "YuiHub",
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontSize = 30.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                        Text(
                            text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        androidx.compose.animation.AnimatedVisibility(
                            visible = checking,
                            enter = androidx.compose.animation.fadeIn(),
                            exit = androidx.compose.animation.fadeOut(),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                Text(
                                    text = stringResource(R.string.about_page_checking_update),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        }
                    }
                }

                item {
                    CardGroup(
                        title = { Text(stringResource(R.string.about_page_update_section)) },
                    ) {
                        item(
                            modifier = Modifier.combinedClickable(
                                onClick = { checkUpdate() },
                                onLongClick = { navController.navigate(Screen.Debug) },
                            ),
                            leadingContent = { SettingIcon(HugeIcons.Code, color = EntryAccents.Blue) },
                            headlineContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.about_page_version))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (hasUpdate) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.about_page_new_version_badge),
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                            color = MaterialTheme.colorScheme.onError,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(50))
                                                .background(MaterialTheme.colorScheme.error)
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            },
                            supportingContent = {
                                if (latestVersion != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = HugeIcons.ArrowRight02,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Text(
                                            text = latestVersionCode?.let { "$latestVersion ($it)" } ?: latestVersion.orEmpty(),
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(start = 6.dp),
                                        )
                                    }
                                } else {
                                    Text(stringResource(R.string.about_page_check_update_desc))
                                }
                            },
                            trailingContent = {
                                if (checking) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    TextButton(onClick = { checkUpdate() }) {
                                        Text(stringResource(R.string.about_page_check_update))
                                    }
                                }
                            },
                        )
                    }
                }

                item {
                    CardGroup(
                        title = { Text(stringResource(R.string.about_page_app_info)) },
                    ) {
                        item(
                            leadingContent = { SettingIcon(HugeIcons.SmartPhone01, color = EntryAccents.Green) },
                            headlineContent = { Text(stringResource(R.string.about_page_system)) },
                            supportingContent = {
                                Text(
                                    "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / " +
                                        "Android ${android.os.Build.VERSION.RELEASE} / SDK ${android.os.Build.VERSION.SDK_INT}"
                                )
                            },
                        )
                    }
                }

                item {
                    CardGroup(
                        title = { Text(stringResource(R.string.about_page_links)) },
                    ) {
                        item(
                            onClick = { context.openUrl("https://github.com/xiaoyuili/Yuihub") },
                            leadingContent = { SettingIcon(HugeIcons.Github, color = EntryAccents.Indigo) },
                            headlineContent = { Text(stringResource(R.string.about_page_github)) },
                            supportingContent = { Text("https://github.com/xiaoyuili/Yuihub") },
                        )
                        item(
                            onClick = { context.openUrl("https://github.com/xiaoyuili/Yuihub/blob/master/LICENSE") },
                            leadingContent = { SettingIcon(HugeIcons.File02, color = EntryAccents.Amber) },
                            headlineContent = { Text(stringResource(R.string.about_page_license)) },
                            supportingContent = { Text("https://github.com/xiaoyuili/Yuihub/blob/master/LICENSE") },
                        )
                    }
                }

                item {
                    val shareText = stringResource(R.string.setting_page_share_text)
                    val share = stringResource(R.string.setting_page_share)
                    val noShareApp = stringResource(R.string.setting_page_no_share_app)
                    CardGroup(
                        title = { Text(stringResource(R.string.about_page_community)) },
                    ) {
                        item(
                            onClick = {
                                val opened = runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=1126840192&card_type=group&source=external".toUri()
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }.isSuccess
                                if (!opened) showQqHelp = true
                            },
                            leadingContent = {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_qq),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            },
                            headlineContent = { Text(stringResource(R.string.about_page_qq_group)) },
                            supportingContent = {
                                Text(stringResource(R.string.about_page_qq_group_hint))
                            },
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        context.writeClipboardText("1126840192")
                                        Toast.makeText(context, R.string.about_page_qq_group_copied, Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.Copy01,
                                        contentDescription = stringResource(R.string.about_page_qq_group_copy),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                        )
                        item(
                            onClick = {
                                val intent = Intent(Intent.ACTION_SEND)
                                intent.type = "text/plain"
                                intent.putExtra(Intent.EXTRA_TEXT, shareText)
                                try {
                                    context.startActivity(Intent.createChooser(intent, share))
                                } catch (e: ActivityNotFoundException) {
                                    Toast.makeText(context, noShareApp, Toast.LENGTH_SHORT).show()
                                }
                            },
                            leadingContent = { SettingIcon(HugeIcons.Share04, color = EntryAccents.Rose) },
                            headlineContent = { Text(stringResource(R.string.setting_page_share)) },
                            supportingContent = { Text(stringResource(R.string.setting_page_share_desc)) },
                        )
                    }
                }
            }
        }
    }

    if (showQqHelp) {
        AlertDialog(
            onDismissRequest = { showQqHelp = false },
            title = { Text(stringResource(R.string.about_page_qq_group_help_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.about_page_qq_group_number))
                    Text(stringResource(R.string.about_page_qq_group_help_desc))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.writeClipboardText("1126840192")
                    Toast.makeText(context, R.string.about_page_qq_group_copied, Toast.LENGTH_SHORT).show()
                    showQqHelp = false
                }) {
                    Text(stringResource(R.string.about_page_qq_group_copy))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    context.openUrl("https://qm.qq.com/cgi-bin/qm/qr?groupUin=1126840192")
                    showQqHelp = false
                }) {
                    Text(stringResource(R.string.about_page_qq_group_open_browser))
                }
            },
        )
    }

    pendingUpdate?.let { update ->
        val downloadState by downloader.state.collectAsStateWithLifecycle()

        UpdateDialog(
            update = update,
            downloadState = downloadState,
            onDismiss = {
                // 下载中关闭 = 取消下载；已完成/失败时只是关窗，不能取消，
                // 否则会把已下载好的安装包从系统下载记录里删掉
                if (downloadState is UpdateDownloader.State.TestingRoutes ||
                    downloadState is UpdateDownloader.State.Downloading
                ) {
                    downloader.cancel()
                }
                pendingUpdate = null
            },
            onIgnore = { pendingUpdate = null },
            onUpdate = { info -> downloader.start(info) },
            onRetry = { downloader.retry() },
            onInstall = {
                when (downloader.install(context)) {
                    UpdateDownloader.InstallOutcome.LAUNCHED -> pendingUpdate = null
                    UpdateDownloader.InstallOutcome.NEED_PERMISSION -> toaster.show(
                        resources.getString(R.string.update_dialog_install_permission_needed),
                        type = ToastType.Warning,
                    )
                    UpdateDownloader.InstallOutcome.UNAVAILABLE -> toaster.show(
                        resources.getString(R.string.update_dialog_install_unavailable),
                        type = ToastType.Error,
                    )
                }
            },
        )
    }
}
