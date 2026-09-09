package me.yui.yuihub.utils

import android.database.CursorWindow
import android.util.Log

private const val TAG = "DatabaseUtil"

object DatabaseUtil {
    fun setCursorWindowSize(size: Int) {
        try {
            val field = CursorWindow::class.java.getDeclaredField("sCursorWindowSize")
            field.isAccessible = true
            val oldValue = field.get(null) as Int
            field.set(null, size)
            Log.i(TAG, "setCursorWindowSize: set $oldValue to $size")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // 已 fork io.requery.android.database 修改了 window size，避免无法反射修改 final 字段
    }
}
