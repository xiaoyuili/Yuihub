package me.yui.yuihub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子代理生成收集与 cancel 竞速的回归测试。
 *
 * 复刻 executeChildAgent 中的竞速块（修复前后两版）在 JVM 上验证：
 * - 旧实现：collect 正常返回后，coroutineScope 还要等挂在 cancelSignal.await() 上的
 *   监听协程——它永不完成，scope 永久挂起，executeChildAgent 不返回，
 *   completeAsyncTask 永远不执行 → 异步任务终态丢失（poll 一直 running，直到 cancel 才解锁）。
 * - 新实现：select 竞速，任一方胜出立即返回，无悬空子协程。
 */
class SubagentCancelRaceTest {

    private class Cancelled : RuntimeException("child agent cancelled")

    /** 修复前：collect 正常返回后等待监听协程 → 挂起 */
    private suspend fun raceBeforeFix(
        collect: suspend () -> Unit,
        cancelSignal: CompletableDeferred<Unit>,
    ) {
        coroutineScope {
            launch {
                cancelSignal.await()
                throw Cancelled()
            }
            collect()
        }
    }

    /** 修复后：select 竞速（与 ChatService.executeChildAgent 相同的结构） */
    private suspend fun raceAfterFix(
        collect: suspend () -> Unit,
        cancelSignal: CompletableDeferred<Unit>,
    ) {
        try {
            coroutineScope {
                val collectJob = launch { collect() }
                val cancelled = select<Boolean> {
                    collectJob.onJoin { false }
                    cancelSignal.onAwait { true }
                }
                if (cancelled) {
                    collectJob.cancel()
                    throw Cancelled()
                }
            }
        } catch (e: Cancelled) {
            throw e
        }
    }

    @Test
    fun `normal completion returns promptly after fix`() = runBlocking {
        val cancelSignal = CompletableDeferred<Unit>()
        var completed = false
        try {
            withTimeout(5_000) {
                raceAfterFix({ completed = true }, cancelSignal)
            }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("正常完成后竞速块应在 5s 内返回（未修复时在此挂死）", e)
        }
        assertTrue("collect 应已正常执行完", completed)
    }

    @Test
    fun `old implementation hangs on normal completion (documents the defect)`() {
        val outcome = runCatching {
            runBlocking {
                withTimeout(300) {
                    raceBeforeFix({ /* 正常完成 */ }, CompletableDeferred())
                }
            }
        }
        val error = outcome.exceptionOrNull()
        assertTrue(
            "旧实现应挂起直到超时，实际: $error",
            error is TimeoutCancellationException,
        )
    }

    @Test
    fun `cancel still terminates a running collect after fix`() {
        val outcome = runCatching {
            runBlocking {
                withTimeout(5_000) {
                    val cancelSignal = CompletableDeferred<Unit>()
                    var cancelled = false
                    try {
                        coroutineScope {
                            // 模拟 cancel_agent：100ms 后从外部完成取消信号
                            launch {
                                kotlinx.coroutines.delay(100)
                                cancelSignal.complete(Unit)
                            }
                            raceAfterFix(
                                collect = {
                                    // 模拟长生成：挂在取消信号上；signal 胜出时 scope 级联取消本协程
                                    cancelSignal.join()
                                },
                                cancelSignal = cancelSignal,
                            )
                        }
                    } catch (e: Cancelled) {
                        cancelled = true
                    }
                    check(cancelled) { "cancel 胜出时应抛 Cancelled" }
                }
            }
        }
        assertTrue("cancel 路径应在 5s 内完成且命中 Cancelled: ${outcome.exceptionOrNull()}", outcome.isSuccess)
    }

    @Test
    fun `late cancel after the race returned does not affect a finished task`() = runBlocking {
        val cancelSignal = CompletableDeferred<Unit>()
        var finishedNormally = false
        try {
            withTimeout(5_000) {
                coroutineScope {
                    // 模拟任务早已完成后 cancel_agent 才到达：collect 立即结束，
                    // 竞速经 onJoin 胜出返回，之后信号才被完成
                    launch {
                        kotlinx.coroutines.delay(200)
                        cancelSignal.complete(Unit)
                    }
                    raceAfterFix(
                        collect = { finishedNormally = true },
                        cancelSignal = cancelSignal,
                    )
                }
            }
        } catch (e: Cancelled) {
            throw AssertionError("collect 已完成后才到达的 cancel 不应把结果改成 cancelled", e)
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("竞速块挂死", e)
        }
        assertTrue(finishedNormally)
    }

    @Test
    fun `status mapping covers all result statuses`() {
        assertEquals(SubagentTaskStatus.COMPLETED, SubagentManager.statusFromResult("ok"))
        assertEquals(SubagentTaskStatus.COMPLETED, SubagentManager.statusFromResult("empty_output"))
        assertEquals(SubagentTaskStatus.TIMEOUT, SubagentManager.statusFromResult("timeout"))
        assertEquals(SubagentTaskStatus.CANCELLED, SubagentManager.statusFromResult("cancelled"))
        assertEquals(SubagentTaskStatus.FAILED, SubagentManager.statusFromResult("error"))
        assertEquals(SubagentTaskStatus.FAILED, SubagentManager.statusFromResult("unknown"))
    }
}
