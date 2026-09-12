package me.yui.yuihub.utils

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val TAG = "CoroutineUtils"

fun <T> Flow<T>.toMutableStateFlow(
    scope: CoroutineScope,
    initial: T
): MutableStateFlow<T> {
    val stateFlow = MutableStateFlow(initial)
    scope.launch {
        runCatching {
            this@toMutableStateFlow.collect { value ->
                stateFlow.value = value
            }
        }.onFailure {
            if (it is CancellationException) throw it
            it.printStackTrace()
            Log.e(TAG, "Error while collecting flow: ${it.message}", it)
            // 采集失败时保留上一个可用值：绝不能终止进程（数据解码失败不应静默杀进程）
        }
    }
    return stateFlow
}
