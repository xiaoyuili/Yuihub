package me.yui.yuihub.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * 系统级特殊权限的检测与跳转。
 *
 * 与 ui.components.ui.permission 那套运行时权限框架不同：这三项都无法用
 * requestPermissions 申请——通知与「所有文件访问」是特殊权限，电池优化白名单只能
 * 引导用户到系统设置里确认，因此统一走「检测状态 + 跳设置」的模式。
 */
object SystemPermissions {

    fun isNotificationEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * 是否已豁免电池优化。Android 6 以下无此机制，视为已开启。
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return runCatching {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
    }

    /**
     * 是否拥有「所有文件访问权限」。Android 11 以下由 WRITE_EXTERNAL_STORAGE 覆盖，
     * 无需此权限即可读写共享存储。
     */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /**
     * 电池优化设置页。优先直接申请豁免（部分 ROM 会弹确认框），不支持时退回列表页。
     */
    @Suppress("BatteryLife")
    fun batteryOptimizationIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
            if (request.isResolvable(context)) return request
        }
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (list.isResolvable(context)) return list
        return appDetailsIntent(context)
    }

    /**
     * 本应用的「所有文件访问权限」页；个别 ROM 不支持带包名跳转，回退到总列表页。
     */
    fun allFilesAccessIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val scoped = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:${context.packageName}"))
            if (scoped.isResolvable(context)) return scoped
            val list = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            if (list.isResolvable(context)) return list
        }
        return appDetailsIntent(context)
    }

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))

    /**
     * 跳转系统设置页。ROM 定制导致目标 Activity 缺失时回退到应用详情页。
     */
    fun openSettings(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.recoverCatching { cause ->
        if (cause is ActivityNotFoundException) {
            context.startActivity(appDetailsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        false
    }.getOrDefault(false)

    private fun Intent.isResolvable(context: Context): Boolean =
        resolveActivity(context.packageManager) != null
}
