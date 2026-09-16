package com.trae.androidmcp

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 宏本地存储：以 JSON 文件形式保存在 filesDir/macros/<id>.json。
 *
 * MCP 通过 save_macro / list_macros / get_macro / delete_macro / run_macro_by_id 管理。
 */
object MacroStore {

    private const val DIR_NAME = "macros"
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private var appContext: Context? = null

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    private fun macrosDir(): File {
        val ctx = appContext ?: throw IllegalStateException("MacroStore not initialized")
        val dir = File(ctx.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun fileFor(id: String): File = File(macrosDir(), "$id.json")

    /** 校验 id：仅允许字母数字下划线中划线，长度 1~64 */
    private fun validateId(id: String): Boolean =
        id.isNotBlank() && id.length <= 64 && id.matches(Regex("^[a-zA-Z0-9_-]+$"))

    /**
     * 保存宏。
     * @param id 宏唯一标识
     * @param name 显示名称
     * @param steps 动作数组（JSON 字符串）
     * @param stopOnError 遇错是否停止
     */
    fun save(id: String, name: String?, steps: String, stopOnError: Boolean): JSONObject {
        if (!validateId(id)) throw IllegalArgumentException("非法 id：仅允许字母数字下划线中划线，1~64 字符")
        if (steps.isBlank()) throw IllegalArgumentException("steps 不能为空")
        val now = dateFmt.format(Date())
        val existing = get(id)
        val createdAt = existing?.optString("created_at") ?: now
        val obj = JSONObject().apply {
            put("id", id)
            put("name", name?.takeIf { it.isNotBlank() } ?: id)
            put("steps", org.json.JSONArray(steps))
            put("stop_on_error", stopOnError)
            put("created_at", createdAt)
            put("updated_at", now)
        }
        fileFor(id).writeText(obj.toString(2), Charsets.UTF_8)
        return obj
    }

    /** 获取宏，不存在返回 null */
    fun get(id: String): JSONObject? {
        val f = fileFor(id)
        if (!f.exists()) return null
        return try {
            JSONObject(f.readText(Charsets.UTF_8))
        } catch (_: Throwable) {
            null
        }
    }

    /** 列出所有宏的摘要（id, name, updated_at） */
    fun list(): List<JSONObject> {
        val dir = macrosDir()
        return dir.listFiles { f -> f.extension.equals("json", ignoreCase = true) }
            ?.mapNotNull { f ->
                try {
                    val o = JSONObject(f.readText(Charsets.UTF_8))
                    JSONObject().apply {
                        put("id", o.optString("id"))
                        put("name", o.optString("name"))
                        put("updated_at", o.optString("updated_at"))
                    }
                } catch (_: Throwable) {
                    null
                }
            }
            ?.sortedByDescending { it.optString("updated_at") }
            ?: emptyList()
    }

    /** 删除宏，返回是否成功 */
    fun delete(id: String): Boolean {
        val f = fileFor(id)
        return if (f.exists()) f.delete() else false
    }
}
