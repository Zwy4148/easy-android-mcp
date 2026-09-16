# 同花顺app操作技能
## 股票卖出技能
注意：需要修改以下的股票、价格、数量为实际要出售的股票、价格、数量
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
      {"type": "wait", "ms": 500},
      {"type": "tap_element", "text": "招商南油"},
      {"type": "wait", "ms": 500},
      {"type": "input_element", "class": "android.widget.EditText", "index": 1, "text": "价格，例如4.99"},
      {"type": "input_element", "class": "android.widget.EditText", "index": 2, "text": "数量，例如400"},
      {"type": "wait", "ms": 1000},
      {"type": "tap_element", "text": "卖 出", "click_type": "coordinate"},
      {"type": "wait", "ms": 1500},
      {"type": "tap_element", "text": "确认卖出"}
      {"type": "tap_element", "text": "确认"}
    ],
    "stop_on_error": true,
    "include_screenshot": true
  }
}
```
## 股票买入技能
注意：需要修改以下的股票、价格、数量为实际要购买的股票、价格、数量
```json
{
  "name": "buy_stock",  
  "arguments": {
    "steps": [
      {"type": "open_app", "package": "com.hexin.plat.android"},
      {"type": "wait", "ms": 2000},
      {"type": "tap_element", "text": "交易"},
      {"type": "wait", "ms": 500},
      {"type": "tap_element", "text": "买入"},
      {"type": "wait", "ms": 500},
      {"type": "input_element", "resource_id": "com.hexin.plat.android:id/auto_stockcode", "text": "股票名称"},
      {"type": "input_element", "class": "android.widget.EditText", "index": 1, "text": "股票名称"},
      {"type": "wait", "ms": 500},
      {"type": "input_element", "class": "android.widget.EditText", "index": 1, "text": "价格，例如4.99"},
      {"type": "wait", "ms": 500},
      {"type": "input_element", "class": "android.widget.EditText", "index": 2, "text": "数量，例如400"},
      {"type": "wait", "ms": 1000},
      {"type": "tap_element", "text": "买 入", "click_type": "coordinate"},
      {"type": "wait", "ms": 500},
      {"type": "tap_element", "text": "确认买入"},
      {"type": "wait", "ms": 500},
      {"type": "tap_element", "text": "确认"}
    ],
    "stop_on_error": false
  }
}
```

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
## 股票撤单
注意：需要修改以下的股票、价格、数量为实际要撤单的股票、价格、数量
```json
{
  "name": "cancel_order",
  "arguments": {
    "steps": [
      {"type": "open_app", "package": "com.hexin.plat.android"},
      {"type": "wait", "ms": 2000},
      {"type": "tap_element", "text": "交易"},
      {"type": "wait", "ms": 500},
      {"type": "tap_element", "text": "撤单"},
      {"type": "wait", "ms": 500},
      {"type": "tap_element", "text": "招商南油", "index": 1},
      {"type": "wait", "ms": 500},
      {"type": "tap_element", "text": "撤单"},
      {"type": "wait", "ms": 500},
      {"type": "tap_element", "text": "确认"}
    ],
    "stop_on_error": true,
  }
}
```