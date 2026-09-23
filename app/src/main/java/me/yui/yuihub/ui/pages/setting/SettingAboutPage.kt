package me.yui.yuihub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight02
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Github
import me.rerere.hugeicons.stroke.SmartPhone01
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
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import me.yui.yuihub.BuildConfig
import me.yui.yuihub.R
import me.yui.yuihub.Screen
import me.yui.yuihub.data.update.AppUpdateInfo
import me.yui.yuihub.data.update.UpdateChecker
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.easteregg.EmojiBurstHost
import me.yui.yuihub.ui.components.ui.CardGroup
import me.yui.yuihub.ui.components.update.UpdateDialog
import me.yui.yuihub.ui.components.update.rememberUpdateDownloader
import me.yui.yuihub.ui.context.LocalNavController
import me.yui.yuihub.ui.context.LocalToaster
import com.dokar.sonner.ToastType
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.utils.openUrl
import me.yui.yuihub.utils.plus
import org.koin.compose.koinInject

@Composable
fun SettingAboutPage() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val updateChecker = koinInject<UpdateChecker>()
    val toaster = LocalToaster.current

    // 更新检查状态：latestVersion 非空即发现新版本，气泡提示持续显示到升级
    var checking by remember { mutableStateOf(false) }
    var latestVersion by remember { mutableStateOf<String?>(null) }
    var latestVersionCode by remember { mutableStateOf<String?>(null) }
    var pendingUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }
    val startDownload = rememberUpdateDownloader()
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
                    toaster.show(context.getString(R.string.about_page_no_new_version), type = ToastType.Success)
                null, UpdateChecker.CheckResult.Error ->
                    toaster.show(context.getString(R.string.about_page_check_update_failed), type = ToastType.Error)
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
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.about_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        EmojiBurstHost(
            modifier = Modifier.fillMaxSize(),
            emojiOptions = emojiOptions,
            burstCount = 12
        ) { onBurst ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AsyncImage(
                            model = R.mipmap.ic_launcher,
                            contentDescription = "Logo",
                            modifier = Modifier
                                .clip(CircleShape)
                                .size(150.dp)
                                .onGloballyPositioned { coordinates ->
                                    val position = coordinates.positionInParent()
                                    val size = coordinates.size
                                    logoCenterPx = Offset(
                                        position.x + size.width / 2f,
                                        position.y + size.height / 2f
                                    )
                                }
                                .clickable {
                                    onBurst(logoCenterPx)
                                }
                        )

                        Text(
                            text = "YuiHub",
                            style = MaterialTheme.typography.displaySmall,
                        )

                        // 「正在检查更新...」提示行：仅检查中显示
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
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        item(
                            modifier = Modifier.combinedClickable(
                                onClick = { checkUpdate() },
                                onLongClick = { navController.navigate(Screen.Debug) },
                            ),
                            leadingContent = { Icon(HugeIcons.Code, null) },
                            supportingContent = {
                                val currentVersion = "${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE}"
                                if (latestVersion != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(currentVersion)
                                        Icon(
                                            imageVector = HugeIcons.ArrowRight02,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .padding(horizontal = 6.dp)
                                                .size(14.dp),
                                        )
                                        Text(
                                            text = latestVersionCode?.let { "$latestVersion / $it" } ?: latestVersion.orEmpty(),
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                } else {
                                    Text(currentVersion)
                                }
                            },
                            headlineContent = {
                                if (hasUpdate) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(R.string.about_page_version))
                                        Spacer(Modifier.width(6.dp))
                                        Box {
                                            // 气泡：红底白字「发现新版本！」
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
                                } else {
                                    Text(stringResource(R.string.about_page_version))
                                }
                            },
                            trailingContent = {
                                when {
                                    checking -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    else -> {}
                                }
                            },
                        )
                        item(
                            leadingContent = { Icon(HugeIcons.SmartPhone01, null) },
                            supportingContent = {
                                Text("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} / SDK ${android.os.Build.VERSION.SDK_INT}")
                            },
                            headlineContent = { Text(stringResource(R.string.about_page_system)) },
                        )
                    }
                }

                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        item(
                            onClick = { context.openUrl("https://github.com/xiaoyuili/Yuihub") },
                            leadingContent = { Icon(HugeIcons.Github, null) },
                            supportingContent = { Text("https://github.com/xiaoyuili/Yuihub") },
                            headlineContent = { Text(stringResource(R.string.about_page_github)) },
                        )
                        item(
                            onClick = { context.openUrl("https://github.com/xiaoyuili/Yuihub/blob/master/LICENSE") },
                            leadingContent = { Icon(HugeIcons.File02, null) },
                            supportingContent = { Text("https://github.com/xiaoyuili/Yuihub/blob/master/LICENSE") },
                            headlineContent = { Text(stringResource(R.string.about_page_license)) },
                        )
                    }
                }
            }
        }
    }

    pendingUpdate?.let { update ->
        UpdateDialog(
            update = update,
            onDismiss = { pendingUpdate = null },
            onIgnore = { pendingUpdate = null },
            onUpdate = { info ->
                pendingUpdate = null
                startDownload(info)
            },
        )
    }
}
