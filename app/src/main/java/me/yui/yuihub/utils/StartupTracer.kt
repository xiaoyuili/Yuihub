package me.yui.yuihub.utils

import android.util.Log
import me.yui.yuihub.BuildConfig
import me.rerere.common.android.Logging

/**
 * 冷启动耗时追踪: 记录从 Application 构造开始的各阶段耗时,
 * 输出到 logcat + 应用内 Debug 页日志, 用于定位启动慢的阶段。
 *
 * 用法: StartupTracer.mark("QuickJS") 记录自上次 mark 以来的耗时;
 * markAt("阶段名") 记录自进程启动总耗时。仅在 DEBUG 构建生效。
 */
object StartupTracer {
    @Volatile
    private var processStartMs: Long = -1

    @Volatile
    private var lastMarkMs: Long = -1

    fun begin() {
        if (processStartMs == -1L) {
            processStartMs = System.currentTimeMillis()
            lastMarkMs = processStartMs
        }
    }

    @Synchronized
    fun mark(name: String) {
        if (!BuildConfig.DEBUG) return
        begin()
        val now = System.currentTimeMillis()
        val step = now - lastMarkMs
        val total = now - processStartMs
        lastMarkMs = now
        val message = "⏱ $name: +${step}ms (total ${total}ms)"
        Log.i("StartupTracer", message)
        Logging.log("Startup", message)
    }
}
