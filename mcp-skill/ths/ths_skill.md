# 同花顺app操作技能
## 查看当前持仓
```json
{
  "name": "view_current_position",
  "arguments": {
    "steps": [
      {"type": "open_app", "package": "com.hexin.plat.android"},
      {"type": "wait", "ms": 2000},
      {"type": "tap_element", "text": "交易"},
      {"type": "wait", "ms": 500},
      {"type": "tap_element", "text": "持仓"},
    ],
    "stop_on_error": true,
  }
}
```
