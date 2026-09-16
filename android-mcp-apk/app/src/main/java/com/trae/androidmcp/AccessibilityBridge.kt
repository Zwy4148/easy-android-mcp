package com.trae.androidmcp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Xml
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import android.util.Base64
import android.graphics.ColorSpace
import java.io.ByteArrayOutputStream

/**
 * 无障碍桥接服务（主控制通道）。
 *
 * 能力：
 *  - 读取当前活动窗口的控件树（等价 uiautomator dump 的精简版）
 *  - dispatchGesture 注入点击/滑动（绕过 BLOCK_UNTRUSTED_TOUCHES）
 *  - 对焦点控件直接 ACTION_SET_TEXT，天然支持中文
 *  - 全局动作：返回/主页/最近任务/通知栏/锁屏
 *  - Android 11(API30)+ 使用系统截图 API 直接截图
 */
class AccessibilityBridge : AccessibilityService() {

    companion object {
        /** 当前服务实例，null 表示用户尚未在系统设置中开启无障碍 */
        @Volatile
        var instance: AccessibilityBridge? = null
            private set

        /** 是否可用（已连接且可获取根节点） */
        fun isReady(): Boolean = instance != null

        /** 最近一次窗口所在应用包名，随事件更新 */
        @Volatile
        var currentPackage: String = ""
            private set

        private const val MAX_DEPTH = 50
        private const val MAX_NODES = 3000
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.let { currentPackage = it.toString() }
    }

    override fun onInterrupt() {
        // 系统中断回调，无需处理
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 主线程同步执行工具：AccessibilityNodeInfo 与手势 API 必须在主线程调用
    // ------------------------------------------------------------------

    private fun <T> runOnMain(timeoutMs: Long = 5_000L, block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        val latch = CountDownLatch(1)
        @Suppress("UNCHECKED_CAST")
        var result: T? = null
        Handler(Looper.getMainLooper()).post {
            try {
                result = block()
            } catch (_: Throwable) {
                // 异常时结果保持 null，由调用方判断
            } finally {
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result
    }

    // ------------------------------------------------------------------
    // UI 树采集
    // ------------------------------------------------------------------

    /** 精简后的控件数据，仅保留自动化所需属性 */
    private data class UiNode(
        val className: String?,
        val resourceId: String?,
        val text: String?,
        val contentDesc: String?,
        val clickable: Boolean,
        val scrollable: Boolean,
        val checkable: Boolean,
        val checked: Boolean,
        val enabled: Boolean,
        val focused: Boolean,
        val selected: Boolean,
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val children: MutableList<UiNode> = mutableListOf()
    ) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }

    /**
     * 导出当前界面为精简 XML，属性集与 uiautomator dump 对齐，
     * 云端 Hermes 的解析逻辑可直接复用。
     */
    fun dumpXml(): String? = runOnMain {
        val root = rootInActiveWindow ?: return@runOnMain null
        val counter = AtomicInteger(0)
        val uiRoot = collectNode(root, 0, counter) ?: return@runOnMain null
        val writer = StringWriter()
        val serializer = Xml.newSerializer()
        serializer.setOutput(writer)
        serializer.startDocument("UTF-8", true)
        serializer.text("\n")
        writeNodeXml(serializer, uiRoot)
        serializer.endDocument()
        writer.toString()
    }

    private fun collectNode(node: AccessibilityNodeInfo?, depth: Int, counter: AtomicInteger): UiNode? {
        if (node == null || depth > MAX_DEPTH || counter.get() >= MAX_NODES) return null
        counter.incrementAndGet()
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val ui = UiNode(
            className = node.className?.toString(),
            resourceId = node.viewIdResourceName,
            text = node.text?.toString(),
            contentDesc = node.contentDescription?.toString(),
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            enabled = node.isEnabled,
            focused = node.isFocused,
            selected = node.isSelected,
            left = rect.left, top = rect.top, right = rect.right, bottom = rect.bottom
        )
        for (i in 0 until node.childCount) {
            collectNode(node.getChild(i), depth + 1, counter)?.let { ui.children.add(it) }
        }
        return ui
    }

    private fun writeNodeXml(serializer: org.xmlpull.v1.XmlSerializer, node: UiNode) {
        serializer.startTag("", "node")
        node.className?.let { serializer.attribute("", "class", it) }
        node.resourceId?.let { serializer.attribute("", "resource-id", it) }
        node.text?.let { if (it.isNotEmpty()) serializer.attribute("", "text", it) }
        node.contentDesc?.let { if (it.isNotEmpty()) serializer.attribute("", "content-desc", it) }
        serializer.attribute("", "clickable", node.clickable.toString())
        serializer.attribute("", "scrollable", node.scrollable.toString())
        if (node.checkable) serializer.attribute("", "checkable", "true")
        if (node.checked) serializer.attribute("", "checked", "true")
        if (!node.enabled) serializer.attribute("", "enabled", "false")
        if (node.focused) serializer.attribute("", "focused", "true")
        if (node.selected) serializer.attribute("", "selected", "true")
        serializer.attribute(
            "", "bounds",
            "[${node.left},${node.top}][${node.right},${node.bottom}]"
        )
        node.children.forEach { writeNodeXml(serializer, it) }
        serializer.endTag("", "node")
    }

    /**
     * 以 JSON 数组返回可交互控件（clickable/scrollable 或带文本），
     * 附带中心点坐标，供不解析 XML 的客户端直接使用。
     */
    fun listInteractiveElements(): JSONArray? = runOnMain {
        val root = rootInActiveWindow ?: return@runOnMain null
        val counter = AtomicInteger(0)
        val uiRoot = collectNode(root, 0, counter) ?: return@runOnMain null
        val arr = JSONArray()
        flattenInteractive(uiRoot, arr)
        arr
    }

    private fun flattenInteractive(node: UiNode, out: JSONArray) {
        val hasText = !node.text.isNullOrEmpty() || !node.contentDesc.isNullOrEmpty()
        if (node.clickable || node.scrollable || hasText) {
            val o = JSONObject()
            node.resourceId?.let { o.put("resource_id", it) }
            node.className?.let { o.put("class", it) }
            node.text?.let { if (it.isNotEmpty()) o.put("text", it) }
            node.contentDesc?.let { if (it.isNotEmpty()) o.put("content_desc", it) }
            o.put("clickable", node.clickable)
            o.put("scrollable", node.scrollable)
            o.put("x", node.centerX)
            o.put("y", node.centerY)
            o.put("bounds", "[${node.left},${node.top}][${node.right},${node.bottom}]")
            out.put(o)
        }
        node.children.forEach { flattenInteractive(it, out) }
    }

    // ------------------------------------------------------------------
    // 按属性查找控件并操作
    // ------------------------------------------------------------------

    /**
     * 在当前 UI 树中查找匹配条件的控件，返回其信息（含中心坐标）。
     * 所有条件均为可选，多个条件之间为 AND 关系。
     * text/contentDesc 支持精确匹配或 contains（match=contains 时）。
     * index 指定第几个匹配项（0-based，默认 0 = 第一个）。
     */
    fun findElement(
        text: String? = null,
        resourceId: String? = null,
        className: String? = null,
        contentDesc: String? = null,
        match: String = "exact",
        index: Int = 0
    ): JSONObject? = runOnMain {
        val root = rootInActiveWindow ?: return@runOnMain null
        val counter = AtomicInteger(0)
        val uiRoot = collectNode(root, 0, counter) ?: return@runOnMain null
        val hits = mutableListOf<UiNode>()
        searchNodeAll(uiRoot, text, resourceId, className, contentDesc, match, hits)
        val hit = hits.getOrNull(index) ?: return@runOnMain null
        JSONObject().apply {
            hit.resourceId?.let { put("resource_id", it) }
            hit.className?.let { put("class", it) }
            hit.text?.let { put("text", it) }
            hit.contentDesc?.let { put("content_desc", it) }
            put("clickable", hit.clickable)
            put("x", hit.centerX)
            put("y", hit.centerY)
            put("bounds", "[${hit.left},${hit.top}][${hit.right},${hit.bottom}]")
            put("bounds_left", hit.left)
            put("bounds_top", hit.top)
            put("bounds_right", hit.right)
            put("bounds_bottom", hit.bottom)
            put("match_index", index)
            put("match_count", hits.size)
        }
    }

    /**
     * 查找匹配控件并点击。
     * index 指定第几个匹配项（0-based）。
     */
    fun tapElement(
        text: String? = null,
        resourceId: String? = null,
        className: String? = null,
        contentDesc: String? = null,
        match: String = "exact",
        index: Int = 0,
        clickType: String = "click"
    ): Pair<Boolean, JSONObject?> {
        val el = findElement(text, resourceId, className, contentDesc, match, index)
            ?: return false to null
        // coordinate 模式：直接坐标点击控件中心（跳过 ACTION_CLICK）
        if (clickType == "coordinate") {
            val cx = el.optDouble("x", Double.NaN)
            val cy = el.optDouble("y", Double.NaN)
            if (cx.isNaN() || cy.isNaN()) return false to el
            tap(cx.toFloat(), cy.toFloat())
            return true to el
        }
        // click 模式（默认）：优先 ACTION_CLICK 可点击祖先，降级坐标点击
        val clicked = runOnMain<Boolean> {
            val root = rootInActiveWindow ?: return@runOnMain false
            val target = findNodeInfo(
                root,
                el.optInt("bounds_left"),
                el.optInt("bounds_top"),
                el.optInt("bounds_right"),
                el.optInt("bounds_bottom")
            ) ?: return@runOnMain false
            var clickNode: AccessibilityNodeInfo? = target
            var n: AccessibilityNodeInfo? = target.parent
            while (n != null && !n.isClickable) {
                n = n.parent
            }
            if (n != null && n.isClickable) clickNode = n
            clickNode?.takeIf { it.isClickable }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        } ?: false
        if (clicked) return true to el
        // 降级：坐标点击（dispatchGesture 即使回调 cancelled 也实际生效，因此视为成功）
        val cx = el.optDouble("x", Double.NaN)
        val cy = el.optDouble("y", Double.NaN)
        if (cx.isNaN() || cy.isNaN()) return false to el
        tap(cx.toFloat(), cy.toFloat())
        return true to el
    }

    /**
     * 查找匹配控件（通常是 EditText），聚焦后输入文本。
     * index 指定第几个匹配项（0-based）。
     */
    fun inputElement(
        text: String,
        resId: String? = null,
        targetText: String? = null,
        className: String? = null,
        match: String = "exact",
        append: Boolean = false,
        index: Int = 0
    ): Boolean {
        val hit = runOnMain<UiNode?> {
            val root = rootInActiveWindow ?: return@runOnMain null
            val counter = AtomicInteger(0)
            val uiRoot = collectNode(root, 0, counter) ?: return@runOnMain null
            val hits = mutableListOf<UiNode>()
            searchNodeAll(uiRoot, targetText, resId, className, null, match, hits)
            hits.getOrNull(index)
        } ?: return false
        // 方式一：直接定位 AccessibilityNodeInfo 并 ACTION_SET_TEXT
        val directOk = runOnMain<Boolean> {
            val root = rootInActiveWindow ?: return@runOnMain false
            val target = findNodeInfo(root, hit.left, hit.top, hit.right, hit.bottom)
                ?: return@runOnMain false
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                if (append) (target.text ?: "").toString() + text else text
            )
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } ?: false
        if (directOk) return true
        // 方式二：点击控件中心聚焦，再对焦点输入框设文本
        val cx = (hit.left + hit.right) / 2f
        val cy = (hit.top + hit.bottom) / 2f
        tap(cx, cy)
        Thread.sleep(300)
        return setFocusedText(text, append)
    }

    /** 收集所有匹配条件的节点（深度优先顺序） */
    private fun searchNodeAll(
        node: UiNode,
        text: String?,
        resourceId: String?,
        className: String?,
        contentDesc: String?,
        match: String,
        out: MutableList<UiNode>
    ) {
        val txtOk = text == null || strMatch(node.text, text, match)
        val ridOk = resourceId == null || strMatch(node.resourceId, resourceId, match)
        val clsOk = className == null || strMatch(node.className, className, match)
        val descOk = contentDesc == null || strMatch(node.contentDesc, contentDesc, match)
        if (txtOk && ridOk && clsOk && descOk) out.add(node)
        for (child in node.children) {
            searchNodeAll(child, text, resourceId, className, contentDesc, match, out)
        }
    }

    private fun strMatch(actual: String?, expected: String, match: String): Boolean {
        if (actual == null) return false
        return when (match) {
            "contains" -> actual.contains(expected, ignoreCase = true)
            "starts_with" -> actual.startsWith(expected, ignoreCase = true)
            "ends_with" -> actual.endsWith(expected, ignoreCase = true)
            else -> actual == expected
        }
    }

    /** 根据 bounds 在原始 AccessibilityNodeInfo 树中定位节点 */
    private fun findNodeInfo(
        node: AccessibilityNodeInfo,
        left: Int, top: Int, right: Int, bottom: Int
    ): AccessibilityNodeInfo? {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.left == left && rect.top == top && rect.right == right && rect.bottom == bottom) {
            return node
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let {
                findNodeInfo(it, left, top, right, bottom)?.let { hit -> return hit }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // 手势注入
    // ------------------------------------------------------------------

    /** 点击屏幕坐标，返回手势是否被系统接受 */
    fun tap(x: Float, y: Float): Boolean = dispatchGesturePath(
        GestureDescription.StrokeDescription(
            android.graphics.Path().apply { moveTo(x, y) },
            0L, 10L
        )
    )

    /** 从 (x1,y1) 滑动到 (x2,y2)，durationMs 为手势持续时间 */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = android.graphics.Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatchGesturePath(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(50L, 10_000L)))
    }

    private fun dispatchGesturePath(stroke: GestureDescription.StrokeDescription): Boolean {
        val result = runOnMain<Boolean>(3_000L) {
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            // dispatchGesture 返回 true 表示手势已被系统接受派发
            // 实测：即使回调 onCancelled，手势实际仍会生效，因此以返回值为准
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gesture: GestureDescription?) {}
                override fun onCancelled(gesture: GestureDescription?) {}
            }, null)
        }
        return result == true
    }

    // ------------------------------------------------------------------
    // 文本输入（直接设置焦点控件文本，原生支持中文/Emoji）
    // ------------------------------------------------------------------

    /**
     * 对当前聚焦的输入框设置文本。
     * append=true 时在原有文本后追加。
     */
    fun setFocusedText(text: String, append: Boolean = false): Boolean = runOnMain {
        val root = rootInActiveWindow ?: return@runOnMain false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditable(root)
            ?: return@runOnMain false
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            if (append) (focused.text ?: "").toString() + text else text
        )
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    } ?: false

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findFirstEditable(it)?.let { hit -> return hit } }
        }
        return null
    }

    // ------------------------------------------------------------------
    // 全局物理按键映射
    // ------------------------------------------------------------------

    /**
     * 执行全局动作。
     * @param key BACK / HOME / APP_SWITCH / NOTIFICATIONS / QUICK_SETTINGS / LOCK_SCREEN
     */
    fun performGlobal(key: String): Boolean = runOnMain {
        val actionId = when (key.uppercase()) {
            "BACK" -> GLOBAL_ACTION_BACK
            "HOME" -> GLOBAL_ACTION_HOME
            "APP_SWITCH", "RECENTS" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) GLOBAL_ACTION_QUICK_SETTINGS
                else return@runOnMain false
            "LOCK_SCREEN", "POWER_DIALOG" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN
                else return@runOnMain false
            else -> return@runOnMain false
        }
        performGlobalAction(actionId)
    } ?: false

    // ------------------------------------------------------------------
    // 自动解锁（亮屏 + 输入密码）
    // ------------------------------------------------------------------

    /**
     * 自动解锁屏幕。
     * @param password 解锁密码；为 null 时读取 Prefs 中存储的密码
     * @return true 表示解锁后 keyguard 已消失
     */
    fun unlock(password: String? = null): Boolean {
        val pwd = password ?: Prefs.getUnlockPassword(this)
        if (pwd.isBlank()) return false

        // 1. 亮屏
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "androidmcp:unlock"
            ).acquire(5000)
            Thread.sleep(800)
        }

        // 2. 检查是否已解锁
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isKeyguardLocked) return true

        // 3. 尝试找到密码输入框；找不到则上滑呼出
        var editable: AccessibilityNodeInfo? = runOnMain {
            findFirstEditable(rootInActiveWindow ?: return@runOnMain null)
        }
        if (editable == null) {
            val dm = resources.displayMetrics
            swipe(dm.widthPixels / 2f, dm.heightPixels * 0.8f,
                dm.widthPixels / 2f, dm.heightPixels * 0.2f, 300)
            Thread.sleep(900)
            editable = runOnMain {
                findFirstEditable(rootInActiveWindow ?: return@runOnMain null)
            }
        }
        if (editable == null) return false

        // 4. 输入密码
        val inputOk = runOnMain<Boolean> {
            val node = editable ?: return@runOnMain false
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pwd
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } ?: false
        if (!inputOk) return false
        Thread.sleep(300)

        // 5. 确认：优先点确认按钮，否则点输入框本身
        runOnMain<Boolean> {
            val root = rootInActiveWindow ?: return@runOnMain false
            val confirm = findNodeByText(root,
                listOf("确认", "完成", "确定", "OK", "Enter", "回车", "解锁"))
            if (confirm != null) {
                confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                editable?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            true
        }

        Thread.sleep(1200)
        return !km.isKeyguardLocked
    }

    /** 在节点树中按文本（精确或包含）查找第一个匹配节点 */
    private fun findNodeByText(
        node: AccessibilityNodeInfo,
        candidates: List<String>
    ): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString().orEmpty()
        val nodeDesc = node.contentDescription?.toString().orEmpty()
        for (c in candidates) {
            if (nodeText.equals(c, ignoreCase = true) ||
                nodeDesc.equals(c, ignoreCase = true) ||
                nodeText.contains(c, ignoreCase = true) ||
                nodeDesc.contains(c, ignoreCase = true)
            ) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let {
                findNodeByText(it, candidates)?.let { hit -> return hit }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // 截图（Android 11+ 系统 API，无需 MediaProjection 弹窗）
    // ------------------------------------------------------------------

    /**
     * 截图并返回 Base64（PNG）。scale 为缩放比例（0.1~1.0）。
     * Android 11 以下返回 null，调用方应降级到 ADB screencap。
     */
    fun takeScreenshotBase64(scale: Float): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        var result: String? = null
        val mainExecutor = java.util.concurrent.Executor { cmd ->
            Handler(Looper.getMainLooper()).post(cmd)
        }
        val ok = runOnMain<Boolean> {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    @RequiresApi(Build.VERSION_CODES.R)
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hardwareBuffer = screenshot.hardwareBuffer
                            val colorSpace: ColorSpace = screenshot.colorSpace
                            val hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            if (hwBitmap != null) {
                                var bmp = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                if (scale in 0.1f..0.99f) {
                                    val w = (bmp.width * scale).toInt().coerceAtLeast(1)
                                    val h = (bmp.height * scale).toInt().coerceAtLeast(1)
                                    val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
                                    if (scaled !== bmp) bmp.recycle()
                                    bmp = scaled
                                }
                                val out = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                                result = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                                bmp.recycle()
                                hwBitmap.recycle()
                            }
                            hardwareBuffer.close()
                        } catch (_: Throwable) {
                            result = null
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                }
            )
            true
        }
        if (ok != true) return null
        latch.await(8_000, TimeUnit.MILLISECONDS)
        return result
    }
}
