package com.trae.androidmcp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * 设置页面：frpc 配置、宏管理、日志查看入口。
 */
class SettingsActivity : Activity() {

    private val proxyTypes = arrayOf("tcp", "http", "https", "udp", "stcp", "xtcp")

    // ---- frpc ----
    private lateinit var tvFrpStatus: TextView
    private lateinit var etFrpServerAddr: EditText
    private lateinit var etFrpServerPort: EditText
    private lateinit var etFrpToken: EditText
    private lateinit var etFrpUser: EditText
    private lateinit var etFrpProxyName: EditText
    private lateinit var spFrpProxyType: Spinner
    private lateinit var etFrpLocalIp: EditText
    private lateinit var etFrpLocalPort: EditText
    private lateinit var etFrpRemotePort: EditText
    private lateinit var etFrpCustomDomains: EditText
    private lateinit var cbFrpRaw: CheckBox
    private lateinit var btnFrpEditRaw: Button
    private lateinit var btnFrpStart: Button
    private lateinit var btnFrpStop: Button

    // ---- 宏 ----
    private lateinit var layoutMacroList: LinearLayout
    private lateinit var btnRefreshMacros: Button

    private val handler = Handler(Looper.getMainLooper())
    private val statusPoll = object : Runnable {
        override fun run() {
            refreshFrpStatus()
            handler.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        bindViews()
        loadFrpPrefs()
        setupListeners()
        refreshMacroList()
    }

    override fun onResume() {
        super.onResume()
        refreshFrpStatus()
        refreshMacroList()
        handler.post(statusPoll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusPoll)
    }

    // ------------------------------------------------------------------
    // 视图绑定
    // ------------------------------------------------------------------

    private fun bindViews() {
        tvFrpStatus = findViewById(R.id.tv_frp_status)
        etFrpServerAddr = findViewById(R.id.et_frp_server_addr)
        etFrpServerPort = findViewById(R.id.et_frp_server_port)
        etFrpToken = findViewById(R.id.et_frp_token)
        etFrpUser = findViewById(R.id.et_frp_user)
        etFrpProxyName = findViewById(R.id.et_frp_proxy_name)
        spFrpProxyType = findViewById(R.id.sp_frp_proxy_type)
        etFrpLocalIp = findViewById(R.id.et_frp_local_ip)
        etFrpLocalPort = findViewById(R.id.et_frp_local_port)
        etFrpRemotePort = findViewById(R.id.et_frp_remote_port)
        etFrpCustomDomains = findViewById(R.id.et_frp_custom_domains)
        cbFrpRaw = findViewById(R.id.cb_frp_raw)
        btnFrpEditRaw = findViewById(R.id.btn_frp_edit_raw)
        btnFrpStart = findViewById(R.id.btn_frp_start)
        btnFrpStop = findViewById(R.id.btn_frp_stop)

        layoutMacroList = findViewById(R.id.layout_macro_list)
        btnRefreshMacros = findViewById(R.id.btn_refresh_macros)

        spFrpProxyType.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, proxyTypes
        )
    }

    private fun loadFrpPrefs() {
        etFrpServerAddr.setText(Prefs.getFrpServerAddr(this))
        etFrpServerPort.setText(Prefs.getFrpServerPort(this).toString())
        etFrpToken.setText(Prefs.getFrpToken(this))
        etFrpUser.setText(Prefs.getFrpUser(this))
        etFrpProxyName.setText(Prefs.getFrpProxyName(this))
        spFrpProxyType.setSelection(proxyTypes.indexOf(Prefs.getFrpProxyType(this)).coerceAtLeast(0))
        etFrpLocalIp.setText(Prefs.getFrpLocalIp(this))
        etFrpLocalPort.setText(Prefs.getFrpLocalPort(this).toString())
        etFrpRemotePort.setText(Prefs.getFrpRemotePort(this).toString())
        etFrpCustomDomains.setText(Prefs.getFrpCustomDomains(this))
        cbFrpRaw.isChecked = Prefs.isFrpUseRaw(this)
    }

    private fun saveFrpPrefs() {
        Prefs.setFrpServerAddr(this, etFrpServerAddr.text.toString())
        etFrpServerPort.text.toString().toIntOrNull()?.let { Prefs.setFrpServerPort(this, it) }
        Prefs.setFrpToken(this, etFrpToken.text.toString())
        Prefs.setFrpUser(this, etFrpUser.text.toString())
        Prefs.setFrpProxyName(this, etFrpProxyName.text.toString())
        Prefs.setFrpProxyType(this, spFrpProxyType.selectedItem.toString())
        Prefs.setFrpLocalIp(this, etFrpLocalIp.text.toString())
        etFrpLocalPort.text.toString().toIntOrNull()?.let { Prefs.setFrpLocalPort(this, it) }
        etFrpRemotePort.text.toString().toIntOrNull()?.let { Prefs.setFrpRemotePort(this, it) }
        Prefs.setFrpCustomDomains(this, etFrpCustomDomains.text.toString())
        Prefs.setFrpUseRaw(this, cbFrpRaw.isChecked)
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    private fun setupListeners() {
        findViewById<Button>(R.id.btn_settings_back).setOnClickListener { finish() }

        btnFrpStart.setOnClickListener {
            saveFrpPrefs()
            val addr = Prefs.getFrpServerAddr(this)
            if (addr.isBlank()) {
                Toast.makeText(this, "请先填写 frps 服务端地址", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (FrpcManager.start()) {
                Toast.makeText(this, "frpc 启动中…", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "frpc 启动失败，请查看日志", Toast.LENGTH_LONG).show()
            }
            refreshFrpStatus()
        }
        btnFrpStop.setOnClickListener {
            FrpcManager.stop()
            refreshFrpStatus()
        }
        btnFrpEditRaw.setOnClickListener {
            saveFrpPrefs()
            showRawConfigDialog()
        }

        btnRefreshMacros.setOnClickListener { refreshMacroList() }
        findViewById<Button>(R.id.btn_view_logs).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
    }

    private fun refreshFrpStatus() {
        FrpcManager.refreshStatus()
        if (FrpcManager.running) {
            tvFrpStatus.text = "frpc：运行中 ✓"
            tvFrpStatus.setTextColor(getColor(R.color.ok_green))
            btnFrpStart.isEnabled = false
            btnFrpStop.isEnabled = true
        } else {
            tvFrpStatus.text = "frpc：未启动"
            tvFrpStatus.setTextColor(getColor(R.color.text_sub))
            btnFrpStart.isEnabled = true
            btnFrpStop.isEnabled = false
        }
    }

    // ------------------------------------------------------------------
    // frpc 完整配置编辑
    // ------------------------------------------------------------------

    private fun showRawConfigDialog() {
        val et = EditText(this).apply {
            setText(Prefs.getFrpRawConfig(this@SettingsActivity))
            setHint("粘贴完整 frpc.toml 配置内容")
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setPadding(24, 16, 24, 16)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(et) }
        AlertDialog.Builder(this)
            .setTitle("编辑完整 frpc.toml")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                Prefs.setFrpRawConfig(this, et.text.toString())
                cbFrpRaw.isChecked = true
                Prefs.setFrpUseRaw(this, true)
                Toast.makeText(this, "已保存并启用自定义配置", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------------
    // 宏列表
    // ------------------------------------------------------------------

    private fun refreshMacroList() {
        layoutMacroList.removeAllViews()
        val macros = MacroStore.list()
        if (macros.isEmpty()) {
            val empty = TextView(this).apply {
                text = "(暂无保存的宏，通过 MCP save_macro 创建)"
                setTextColor(getColor(R.color.text_sub))
                textSize = 13f
                setPadding(0, 8, 0, 8)
            }
            layoutMacroList.addView(empty)
            return
        }
        for (m in macros) {
            val id = m.optString("id")
            val name = m.optString("name")
            val updated = m.optString("updated_at")
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 10, 0, 10)
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this@SettingsActivity).apply {
                text = "$name  [$id]"
                setTextColor(getColor(R.color.text_main))
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            info.addView(TextView(this@SettingsActivity).apply {
                text = updated
                setTextColor(getColor(R.color.text_sub))
                textSize = 11f
            })
            row.addView(info)
            val delBtn = Button(this, null, android.R.attr.buttonBarButtonStyle).apply {
                text = "删除"
                setTextColor(getColor(R.color.warn_red))
                textSize = 12f
                setOnClickListener {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("删除宏")
                        .setMessage("确定删除宏「$name」($id)？")
                        .setPositiveButton("删除") { _, _ ->
                            MacroStore.delete(id)
                            refreshMacroList()
                            Toast.makeText(this@SettingsActivity, "已删除", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            row.addView(delBtn)
            layoutMacroList.addView(row)
        }
    }
}
