package me.yui.yuihub.data.ai.transformers

import android.util.Log
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Paths
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.data.db.entity.WorkspaceEntity
import me.yui.yuihub.data.repository.WorkspaceRepository
import me.rerere.workspace.PackageMirrorCatalog
import me.rerere.workspace.PackageMirrorSetup
import me.rerere.workspace.WorkspaceShellStatus

/**
 * Workspace 系统提示注入转换器
 *
 * 当助手绑定了一个 shell 已就绪的 workspace 时, 在系统提示词中追加一段引导,
 * 让模型了解 workspace 环境与 workspace_* 工具的使用方式。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return messages
        val workspace = workspaceRepository.getById(workspaceId) ?: return messages
        // 与 ChatService.createWorkspaceToolsIfReady 保持一致: 仅在 shell 就绪时注入
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return messages

        val prompt = buildWorkspacePrompt(workspace, ctx.workspaceCwd) +
            buildAgentsPrompt(workspaceId, ctx.workspaceCwd) +
            buildPackageMirrorPrompt(workspace.packageMirrorSetup())

        // 追加到第一条 system 消息; 若不存在则插入一条
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex]
                    .appendText("\n\n$prompt")
                    .copy(isSynthetic = true)
            }
        } else {
            listOf(UIMessage.system(prompt).copy(isSynthetic = true)) + messages
        }
    }

    private suspend fun buildAgentsPrompt(workspaceId: String, cwd: String?): String {
        // ProotShellRunner 将 HOME 固定为 /root；相对 PWD 按 /workspace 解析。
        val workingDirectory = Paths.get("/workspace")
            .resolve(cwd?.takeIf { it.isNotBlank() } ?: ".")
            .normalize()
        val paths = linkedSetOf(
            "/root/.agents/AGENTS.md",
            "/workspace/AGENTS.md",
            workingDirectory.resolve("AGENTS.md").toString(),
        )
        val instructions = paths.mapNotNull { path ->
            try {
                val size = workspaceRepository.rootfsFileSize(workspaceId, path)
                require(size <= MAX_AGENTS_BYTES) { "AGENTS.md exceeds $MAX_AGENTS_BYTES bytes" }
                val content = ByteArrayOutputStream().use { output ->
                    workspaceRepository.exportRootfsFile(workspaceId, path, output)
                    output.toString(Charsets.UTF_8.name())
                }
                content.takeIf { it.isNotBlank() }?.let { path to it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d("WorkspaceReminder", "Skipping workspace instructions: $path", e)
                null
            }
        }
        if (instructions.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine()
            appendLine("<workspace_instructions>")
            appendLine("Follow the AGENTS.md instructions below.")
            instructions.forEach { (path, content) ->
                appendLine()
                appendLine("AGENTS.md source: $path")
                appendLine(content)
            }
            append("</workspace_instructions>")
        }
    }

    private companion object {
        const val MAX_AGENTS_BYTES = 64L * 1024
    }
}

private fun buildWorkspacePrompt(workspace: WorkspaceEntity, cwd: String? = null): String = buildString {
    appendLine("<workspace>")
    appendLine("You have a persistent Linux workspace \"${workspace.name}\" (sandboxed proot rootfs).")
    appendLine("- Files area: `/workspace` — your working directory; it persists across turns. All workspace-tool paths must be absolute inside the Rootfs (e.g. `/workspace/notes.md`).")
    appendLine("- Tools: `workspace_read_file` / `workspace_write_file` / `workspace_edit_file` (prefer edit for targeted changes over rewriting whole files), `workspace_shell`, `workspace_present_file`.")
    appendLine("- Call `workspace_present_file` only when the user asks to receive a file; never send files on your own initiative.")
    appendLine("- Skills live at `/skills/<skill-name>/SKILL.md` — read a skill before using it, and follow its instructions.")
    appendLine("- Skills and workspace files may mention other AI products (Claude, Codex, etc.) as reference material or tooling docs. These describe OTHER products, not you: your identity, model and capabilities come only from this app and the system prompt — never claim to be or act as another product's assistant.")
    appendLine("- User uploads are mounted at `/upload` (READ-ONLY): read from there, but never modify, overwrite or delete; copy into `/workspace` first if you need to change one.")
    workspace.mountDirList().forEach { mount ->
        val mode = if (mount.readOnly) "READ-ONLY" else "read-write"
        appendLine("- Host directory `${mount.sourcePath}` is mounted at `${mount.target}` ($mode); changes there are visible to the user's other Android apps.")
    }
    if (!cwd.isNullOrBlank()) {
        appendLine("- Current working directory: `$cwd` — the default context for file operations and shell commands.")
    }
    append("</workspace>")
}

/**
 * 国内镜像源已配置时追加的提示词。
 *
 * 光告诉模型「源换好了」不够：没被自动覆盖到的下载（GitHub Release 预编译二进制、
 * Maven 依赖等）依旧会卡住，模型容易因此报「环境不可用」。这里把「慢/连不上就先换国内源
 * 重试」写成可执行的指令，并给出各管理器的具体写法与备选站。
 */
private fun buildPackageMirrorPrompt(setup: PackageMirrorSetup): String {
    if (!setup.active) return ""
    return buildString {
        appendLine()
        appendLine("<package_mirrors>")
        appendLine("This workspace's package managers already point at fast domestic (China) mirrors — keep them; never switch a working install back to official endpoints.")
        if (setup.aptRepoUrl.isNotBlank()) {
            appendLine("- apt: `${setup.aptRepoUrl}` (already rewritten in the sources list; run `apt-get update` before the first install of a session)")
        }
        if (setup.npmRegistry.isNotBlank()) {
            appendLine("- npm: `${setup.npmRegistry}` (already in `/root/.npmrc`, mirrors prebuilt binaries too)")
        }
        if (setup.pipIndexUrl.isNotBlank()) {
            appendLine("- pip: `${setup.pipIndexUrl}` (already in `/etc/pip.conf`)")
        }
        if (setup.goProxy.isNotBlank()) {
            appendLine("- Go: `GOPROXY=${setup.goProxy},direct` (already exported)")
        }
        appendLine("A slow, hanging or unreachable download is a mirror problem, NOT a broken environment — retry once against a domestic mirror (see redirects below) with a raised timeout; if it still fails, report the exact error to the user instead of retrying further.")
        appendLine("One-off redirects: GitHub release/archive hangs → prefix `https://gh-proxy.com/`; npm `--registry=https://registry.npmmirror.com`; pip `-i ${setup.pipIndexUrl.ifBlank { "https://pypi.tuna.tsinghua.edu.cn/simple" }}`; Maven/Gradle deps → add `maven.aliyun.com/repository/public`; Gradle wrapper → rewrite the host to `mirrors.cloud.tencent.com`; apt → swap the `URIs:` line in `/etc/apt/sources.list.d/ubuntu.sources` then `apt-get update` (other mirrors: ${otherAptMirrors(setup)}).")
        appendLine("The base image ships without `ca-certificates`: on TLS errors run `apt-get install -y ca-certificates` first, then retry.")
        append("</package_mirrors>")
    }
}

/** 备选 apt 站：从候选清单里取，排除当前已生效的主机。 */
private fun otherAptMirrors(setup: PackageMirrorSetup): String {
    val currentHost = runCatching { URI(setup.aptRepoUrl).host }.getOrNull()
    return PackageMirrorCatalog.APT_SITES
        .asSequence()
        .map { it.host.removePrefix("http://").removePrefix("https://") }
        .distinct()
        .filter { it != currentHost }
        .joinToString(", ") { "http://$it" }
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
