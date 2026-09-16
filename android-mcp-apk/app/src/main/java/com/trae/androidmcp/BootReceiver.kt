package com.trae.androidmcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机自启：设备重启后自动拉起 MCP 前台服务（需无障碍权限已保留）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        // 仅在用户已配置过（Token 存在）时自动启动
        val token = Prefs.getToken(context)
        if (token.isNotBlank()) {
            McpForegroundService.start(context)
        }
    }
}
