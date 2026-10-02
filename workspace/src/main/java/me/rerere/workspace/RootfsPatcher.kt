package me.rerere.workspace

import java.io.File
import java.nio.file.Files

class RootfsPatcher {
    fun patch(
        linuxDir: File,
        options: RootfsPatchOptions = RootfsPatchOptions(),
    ) {
        val etcDir = File(linuxDir, "etc")
        if (!etcDir.isDirectory) return

        // patch 是幂等读改写, 参数未变时重复执行纯属浪费;
        // 每条 shell 命令都要走一次, 这里用 marker 记住已完成的参数集直接返回。
        val marker = File(etcDir, PATCH_MARKER)
        val signature = options.markerSignature()
        val alreadyPatched = marker.isFile && marker.readText() == signature
        // /tmp 不在 marker 短路内: App 启动时 cleanupAllTempDirs 会删掉 rootfs 的 /tmp 与
        // /var/tmp, 若只靠 marker 短路, 重启后首次命令的临时脚本(写在 /tmp)会因目录缺失
        // 直接 exit 127。这里每次都重建, 仅几个 mkdir/stat, 开销可忽略。
        ensureTempDirs(linuxDir)
        // CA 证书同理放在短路外: 每次仅 stat 一个文件, 缺失时才重建。
        // ubuntu-base 最小镜像不含 ca-certificates, 不补的话所有 https 请求都会失败
        // (curl 报 000), 而 apt 装证书又依赖网络, 形成死循环——这里直接复用
        // Android 系统证书库离线生成, 新 rootfs 与旧 rootfs 都能自愈。
        ensureCaCertificates(etcDir)
        if (alreadyPatched) return

        ensureRootfsDns(etcDir, options.nameservers)
        ensureHosts(etcDir, options.hostname)
        ensureHostname(etcDir, options.hostname)
        ensureLocale(etcDir, options.locale)
        ensureGroupNames(etcDir, options.groupIds.ifEmpty { currentSupplementaryGroupIds() })
        ensureAptMirror(linuxDir)
        marker.writeText(signature)
    }

    /**
     * 用 Android 系统证书库离线生成 Rootfs 的 CA bundle。
     *
     * 生成的 [ca-certificates.crt] 是 Debian/Ubuntu 系 curl/openssl 的默认信任链，
     * 同时把每张证书按 Android 原有哈希名（*.0）拷贝到 /etc/ssl/certs，
     * 两条路径都能在 `apt-get install ca-certificates` 之前的裸镜像里立即生效。
     */
    private fun ensureCaCertificates(etcDir: File) {
        val sslCertsDir = File(etcDir, "ssl/certs")
        val bundle = File(sslCertsDir, "ca-certificates.crt")
        if (bundle.isFile && bundle.length() > 0) return

        val sources = CA_SOURCE_DIRS.map(::File).filter { it.isDirectory }
        if (sources.isEmpty()) return

        sslCertsDir.mkdirs()
        val builder = StringBuilder()
        var count = 0
        sources.forEach { dir ->
            val certs = dir.listFiles { file -> file.isFile && file.name.endsWith(".0") } ?: return@forEach
            certs.forEach { cert ->
                runCatching {
                    val text = cert.readText()
                    if (!text.contains("BEGIN CERTIFICATE")) return@runCatching
                    val target = File(sslCertsDir, cert.name)
                    if (!target.exists()) target.writeText(text)
                    builder.append(text)
                    if (!text.endsWith("\n")) builder.append('\n')
                    count++
                }
            }
        }
        if (count > 0) {
            bundle.writeText(builder.toString())
        }
    }

    /**
     * 把 apt 源换成国内镜像。ubuntu-base 的源默认指向 ports.ubuntu.com / archive.ubuntu.com，
     * 国内直连极慢，AI 装包（node/git 等）经常超时被当成「环境不可用」。
     *
     * 用户在工作区「环境配置」里显式选过源时不再插手，避免每条 shell 命令都把用户选的站点
     * 抢回默认站。具体改写规则（deb822 + one-line、ubuntu 与 ubuntu-ports 目录）见 [AptSources]。
     */
    private fun ensureAptMirror(linuxDir: File) {
        if (AptSources.isExplicit(linuxDir)) return
        AptSources.rewriteTo(linuxDir, AptSources.DEFAULT_MIRROR, explicit = false)
    }

    private fun ensureGroupNames(etcDir: File, groupIds: List<Long>) {
        val target = File(etcDir, "group")
        if (!target.exists()) {
            target.writeText("root:x:0:\n")
        }
        val lines = target.readLines().toMutableList()
        val existingIds = lines.mapNotNull { line ->
            line.split(':').getOrNull(2)?.toLongOrNull()
        }.toSet()
        val existingNames = lines.mapNotNull { line ->
            line.substringBefore(':').takeIf { it.isNotBlank() }
        }.toSet()
        val additions = groupIds
            .filter { it > 0 && it !in existingIds }
            .distinct()
            .map { id ->
                val baseName = "android_gid_$id"
                val name = if (baseName in existingNames) "${baseName}_workspace" else baseName
                "$name:x:$id:"
            }
        if (additions.isEmpty()) return

        target.appendText(
            buildString {
                if (target.length() > 0 && !target.readText().endsWith('\n')) {
                    appendLine()
                }
                additions.forEach { appendLine(it) }
            }
        )
    }

    private fun ensureRootfsDns(
        etcDir: File,
        nameservers: List<String>,
    ) {
        val resolvConf = File(etcDir, "resolv.conf")
        val shouldWrite = when {
            Files.isSymbolicLink(resolvConf.toPath()) -> true
            !resolvConf.exists() -> true
            !resolvConf.isFile -> true
            else -> resolvConf.readText()
                .lineSequence()
                .filter { it.trimStart().startsWith("nameserver ") }
                .none { line ->
                    val server = line.trim().removePrefix("nameserver").trim()
                    server.isNotBlank() && server !in LOCAL_RESOLVERS
                }
        }
        if (!shouldWrite) return

        if (resolvConf.exists() || Files.isSymbolicLink(resolvConf.toPath())) {
            resolvConf.delete()
        }

        val servers = nameservers
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_DNS_SERVERS)
            .ifEmpty { DEFAULT_DNS_SERVERS }

        resolvConf.writeText(
            buildString {
                appendLine("# Generated by YuiHub workspace.")
                servers.forEach { appendLine("nameserver $it") }
                appendLine("options edns0 trust-ad")
            }
        )
    }

    private fun ensureHosts(etcDir: File, hostname: String) {
        val hosts = File(etcDir, "hosts")
        val lines = if (hosts.isFile) hosts.readLines() else emptyList()
        val hasIpv4Localhost = lines.any { line ->
            val normalized = line.substringBefore('#').trim().split(WHITESPACE_REGEX)
            normalized.firstOrNull() == "127.0.0.1" && "localhost" in normalized.drop(1)
        }
        val hasIpv6Localhost = lines.any { line ->
            val normalized = line.substringBefore('#').trim().split(WHITESPACE_REGEX)
            normalized.firstOrNull() == "::1" && "localhost" in normalized.drop(1)
        }
        if (hasIpv4Localhost && hasIpv6Localhost) return

        hosts.parentFile?.mkdirs()
        hosts.appendText(
            buildString {
                if (hosts.exists() && hosts.length() > 0 && !hosts.readText().endsWith('\n')) {
                    appendLine()
                }
                if (!hasIpv4Localhost) {
                    append("127.0.0.1 localhost")
                    if (hostname.isNotBlank() && hostname != "localhost") {
                        append(" ")
                        append(hostname)
                    }
                    appendLine()
                }
                if (!hasIpv6Localhost) {
                    appendLine("::1 localhost ip6-localhost ip6-loopback")
                }
            }
        )
    }

    private fun ensureHostname(etcDir: File, hostname: String) {
        val target = File(etcDir, "hostname")
        if (target.isFile && target.readText().trim().isNotBlank()) return
        target.writeText("${hostname.ifBlank { DEFAULT_HOSTNAME }}\n")
    }

    private fun ensureLocale(etcDir: File, locale: String) {
        val defaultDir = File(etcDir, "default").apply { mkdirs() }
        val target = File(defaultDir, "locale")
        val lines = if (target.isFile) target.readLines().toMutableList() else mutableListOf()
        if (lines.any { it.trim().startsWith("LANG=") }) return
        lines += "LANG=$locale"
        target.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun ensureTempDirs(linuxDir: File) {
        listOf("tmp", "var/tmp", "root").forEach { path ->
            File(linuxDir, path).mkdirs()
        }
        listOf(File(linuxDir, "tmp"), File(linuxDir, "var/tmp")).forEach { dir ->
            dir.setReadable(true, false)
            dir.setWritable(true, false)
            dir.setExecutable(true, false)
        }
        File(linuxDir, "root").apply {
            setReadable(true, true)
            setWritable(true, true)
            setExecutable(true, true)
        }
    }

    private fun currentSupplementaryGroupIds(): List<Long> {
        val status = File("/proc/self/status")
        if (!status.isFile) return emptyList()
        val groups = status.readLines().firstOrNull { it.startsWith("Groups:") } ?: return emptyList()
        return groups
            .removePrefix("Groups:")
            .trim()
            .split(WHITESPACE_REGEX)
            .mapNotNull { it.toLongOrNull() }
    }

    private companion object {
        private const val PATCH_MARKER = ".yuihub-patched"
        private const val MAX_DNS_SERVERS = 3
        private const val DEFAULT_HOSTNAME = "localhost"
        private val WHITESPACE_REGEX = Regex("\\s+")
        // Android 系统 CA 存储的候选位置（新版以 conscrypt apex 为主，旧版在 /system/etc）
        private val CA_SOURCE_DIRS = listOf(
            "/apex/com.android.conscrypt/cacerts",
            "/system/etc/security/cacerts",
            "/system/etc/cacerts",
        )
        private val LOCAL_RESOLVERS = setOf(
            "127.0.0.1",
            "127.0.0.53",
            "::1",
        )
        private val DEFAULT_DNS_SERVERS = listOf(
            "1.1.1.1",
            "8.8.8.8",
            "223.5.5.5",
        )
    }
}

data class RootfsPatchOptions(
    val nameservers: List<String> = emptyList(),
    val hostname: String = "localhost",
    val locale: String = "C.UTF-8",
    val groupIds: List<Long> = emptyList(),
) {
    /** 参数摘要, 用于判断已 patch 的 rootfs 是否需要重新 patch */
    internal fun markerSignature(): String =
        "dns=${nameservers.sorted().joinToString(",")};host=$hostname;locale=$locale;groups=${groupIds.sorted().joinToString(",")}"
}
