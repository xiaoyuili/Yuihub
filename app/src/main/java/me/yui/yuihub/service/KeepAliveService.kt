package me.yui.yuihub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import me.yui.yuihub.KEEP_AWAKE_NOTIFICATION_CHANNEL_ID
import me.yui.yuihub.R
import me.yui.yuihub.RouteActivity

private const val TAG = "KeepAliveService"

/**
 * 常驻前台服务 + Partial WakeLock，用于阻止系统在后台杀掉进程。
 *
 * 聊天生成期间另有 ChatGenerationForegroundService 兜底；本服务覆盖的是「agent 空闲但
 * 用户切到别的界面」这段窗口——没有它，国产 ROM 的后台管控会在几分钟内回收进程。
 * 仅在用户在「权限管理」中显式开启保活时运行，因为它会明显增加耗电。
 */
class KeepAliveService : Service() {
    companion object {
        const val NOTIFICATION_ID = 2003

        private const val ACTION_STOP = "me.yui.yuihub.action.KEEP_AWAKE_STOP"
        private const val WAKE_LOCK_TAG = "YuiHub::KeepAliveWakeLock"

        private var running = false

        fun isRunning(): Boolean = running

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, KeepAliveService::class.java),
                )
            }.onFailure {
                Log.e(TAG, "Unable to start keep-alive service", it)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, KeepAliveService::class.java).setAction(ACTION_STOP),
                )
            }.onFailure {
                Log.e(TAG, "Unable to stop keep-alive service", it)
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        enterForeground()
        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        running = false
        super.onDestroy()
    }

    private fun enterForeground() {
        try {
            val notification = NotificationCompat.Builder(
                this,
                KEEP_AWAKE_NOTIFICATION_CHANNEL_ID,
            )
                .setSmallIcon(R.drawable.ic_stat_yuihub)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.setting_permissions_keep_awake_notification))
                .setContentIntent(openAppPendingIntent())
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setSilent(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            running = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter foreground", e)
            stopSelf()
        }
    }

    /**
     * Partial WakeLock 只保持 CPU 可运行，不点亮屏幕。
     * 用应用级 tag 便于 dumpsys 排查，且不设置超时（保活本身没有明确终点）。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                runCatching { acquire() }.onFailure { Log.e(TAG, "Failed to acquire wake lock", it) }
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.runCatching { release() }
        wakeLock = null
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
