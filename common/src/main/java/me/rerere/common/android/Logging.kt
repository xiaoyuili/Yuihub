package me.rerere.common.android

import kotlin.uuid.Uuid

private const val MAX_RECENT_LOGS = 100

data class TextLog(
    val id: Uuid = Uuid.random(),
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val message: String,
)

object Logging {
    private val recentLogs = arrayListOf<TextLog>()

    fun log(tag: String, message: String) {
        synchronized(recentLogs) {
            recentLogs.add(0, TextLog(tag = tag, message = message))
            if (recentLogs.size > MAX_RECENT_LOGS) {
                recentLogs.removeLastOrNull()
            }
        }
    }
}
