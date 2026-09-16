---
name: "android-mcp-control"
description: "通过 Android MCP 无障碍服务控制手机（点击、滑动、输入、截图、宏执行）。当用户要求操作安卓手机、自动化 App 流程、或调用 tap/find_element/run_macro 等工具时使用。"
---

# Android MCP 手机控制

通过无障碍服务 + MCP 协议远程操控 Android 手机。所有操作通过 HTTP MCP 端点执行，无需 root、无需 ADB（ADB 为备选通道）。

## 连接信息

- **URL**: `http://<手机IP>:<端口>/mcp`（默认端口 9980）
- **鉴权**: `Authorization: Bearer <Token>`

## 工具清单
同花顺app操作，参考ths/ths_skill.md

### 观察类

| 工具 | 说明 | 参数 |
|------|------|------|
| `get_ui_xml` | 导出当前界面控件树 XML（uiautomator 格式） | 无 |
| `get_ui_elements` | 返回可交互控件 JSON 数组 | 无 |
| `find_element` | 按属性查找控件，返回中心坐标+边界 | `text`/`resource_id`/`class`/`content_desc`, `match`(exact/contains/starts_with/ends_with), `index` |
| `screenshot` | 截图返回 Base64 PNG | `scale`(0.1~1.0, 默认0.5) |
| `get_status` | 设备状态（包名、无障碍、ADB、机型） | 无 |

### 操作类

| 工具 | 说明 | 参数 |
|------|------|------|
| `tap` | 坐标点击 | `x`, `y` |
| `swipe` | 滑动 | `x1,y1,x2,y2`, `duration_ms` |
| `tap_element` | 按属性查找并点击控件中心 | 同 `find_element` |
| `input_text` | 对当前聚焦输入框设文本 | `text`, `append` |
| `input_element` | 查找输入框并设文本 | 同 `find_element` + `text`, `append` |
| `press_key` | 全局按键 | `key`: BACK/HOME/APP_SWITCH/NOTIFICATIONS/QUICK_SETTINGS/LOCK_SCREEN |
| `adb_shell` | ADB 执行 shell 命令（备选） | `command` |

### 宏执行

| 工具 | 说明 |
|------|------|
| `run_macro` | 按顺序执行动作数组，返回每步结果 + 执行后页面详情 |

**参数：**
- `steps`: 动作数组
- `stop_on_error`: 遇错是否停止（默认 true）
- `include_screenshot`: 结果附加截图（默认 false）
- `screenshot_scale`: 截图缩放（默认 0.3）

**支持的 step 类型：**
- `home` / `back` / `app_switch` / `notifications` / `quick_settings` / `lock_screen`
- `open_app` (`package`)
- `tap` (`x`,`y`)
- `swipe` (`x1,y1,x2,y2,duration_ms`)
- `input` (`text`, `append`)
- `wait` (`ms`)
- `press_key` (`key`)
- `tap_element` (同 find_element 参数)
- `input_element` (同 find_element 参数 + `text`)

**返回：**
```json
{
  "steps": [{"step":0,"type":"...","result":"...","error":false}, ...],
  "final_page": {
    "package": "com.xxx",
    "elements": [...],
    "screenshot": "iVBORw0..."  // 仅 include_screenshot=true 时
  }
}
```

## 核心工作流

### 1. 先观察再操作

```
find_element / get_ui_elements → 确认控件存在 → tap_element / input_element
```

永远先获取页面状态，确认目标控件的 text/resource_id/class，再执行操作。

### 2. 控件定位策略

**优先级：**
1. `resource_id` — 最稳定，如 `com.hexin.plat.android:id/btn_transaction`
2. `text` + `match:contains` — 处理空格/特殊字符
3. `class` + `index` — 定位第 N 个同类型控件（如第 2 个 EditText）

**注意：**
- 金融 App 的按钮文本常含特殊空格（如「卖 出」），用 `match:"contains"` + `text:"卖"` + `index` 定位
- 按钮点击事件常绑在父容器（RelativeLayout/LinearLayout），`tap_element` 会自动向上找可点击祖先

### 3. 输入文本

- 用 `input_element` 按 class+index 定位输入框并设值
- 价格框通常是第 2 个 EditText（index=1），数量框第 3 个（index=2）

### 4. 复杂流程用 run_macro

多步操作封装成宏一次性执行，结果包含执行后页面详情（控件+截图），AI 可直接判断是否成功。

## 实战示例：同花顺卖出股票

```json
{
  "name": "run_macro",
  "arguments": {
    "steps": [
      {"type": "open_app", "package": "com.hexin.plat.android"},
      {"type": "wait", "ms": 2000},
      {"type": "tap_element", "text": "交易"},
      {"type": "wait", "ms": 1000},
      {"type": "tap_element", "text": "卖出"},
      {"type": "wait", "ms": 1000},
      {"type": "tap_element", "text": "招商南油"},
      {"type": "wait", "ms": 1000},
      {"type": "input_element", "class": "android.widget.EditText", "index": 1, "text": "5.12"},
      {"type": "input_element", "class": "android.widget.EditText", "index": 2, "text": "100"},
      {"type": "wait", "ms": 1000},
      {"type": "tap_element", "text": "卖", "match": "contains", "index": 2},
      {"type": "wait", "ms": 1500},
      {"type": "tap_element", "text": "确认卖出"}
    ],
    "stop_on_error": true,
    "include_screenshot": true
  }
}
```

## 注意事项

- 坐标点击即使返回「手势被拒绝」也实际生效，无需重试
- 部分金融 App 屏蔽 dispatchGesture，优先用 `tap_element`（ACTION_CLICK）
- 输入框用 `ACTION_SET_TEXT` 直接设值（原生中文）
- 截图需 Android 11+，低版本用 `adb_shell screencap`
- 每步操作后建议 `wait` 500~1000ms 等待页面响应
