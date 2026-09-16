package com.trae.androidmcp

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * 应用配置：统一使用 SharedPreferences 持久化。
 * 首次启动自动生成随机 Bearer Token，避免空 Token 裸奔。
 */
object Prefs {
    private const val FILE_NAME = "android_mcp_prefs"

    private const val KEY_PORT = "server_port"
    private const val KEY_TOKEN = "auth_token"
    private const val KEY_ADB_ENABLED = "adb_enabled"
    private const val KEY_ADB_TARGET = "adb_target"

    // ---- frpc 配置 ----
    private const val KEY_FRP_SERVER_ADDR = "frp_server_addr"
    private const val KEY_FRP_SERVER_PORT = "frp_server_port"
    private const val KEY_FRP_TOKEN = "frp_token"
    private const val KEY_FRP_USER = "frp_user"
    private const val KEY_FRP_PROXY_NAME = "frp_proxy_name"
    private const val KEY_FRP_PROXY_TYPE = "frp_proxy_type"
    private const val KEY_FRP_LOCAL_IP = "frp_local_ip"
    private const val KEY_FRP_LOCAL_PORT = "frp_local_port"
    private const val KEY_FRP_REMOTE_PORT = "frp_remote_port"
    private const val KEY_FRP_CUSTOM_DOMAINS = "frp_custom_domains"
    private const val KEY_FRP_USE_RAW = "frp_use_raw"
    private const val KEY_FRP_RAW_CONFIG = "frp_raw_config"

    // ---- 自动解锁 ----
    private const val KEY_UNLOCK_PASSWORD = "unlock_password"

    const val DEFAULT_PORT = 8080
    const val DEFAULT_ADB_TARGET = "127.0.0.1:5555"
    const val DEFAULT_FRP_SERVER_PORT = 7000
    const val DEFAULT_FRP_PROXY_TYPE = "tcp"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** 读取监听端口 */
    fun getPort(ctx: Context): Int = prefs(ctx).getInt(KEY_PORT, DEFAULT_PORT)

    fun setPort(ctx: Context, port: Int) {
        prefs(ctx).edit().putInt(KEY_PORT, port.coerceIn(1, 65535)).apply()
    }

    /** 读取鉴权 Token；不存在则生成 32 字节随机十六进制串 */
    fun getToken(ctx: Context): String {
        val p = prefs(ctx)
        val existing = p.getString(KEY_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val generated = bytes.joinToString("") { "%02x".format(it) }
        p.edit().putString(KEY_TOKEN, generated).apply()
        return generated
    }

    fun setToken(ctx: Context, token: String) {
        prefs(ctx).edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    fun isAdbEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ADB_ENABLED, false)

    fun setAdbEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ADB_ENABLED, enabled).apply()
    }

    fun getAdbTarget(ctx: Context): String =
        prefs(ctx).getString(KEY_ADB_TARGET, DEFAULT_ADB_TARGET) ?: DEFAULT_ADB_TARGET

    fun setAdbTarget(ctx: Context, target: String) {
        prefs(ctx).edit().putString(KEY_ADB_TARGET, target.trim()).apply()
    }

    // ------------------------------------------------------------------
    // frpc 配置
    // ------------------------------------------------------------------

    fun getFrpServerAddr(ctx: Context): String =
        prefs(ctx).getString(KEY_FRP_SERVER_ADDR, "") ?: ""

    fun setFrpServerAddr(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_FRP_SERVER_ADDR, v.trim()).apply()
    }

    fun getFrpServerPort(ctx: Context): Int =
        prefs(ctx).getInt(KEY_FRP_SERVER_PORT, DEFAULT_FRP_SERVER_PORT)

    fun setFrpServerPort(ctx: Context, port: Int) {
        prefs(ctx).edit().putInt(KEY_FRP_SERVER_PORT, port.coerceIn(1, 65535)).apply()
    }

    fun getFrpToken(ctx: Context): String =
        prefs(ctx).getString(KEY_FRP_TOKEN, "") ?: ""

    fun setFrpToken(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_FRP_TOKEN, v).apply()
    }

    fun getFrpUser(ctx: Context): String =
        prefs(ctx).getString(KEY_FRP_USER, "") ?: ""

    fun setFrpUser(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_FRP_USER, v.trim()).apply()
    }

    fun getFrpProxyName(ctx: Context): String =
        prefs(ctx).getString(KEY_FRP_PROXY_NAME, "mcp-tcp") ?: "mcp-tcp"

    fun setFrpProxyName(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_FRP_PROXY_NAME, v.trim()).apply()
    }

    fun getFrpProxyType(ctx: Context): String =
        prefs(ctx).getString(KEY_FRP_PROXY_TYPE, DEFAULT_FRP_PROXY_TYPE) ?: DEFAULT_FRP_PROXY_TYPE

    fun setFrpProxyType(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_FRP_PROXY_TYPE, v.trim()).apply()
    }

    fun getFrpLocalIp(ctx: Context): String =
        prefs(ctx).getString(KEY_FRP_LOCAL_IP, "127.0.0.1") ?: "127.0.0.1"

    fun setFrpLocalIp(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_FRP_LOCAL_IP, v.trim()).apply()
    }

    fun getFrpLocalPort(ctx: Context): Int =
        prefs(ctx).getInt(KEY_FRP_LOCAL_PORT, 8080)

    fun setFrpLocalPort(ctx: Context, port: Int) {
        prefs(ctx).edit().putInt(KEY_FRP_LOCAL_PORT, port.coerceIn(1, 65535)).apply()
    }

    fun getFrpRemotePort(ctx: Context): Int =
        prefs(ctx).getInt(KEY_FRP_REMOTE_PORT, 0)

    fun setFrpRemotePort(ctx: Context, port: Int) {
        prefs(ctx).edit().putInt(KEY_FRP_REMOTE_PORT, port.coerceIn(0, 65535)).apply()
    }

    fun getFrpCustomDomains(ctx: Context): String =
        prefs(ctx).getString(KEY_FRP_CUSTOM_DOMAINS, "") ?: ""

    fun setFrpCustomDomains(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_FRP_CUSTOM_DOMAINS, v.trim()).apply()
    }

    fun isFrpUseRaw(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_FRP_USE_RAW, false)

    fun setFrpUseRaw(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_FRP_USE_RAW, enabled).apply()
    }

    fun getFrpRawConfig(ctx: Context): String =
        prefs(ctx).getString(KEY_FRP_RAW_CONFIG, "") ?: ""

    fun setFrpRawConfig(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_FRP_RAW_CONFIG, v).apply()
    }

    // ------------------------------------------------------------------
    // 自动解锁密码（明文存储，用户已知风险）
    // ------------------------------------------------------------------

    fun getUnlockPassword(ctx: Context): String =
        prefs(ctx).getString(KEY_UNLOCK_PASSWORD, "") ?: ""

    fun setUnlockPassword(ctx: Context, pwd: String) {
        prefs(ctx).edit().putString(KEY_UNLOCK_PASSWORD, pwd).apply()
    }
}
