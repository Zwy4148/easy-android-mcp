package com.trae.androidmcp

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils.SimpleStringSplitter
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.app.Activity

/**
 * 主控 Activity：权限引导、配置读写、启停 MCP 前台服务、实时状态展示。
 */
class MainActivity : Activity() {

    private lateinit var tvAccStatus: TextView
    private lateinit var btnAccessibility: Button
    private lateinit var btnWireless: Button
    private lateinit var etPort: EditText
    private lateinit var etToken: EditText
    private lateinit var cbAdb: CheckBox
    private lateinit var etAdbTarget: EditText
    private lateinit var btnTestAdb: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvServerStatus: TextView
    private lateinit var tvAddr: TextView
    private lateinit var tvAdbStatus: TextView
    private lateinit var btnViewLogs: Button

    // ---- 设置按钮 ----
    private lateinit var btnSettings: Button

    // ---- 自动解锁 ----
    private lateinit var etUnlockPwd: EditText
    private lateinit var btnSaveUnlock: Button
    private lateinit var btnTestUnlock: Button

    private val handler = Handler(Looper.getMainLooper())
    private val statusPoll = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化日志缓冲（注入 Context，供按日期落盘）
        LogBuffer.init(this)
        FrpcManager.init(this)
        MacroStore.init(this)

        bindViews()
        loadPrefs()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityStatus()
        refreshStatus()
        handler.post(statusPoll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusPoll)
    }

    // ------------------------------------------------------------------
    // 视图绑定与初始化
    // ------------------------------------------------------------------

    private fun bindViews() {
        tvAccStatus = findViewById(R.id.tv_status_accessibility)
        btnAccessibility = findViewById(R.id.btn_accessibility)
        btnWireless = findViewById(R.id.btn_wireless)
        etPort = findViewById(R.id.et_port)
        etToken = findViewById(R.id.et_token)
        cbAdb = findViewById(R.id.cb_adb_enable)
        etAdbTarget = findViewById(R.id.et_adb_target)
        btnTestAdb = findViewById(R.id.btn_test_adb)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        tvServerStatus = findViewById(R.id.tv_status_server)
        tvAddr = findViewById(R.id.tv_addr)
        tvAdbStatus = findViewById(R.id.tv_status_adb)
        btnViewLogs = findViewById(R.id.btn_view_logs)
        btnSettings = findViewById(R.id.btn_settings)

        // 自动解锁
        etUnlockPwd = findViewById(R.id.et_unlock_pwd)
        btnSaveUnlock = findViewById(R.id.btn_save_unlock)
        btnTestUnlock = findViewById(R.id.btn_test_unlock)
    }

    private fun loadPrefs() {
        etPort.setText(Prefs.getPort(this).toString())
        etToken.setText(Prefs.getToken(this))
        cbAdb.isChecked = Prefs.isAdbEnabled(this)
        etAdbTarget.setText(Prefs.getAdbTarget(this))

        // 自动解锁
        etUnlockPwd.setText(Prefs.getUnlockPassword(this))
    }

    private fun savePrefs() {
        etPort.text.toString().toIntOrNull()?.let { Prefs.setPort(this, it) }
        etToken.text.toString().takeIf { it.isNotBlank() }?.let { Prefs.setToken(this, it) }
        Prefs.setAdbEnabled(this, cbAdb.isChecked)
        etAdbTarget.text.toString().takeIf { it.isNotBlank() }?.let { Prefs.setAdbTarget(this, it) }
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    private fun setupListeners() {
        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnWireless.setOnClickListener {
            // 跳转到开发者选项（无线调试入口在其中）
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        btnTestAdb.setOnClickListener {
            savePrefs()
            testAdb()
        }
        btnStart.setOnClickListener {
            savePrefs()
            if (!AccessibilityBridge.isReady()) {
                Toast.makeText(this, "请先开启无障碍权限", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            McpForegroundService.start(this)
            Toast.makeText(this, "MCP 服务启动中…", Toast.LENGTH_SHORT).show()
        }
        btnStop.setOnClickListener {
            McpForegroundService.stop(this)
            Toast.makeText(this, "MCP 服务已停止", Toast.LENGTH_SHORT).show()
        }
        btnViewLogs.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 自动解锁
        btnSaveUnlock.setOnClickListener {
            Prefs.setUnlockPassword(this, etUnlockPwd.text.toString())
            Toast.makeText(this, "解锁密码已保存", Toast.LENGTH_SHORT).show()
        }
        btnTestUnlock.setOnClickListener {
            Prefs.setUnlockPassword(this, etUnlockPwd.text.toString())
            if (!AccessibilityBridge.isReady()) {
                Toast.makeText(this, "请先开启无障碍权限", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "3 秒后尝试解锁…", Toast.LENGTH_SHORT).show()
            // 延迟执行，让用户有时间按电源键锁屏
            handler.postDelayed({
                val ok = AccessibilityBridge.instance?.unlock() ?: false
                runOnUiThread {
                    Toast.makeText(this, if (ok) "解锁成功 ✓" else "解锁失败 ✗", Toast.LENGTH_LONG).show()
                }
            }, 3000)
        }
    }

    private fun testAdb() {
        if (!cbAdb.isChecked) {
            Toast.makeText(this, "请先勾选「启用 ADB 备选通道」", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val client = AdbShellClient()
            val ok = try {
                client.connect(Prefs.getAdbTarget(this), 4000)
                val out = client.exec("echo adb_ok")
                out.trim() == "adb_ok"
            } catch (e: Throwable) {
                false
            } finally {
                try { client.close() } catch (_: Throwable) {}
            }
            runOnUiThread {
                if (ok) {
                    Toast.makeText(this, "ADB 连接成功 ✓", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "ADB 连接失败，请检查无线调试端口", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // 状态刷新
    // ------------------------------------------------------------------

    private fun refreshAccessibilityStatus() {
        val enabled = isAccessibilityEnabled()
        if (enabled) {
            tvAccStatus.text = "无障碍：已启用 ✓"
            tvAccStatus.setTextColor(getColor(R.color.ok_green))
        } else {
            tvAccStatus.text = "无障碍：未启用 ✗"
            tvAccStatus.setTextColor(getColor(R.color.warn_red))
        }
    }

    private fun refreshStatus() {
        // MCP 服务
        if (McpForegroundService.running) {
            tvServerStatus.text = "MCP 服务：运行中 ✓"
            tvServerStatus.setTextColor(getColor(R.color.ok_green))
            tvAddr.text = "接入地址：http://${McpForegroundService.listenAddress}/mcp"
            btnStart.isEnabled = false
            btnStop.isEnabled = true
        } else {
            tvServerStatus.text = "MCP 服务：未启动"
            tvServerStatus.setTextColor(getColor(R.color.text_sub))
            tvAddr.text = "接入地址：-"
            btnStart.isEnabled = true
            btnStop.isEnabled = false
        }

        // ADB 通道
        if (!cbAdb.isChecked) {
            tvAdbStatus.text = "ADB 通道：未启用"
        } else if (McpForegroundService.adbConnected) {
            tvAdbStatus.text = "ADB 通道：已连接 ✓"
            tvAdbStatus.setTextColor(getColor(R.color.ok_green))
        } else {
            tvAdbStatus.text = "ADB 通道：未连接"
            tvAdbStatus.setTextColor(getColor(R.color.warn_red))
        }
    }

    // ------------------------------------------------------------------
    // 无障碍状态检测
    // ------------------------------------------------------------------

    private fun isAccessibilityEnabled(): Boolean {
        val mgr = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (!mgr.isEnabled) return false
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = SimpleStringSplitter(':')
        splitter.setString(enabled)
        val expected = ComponentName(this, AccessibilityBridge::class.java).flattenToString()
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    // ------------------------------------------------------------------
    // frpc 完整配置编辑（已移至 SettingsActivity）
    // ------------------------------------------------------------------

    @Suppress("unused")
    private fun openWirelessDebugging() {
        // Android 11+ 可直接跳转无线调试页面
        try {
            val intent = Intent("com.android.settings.action.WIRELESS_DEBUGGING_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
    }
}
