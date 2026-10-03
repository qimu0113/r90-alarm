# 真机验证 · ColorOS 16 是否遵守 `EXTRA_SKIP_UI`

**结论：遵守。一键无 UI 设置闹钟在这台设备上成立。**

---

## 1. 被测对象

| 项 | 值 |
|---|---|
| 机型 | OnePlus PMB110（一加 Ace 6 至尊版） |
| 系统 | Android 16（SDK 36） |
| 版本号 | `PMB110_16.0.9.400(CN01)` |
| 安全补丁 | 2026-07-01 |
| 时钟 App | `com.coloros.alarmclock`（不是 AOSP DeskClock） |
| 时钟 App 体积 | 46,647,485 B |
| adb 序列号 | `[已隐去]`（唯一设备标识，不公开） |

## 2. 静态证据

### 2.1 全系统只有一个 `ACTION_SET_ALARM` 处理器

```
adb shell cmd package query-activities --brief -a android.intent.action.SET_ALARM
→ com.coloros.alarmclock/com.oplus.alarmclock.cts.HandleApiActivity
```

一个 activity-alias（`com.coloros.alarmclock.cts.HandleApiActivity`）+ 一个实体 Activity
（`com.oplus.alarmclock.cts.HandleApiActivity`），两者都声明：

| 属性 | 值 |
|---|---|
| `android:permission` | `com.android.alarm.permission.SET_ALARM` |
| `android:exported` | `true` |
| `android:theme` | `@0x0103000f` |
| 处理的 action | SET_ALARM / SET_TIMER / SNOOZE_ALARM / DISMISS_ALARM / SHOW_ALARMS / SHOW_TIMERS |

**关于 theme**：`0x0103000f` 在 framework-res 里落到的名字是 **`style/Theme.Translucent`**，
**不是** `style/Theme.NoDisplay`（后者是 `0x01030055`）。
即 ColorOS 的 API 处理器**保留了渲染界面的能力**，与 AOSP DeskClock 的写法不同。
所以「主题是 NoDisplay 所以不会弹界面」这条推断**不成立**，必须靠实测。

### 2.2 时钟 APK 的 dex 里确实引用了 `SKIP_UI`

扫描 `classes2.dex` 得到的 `android.intent.extra.alarm.*` 常量：

```
android.intent.extra.alarm.HOUR
android.intent.extra.alarm.MINUTES
android.intent.extra.alarm.MESSAGE
android.intent.extra.alarm.DAYS
android.intent.extra.alarm.DAYS_OF_WEEK
android.intent.extra.alarm.RINGTONE
android.intent.extra.alarm.LENGTH
android.intent.extra.alarm.DELETE_AFTER_USE
android.intent.extra.alarm.SKIP_UI          ← 存在
android.intent.extra.alarm.holiday_switch   ← ColorOS 自有扩展
android.intent.extra.alarm.workday_switch   ← ColorOS 自有扩展
```

`HandleApiActivity` 中有**两个方法**读取该常量：`b0` 与 `c0`。
（`DELETE_AFTER_USE` 是 AOSP DeskClock 的 extra，进一步佐证其血统来自 DeskClock 分叉。）

### 2.3 `HandleApiActivity.b0` 的反编译（即 `handleSetAlarm`）

`b0` 的 catch 块中含日志字符串 `"handleSetAlarm e : "`，可确认其身份。

```
; ── 读参数 ──
const-string v5, "android.intent.extra.alarm.SKIP_UI"
invoke-virtual {v14, v5, v0}, Landroid/content/Intent;->getBooleanExtra:(Ljava/lang/String;Z)Z
move-result v0                        ; v0 = SKIP_UI（默认 false）

; ── 打日志（OPPO 把 SkipUi 拼成了 ShipUi）──
"... mShipUi = " + v0

; ── 分支 ──
if-eqz v0, 00d8                       ; SKIP_UI == false → 跳 00d8

; ===== SKIP_UI == true 分支（00cc–00d5）=====
invoke-virtual/range {v5,v6,v7,v8,v9,v10,v11,v12},
    HandleApiActivity;->i0:(Landroid/content/Intent;IIBLjava/lang/String;Ljava/lang/String;I)V
invoke-virtual {v13, v1}, HandleApiActivity;->m0:(Ljava/lang/String;)V
return-void                           ; ← 直接返回，不启动任何 Activity

; ===== SKIP_UI == false 分支（00d8 起）=====
00d8: if-ne v7, v2, 00ed              ; v7=hour, v2=-1 → hour != -1 则跳 00ed
      new Intent(context, com.oplus.alarmclock.AlarmClock)
      putExtras(原 intent); putExtra("is_from_handle_api", 1)
      startActivity(...)              ; hour == -1 → 打开时钟主页
      goto 011c
00ed: if (g0() == false) { i0(...) }  ; 条件满足时也写库
      new Intent(context, com.oplus.alarmclock.cts.AlarmClockCTS)
      startActivity(...)              ; hour != -1 → 打开预填页
011c: return
```

**读法**：`SKIP_UI=true` 走 `i0()` 写库后**直接 return**；`SKIP_UI=false` 才 `startActivity` 拉界面。

## 3. 真机实测（对照实验）

三次操作，命令与结果一一对应。

### 3.1 基线

| 观察点 | 值 |
|---|---|
| 顶层 Activity | `com.android.settings/.Settings$DevelopmentSettingsDashboardActivity` |
| `dumpsys alarm` 中 coloros 时钟条目 | 仅 1 条：`action.next.workday.notices`（ColorOS 工作日提醒，非用户闹钟） |

### 3.2 测试组：`SKIP_UI = true`

```bash
adb shell am start -a android.intent.action.SET_ALARM \
  --ei android.intent.extra.alarm.HOUR 21 \
  --ei android.intent.extra.alarm.MINUTES 37 \
  --es android.intent.extra.alarm.MESSAGE "R90测试SKIPUI_TRUE" \
  --ez android.intent.extra.alarm.SKIP_UI true
```

3 秒后：

| 观察点 | 结果 |
|---|---|
| 顶层 Activity | `com.android.settings/...DevelopmentSettingsDashboardActivity` —— **与基线完全一致，无界面弹出** |
| `dumpsys alarm` 新增条目 | `action.next.alarm.notices`、`change_state`、`indicator` |

时间戳换算（`origWhen`，毫秒）：

| 条目 | origWhen | 换算 |
|---|---|---|
| `next.alarm.notices` | 1791033720000 | 2026-10-03 21:22:00 +0800 |
| `change_state` / `indicator` | 1791034620000 | 2026-10-03 21:37:00 +0800 |

→ 请求的 21:37 精确落地；21:22 是 ColorOS 自动加的「提前 15 分钟预告」。

### 3.3 对照组：`SKIP_UI = false`

```bash
adb shell am start -a android.intent.action.SET_ALARM \
  --ei android.intent.extra.alarm.HOUR 21 \
  --ei android.intent.extra.alarm.MINUTES 38 \
  --es android.intent.extra.alarm.MESSAGE "R90测试SKIPUI_FALSE" \
  --ez android.intent.extra.alarm.SKIP_UI false
```

3 秒后：

| 观察点 | 结果 |
|---|---|
| 顶层 Activity | `com.coloros.alarmclock/com.oplus.alarmclock.cts.AlarmClockCTS` —— **界面被拉起** |

### 3.4 三组对照汇总

| 组 | `SKIP_UI` | 顶层 Activity 变化 | 闹钟是否写入 |
|---|---|---|---|
| 基线 | — | 无 | 无 |
| 测试组 | `true` | **无变化** | ✅ 已写入（21:37） |
| 对照组 | `false` | **变为 `AlarmClockCTS`** | 界面等待用户保存 |

**反证成立**：若「顶层 Activity 不变」只是因为设备锁屏导致所有 Activity 都不显示，
那么对照组也应同样不变 —— 但对照组明确跳转到了 `AlarmClockCTS`。
所以测试组的「无变化」是 `SKIP_UI` 生效所致，不是锁屏副作用。

且对照组跳转的目标 `AlarmClockCTS` 与 smali 中
`hour != -1 → startActivity(AlarmClockCTS)` 的分支**逐字吻合**（本次传入 HOUR=21）。
静态反编译与真机行为互相印证。

## 4. 附带确认的边界

| 项 | 结论 | 依据 |
|---|---|---|
| 能否读取系统时钟里的闹钟列表 | **不能** | `ClockProvider`（authority `com.coloros.alarmclock.alarmclock`）声明了 `oppo.permission.OPPO_COMPONENT_SAFE`，shell(uid 2000) 访问报 `SecurityException` |
| 能否程序化删除闹钟 | **不能** | 同上；且清单中不存在 `ACTION_DELETE_ALARM` 之类的 action |
| `EXTRA_HOUR` 语义 | 传 0–23 钟点值有效 | 传 21 得到 21:37，未受 12/24 制式影响 |
| 是否有 `Theme.NoDisplay` 保护 | **没有** | handler 的 theme 是 `Theme.Translucent` |

## 5. 对方案的影响

| 原判断 | 修正后 |
|---|---|
| R1 风险「厂商忽略 `SKIP_UI`」列为**高概率** | 本机型实测**不成立**，`SKIP_UI` 被遵守。三层降级中的 L1 在本机即主路径 |
| §2.2 厂商表 OPPO ColorOS 标「低置信度，待真机验证」 | 更新为**已实测遵守**（限本机型本版本） |
| Q1「ColorOS 是否遵守 `SKIP_UI` 仍属待验证」 | **已解决** |
| 「无法确认是否真的一键设置」 | 仍成立（`startActivity` 单向无返回）。但本机已知会成功 |

**仍未覆盖**：本结论来自单一机型 + 单一系统版本（PMB110 / ColorOS 16.0.9.400）。
其他 ColorOS 版本、其他 OPPO/vivo/小米机型**未验证**，不得外推。
