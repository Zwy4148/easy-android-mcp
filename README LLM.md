



# Android MCP APK

将 Android 手机变成一个标准 **MCP（Model Context Protocol）** 服务器，供 AI 客户端通过 HTTP 调用完成手机自动化操作。

- **主控制通道**：无障碍服务（AccessibilityService）— 无需 root、无需 PC
- **备选通道**：ADB 无线调试 — 作为无障碍不可用时的兜底
- **协议**：JSON-RPC 2.0 over HTTP，路径 `/mcp`，Bearer Token 鉴权
- **运行方式**：前台服务常驻，开机自启

---

## 功能特性

### MCP 工具一览

服务启动后，客户端可通过 `tools/list` 获取完整工具定义，通过 `tools/call` 调用。

| 工具 | 说明 | 关键参数 |
|------|------|----------|
| `get_ui_xml` | 导出当前界面控件树 XML（对齐 uiautomator dump 格式） | — |
| `get_ui_elements` | 以 JSON 数组返回所有可交互控件（含坐标、文本、resource-id） | — |
| `tap` | 点击屏幕坐标 | `x`, `y` |
| `swipe` | 滑动 | `x1`, `y1`, `x2`, `y2`, `duration_ms` |
| `input_text` | 对当前聚焦输入框设置文本（原生支持中文/Emoji） | `text`, `append` |
| `press_key` | 全局按键 | `key`：BACK / HOME / APP_SWITCH / NOTIFICATIONS / QUICK_SETTINGS / LOCK_SCREEN |
| `screenshot` | 截图返回 Base64 PNG（Android 11+） | `scale`（0.1~1.0，默认 0.5） |
| `adb_shell` | 通过 ADB 执行 shell 命令 | `command` |
| `get_status` | 获取设备与服务状态 | — |
| `unlock` | 自动唤醒并解锁屏幕（字母数字密码） | `password`（可选，不传用已保存的） |
| `get_logs` | 获取最近运行日志 | `lines`（默认 100，最多 500） |
| `find_element` | 按属性查找控件，返回中心坐标与 bounds | `text` / `resource_id` / `class` / `content_desc` / `match` |
| `tap_element` | 按属性查找控件并点击 | 同上 + `click_type`（click / coordinate） |
| `input_element` | 按属性查找输入框并设置文本 | `text` + 定位条件 |
| `run_macro` | 执行宏（一键式组合操作） | `steps`, `stop_on_error` |
| `save_macro` | 保存宏到本地 | `macro_json` 或 `id` + `steps` |
| `list_macros` | 列出已保存的宏 | — |
| `get_macro` | 获取宏的完整定义 | `id` |
| `delete_macro` | 删除宏 | `id` |
| `run_macro_by_id` | 按 id 执行已保存的宏 | `id`, `include_screenshot`, `screenshot_scale` |

### 宏（Macro）系统

宏是一组按顺序执行的动作序列，适合封装"打开 App → 登录 → 进入某页面"等重复性流程。

**支持的 step 类型**：

| type | 参数 | 说明 |
|------|------|------|
| `home` / `back` / `app_switch` / `notifications` / `quick_settings` / `lock_screen` | — | 全局动作 |
| `unlock` | `password`（可选） | 自动解锁 |
| `open_app` | `package` | 启动指定包名的 App |
| `tap` | `x`, `y` | 点击坐标 |
| `swipe` | `x1`, `y1`, `x2`, `y2`, `duration_ms` | 滑动 |
| `input` | `text`, `append` | 对当前焦点输入框输入 |
| `wait` | `ms` | 等待（50~30000ms） |
| `press_key` | `key` | 全局按键 |
| `tap_element` | `text` / `resource_id` / `class` / `content_desc` / `match` / `index` | 查找控件并点击 |
| `input_element` | `text` + 定位条件 | 查找输入框并输入 |

宏执行完毕后，会返回每步结果以及当前页面的包名、控件列表（可选截图），方便 AI 感知执行效果。

**保存宏的两种方式**：

```json
// 方式一：传完整 macro_json
{
  "macro_json": "{\"id\":\"open_wechat\",\"name\":\"打开微信\",\"steps\":[{\"type\":\"home\"},{\"type\":\"open_app\",\"package\":\"com.tencent.mm\"}]}"
}

// 方式二：拆分参数
{
  "id": "open_wechat",
  "name": "打开微信",
  "steps": [{"type":"home"},{"type":"open_app","package":"com.tencent.mm"}]
}
```

### 自动解锁

在首页输入锁屏字母数字密码并保存后，可通过 `unlock` 工具或宏中的 `unlock` 步骤自动唤醒屏幕、上滑、输入密码并确认。密码以明文存储在 SharedPreferences 中。

### 日志系统

- 内存环形缓冲保留最近 500 条日志
- 按日期持久化到 `<filesDir>/logs/YYYY-MM-DD.log`
- App 内"查看运行日志"可按日期浏览、复制、清空
- `get_logs` 工具可返回最近 N 行日志供排障

### frp 内网穿透（内置，入口已隐藏）

项目内置 frp 客户端能力，可将本地 MCP 端口映射到公网 frps 服务器。该功能的 UI 入口目前在设置页隐藏（`activity_settings.xml` 中 frp 卡片 `visibility="gone"`），但代码与配置项完整保留。

配置项（SharedPreferences）：服务端地址/端口、auth.token、user、代理名称/类型（tcp/http/https/udp/stcp/xtcp）、本地 IP/端口、远端端口、自定义域名，或直接使用完整 `frpc.toml` 原文。

---

## 快速开始

### 1. 安装与权限

1. 安装 APK（`app/build/outputs/apk/debug/app-debug.apk`）
2. 打开 App → 点击「① 开启无障碍权限」→ 在系统设置中找到「Android MCP 自动化服务」并开启
3. （可选）开启无线调试，填写 ADB 地址作为备选通道

### 2. 启动服务

1. 设置监听端口（默认 8080）
2. 配置 Bearer Token（首次启动自动生成 32 字节随机十六进制串）
3. 点击「启动 MCP 服务」
4. 状态栏显示接入地址，如 `http://192.168.1.100:8080/mcp`

### 3. 调用 MCP

服务地址：`http://<设备IP>:<端口>/mcp`

鉴权方式（二选一）：
- Header：`Authorization: Bearer <token>`（或直接 `<token>`）
- Query：`?token=<token>`

**示例：获取工具列表**

```bash
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

**示例：点击屏幕**

```bash
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"tap","arguments":{"x":540,"y":960}}}'
```

**示例：执行宏**

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "run_macro",
    "arguments": {
      "steps": [
        {"type": "home"},
        {"type": "open_app", "package": "com.tencent.mm"},
        {"type": "wait", "ms": 2000},
        {"type": "tap_element", "text": "通讯录"},
        {"type": "wait", "ms": 500},
        {"type": "input_element", "text": "测试消息", "resource_id": "com.tencent.mm:id/d1v"}
      ]
    }
  }
}
```

---

## 项目结构

```
android-mcp-apk/
├── app/
│   ├── build.gradle.kts              # 构建配置（依赖 nanohttpd、frpclient jar）
│   ├── libs/
│   │   └── frpclient-classes.jar     # Go frp 客户端的 Java 绑定
│   ├── src/main/
│   │   ├── jniLibs/arm64-v8a/
│   │   │   └── libgojni.so           # Go frp 客户端原生库
│   │   ├── java/com/trae/androidmcp/
│   │   │   ├── MainActivity.kt       # 首页：权限引导、服务配置与启停、自动解锁
│   │   │   ├── SettingsActivity.kt   # 设置页：frp 配置（隐藏）、宏管理、日志入口
│   │   │   ├── LogActivity.kt        # 日志页：按日期查看/复制/清空日志
│   │   │   ├── McpForegroundService.kt  # MCP 前台服务（NanoHTTPD + JSON-RPC 分发）
│   │   │   ├── AccessibilityBridge.kt   # 无障碍服务：UI 树、手势、输入、截图、解锁
│   │   │   ├── AdbShellClient.kt     # 最小化 ADB 客户端（无线调试备选通道）
│   │   │   ├── FrpcManager.kt        # frp 客户端管理
│   │   │   ├── MacroStore.kt         # 宏的本地文件存储
│   │   │   ├── LogBuffer.kt          # 日志缓冲与按日期落盘
│   │   │   ├── Prefs.kt              # 统一配置持久化
│   │   │   └── BootReceiver.kt       # 开机自启
│   │   ├── res/
│   │   │   ├── layout/               # activity_main / activity_settings / activity_log
│   │   │   └── xml/accessibility_service_config.xml
│   │   └── AndroidManifest.xml
│   └── ...
├── gradle/wrapper/
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## 核心模块说明

### AccessibilityBridge（主控制通道）

继承 `AccessibilityService`，提供：

- **UI 树采集**：`dumpXml()` 导出 uiautomator 格式 XML；`listInteractiveElements()` 返回可交互控件 JSON
- **控件查找**：`findElement()` 按 text/resource_id/class/content_desc 多条件 AND 查找，支持 exact/contains/starts_with/ends_with 匹配
- **手势注入**：`tap()` / `swipe()` 通过 `dispatchGesture` 注入
- **文本输入**：`setFocusedText()` 直接 `ACTION_SET_TEXT`，原生支持中文/Emoji
- **全局动作**：`performGlobal()` 映射 BACK/HOME/APP_SWITCH/NOTIFICATIONS/QUICK_SETTINGS/LOCK_SCREEN
- **截图**：`takeScreenshotBase64()` 使用 Android 11+ 系统截图 API，无需 MediaProjection 弹窗
- **自动解锁**：`unlock()` 亮屏 → 上滑 → 定位输入框 → 输入密码 → 确认

### McpForegroundService

前台服务内嵌 `NanoHTTPD`，实现标准 MCP 协议：

- 仅接受 `POST /mcp`
- Bearer Token 鉴权（支持 Header 或 `?token=` Query）
- JSON-RPC 2.0 分发：`initialize` / `tools/list` / `tools/call` / `ping` / `notifications/initialized`
- 运行时通过 `LogBuffer` 记录请求/鉴权/错误日志

### AdbShellClient（备选通道）

纯 Kotlin 实现的最小化 ADB 客户端，直接连接本机 adbd（无线调试端口）：

- 实现 CNXN/AUTH/OPEN/OKAY/WRTE/CLSE 协议
- RSA 签名握手，首次需在设备上确认授权
- `exec(command)` 执行 shell 命令并返回合并输出

### MacroStore

宏以 JSON 文件存储在 `<filesDir>/macros/<id>.json`，包含 id、name、steps、stop_on_error、created_at、updated_at。id 仅允许字母数字下划线中划线，长度 1~64。

---

## 构建

环境要求：JDK 17、Android SDK（compileSdk 35）、NDK r26（仅构建 Go frp 库时需要）。

```powershell
# 构建 debug APK
cd android-mcp-apk
$env:JAVA_HOME = "C:\Program Files (x86)\Android\openjdk\jdk-17.0.8.101-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleDebug --no-daemon
```

输出：`app/build/outputs/apk/debug/app-debug.apk`

---

## 权限说明

| 权限 | 用途 |
|------|------|
| `INTERNET` / `ACCESS_NETWORK_STATE` | MCP HTTP 服务监听 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | 前台服务常驻 |
| `WAKE_LOCK` | 自动解锁与宏执行前亮屏 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 MCP 服务 |
| 无障碍权限 | 主控制通道（读 UI 树、注入手势、输入、截图） |

---

## 注意事项

- 最低支持 Android 8.0（API 26），截图功能需 Android 11+
- 无障碍服务必须由用户手动在系统设置中开启，App 无法自动授权
- ADB 备选通道需先在设备上开启无线调试并完成授权
- Token 以明文存储，请勿在不可信网络暴露 MCP 端口
- frp 功能入口默认隐藏，需修改 `activity_settings.xml` 中 frp 卡片的 `visibility` 属性恢复
