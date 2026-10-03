# 真机运行验证 · R90 闹钟 App

承接 `真机验证-ColorOS16-SKIPUI.md`。那份验证的是**系统能力**，这份验证**App 本身**。

被测设备仍在 PMB110 / ColorOS 16.0.9.400 / Android 16 (SDK 36)。
安装时间：`2026-10-03 01:00:10`。

---

## 1. 安装

| 项 | 值 |
|---|---|
| 命令 | `adb install -r app/build/outputs/apk/debug/app-debug.apk` |
| 结果 | `Performing Streamed Install / Success` |
| 包名 / 版本 | `com.yanfei.r90alarm` / 0.1 |
| 落盘路径 | `/data/app/~~[安装随机串]/com.yanfei.r90alarm-[签名散列]/base.apk`（两段随机值随安装变化，无分析价值，已隐去） |

> 首次尝试（设备仍在加密锁屏时）挂起 5 分 39 秒无响应，被迫中止。
> **解锁后重试一次即成功** —— 结论：ColorOS 的 `adb install` 需要设备处于解锁状态。

## 2. 界面实测

### 2.1 主界面（深色，系统默认字体）

| 观察点 | 结果 |
|---|---|
| 大字时间 | `1:01:30`，逐秒跳动（跨多次截图均在同一秒对齐，未观察到漂移） |
| 日期行 | `2026年10月3日 星期六` —— 正确 |
| 胶囊行 | `准备入睡` + `立即 / 5 分 / 10 分 / 15 分 / 20 分…`，横向可滚动，选中项 `15 分` 高亮 |
| 列表 | 9 行齐全，第 1 行起可见；每行含「周期号 / 起床时间 / 今天·周六 / 设置按钮」 |
| 崩溃 / ANR | 无（`logcat` 无本包 FATAL） |

**取整逻辑在真机上逐行核验通过**（`uiautomator dump` 取真实节点文本，时间基准 `1:01:05`，入睡 15 分）：

| 周期 | 算式 | 期望（向上取整） | 实测显示 | 判定 |
|---|---|---|---|---|
| 1 | 1:01:05 + 15m + 90m = 2:46:05 | 2:47 | `2:47 AM` | ✅ |
| 2 | + 180m = 4:16:05 | 4:17 | `4:17 AM` | ✅ |
| 3 | + 270m = 5:46:05 | 5:47 | `5:47 AM` | ✅ |
| 4 | + 360m = 7:16:05 | 7:17 | `7:17 AM` | ✅ |
| 5 | + 450m = 8:46:05 | 8:47 | `8:47 AM` | ✅ |
| 6 | + 540m = 10:16:05 | 10:17 | `10:17 AM` | ✅ |
| 7 | + 630m = 11:46:05 | 11:47 | `11:47 AM` | ✅ |
| 8 | + 720m = 13:16:05 | 13:17 | `1:17 PM` | ✅ |
| 9 | + 810m = 14:46:05 | 14:47 | `2:47 PM` | ✅ |

（`1:01:05` 的秒位非零，故每一档都是从 `:05` 向上进位到下一分钟，9 档全对。）

### 2.2 大字体（T-28）

| font_scale | 结果 |
|---|---|
| 1.3 | ✅ 无截断、无重叠；行高自动放大，按钮文字仍完整 |
| 1.5 | ✅ 仍无截断、无重叠；可滚动区域正常 |

测完已把 `font_scale` 还原为 `1.0`。

### 2.3 深色 / 浅色模式

| 模式 | 结果 |
|---|---|
| 深色（系统默认） | ✅ 深底浅字，紫色强调色，对比度良好 |
| 浅色（`cmd uimode night no`） | ✅ 白底深字、紫色按钮；无残留深色块 |

测完已把 `uimode` 还原为 `auto`（原始值）。

> 观察：浅色模式下屏幕**状态栏区域**出现一块灰底。该现象在系统时钟 App 里同样存在，
> 故判定为 ColorOS 系统行为，**非本 App 缺陷**。

## 3. 端到端链路（最关键的一项）

**这是唯一能证明「App 真的调用了系统时钟」的测试。**

### 3.1 操作

在 App 内滚动到周期 9（`2:48 PM`），点击其「设置」按钮（坐标 `1026,2560`）。
点击时刻：`2026-10-03 01:02:18`。

### 3.2 证据 A：顶层 Activity 全程未变

| 时刻 | `topResumedActivity` |
|---|---|
| 点击前 | `com.yanfei.r90alarm/.MainActivity` |
| 点击后 3 秒 | `com.yanfei.r90alarm/.MainActivity`（**未变，无时钟界面**） |

### 3.3 证据 B：ColorOS handler 的生命周期日志

`logcat` 中 `01:02:18.7xx ~ 01:02:18.97x` 的关键行（约 230 毫秒内完成）：

```
D MAGT_SYNC_FRAME: package = com.coloros.alarmclock,
    action = com.oplus.alarmclock.cts.HandleApiActivity, pid = 32512, uid = 10309
V WindowManager: Sent Transition (#31083) type = OPEN
    triggerTask = TaskInfo{ taskId=3997 ... A=10488:com.yanfei.r90alarm }
    topActivity = ComponentInfo{com.coloros.alarmclock/com.oplus.alarmclock.cts.HandleApiActivity}
V WindowManager: topActivityInfo=ActivityInfo{... HandleApiActivity}
    isVisibleRequested=true isTopActivityNoDisplay=false isTopActivityTransparent=true
D Transition: finishTransition ar ActivityRecord{... HandleApiActivity t3997} ,
    isVisibleRequested=false, state=STOPPING
D ActivityTaskManager: removeAppToken: ActivityRecord{... HandleApiActivity t3997} ... destroyed
```

要点：
1. handler 被拉起在 **R90 自己的 task 里**（`taskId=3997` / `A=10488:com.yanfei.r90alarm`）
2. `isTopActivityNoDisplay=false` + `isTopActivityTransparent=true` —— 印证静态分析查到的 `Theme.Translucent`（不是 `NoDisplay`）
3. 约 230 ms 后 `state=STOPPING → activityDestroyed`，**从未真正显示**
4. 期间 `topResumedActivity` 始终是 R90 自己

### 3.4 证据 C：闹钟真的写进了系统时钟

`dumpsys alarm` 新增条目 `origWhen=1791010080000` → 换算 **2026-10-03 14:48:00**。

与 App 同屏显示值比对：

| 时刻 | App 显示（周期 9） | 算式 | 落库闹钟 |
|---|---|---|---|
| 01:01:55 | `2:47 PM` | 1:01:55+15+810m = 14:46:55 → 14:47 | — |
| **01:02:18（点击）** | 应为 `2:48 PM` | 1:02:18+15+810m = 14:47:18 → **14:48** | **14:48** ✅ |
| 01:02:37 | `2:48 PM` | 1:02:37+15+810m = 14:47:37 → 14:48 | — |

### 3.5 证据 D：系统时钟列表里能看到它

`ACTION_SHOW_ALARMS` 打开系统时钟，`uiautomator dump` 读到：

```
2:48 | 下午 | 响一次 | R90 闹钟 | 开启(true)
```

**「R90 闹钟」这个标签来自 App 的 `EXTRA_MESSAGE`**；开关为 `true`（已启用）。
这一条是验收标准里「系统时钟的闹钟列表里能看到设置的闹钟」的直接证据。

## 4. 权限与隐私（T-32）

| 项 | 结果 |
|---|---|
| 申请权限总数 | **2 个** |
| `com.android.alarm.permission.SET_ALARM` | `granted=true`（安装时即授予，**无运行时弹窗**） |
| `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | `granted=true`（androidx 自动生成，自用） |
| 运行时权限弹窗 | **无** |
| 网络活动 | `dumpsys netstats detail` 中匹配 `r90alarm` 的记录数 = **0** |

## 5. 设备上遗留的闹钟 —— 已全部清理（2026-10-03 01:2x）

本次验证在系统时钟里留下 4 条闹钟，**已通过 `uiautomator` 界面自动化全部删除**。

删除前实测到的清单（用 `uiautomator dump` 逐条读出）：

| 时间 | 标签 | 删除前状态 | 来源 |
|---|---|---|---|
| 8:46 上午 | R90 闹钟 | 开启（删前 dump 实读） | ✅ 用户已确认是他自己点的（周期 5） |
| 2:48 下午 | R90 闹钟 | 开启（见 §3.5 的 dump；删前未再读一次） | 本次点击 App「设置」按钮产生 |
| 9:34 下午 | R90测试SKIPUI_FALSE | 开启（删前 dump 实读） | `am start` 验证 `SKIP_UI` 对照组时产生 |
| 9:37 下午 | R90测试SKIPUI_TRUE | 关闭（删前 dump 实读） | `am start` 验证 `SKIP_UI` 测试组时产生 |

> 8:46 这条的算式佐证：`1:00:31 + 15m + 5×90m = 8:45:31 → 8:46`，即当时界面上的周期 5。

### 5.1 为什么不能"用命令删"

先试过两条非 UI 路径，都不通：

| 路径 | 结果 |
|---|---|
| `content query --uri content://com.coloros.alarmclock.alarmclock/...` | `SecurityException` —— `ClockProvider` 要求系统级权限 `oppo.permission.OPPO_COMPONENT_SAFE`，adb shell（uid 2000）没有 |
| 找 `ACTION_DELETE_ALARM` 之类的 Intent | **清单里不存在**。可用的 alarm 相关 action 只有 `SET_ALARM` / `SET_TIMER` / `SNOOZE_ALARM` / `DISMISS_ALARM` / `SHOW_ALARMS` / `SHOW_TIMERS`，其中没有"删除"语义 |

结论：**ColorOS 的闹钟只能通过界面删**。

### 5.2 实际删除流程（可复现）

```bash
# 1. 打开系统时钟的闹钟列表
adb shell am start -a android.intent.action.SHOW_ALARMS

# 2. 取节点树（注意 Git Bash 下要禁路径转换）
MSYS_NO_PATHCONV=1 adb shell uiautomator dump /sdcard/u.xml
MSYS_NO_PATHCONV=1 adb pull /sdcard/u.xml ./u.xml

# 3. 长按任意一条 → 进入多选模式（顶部出现「已选择 N 项」+「取消 / 全选」，底部导航变成「删除」）
adb shell input swipe 222 2051 222 2051 900        # 同点按住 900ms = 长按

# 4. 再「点击整行」切换每一条的选中状态（注意是全行可点，不是点右侧开关）
adb shell input tap 540 2095

# 5. 点底部「删除」
adb shell input tap 636 2618
```

### 5.3 删除后的核验（双重）

| 证据 | 结果 |
|---|---|
| 列表顶部标题 | 从「距离下次响铃还有 7 小时 29 分钟」→ **「所有闹钟已关闭」** |
| 全列表回读 | 滚回顶部重新 `uiautomator dump`，全文搜 `R90` → **`False`** |
| `dumpsys alarm` | `grep -i R90` → **无输出**（剩下的 `com.coloros.alarmclock` 条目都是系统自身的 `action.next.alarm.notices` / `action.next.workday.notices` 广播，不是用户闹钟） |

### 5.4 副作用还原

本次验证过程中改过的系统设置，已确认还原：

| 项 | 原值（备份） | 当前值 |
|---|---|---|
| `font_scale` | `1.0` | `1.0` ✅ |
| 夜间模式 | `Night mode: auto` | `Night mode: auto` ✅ |

## 6. 仍未覆盖

| 项 | 说明 |
|---|---|
| T-01 ~ T-40 未逐条执行 | 本次覆盖了其中的界面渲染、取整、大字体、深浅色、系统闹钟链路、权限六类，其余用例（时区切换、跨年闰年、免打扰、重启后闹钟留存、省电模式等）**未跑** |
| Level 3 降级路径 | 未测。需要禁用系统时钟 App 才能触发，属侵入性操作，未执行 |
| 跨天标签的「明天 / 后天」 | 本次测试时段是凌晨 1 点，9 档全部落在当天，「明天/后天」两档**未被真机覆盖**（仅单元测试覆盖） |
| 秒级心跳的功耗 | 未测量。R8 release 版的运行时行为也未验证 |
