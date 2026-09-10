package me.yui.yuihub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import me.yui.yuihub.AppScope
import me.yui.yuihub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.yui.yuihub.R
import me.yui.yuihub.RouteActivity
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

private const val TAG = "ChatGenerationFgs"

/**
 * Keeps the app process in the foreground while one or more chat generations are active.
 *
 * Generation itself remains owned by [ChatService]. This service only provides the Android
 * foreground-service lifetime required for streaming to continue after the activity is hidden.
 */
class ChatGenerationForegroundService : Service() {
    companion object {
        private const val ACTION_ACQUIRE = "me.yui.yuihub.action.CHAT_GENERATION_ACQUIRE"
        private const val ACTION_RELEASE = "me.yui.yuihub.action.CHAT_GENERATION_RELEASE"
        private const val EXTRA_GENERATION_ID = "generation_id"
        private const val EXTRA_CONVERSATION_ID = "conversation_id"

        const val NOTIFICATION_ID = 2002

        fun acquire(context: Context, generationId: Uuid, conversationId: Uuid): Boolean {
            val intent = Intent(context, ChatGenerationForegroundService::class.java).apply {
                action = ACTION_ACQUIRE
                putExtra(EXTRA_GENERATION_ID, generationId.toString())
                putExtra(EXTRA_CONVERSATION_ID, conversationId.toString())
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.onFailure {
                Log.e(TAG, "Unable to start chat generation foreground service", it)
            }.getOrDefault(false)
        }

        fun release(context: Context, generationId: Uuid) {
            val intent = Intent(context, ChatGenerationForegroundService::class.java).apply {
                action = ACTION_RELEASE
                putExtra(EXTRA_GENERATION_ID, generationId.toString())
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                Log.e(TAG, "Unable to release chat generation foreground service", it)
            }
        }
    }

    private val activeGenerations = linkedMapOf<String, String>()
    private var isForeground = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val appScope: AppScope by inject()
    private val chatService: ChatService by inject()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACQUIRE -> acquire(intent)
            ACTION_RELEASE -> release(intent)
            else -> stopService()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeGenerations.clear()
        releaseWakeLock()
        releaseWifiLock()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.e(TAG, "Foreground service timed out (type=$fgsType)")
        activeGenerations.values
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .distinct()
            .forEach { conversationId ->
                appScope.launch {
                    chatService.stopGeneration(conversationId)
                }
            }
        // Android only allows a few seconds after onTimeout before raising RemoteServiceException.
        stopService()
    }

    private fun acquire(intent: Intent) {
        val generationId = intent.getStringExtra(EXTRA_GENERATION_ID) ?: return stopService()
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return stopService()
        activeGenerations[generationId] = conversationId
        updateForegroundNotification(conversationId)
        acquireWakeLock()
        acquireWifiLock()
    }

    private fun release(intent: Intent) {
        intent.getStringExtra(EXTRA_GENERATION_ID)?.let(activeGenerations::remove)
        if (activeGenerations.isEmpty()) {
            releaseWakeLock()
            releaseWifiLock()
            stopService()
        } else {
            updateForegroundNotification(activeGenerations.values.last())
        }
    }

    /**
     * 前台服务只保证进程不被冻结, 不阻止 CPU 休眠与 Doze 推迟网络;
     * 息屏/后台跑工具链 (proot、网络请求) 时会长时间停摆, 需 Partial WakeLock。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YuiHub::ChatGeneration")
            .apply {
                setReferenceCounted(false)
                runCatching { acquire() }
                    .onFailure { Log.e(TAG, "Failed to acquire generation wake lock", it) }
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.runCatching { release() }
        wakeLock = null
    }

    /** Wi-Fi 高性能模式: 防止息屏后 Wi-Fi 进省电模式导致流式请求断流 */
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager.createWifiLock(mode, "YuiHub::ChatGenerationWifi")
            .apply {
                setReferenceCounted(false)
                runCatching { acquire() }
                    .onFailure { Log.e(TAG, "Failed to acquire generation wifi lock", it) }
            }
    }

    private fun releaseWifiLock() {
        wifiLock?.takeIf { it.isHeld }?.runCatching { release() }
        wifiLock = null
    }

    private fun updateForegroundNotification(conversationId: String) {
        try {
            val notification = buildNotification(conversationId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter foreground", e)
            activeGenerations.clear()
            stopSelf()
        }
    }

    private fun stopService() {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    private fun buildNotification(conversationId: String) =
        NotificationCompat.Builder(this, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_yuihub)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_live_update_title))
            .setContentIntent(getConversationPendingIntent(conversationId))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun getConversationPendingIntent(conversationId: String): PendingIntent {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId)
        }
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
