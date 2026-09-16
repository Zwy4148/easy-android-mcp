package com.trae.androidmcp

import android.content.Context
import java.io.File

/**
 * frp 客户端管理器（frpc 二进制子进程版）。
 *
 * 将 assets/frpc 拷贝到 filesDir 并赋予可执行权限，生成 frpc.toml 后以子进程启动。
 * stdout/stderr 汇入 LogBuffer（标签 FRPC）。
 */
object FrpcManager {

    private const val TAG = "FRPC"
    private const val ASSET_NAME = "frpc"

    private var process: Process? = null

    @Volatile
    var running: Boolean = false
        private set

    private var ctxRef: Context? = null

    /** 必须在 Service/Activity 中调用一次注入 Context */
    fun init(ctx: Context) {
        ctxRef = ctx.applicationContext
    }

    private fun binFile(ctx: Context): File = File(ctx.filesDir, "frpc")
    private fun confFile(ctx: Context): File = File(ctx.filesDir, "frpc.toml")

    /**
     * 确保二进制已从 assets 拷贝到 filesDir 并可执行。
     * 仅在文件不存在或不可执行时拷贝。
     */
    private fun ensureBinary(ctx: Context): File {
        val bin = binFile(ctx)
        if (!bin.exists() || !bin.canExecute()) {
            val assetSize = ctx.assets.open(ASSET_NAME).use { it.available().toLong() }
            LogBuffer.i(TAG, "部署 frpc 二进制（约 ${assetSize / 1024} KB 压缩）")
            ctx.assets.open(ASSET_NAME).use { input ->
                bin.outputStream().use { output -> input.copyTo(output) }
            }
            bin.setExecutable(true, false)
        }
        return bin
    }

    /**
     * 根据 Prefs 生成 frpc.toml 内容（frp 0.62.1 TOML 格式）。
     * 若启用自定义完整配置则直接使用原文，并在缺少日志配置时补 log.to = console。
     */
    private fun buildConfig(ctx: Context): String {
        if (Prefs.isFrpUseRaw(ctx)) {
            var raw = Prefs.getFrpRawConfig(ctx)
            if (raw.isBlank()) throw IllegalStateException("启用了原始配置但内容为空")
            // 若未指定日志输出，强制 console，避免写文件权限错误
            if (!raw.contains(Regex("log\\.to\\s*=")) && !raw.contains(Regex("log\\.file\\s*="))) {
                raw = "log.to = \"console\"\n$raw"
            }
            return raw
        }
        val sb = StringBuilder()
        sb.append("serverAddr = \"").append(Prefs.getFrpServerAddr(ctx)).append("\"\n")
        sb.append("serverPort = ").append(Prefs.getFrpServerPort(ctx)).append("\n")
        val token = Prefs.getFrpToken(ctx)
        if (token.isNotBlank()) sb.append("auth.token = \"").append(token).append("\"\n")
        val user = Prefs.getFrpUser(ctx)
        if (user.isNotBlank()) sb.append("user = \"").append(user).append("\"\n")
        // 日志走控制台，避免 frp 默认写文件到不可写目录导致 permission denied
        sb.append("log.to = \"console\"\n")
        sb.append("log.level = \"info\"\n")
        sb.append("\n")
        sb.append("[[proxies]]\n")
        sb.append("name = \"").append(Prefs.getFrpProxyName(ctx)).append("\"\n")
        sb.append("type = \"").append(Prefs.getFrpProxyType(ctx)).append("\"\n")
        sb.append("localIP = \"").append(Prefs.getFrpLocalIp(ctx)).append("\"\n")
        sb.append("localPort = ").append(Prefs.getFrpLocalPort(ctx)).append("\n")
        val remotePort = Prefs.getFrpRemotePort(ctx)
        if (remotePort > 0) sb.append("remotePort = ").append(remotePort).append("\n")
        val domains = Prefs.getFrpCustomDomains(ctx)
        if (domains.isNotBlank()) {
            val list = domains.split(",").joinToString(", ") { "\"${it.trim()}\"" }
            sb.append("customDomains = [").append(list).append("]\n")
        }
        return sb.toString()
    }

    /** 启动 frpc。返回 true 表示进程已拉起。 */
    @Synchronized
    fun start(): Boolean {
        val ctx = ctxRef ?: return false
        if (running) {
            LogBuffer.w(TAG, "frpc 已在运行，忽略重复启动")
            return true
        }
        return try {
            val bin = ensureBinary(ctx)
            val conf = confFile(ctx)
            val config = buildConfig(ctx)
            conf.writeText(config, Charsets.UTF_8)
            LogBuffer.i(TAG, "启动 frpc → ${Prefs.getFrpServerAddr(ctx)}:${Prefs.getFrpServerPort(ctx)}")
            LogBuffer.d(TAG, "配置:\n$config")

            val pb = ProcessBuilder(bin.absolutePath, "-c", conf.absolutePath)
                .redirectErrorStream(true)
                .directory(ctx.filesDir)
            val p = pb.start()
            process = p
            running = true
            Thread({ readOutput(p) }, "frpc-reader").apply { isDaemon = true }.start()
            Thread({ waitExit(p) }, "frpc-watcher").apply { isDaemon = true }.start()
            true
        } catch (t: Throwable) {
            LogBuffer.e(TAG, "启动失败: ${t.message}")
            running = false
            process = null
            false
        }
    }

    /** 停止 frpc */
    @Synchronized
    fun stop() {
        val p = process
        if (p != null) {
            try {
                p.destroy()
            } catch (_: Throwable) {
            }
            process = null
        }
        running = false
        LogBuffer.i(TAG, "frpc 已停止")
    }

    /** 从子进程同步运行状态 */
    fun refreshStatus() {
        // running 字段由 watcher 线程维护；此处无需额外操作
    }

    /** frpc 无状态 JSON 接口，保留兼容 */
    fun getStatus(): String = "{}"

    private fun readOutput(p: Process) {
        try {
            p.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    when {
                        line.contains("[E]") || line.contains("error", ignoreCase = true) ->
                            LogBuffer.e(TAG, line)
                        line.contains("[W]") || line.contains("warn", ignoreCase = true) ->
                            LogBuffer.w(TAG, line)
                        else -> LogBuffer.i(TAG, line)
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun waitExit(p: Process) {
        try {
            val code = p.waitFor()
            if (running) {
                running = false
                process = null
                LogBuffer.w(TAG, "frpc 进程退出，code=$code")
            }
        } catch (_: Throwable) {
        }
    }
}
