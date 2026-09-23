package me.rerere.workspace

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 常驻的 proot + bash 会话。
 *
 * AI 的 shell 工具若每条命令都冷启动一个 proot 进程并重跑登录 shell, 在 PRoot 的
 * ptrace 开销下这个固定成本非常可观。会话把 proot/bash 进程保活, 后续命令通过
 * stdin 逐条投递自构成的脚本, 每条命令只付一次「写入 + 等结束 marker」的 IO。
 *
 * 单条命令的协议(全部写入主壳的 stdin, 每行一条完整命令):
 * 1. 命令文本与 stdin 数据 base64 后按块赋值给变量(避开引号/换行转义与管道死锁);
 * 2. 解码命令到 rootfs /tmp 下的临时文件, 在子壳里 cd 后交给子 bash 执行——
 *    语法错误、exit、exec 都只作用于子 bash, 主壳与后续命令不受影响;
 * 3. 子壳输出直出管道, 主壳随后向 stdout/stderr 各写一行含退出码的唯一 END marker。
 *    读取侧用滚动窗口跨块扫描 marker, 正文超过 [MAX_OUTPUT_CHARS] 的部分只计数不保留,
 *    因此截断不丢退出码, 也不需要 shell 侧任何额外进程(每条命令 0 次 fork)。
 *
 * 每个会话的命令严格串行(execute 互斥), 输出不会交错。后台服务按工具层约定
 * 重定向输出到文件; 若未重定向, 其输出会混入后续命令的结果里, 与一次性模式相同。
 */
class PersistentProotSession private constructor(
    private val process: Process,
) {
    private val stdin: OutputStream = BufferedOutputStream(process.outputStream, 64 * 1024)
    private val pumpQueue = LinkedBlockingQueue<PumpChunk>()
    private val writeLock = Any()
    private val executeLock = Any()
    private val alive = AtomicBoolean(true)
    val lastUsedAtMs = AtomicLong(System.currentTimeMillis())

    private class PumpChunk(val isStdout: Boolean, val text: String)

    companion object {
        private val PUMP_POISON = PumpChunk(false, "")
        private const val INIT_MARKER = "__YUIHUB_SESSION_READY"
        private const val MARKER_PREFIX = "__YUIHUB_END_"
        private const val CMD_TMP_PREFIX = "/tmp/.yuihub-cmd-"
        private const val BASE64_CHUNK = 96 * 1024
        private const val POLL_SLICE_MS = 250L
        private const val STDERR_DRAIN_GRACE_MS = 500L

        /**
         * 进程由调用方构造(挂载表等参数只有 Runner 知道), 这里只负责泵与握手。
         * 握手行排队在登录 profile 之后, 读到它即说明主 bash 就绪。
         */
        fun launch(process: Process, initTimeoutMs: Long): PersistentProotSession {
            val session = PersistentProotSession(process)
            session.startPumps()
            try {
                // 握手时校验 /tmp 存在且可写; 异常(如 App 启动清理后目录缺失)就地重建,
                // 否则后续命令的临时脚本写入会直接 exit 127
                session.writeRaw(
                    "if ! [ -d /tmp ] || ! [ -w /tmp ]; then mkdir -p /tmp && chmod 1777 /tmp; fi\n"
                )
                session.writeRaw("printf '%s\\n' '$INIT_MARKER'\n")
                val deadline = System.currentTimeMillis() + initTimeoutMs
                while (true) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) throw IOException("proot session init timed out")
                    val chunk = session.pumpQueue.poll(remaining, TimeUnit.MILLISECONDS)
                        ?: continue
                    if (chunk.text.contains(INIT_MARKER)) break
                }
            } catch (e: Exception) {
                session.destroy()
                throw e
            }
            return session
        }
    }

    fun isAlive(): Boolean = alive.get() && process.isAlive

    /**
     * 执行一条命令。会话进程死亡或流断开抛 [IOException], 由调用方重建会话后重试;
     * 命令超时不抛异常(命令可能有副作用, 不能盲目重试), 返回 timedOut 结果并销毁会话。
     */
    fun execute(context: WorkspaceShellContext): WorkspaceCommandResult = synchronized(executeLock) {
        if (!alive.get() || !process.isAlive) {
            alive.set(false)
            throw IOException("proot session is dead")
        }
        lastUsedAtMs.set(System.currentTimeMillis())
        pumpQueue.clear()

        val marker = MARKER_PREFIX + java.lang.Long.toUnsignedString(System.nanoTime(), 36)
        val script = buildCommandScript(context.command, context.prootCwdSpec(), stdin = context.stdin, marker = marker)

        try {
            synchronized(writeLock) {
                stdin.write(script.toByteArray(Charsets.UTF_8))
                stdin.flush()
            }
        } catch (e: IOException) {
            alive.set(false)
            throw IOException("proot session stdin closed", e)
        }

        // 协程取消会中断 poll; 此时命令可能还在跑, 连会话一起销毁避免留下孤儿进程。
        // 重抛 InterruptedException 让 runInterruptible 正常转成 CancellationException。
        return try {
            collectOutput(marker, context.timeoutMillis)
        } catch (e: InterruptedException) {
            destroy()
            throw e
        }
    }

    fun destroy() {
        if (!alive.compareAndSet(true, false)) return
        // PRoot 收到致命信号时会连带终止所有 tracee, 因此杀掉 proot 本体即可清掉主壳;
        // nohup 且自行重定向了输出的服务会幸存, 与一次性模式的语义一致。
        runCatching {
            synchronized(writeLock) {
                stdin.write("exit\n".toByteArray())
                stdin.flush()
            }
        }
        runCatching { process.destroy() }
        thread(isDaemon = true, name = "yuihub-proot-reaper") {
            runCatching {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            }
        }
    }

    private fun startPumps() {
        startPump(process.inputStream, isStdout = true)
        startPump(process.errorStream, isStdout = false)
    }

    private fun startPump(stream: InputStream, isStdout: Boolean) {
        thread(isDaemon = true, name = if (isStdout) "yuihub-proot-stdout" else "yuihub-proot-stderr") {
            val buffer = CharArray(8 * 1024)
            try {
                stream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        pumpQueue.put(PumpChunk(isStdout, String(buffer, 0, n)))
                    }
                }
            } catch (_: IOException) {
                // 会话被销毁时流被关闭, 静默收尾
            }
            runCatching { pumpQueue.put(PUMP_POISON) }
        }
    }

    private fun collectOutput(marker: String, timeoutMillis: Long): WorkspaceCommandResult {
        val stdout = StreamSide(marker)
        val stderr = StreamSide(marker)
        val deadline = System.currentTimeMillis() + timeoutMillis
        var stderrDeadline = Long.MAX_VALUE
        var streamDied = false

        while (!stdout.done || !stderr.done) {
            val now = System.currentTimeMillis()
            val effectiveDeadline = minOf(deadline, stderrDeadline)
            if (now >= effectiveDeadline) {
                if (!stdout.done) {
                    destroy()
                    return WorkspaceCommandResult(
                        exitCode = -1,
                        stdout = stdout.retained(),
                        stderr = stderr.retained(),
                        timedOut = true,
                        truncated = stdout.truncated || stderr.truncated,
                    )
                }
                break // stdout 已完结, 只差 stderr 的收尾 marker, 用现有内容返回
            }
            val chunk = pumpQueue.poll(minOf(effectiveDeadline - now, POLL_SLICE_MS), TimeUnit.MILLISECONDS)
                ?: continue
            if (chunk === PUMP_POISON) {
                streamDied = true
                break
            }
            val side = if (chunk.isStdout) stdout else stderr
            if (side.done) continue
            side.feed(chunk.text)
            if (side.done && chunk.isStdout) {
                stderrDeadline = now + STDERR_DRAIN_GRACE_MS
            }
        }

        if (streamDied) {
            alive.set(false)
            destroy()
            throw IOException("proot session terminated during command execution")
        }

        return WorkspaceCommandResult(
            exitCode = stdout.exitCode ?: -1,
            stdout = stdout.retained(),
            stderr = stderr.retained(),
            timedOut = false,
            truncated = stdout.truncated || stderr.truncated,
        )
    }

    /**
     * 单个流的收集器: 保留前 [MAX_OUTPUT_CHARS] 字符, 之后的只计数;
     * 用一个小滚动尾窗跨块扫描 marker(命令输出可能把 marker 推到任意远的下游)。
     */
    private class StreamSide(private val marker: String) {
        val content = StringBuilder()
        var totalSeen = 0L
        var exitCode: Int? = null
        var truncated = false
        var done = false
        private var tail = ""

        fun feed(text: String) {
            val combined = tail + text
            val idx = combined.indexOf(marker)
            val tlen = tail.length
            if (idx >= 0) {
                // marker 命中: 只有 combined 中超出 tail 的部分是新内容
                if (idx > tlen) appendContent(combined.substring(tlen, idx))
                val lineEnd = combined.indexOf('\n', idx)
                val line = if (lineEnd < 0) combined.substring(idx) else combined.substring(idx, lineEnd)
                exitCode = Regex("rc=(-?\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
                // marker 行之后的残留(理论上无)并入内容
                if (lineEnd >= 0 && lineEnd + 1 < combined.length) {
                    appendContent(combined.substring(lineEnd + 1))
                }
                done = true
                return
            }
            appendContent(text)
            tail = if (combined.length >= marker.length - 1) {
                combined.substring(combined.length - (marker.length - 1))
            } else {
                combined
            }
        }

        private fun appendContent(text: String) {
            totalSeen += text.length
            val remaining = MAX_OUTPUT_CHARS - content.length
            if (remaining > 0) {
                content.append(text, 0, minOf(text.length, remaining))
            }
            truncated = totalSeen > MAX_OUTPUT_CHARS
        }

        fun retained(): String = content.toString()
    }

    private fun writeRaw(text: String) {
        synchronized(writeLock) {
            stdin.write(text.toByteArray(Charsets.UTF_8))
            stdin.flush()
        }
    }

    private fun buildCommandScript(
        command: String,
        cwdSpec: String,
        stdin: ByteArray?,
        marker: String,
    ): String = buildString {
        append("__YUIHUB_TMP='").append(CMD_TMP_PREFIX).append(java.lang.Long.toUnsignedString(System.nanoTime(), 36)).append("'\n")
        append("__YUIHUB_TMP_DIR=\"\${__YUIHUB_TMP%/*}\"\n")
        val cmdChunks = appendAssignments(this, "__YUIHUB_CMD", command.toByteArray(Charsets.UTF_8))
        val inChunks = if (stdin != null && stdin.isNotEmpty()) {
            appendAssignments(this, "__YUIHUB_IN", stdin)
        } else {
            0
        }
        append("__YUIHUB_CWD='").append(cwdSpec.replace("'", "'\\''")).append("'\n")
        append("__YUIHUB_MK='").append(marker).append("'\n")
        // /tmp 自愈: 目录被清理(如 App 启动时 cleanupAllTempDirs)时先重建, 避免脚本写入失败 exit 127
        append("mkdir -p -- \"\${__YUIHUB_TMP_DIR}\"\n")
        append("printf '%s' \"").append(varRefs("__YUIHUB_CMD", cmdChunks)).append("\" | base64 -d > \"\$__YUIHUB_TMP\"\n")
        // cd 失败时 && 短路, 子壳退出码 = cd 的退出码; 不用 "|| exit $?" —— 此处的 $? 是 read 的, 不是上一行的
        if (inChunks > 0) {
            append("printf '%s' \"").append(varRefs("__YUIHUB_IN", inChunks)).append("\" | base64 -d | { cd -- \"\$__YUIHUB_CWD\" && /bin/bash \"\$__YUIHUB_TMP\"; }\n")
        } else {
            // 用子壳(圆括号): 花括号组会在主壳上下文里 cd, 失败或 exit 会把整个常驻会话杀掉
            append("( cd -- \"\$__YUIHUB_CWD\" && /bin/bash \"\$__YUIHUB_TMP\" ) < /dev/null\n")
        }
        // __YUIHUB_RC 由主壳循环在上一行 eval 后写入(见 PERSISTENT_MAIN_COMMAND), marker 行直接取用
        append("printf '%s\\n' \"\$__YUIHUB_MK rc=\$__YUIHUB_RC\"\n")
        append("printf '%s\\n' \"\$__YUIHUB_MK rc=\$__YUIHUB_RC\" >&2\n")
        append("rm -f -- \"\$__YUIHUB_TMP\"\n")
    }

    /** base64 分块赋值: `<name>0='..', <name>1='..' .., <name>_N=块数`, 返回块数 */
    private fun appendAssignments(sb: StringBuilder, name: String, data: ByteArray): Int {
        val encoded = Base64.getEncoder().encodeToString(data)
        var index = 0
        var offset = 0
        while (offset < encoded.length) {
            val end = minOf(offset + BASE64_CHUNK, encoded.length)
            sb.append(name).append(index).append("='").append(encoded, offset, end).append("'\n")
            index++
            offset = end
        }
        sb.append(name).append("_N=").append(index).append('\n')
        return index
    }

    /** 生成 "$name0$name1..." 形式的变量引用串, printf 一次性拼接全部分块 */
    private fun varRefs(name: String, chunkCount: Int): String =
        (0 until maxOf(1, chunkCount)).joinToString("") { "\$$name$it" }

    private fun WorkspaceShellContext.prootCwdSpec(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WorkspaceManager.ROOTFS_WORKSPACE_DIR
        } else {
            WorkspaceManager.ROOTFS_WORKSPACE_DIR + "/" + normalized
        }
    }
}
