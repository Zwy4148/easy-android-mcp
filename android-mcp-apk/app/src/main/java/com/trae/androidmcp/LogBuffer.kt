package com.trae.androidmcp

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 日志缓冲：内存环形缓冲 + 按日期落盘持久化。
 *
 * - 内存缓冲：保留最近 500 条，供实时展示
 * - 文件持久化：按日期写入 <filesDir>/logs/YYYY-MM-DD.log，重启不丢失
 */
object LogBuffer {

    data class Entry(
        val time: String,
        val level: String,
        val tag: String,
        val message: String
    )

    private const val MAX_SIZE = 500
    private val buffer = CopyOnWriteArrayList<Entry>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private var appContext: Context? = null

    /** 单线程顺序写文件，避免并发竞争 */
    private val fileExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LogBufferWriter").apply { isDaemon = true }
    }

    /** 必须在 Application/Service/Activity 启动时调用一次，注入 Context 供落盘使用 */
    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    fun d(tag: String, msg: String) = add("D", tag, msg)
    fun i(tag: String, msg: String) = add("I", tag, msg)
    fun w(tag: String, msg: String) = add("W", tag, msg)
    fun e(tag: String, msg: String) = add("E", tag, msg)

    private fun add(level: String, tag: String, msg: String) {
        val now = Date()
        val entry = Entry(timeFmt.format(now), level, tag, msg)
        buffer.add(entry)
        while (buffer.size > MAX_SIZE) {
            buffer.removeAt(0)
        }
        // 异步追加到当日日志文件
        val ctx = appContext
        if (ctx != null) {
            val dateStr = dateFmt.format(now)
            val line = "${entry.time} ${entry.level}/${entry.tag}: ${entry.message}"
            fileExecutor.execute {
                try {
                    appendToFile(ctx, dateStr, line)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun appendToFile(ctx: Context, dateStr: String, line: String) {
        val dir = File(ctx.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "$dateStr.log")
        file.appendText("$line\n", Charsets.UTF_8)
    }

    // ------------------------------------------------------------------
    // 内存缓冲访问（实时）
    // ------------------------------------------------------------------

    /** 返回全部内存日志（时间正序） */
    fun all(): List<Entry> = buffer.toList()

    /** 返回纯文本格式，便于复制 */
    fun asText(): String =
        buffer.joinToString("\n") { "${it.time} ${it.level}/${it.tag}: ${it.message}" }

    fun clear() = buffer.clear()

    // ------------------------------------------------------------------
    // 日期文件访问（持久化）
    // ------------------------------------------------------------------

    /** 今天的日期字符串 yyyy-MM-dd */
    fun todayStr(): String = dateFmt.format(Date())

    /** 列出所有有日志的日期（降序，最新在前） */
    fun listDates(): List<String> {
        val ctx = appContext ?: return emptyList()
        val dir = File(ctx.filesDir, "logs")
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension.equals("log", ignoreCase = true) }
            ?.map { it.nameWithoutExtension }
            ?.sortedDescending()
            ?: emptyList()
    }

    /** 读取指定日期的日志文本（yyyy-MM-dd） */
    fun getLogForDate(date: String): String {
        val ctx = appContext ?: return ""
        val file = File(ctx.filesDir, "logs/$date.log")
        if (!file.exists()) return ""
        return try {
            file.readText(Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
    }

    /** 删除指定日期的日志文件 */
    fun clearDate(date: String) {
        val ctx = appContext ?: return
        val file = File(ctx.filesDir, "logs/$date.log")
        if (file.exists()) file.delete()
        // 若删除的是今天，同时清空内存缓冲
        if (date == todayStr()) buffer.clear()
    }

    /** 等排队中的写操作完成（读取当日日志前调用，避免漏最新行） */
    fun flush() {
        try {
            fileExecutor.submit {}.get()
        } catch (_: Throwable) {
        }
    }
}
