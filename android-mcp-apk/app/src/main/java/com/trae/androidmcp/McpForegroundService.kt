package com.trae.androidmcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * MCP 前台服务。
 *
 * 内嵌 NanoHTTPD，对外暴露标准 MCP（JSON-RPC 2.0 over HTTP）接口。
 * 控制能力以无障碍服务 [AccessibilityBridge] 为主，ADB shell 为辅。
 */
class McpForegroundService : Service() {

    companion object {
        private const val TAG = "McpForegroundService"
        private const val CHANNEL_ID = "android_mcp_channel"
        private const val NOTIFICATION_ID = 0xA11C

        /** 协议版本：与官方 MCP SDK 对齐 */
        private const val MCP_PROTOCOL_VERSION = "2024-11-05"

        /** 服务运行状态，供 Activity 轮询展示 */
        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var listenAddress: String = ""
            private set

        @Volatile
        var adbConnected: Boolean = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, McpForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, McpForegroundService::class.java))
        }
    }

    private var server: McpServer? = null
    private var adb: AdbShellClient? = null

    override fun onCreate() {
        super.onCreate()
        LogBuffer.init(this)
        FrpcManager.init(this)
        MacroStore.init(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("MCP 服务启动中…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server != null) return START_STICKY

        val port = Prefs.getPort(this)
        val token = Prefs.getToken(this)
        LogBuffer.i("MCP", "启动服务 port=$port token=${token.take(8)}…")

        // ADB 备选通道：仅在用户显式开启时连接
        if (Prefs.isAdbEnabled(this)) {
            val target = Prefs.getAdbTarget(this)
            LogBuffer.i("ADB", "连接 adbd $target")
            adb = AdbShellClient().apply {
                try {
                    connect(target)
                    adbConnected = true
                    LogBuffer.i("ADB", "连接成功")
                } catch (t: Throwable) {
                    LogBuffer.e("ADB", "连接失败: ${t.message}")
                    adbConnected = false
                }
            }
        }

        server = McpServer(port, token, this, adb).apply {
            try {
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                running = true
                listenAddress = computeListenAddress(port)
                updateNotification("MCP 运行中 · :$port")
                Log.i(TAG, "MCP server listening on $listenAddress")
                LogBuffer.i("MCP", "HTTP 服务已启动 $listenAddress")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start MCP server", t)
                LogBuffer.e("MCP", "启动失败: ${t.message}")
                running = false
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            server?.stop()
        } catch (_: Throwable) {
        }
        try {
            adb?.close()
        } catch (_: Throwable) {
        }
        server = null
        adb = null
        running = false
        adbConnected = false
        listenAddress = ""
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "Android MCP 服务",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "保持 MCP 自动化服务常驻"
                    setShowBadge(false)
                }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Android MCP")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun computeListenAddress(port: Int): String {
        val ip = getLocalIpAddress() ?: "127.0.0.1"
        return "$ip:$port"
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    // ==================================================================
    // MCP HTTP 服务器实现
    // ==================================================================

    private class McpServer(
        port: Int,
        private val token: String,
        private val ctx: Context,
        private val adb: AdbShellClient?
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            val remoteIp = session.remoteIpAddress ?: "unknown"
            // 入口日志：只要请求到达本服务就一定会有这条
            LogBuffer.i("MCP", "← $remoteIp ${session.method} ${session.uri}")
            LogBuffer.d("MCP", "headers: ${session.headers.keys.joinToString()}")

            // 仅接受 POST /mcp（兼容根路径）
            if (session.method != Method.POST) {
                LogBuffer.w("MCP", "拒绝非 POST 请求: ${session.method}")
                return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "POST only")
            }

            // ---- 鉴权：支持多种传 token 方式 ----
            // 1) Authorization header（大小写不敏感）
            val authHeader = session.headers.entries.firstOrNull {
                it.key.equals("authorization", ignoreCase = true)
            }?.value
            // 2) ?token= query 参数
            val queryToken = parseQueryToken(session.queryParameterString)

            // 规范化：去掉可能的 "Bearer " 前缀，统一比较裸 token
            val providedRaw = (authHeader ?: queryToken)?.trim()
            val providedToken = providedRaw
                ?.removePrefix("Bearer ")
                ?.removePrefix("bearer ")
                ?.trim()

            if (providedToken == null || providedToken != token) {
                val reason = when {
                    providedRaw == null -> "未携带 Authorization header 或 ?token= 参数（headers=${session.headers.keys.joinToString()}）"
                    providedToken == null -> "Authorization 值为空"
                    else -> "Token 不匹配（收到前8位: ${providedToken.take(8)}…，期望前8位: ${token.take(8)}…）"
                }
                LogBuffer.e("AUTH", "$remoteIp 鉴权失败: $reason")
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                    """{"error":"unauthorized","detail":"$reason"}""")
            }

            LogBuffer.i("AUTH", "$remoteIp 鉴权通过")

            // 读取请求体
            val len = session.headers.entries.firstOrNull {
                it.key.equals("content-length", ignoreCase = true)
            }?.value?.toIntOrNull() ?: 0
            val body = if (len > 0) {
                val buf = ByteArray(len)
                var off = 0
                while (off < len) {
                    val n = session.inputStream.read(buf, off, len - off)
                    if (n < 0) break
                    off += n
                }
                String(buf, 0, off, Charsets.UTF_8)
            } else {
                val sb = StringBuilder()
                val tmp = ByteArray(4096)
                var n: Int
                while (session.inputStream.read(tmp).also { n = it } > 0) {
                    sb.append(String(tmp, 0, n))
                }
                sb.toString()
            }

            LogBuffer.d("MCP", "请求体: ${body.take(200)}")
            val resp = handleRpc(body)
            LogBuffer.d("MCP", "响应: ${resp.take(200)}")
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", resp)
        }

        /** 从 query string 中解析 token 参数 */
        private fun parseQueryToken(query: String?): String? {
            if (query.isNullOrBlank()) return null
            query.split("&").forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val k = pair.substring(0, idx)
                    val v = pair.substring(idx + 1)
                    if (k.equals("token", ignoreCase = true)) {
                        return java.net.URLDecoder.decode(v, "UTF-8")
                    }
                }
            }
            return null
        }

        // ------------------------------------------------------------------
        // JSON-RPC 分发
        // ------------------------------------------------------------------

        private fun handleRpc(body: String): String {
            val req = try {
                JSONObject(body)
            } catch (e: Throwable) {
                return errorJson(null, -32700, "Parse error")
            }
            val id = req.opt("id")
            val method = req.optString("method", "")
            val params = req.optJSONObject("params") ?: JSONObject()

            return try {
                when (method) {
                    "initialize" -> resultJson(id, initialize(params))
                    "notifications/initialized" -> resultJson(id, JSONObject())
                    "ping" -> resultJson(id, JSONObject())
                    "tools/list" -> resultJson(id, toolsList())
                    "tools/call" -> resultJson(id, toolsCall(params))
                    else -> errorJson(id, -32601, "Method not found: $method")
                }
            } catch (e: Throwable) {
                errorJson(id, -32603, "Internal error: ${e.message}")
            }
        }

        private fun initialize(params: JSONObject): JSONObject {
            // 读取客户端声明的协议版本（服务端始终返回自身支持的版本）
            params.optString("protocolVersion", MCP_PROTOCOL_VERSION)
            return JSONObject().apply {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                put("capabilities", JSONObject().put("tools", JSONObject()))
                put("serverInfo", JSONObject()
                    .put("name", "android-mcp")
                    .put("version", "1.0.0"))
            }
        }

        private fun toolsList(): JSONObject {
            return JSONObject().put("tools", JSONArray(listOf(
                toolDef(
                    "get_ui_xml",
                    "导出当前界面的控件树 XML（对齐 uiautomator dump 格式）",
                    mapOf()
                ),
                toolDef(
                    "get_ui_elements",
                    "以 JSON 数组返回当前界面所有可交互控件（含坐标、文本、resource-id）",
                    mapOf()
                ),
                toolDef(
                    "tap",
                    "点击屏幕坐标 (x,y)",
                    mapOf(
                        "x" to mapOf("type" to "number", "description" to "横坐标(像素)"),
                        "y" to mapOf("type" to "number", "description" to "纵坐标(像素)")
                    ),
                    listOf("x", "y")
                ),
                toolDef(
                    "swipe",
                    "从 (x1,y1) 滑动到 (x2,y2)",
                    mapOf(
                        "x1" to mapOf("type" to "number"),
                        "y1" to mapOf("type" to "number"),
                        "x2" to mapOf("type" to "number"),
                        "y2" to mapOf("type" to "number"),
                        "duration_ms" to mapOf("type" to "integer", "description" to "手势持续毫秒(50~10000)")
                    ),
                    listOf("x1", "y1", "x2", "y2")
                ),
                toolDef(
                    "input_text",
                    "对当前聚焦输入框设置文本（原生支持中文/Emoji）",
                    mapOf(
                        "text" to mapOf("type" to "string", "description" to "要输入的文本"),
                        "append" to mapOf("type" to "boolean", "description" to "true=追加, false=覆盖(默认)")
                    ),
                    listOf("text")
                ),
                toolDef(
                    "press_key",
                    "执行全局按键动作",
                    mapOf(
                        "key" to mapOf("type" to "string",
                            "description" to "BACK | HOME | APP_SWITCH | NOTIFICATIONS | QUICK_SETTINGS | LOCK_SCREEN")
                    ),
                    listOf("key")
                ),
                toolDef(
                    "screenshot",
                    "截图并返回 Base64 PNG（Android 11+ 使用系统截图 API）",
                    mapOf(
                        "scale" to mapOf("type" to "number", "description" to "缩放比例 0.1~1.0，默认 0.5")
                    )
                ),
                toolDef(
                    "adb_shell",
                    "通过 ADB 执行 shell 命令（需已开启并连接无线调试，作为无障碍的备选通道）",
                    mapOf(
                        "command" to mapOf("type" to "string", "description" to "shell 命令，如 dumpsys window | grep mCurrentFocus")
                    ),
                    listOf("command")
                ),
                toolDef(
                    "get_status",
                    "获取设备与服务状态（包名、无障碍可用、ADB 可用、前台 Activity）",
                    mapOf()
                ),
                toolDef(
                    "unlock",
                    "自动唤醒并解锁屏幕（字母数字密码）。password 可选，不传则使用 App 内存储的解锁密码。",
                    mapOf(
                        "password" to mapOf("type" to "string", "description" to "锁屏密码，不传则使用已保存的密码")
                    )
                ),
                toolDef(
                    "get_logs",
                    "获取 MCP 服务最近的运行日志（鉴权、请求、错误），用于排障",
                    mapOf(
                        "lines" to mapOf("type" to "integer", "description" to "返回最近 N 行，默认 100")
                    )
                ),
                toolDef(
                    "run_macro",
                    "一键式组合操作：按顺序执行一系列动作（回桌面/打开App/点击/滑动/输入/等待/按键）。" +
                        "steps 为数组，每个元素含 type 字段：home|back|app_switch|notifications|quick_settings|lock_screen|" +
                        "open_app(需 package)|tap(需 x,y)|swipe(需 x1,y1,x2,y2)|input(需 text)|wait(需 ms)|press_key(需 key)|" +
                        "tap_element(需 text/resource_id/class/content_desc 之一)|input_element(需 text 及定位条件)",
                    mapOf(
                        "steps" to mapOf("type" to "array", "description" to "动作序列数组"),
                        "stop_on_error" to mapOf("type" to "boolean", "description" to "遇错是否停止，默认 true")
                    ),
                    listOf("steps")
                ),
                toolDef(
                    "save_macro",
                    "将宏保存到本地，供 run_macro_by_id 按 id 触发。两种方式：①传 macro_json 完整宏文件；②分别传 id + steps。id 仅允许字母数字下划线中划线。",
                    mapOf(
                        "macro_json" to mapOf("type" to "string", "description" to "完整宏 JSON 字符串（含 id/name/steps/stop_on_error），可直接上传宏文件内容"),
                        "id" to mapOf("type" to "string", "description" to "宏唯一标识（1~64字符，字母数字_-），优先于 macro_json 中的 id"),
                        "name" to mapOf("type" to "string", "description" to "显示名称（可选），优先于 macro_json 中的 name"),
                        "steps" to mapOf("type" to "array", "description" to "动作序列数组，格式同 run_macro；优先于 macro_json 中的 steps"),
                        "stop_on_error" to mapOf("type" to "boolean", "description" to "遇错是否停止，默认 true")
                    ),
                    emptyList()
                ),
                toolDef(
                    "list_macros",
                    "列出本地已保存的所有宏（id、名称、更新时间）。",
                    mapOf()
                ),
                toolDef(
                    "get_macro",
                    "按 id 获取已保存宏的完整定义（含 steps）。",
                    mapOf(
                        "id" to mapOf("type" to "string", "description" to "宏 id")
                    ),
                    listOf("id")
                ),
                toolDef(
                    "delete_macro",
                    "按 id 删除已保存的宏。",
                    mapOf(
                        "id" to mapOf("type" to "string", "description" to "宏 id")
                    ),
                    listOf("id")
                ),
                toolDef(
                    "run_macro_by_id",
                    "按 id 执行本地已保存的宏。include_screenshot / screenshot_scale 可覆盖宏中的默认值。",
                    mapOf(
                        "id" to mapOf("type" to "string", "description" to "宏 id"),
                        "include_screenshot" to mapOf("type" to "boolean", "description" to "结果附加截图，默认 false"),
                        "screenshot_scale" to mapOf("type" to "number", "description" to "截图缩放 0.1~1.0，默认 0.3")
                    ),
                    listOf("id")
                ),
                toolDef(
                    "find_element",
                    "按属性查找当前界面中的控件，返回其中心坐标与 bounds。" +
                        "可选条件：text / resource_id / class / content_desc，多条件 AND。" +
                        "match: exact(默认) | contains | starts_with | ends_with",
                    mapOf(
                        "text" to mapOf("type" to "string", "description" to "控件文本"),
                        "resource_id" to mapOf("type" to "string", "description" to "资源ID，如 com.xxx:id/btn_ok"),
                        "class" to mapOf("type" to "string", "description" to "类名，如 android.widget.Button"),
                        "content_desc" to mapOf("type" to "string", "description" to "内容描述"),
                        "match" to mapOf("type" to "string", "description" to "匹配方式: exact/contains/starts_with/ends_with")
                    )
                ),
                toolDef(
                    "tap_element",
                    "按属性查找控件并点击其中心坐标。参数同 find_element。",
                    mapOf(
                        "text" to mapOf("type" to "string", "description" to "控件文本"),
                        "resource_id" to mapOf("type" to "string", "description" to "资源ID"),
                        "class" to mapOf("type" to "string", "description" to "类名"),
                        "content_desc" to mapOf("type" to "string", "description" to "内容描述"),
                        "match" to mapOf("type" to "string", "description" to "匹配方式")
                    )
                ),
                toolDef(
                    "input_element",
                    "按属性查找输入框控件并直接设置文本（原生中文）。" +
                        "用 text 指定要输入的内容，用 resource_id/text/class 定位目标输入框。",
                    mapOf(
                        "text" to mapOf("type" to "string", "description" to "要输入的文本", "required" to true),
                        "resource_id" to mapOf("type" to "string", "description" to "输入框资源ID"),
                        "target_text" to mapOf("type" to "string", "description" to "输入框已有文本（用于定位）"),
                        "class" to mapOf("type" to "string", "description" to "类名，如 android.widget.EditText"),
                        "match" to mapOf("type" to "string", "description" to "匹配方式"),
                        "append" to mapOf("type" to "boolean", "description" to "true=追加, false=覆盖(默认)")
                    )
                )
            )))
        }

        private fun toolDef(name: String, desc: String, props: Map<String, Map<String, Any>>,
                            required: List<String> = emptyList()): JSONObject {
            val schema = JSONObject().put("type", "object")
            if (props.isNotEmpty()) {
                val p = JSONObject()
                props.forEach { (k, v) -> p.put(k, JSONObject(v)) }
                schema.put("properties", p)
            }
            if (required.isNotEmpty()) {
                schema.put("required", JSONArray(required))
            }
            return JSONObject()
                .put("name", name)
                .put("description", desc)
                .put("inputSchema", schema)
        }

        private fun toolsCall(params: JSONObject): JSONObject {
            val name = params.optString("name", "")
            val args = params.optJSONObject("arguments") ?: JSONObject()
            return try {
                val (text, isErr) = dispatchTool(name, args)
                JSONObject().apply {
                    put("content", JSONArray().put(JSONObject()
                        .put("type", "text")
                        .put("text", text)))
                    put("isError", isErr)
                }
            } catch (e: Throwable) {
                JSONObject().apply {
                    put("content", JSONArray().put(JSONObject()
                        .put("type", "text")
                        .put("text", "Tool error: ${e.message}")))
                    put("isError", true)
                }
            }
        }

        // ------------------------------------------------------------------
        // 工具实现
        // ------------------------------------------------------------------

        private fun dispatchTool(name: String, args: JSONObject): Pair<String, Boolean> {
            val bridge = AccessibilityBridge.instance
            return when (name) {
                "get_ui_xml" -> {
                    if (bridge == null) return "无障碍服务未启用" to true
                    val xml = bridge.dumpXml() ?: return "无法获取窗口根节点" to true
                    xml to false
                }
                "get_ui_elements" -> {
                    if (bridge == null) return "无障碍服务未启用" to true
                    bridge.listInteractiveElements()?.toString(2)?.let { it to false }
                        ?: ("无法获取窗口根节点" to true)
                }
                "tap" -> {
                    if (bridge == null) return "无障碍服务未启用" to true
                    val x = args.optDouble("x", Double.NaN)
                    val y = args.optDouble("y", Double.NaN)
                    if (x.isNaN() || y.isNaN()) return "参数 x/y 缺失" to true
                    bridge.tap(x.toFloat(), y.toFloat())
                    "tapped" to false
                }
                "swipe" -> {
                    if (bridge == null) return "无障碍服务未启用" to true
                    bridge.swipe(
                        args.optDouble("x1").toFloat(),
                        args.optDouble("y1").toFloat(),
                        args.optDouble("x2").toFloat(),
                        args.optDouble("y2").toFloat(),
                        args.optLong("duration_ms", 300L)
                    )
                    "swiped" to false
                }
                "input_text" -> {
                    if (bridge == null) return "无障碍服务未启用" to true
                    val text = args.optString("text", "")
                    val append = args.optBoolean("append", false)
                    val ok = bridge.setFocusedText(text, append)
                    (if (ok) "text set" else "未找到可编辑控件") to !ok
                }
                "press_key" -> {
                    if (bridge == null) return "无障碍服务未启用" to true
                    val key = args.optString("key", "")
                    val ok = bridge.performGlobal(key)
                    (if (ok) "key dispatched" else "不支持的按键: $key") to !ok
                }
                "screenshot" -> {
                    if (bridge == null) return "无障碍服务未启用" to true
                    val scale = args.optDouble("scale", 0.5).toFloat().coerceIn(0.1f, 1.0f)
                    val b64 = bridge.takeScreenshotBase64(scale)
                        ?: return "截图失败（Android 11+ 支持系统截图；低版本请用 adb_shell screencap）" to true
                    b64 to false
                }
                "adb_shell" -> {
                    val client = adb
                    if (client == null || !AdbShellClient.connected) return "ADB 通道未连接" to true
                    val cmd = args.optString("command", "")
                    if (cmd.isBlank()) return "command 不能为空" to true
                    try {
                        val out = client.exec(cmd)
                        out to false
                    } catch (e: Throwable) {
                        "ADB 执行失败: ${e.message}" to true
                    }
                }
                "get_status" -> {
                    val o = JSONObject().apply {
                        put("package", AccessibilityBridge.currentPackage)
                        put("accessibility_ready", AccessibilityBridge.isReady())
                        put("adb_connected", AdbShellClient.connected)
                        put("sdk_int", Build.VERSION.SDK_INT)
                        put("model", Build.MODEL)
                    }
                    o.toString(2) to false
                }
                "unlock" -> {
                    val bridge = AccessibilityBridge.instance
                        ?: return "无障碍服务未启用" to true
                    val pwd = nullIfEmpty(args.optString("password"))
                    val ok = bridge.unlock(pwd)
                    (if (ok) "unlock ok" else "解锁失败，请检查密码或锁屏类型") to !ok
                }
                "get_logs" -> {
                    val lines = args.optInt("lines", 100).coerceIn(1, 500)
                    // 从当日持久化文件读取（包含内存缓冲尚未落盘的行）
                    LogBuffer.flush()
                    val fileText = LogBuffer.getLogForDate(LogBuffer.todayStr())
                    val allLines = fileText.lines().filter { it.isNotBlank() }
                    val recent = allLines.takeLast(lines)
                    val text = recent.joinToString("\n")
                    text.ifEmpty { "(暂无日志)" } to false
                }
                "run_macro" -> runMacro(args)
                "save_macro" -> {
                    try {
                        // 支持两种来源：macro_json 完整文件 或 拆分参数
                        var macroObj: JSONObject? = null
                        val macroJsonStr = nullIfEmpty(args.optString("macro_json"))
                        if (macroJsonStr != null) {
                            macroObj = JSONObject(macroJsonStr)
                        }
                        // id：优先参数，其次 macro_json
                        var id = nullIfEmpty(args.optString("id"))
                            ?: macroObj?.optString("id")?.takeIf { it.isNotBlank() }
                        if (id.isNullOrBlank()) return "缺少 id（需传 id 或 macro_json 中包含 id）" to true
                        // name：优先参数，其次 macro_json
                        val name = nullIfEmpty(args.optString("name"))
                            ?: macroObj?.optString("name")?.takeIf { it.isNotBlank() }
                        // steps：优先参数，其次 macro_json
                        var steps = args.optJSONArray("steps")
                        if (steps == null) steps = macroObj?.optJSONArray("steps")
                        if (steps == null || steps.length() == 0) return "steps 不能为空" to true
                        // stop_on_error：优先参数，其次 macro_json，默认 true
                        var stopOnError = args.optBoolean("stop_on_error", true)
                        if (!args.has("stop_on_error") && macroObj != null) {
                            stopOnError = macroObj.optBoolean("stop_on_error", true)
                        }
                        val saved = MacroStore.save(id, name, steps.toString(), stopOnError)
                        saved.toString(2) to false
                    } catch (e: org.json.JSONException) {
                        "macro_json 解析失败: ${e.message}" to true
                    } catch (e: IllegalArgumentException) {
                        (e.message ?: "保存失败") to true
                    } catch (e: Throwable) {
                        "保存失败: ${e.message}" to true
                    }
                }
                "list_macros" -> {
                    val list = MacroStore.list()
                    org.json.JSONArray(list.map { it }).toString(2) to false
                }
                "get_macro" -> {
                    val id = args.optString("id", "")
                    val m = MacroStore.get(id) ?: return "未找到宏: $id" to true
                    m.toString(2) to false
                }
                "delete_macro" -> {
                    val id = args.optString("id", "")
                    val ok = MacroStore.delete(id)
                    (if (ok) "已删除宏: $id" else "未找到宏: $id") to !ok
                }
                "run_macro_by_id" -> {
                    val id = args.optString("id", "")
                    val m = MacroStore.get(id) ?: return "未找到宏: $id" to true
                    // 用已保存的 steps + stop_on_error 重建 args，允许覆盖截图选项
                    val runArgs = JSONObject().apply {
                        put("steps", m.optJSONArray("steps"))
                        put("stop_on_error", m.optBoolean("stop_on_error", true))
                        if (args.has("include_screenshot")) put("include_screenshot", args.optBoolean("include_screenshot"))
                        if (args.has("screenshot_scale")) put("screenshot_scale", args.optDouble("screenshot_scale"))
                    }
                    runMacro(runArgs)
                }
                "find_element" -> {
                    if (bridge == null) return "无障碍服务未启用" to true
                    val el = bridge.findElement(
                        text = nullIfEmpty(args.optString("text")),
                        resourceId = nullIfEmpty(args.optString("resource_id")),
                        className = nullIfEmpty(args.optString("class")),
                        contentDesc = nullIfEmpty(args.optString("content_desc")),
                        match = args.optString("match", "exact"),
                        index = args.optInt("index", 0)
                    )
                    el?.toString(2)?.let { it to false } ?: ("未找到匹配控件" to true)
                }
                "tap_element" -> {
                    if (bridge == null) return "无障碍服务未启用" to true
                    val (ok, el) = bridge.tapElement(
                        text = nullIfEmpty(args.optString("text")),
                        resourceId = nullIfEmpty(args.optString("resource_id")),
                        className = nullIfEmpty(args.optString("class")),
                        contentDesc = nullIfEmpty(args.optString("content_desc")),
                        match = args.optString("match", "exact"),
                        index = args.optInt("index", 0),
                        clickType = args.optString("click_type", "click")
                    )
                    if (el == null) return "未找到匹配控件" to true
                    val o = JSONObject()
                        .put("tapped", ok)
                        .put("element", el)
                    o.toString(2) to !ok
                }
                "input_element" -> {
                    if (bridge == null) return "无障碍服务未启用" to true
                    val text = args.optString("text", "")
                    if (text.isEmpty()) return "text 不能为空" to true
                    val ok = bridge.inputElement(
                        text = text,
                        resId = nullIfEmpty(args.optString("resource_id")),
                        targetText = nullIfEmpty(args.optString("target_text")),
                        className = nullIfEmpty(args.optString("class")),
                        match = args.optString("match", "exact"),
                        append = args.optBoolean("append", false),
                        index = args.optInt("index", 0)
                    )
                    (if (ok) "input ok" else "未找到可编辑控件或设置失败") to !ok
                }
                else -> "未知工具: $name" to true
            }
        }

        private fun nullIfEmpty(s: String?): String? =
            if (s.isNullOrEmpty()) null else s

        /** 唤醒屏幕并保持短暂亮屏，供宏执行前使用 */
        private fun wakeScreen() {
            try {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isInteractive) {
                    val wl = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                            PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            PowerManager.ON_AFTER_RELEASE,
                        "androidmcp:macro-wake"
                    )
                    wl.acquire(3000)
                    Thread.sleep(500)
                }
            } catch (t: Throwable) {
                LogBuffer.w("MCP", "亮屏失败: ${t.message}")
            }
        }

        // ------------------------------------------------------------------
        // 宏（一键式组合操作）
        // ------------------------------------------------------------------

        private fun runMacro(args: JSONObject): Pair<String, Boolean> {
            val stepsArr = args.optJSONArray("steps")
            if (stepsArr == null || stepsArr.length() == 0) return "steps 不能为空" to true
            val stopOnError = args.optBoolean("stop_on_error", true)
            val includeScreenshot = args.optBoolean("include_screenshot", false)
            val screenshotScale = args.optDouble("screenshot_scale", 0.3).toFloat().coerceIn(0.1f, 1.0f)
            val bridge = AccessibilityBridge.instance

            // 执行前先亮屏，避免息屏时无障碍/点击无效
            wakeScreen()

            val results = JSONArray()
            for (i in 0 until stepsArr.length()) {
                val step = stepsArr.optJSONObject(i) ?: continue
                val type = step.optString("type", "")
                val stepResult = JSONObject().put("step", i).put("type", type)
                try {
                    val (msg, isErr) = when (type) {
                        "home" -> {
                            val ok = bridge?.performGlobal("HOME") ?: false
                            (if (ok) "home" else "失败") to !ok
                        }
                        "back" -> {
                            val ok = bridge?.performGlobal("BACK") ?: false
                            (if (ok) "back" else "失败") to !ok
                        }
                        "app_switch" -> {
                            val ok = bridge?.performGlobal("APP_SWITCH") ?: false
                            (if (ok) "app_switch" else "失败") to !ok
                        }
                        "notifications" -> {
                            val ok = bridge?.performGlobal("NOTIFICATIONS") ?: false
                            (if (ok) "notifications" else "失败") to !ok
                        }
                        "quick_settings" -> {
                            val ok = bridge?.performGlobal("QUICK_SETTINGS") ?: false
                            (if (ok) "quick_settings" else "失败") to !ok
                        }
                        "lock_screen" -> {
                            val ok = bridge?.performGlobal("LOCK_SCREEN") ?: false
                            (if (ok) "lock_screen" else "失败") to !ok
                        }
                        "unlock" -> {
                            if (bridge == null) return "无障碍服务未启用" to true
                            val pwd = nullIfEmpty(step.optString("password"))
                            val ok = bridge.unlock(pwd)
                            (if (ok) "unlock ok" else "解锁失败") to !ok
                        }
                        "open_app" -> {
                            val pkg = step.optString("package", "")
                            if (pkg.isBlank()) return "open_app 缺少 package" to true
                            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                            if (intent != null) {
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(intent)
                                "已启动 $pkg" to false
                            } else {
                                // 尝试 ADB monkey 兜底
                                try {
                                    val client = adb
                                    if (client != null && AdbShellClient.connected) {
                                        client.exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
                                        "已通过 monkey 启动 $pkg" to false
                                    } else {
                                        "未找到 App: $pkg" to true
                                    }
                                } catch (_: Throwable) {
                                    "未找到 App: $pkg" to true
                                }
                            }
                        }
                        "tap" -> {
                            val x = step.optDouble("x", Double.NaN)
                            val y = step.optDouble("y", Double.NaN)
                            if (x.isNaN() || y.isNaN()) {
                                "tap 缺少 x/y" to true
                            } else {
                                bridge?.tap(x.toFloat(), y.toFloat())
                                "tapped ($x,$y)" to false
                            }
                        }
                        "swipe" -> {
                            bridge?.swipe(
                                step.optDouble("x1").toFloat(),
                                step.optDouble("y1").toFloat(),
                                step.optDouble("x2").toFloat(),
                                step.optDouble("y2").toFloat(),
                                step.optLong("duration_ms", 300L)
                            )
                            "swiped" to false
                        }
                        "input" -> {
                            val text = step.optString("text", "")
                            val append = step.optBoolean("append", false)
                            val ok = bridge?.setFocusedText(text, append) ?: false
                            (if (ok) "input ok" else "未找到可编辑控件") to !ok
                        }
                        "wait" -> {
                            val ms = step.optLong("ms", 500L).coerceIn(50L, 30000L)
                            Thread.sleep(ms)
                            "waited ${ms}ms" to false
                        }
                        "press_key" -> {
                            val key = step.optString("key", "")
                            val ok = bridge?.performGlobal(key) ?: false
                            (if (ok) "key $key" else "不支持的按键: $key") to !ok
                        }
                        "tap_element" -> {
                            if (bridge == null) return "无障碍服务未启用" to true
                            val (ok, el) = bridge.tapElement(
                                text = nullIfEmpty(step.optString("text")),
                                resourceId = nullIfEmpty(step.optString("resource_id")),
                                className = nullIfEmpty(step.optString("class")),
                                contentDesc = nullIfEmpty(step.optString("content_desc")),
                                match = step.optString("match", "exact"),
                                index = step.optInt("index", 0),
                                clickType = step.optString("click_type", "click")
                            )
                            if (el == null) "未找到匹配控件" to true
                            else (if (ok) "tapped element" else "手势被拒绝") to !ok
                        }
                        "input_element" -> {
                            if (bridge == null) return "无障碍服务未启用" to true
                            val txt = step.optString("text", "")
                            if (txt.isEmpty()) return "input_element 缺少 text" to true
                            val ok = bridge.inputElement(
                                text = txt,
                                resId = nullIfEmpty(step.optString("resource_id")),
                                targetText = nullIfEmpty(step.optString("target_text")),
                                className = nullIfEmpty(step.optString("class")),
                                match = step.optString("match", "exact"),
                                append = step.optBoolean("append", false),
                                index = step.optInt("index", 0)
                            )
                            (if (ok) "input ok" else "未找到可编辑控件") to !ok
                        }
                        else -> "未知动作类型: $type" to true
                    }
                    stepResult.put("result", msg).put("error", isErr)
                    results.put(stepResult)
                    if (isErr && stopOnError) break
                } catch (e: Throwable) {
                    stepResult.put("result", "异常: ${e.message}").put("error", true)
                    results.put(stepResult)
                    if (stopOnError) break
                }
            }
            // 执行完毕后，附加当前页面详情（包名 + 控件列表），方便 AI 感知执行结果
            val finalPage = JSONObject()
            try {
                val pkg = AccessibilityBridge.currentPackage
                finalPage.put("package", pkg)
                val elements = bridge?.listInteractiveElements()
                if (elements != null) finalPage.put("elements", elements)
                if (includeScreenshot) {
                    val b64 = bridge?.takeScreenshotBase64(screenshotScale)
                    if (b64 != null) finalPage.put("screenshot", b64)
                }
            } catch (_: Throwable) {}
            val output = JSONObject().put("steps", results).put("final_page", finalPage)
            return output.toString(2) to false
        }

        // ------------------------------------------------------------------
        // JSON-RPC 响应构造
        // ------------------------------------------------------------------

        private fun resultJson(id: Any?, result: JSONObject): String {
            val o = JSONObject()
                .put("jsonrpc", "2.0")
                .put("result", result)
            if (id != null) o.put("id", id)
            return o.toString()
        }

        private fun errorJson(id: Any?, code: Int, msg: String): String {
            val o = JSONObject()
                .put("jsonrpc", "2.0")
                .put("error", JSONObject().put("code", code).put("message", msg))
            if (id != null) o.put("id", id)
            return o.toString()
        }
    }
}
