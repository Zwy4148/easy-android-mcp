package com.trae.androidmcp

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 独立日志页面：按日期查看持久化日志。
 *
 * - 查看今天：自动实时刷新（每秒）
 * - 查看历史日期：手动刷新
 * - 支持复制全部、清空当日日志
 */
class LogActivity : Activity() {

    private lateinit var btnBack: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnDate: Button
    private lateinit var btnToday: Button
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnCopy: Button
    private lateinit var btnClear: Button

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val calendar = Calendar.getInstance()

    private var firstLoad = true

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            loadLog()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        // 确保 LogBuffer 已初始化（Service 未启动时也能读历史日志）
        LogBuffer.init(this)

        bindViews()
        setupListeners()
        calendar.time = Date()
        updateDateButton()
    }

    override fun onResume() {
        super.onResume()
        loadLog()
        // 仅查看今天时自动刷新
        if (isToday()) {
            handler.post(refreshRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btn_back)
        btnRefresh = findViewById(R.id.btn_refresh)
        btnPrev = findViewById(R.id.btn_prev_day)
        btnNext = findViewById(R.id.btn_next_day)
        btnDate = findViewById(R.id.btn_date)
        btnToday = findViewById(R.id.btn_today)
        tvLog = findViewById(R.id.tv_log)
        scrollLog = findViewById(R.id.scroll_log)
        btnCopy = findViewById(R.id.btn_copy)
        btnClear = findViewById(R.id.btn_clear)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }
        btnRefresh.setOnClickListener { loadLog() }
        btnPrev.setOnClickListener { shiftDay(-1) }
        btnNext.setOnClickListener { shiftDay(1) }
        btnDate.setOnClickListener { showDatePicker() }
        btnToday.setOnClickListener { goToday() }
        btnCopy.setOnClickListener { copyAll() }
        btnClear.setOnClickListener { clearCurrentDate() }
    }

    private fun shiftDay(delta: Int) {
        calendar.add(Calendar.DAY_OF_YEAR, delta)
        updateDateButton()
        handler.removeCallbacks(refreshRunnable)
        firstLoad = true
        loadLog()
        if (isToday()) handler.post(refreshRunnable)
    }

    private fun goToday() {
        calendar.time = Date()
        updateDateButton()
        handler.removeCallbacks(refreshRunnable)
        firstLoad = true
        loadLog()
        handler.post(refreshRunnable)
    }

    private fun currentDate(): String = dateFmt.format(calendar.time)

    private fun isToday(): Boolean = currentDate() == dateFmt.format(Date())

    private fun updateDateButton() {
        val label = if (isToday()) "${currentDate()}（今天）" else currentDate()
        btnDate.text = label
        // 未来日期不可浏览
        btnNext.isEnabled = !isToday()
        btnNext.alpha = if (isToday()) 0.4f else 1f
    }

    private fun loadLog() {
        val date = currentDate()
        // 查看今天时，先把内存缓冲里尚未落盘的行刷入文件
        if (isToday()) LogBuffer.flush()
        val text = LogBuffer.getLogForDate(date)
        tvLog.text = text.ifEmpty { "(该日期暂无日志)" }
        // 仅首次加载时定位到最底部，之后刷新不再自动滚动，避免打断用户阅读
        if (firstLoad) {
            firstLoad = false
            scrollLog.post {
                scrollLog.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun copyAll() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("mcp_logs_${currentDate()}", tvLog.text))
        Toast.makeText(this, "已复制 ${currentDate()} 日志", Toast.LENGTH_SHORT).show()
    }

    private fun clearCurrentDate() {
        val date = currentDate()
        AlertDialog.Builder(this)
            .setTitle("确认清空")
            .setMessage("确定要删除 $date 的全部日志吗？此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                LogBuffer.clearDate(date)
                loadLog()
                Toast.makeText(this, "已清空 $date 日志", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 弹出已有日志日期列表供选择 */
    private fun showDatePicker() {
        val dates = LogBuffer.listDates().toTypedArray()
        if (dates.isEmpty()) {
            Toast.makeText(this, "暂无历史日志日期", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("选择日期")
            .setItems(dates) { _, which ->
                val selected = dates[which]
                calendar.time = dateFmt.parse(selected) ?: Date()
                updateDateButton()
                handler.removeCallbacks(refreshRunnable)
                firstLoad = true
                loadLog()
                if (isToday()) handler.post(refreshRunnable)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
