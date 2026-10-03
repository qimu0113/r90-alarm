# R90 闹钟

**打开就知道「现在睡，睡够 N 个 90 分钟周期该几点起」，点一下把那个时间交给系统时钟。**

[English](README.en.md) ｜ 离线 · 零运行时权限 · 无数据库 · 无账号 · 纯原生 Compose

<p align="center">
  <img src="docs/screenshots/main-dark.png" width="230" alt="主界面 · 深色">
  <img src="docs/screenshots/main-light.png" width="230" alt="主界面 · 浅色">
  <img src="docs/screenshots/log-panel.png" width="230" alt="诊断日志">
</p>

---

## 它是什么

睡眠周期以 90 分钟为一轮（R90 理论）。这个 App 把**现在这一刻**当起点，一口气算出 9 档起床时间摊在一屏里，每行一个「设为系统闹钟」按钮。

对齐的是睡眠节律，不是固定钟点。你习惯 2 点睡，它算出来的就是 2 点往后数若干个 90 分钟的点；你 3 点半才睡，它也立刻跟着变。

**响铃这件事完全外包给系统时钟 App。** 这个工程里一行 `AlarmManager`、一行 `NotificationManager` 都没有。

原因是国产 ROM 的电池优化会冻结后台进程，「`AlarmManager` 闹钟不响」是社区高频问题；而系统时钟是系统级预装应用，电池优化和自启限制基本不碰它。与其和各家的后台策略搏斗，不如把闹钟交给一个本来就有豁免权的 App。

## 它不做什么

| | |
|---|---|
| 不联网 | **连 `INTERNET` 权限都不申请** —— 系统层面就没网，不是"承诺不上传" |
| 不登录注册 | 没有任何账号体系 |
| 不建数据库 | 无 Room、无 SQLite、无 SharedPreferences 业务数据 |
| 不申请运行时权限 | 安装即用，**一次弹窗都没有** |
| 不后台常驻 | 无 Service、无前台通知、无开机自启 |

代码量：6 个 Kotlin 源文件、1310 行。

## 下载安装

### 直接用（推荐给只想用的）

1. 打开本仓库的 [Releases](https://github.com/qimu0113/r90-alarm/releases) 页面
2. 下载 `r90-alarm-v0.1.apk`
3. 手机上点开安装（首次需要允许「安装未知来源应用」）

APK 的基本信息：

| 项 | 值 |
|---|---|
| 包名 | `com.yanfei.r90alarm` |
| 版本 | 0.1 |
| minSdk / targetSdk | 26 / 35（Android 8.0 及以上） |
| 体积 | 1,146,076 字节（约 1.1 MB） |
| APK SHA-256 | 见对应 [Release](https://github.com/qimu0113/r90-alarm/releases) 说明（每次构建都会写入当时产物的哈希） |
| 权限 | 系统权限只声明 1 项：`com.android.alarm.permission.SET_ALARM`（normal 级，安装即授予、**不弹窗**）。另有一项 androidx 自动加入的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（应用自定义、无实际能力）。**没有 `INTERNET`** |
| 签名 | APK Signature Scheme **v2**（minSdk 26 已高于 v1 所需的 API 24，无需 v1） |
| 来源 | 本仓库 `main` 分支源码构建，可用 `apksigner verify` 自行比对指纹 |

> 签名证书指纹（SHA-256）：`289f0d2c7fc0101941de962d3f0253a8eeb901fca1beb318cc499bd97c07f216`
> 装完不放心，可以自己 clone 下来构建一份对比（见 [自己构建](#自己构建)）。

### 从源码

```bash
git clone https://github.com/qimu0113/r90-alarm.git
cd r90-alarm
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

只需要 JDK 17–21 + Android SDK 35。**Android Studio 不是必需的**，命令行就能编。细节见 [自己构建](#自己构建)。

## 它怎么工作

```
现在时刻
  → + 准备入睡时长（默认 15 分钟，可调）
  → + N × 90 分钟（N = 1..9）
  = 起床时间
  → 交给系统时钟（AlarmClock.ACTION_SET_ALARM）
```

取整口径：秒**向上取整**（`04:32:59` → `04:33`），也就是闹钟最多晚响 59 秒。取整后还要判「跨天没跨天」，是今天、明天还是后天，标签直接标在行上。

## 三层降级

系统时钟那个「静默设置」开关（`EXTRA_SKIP_UI`）官方定义是**被请求**跳过 UI —— 厂商有权不理会。所以这里准备了三层：

```
Level 1   SKIP_UI = true     请求静默设置，不弹界面
Level 2   SKIP_UI = false    打开系统时钟并预填，用户按一下保存
Level 3   系统里没有时钟 App  弹对话框：「找不到系统时钟」+ 三条补救路径
```

`SystemAlarmLauncher.setAlarm()` 自动跑完 L1 → L2，返回 `SilentRequested` / `UiFallback` / `NoHandler` 三选一。走到哪一级会明确告诉用户，成功了给反馈，失败了给原因。

**目标机型实测（一加 Ace 6 至尊版 / ColorOS 16）**：`SKIP_UI = true` 被遵守，无界面直接写入闹钟。对照实验用 `SKIP_UI = false` 验证了不是锁屏导致的假象。完整证据链（含 dex 反编译和命令原文）在 `docs/真机验证-ColorOS16-SKIPUI.md`。

> 除 OPPO 外，其他厂商对 `SKIP_UI` 的处理**属于推测，没有真机验证**。这正是保留 L2 的原因。

## 诊断日志

App 右上角 ⓘ 打开。

存在的意义：**系统时钟不回传任何结果**（`startActivity` 是单向的），所以 App 永远无法自证"刚才那下到底弹没弹界面"。日志就是用来把「这台设备上实际发生了什么」记下来，之后离线分析兼容性，不必一直连着电脑。

**记什么**

| 时机 | 内容 |
|---|---|
| 每次冷启动 | `APP_START` + 设备型号 / Android 版本 / 系统构建号 / App 版本 / 时区 / 是否 24 小时制 / 字体缩放 |
| 每次点「设置」 | `SET_ALARM` + 目标时刻 / 周期数 / 入睡档位 / 走了哪一级 / 时钟 handler 组件名 / 调用耗时 / 异常类名 |

**关键的间接证据：本 App 被暂停了多久。**

任何 Activity 被拉起，调用方都会先 `onPause`。于是：

- 静默路径 → 瞬时暂停（本机实测 **200ms**）后立刻回来 → 判定「无界面」
- 弹界面路径 → 持续暂停直到用户按保存 → 判定「有界面」

阈值取 1200ms。这是**启发式判断**，所以日志里同时保留原始数值（暂停次数 / 首次延迟 / 累计时长 / 是否仍在暂停），离线分析时以原始值为准。

一条真实记录：

```
[2026-10-03 01:48:44.905] SET_ALARM | 目标=3:34 AM 周期=1 入睡=15m | level=1 SilentRequested
  handler=com.coloros.alarmclock/com.oplus.alarmclock.cts.HandleApiActivity elapsed=32ms err=-
  | ui=pause1/first=57ms/total=200ms/still=false → 判定=无界面(瞬时暂停 200ms)
```

**隐私边界**：日志只写在 App 私有目录（`filesDir`，不需要任何权限），导出走系统分享面板或剪贴板 —— 发不发、发给谁完全由你决定。上限 500 行，超了自动丢最旧的。

## 兼容性

完整评估见 `docs/兼容性说明.md`，这里是速览：

| 层面 | 结论 |
|---|---|
| 系统版本 | **Android 8.0（API 26）及以上**。用到的 API 最低要求都 ≤ 26（`EXTRA_SKIP_UI` 需 API 11、`java.time` 需 API 26） |
| 权限 | 只需 `SET_ALARM`（normal 级，安装即授予，**不弹窗**） |
| Android 11+ 包可见性 | 已用 `<intent>` 声明 `<queries>`，跨厂商通用 |
| Android 12+ `exported` | 已显式声明 |
| Android 15+ 边到边 | 依赖 `Scaffold` 默认 insets，Android 16 真机实测界面完整 |
| **厂商 `SKIP_UI` 行为** | **唯一的变数**。遵守 → 无界面直接设置；不遵守 → 自动降级成「预填 + 用户保存」。本机 ColorOS 16 已实测遵守 |

## 自己构建

| 项 | 要求 |
|---|---|
| JDK | 17 – 21（AGP 8.6.1 支持区间） |
| Android SDK | API 35（compileSdk / targetSdk），build-tools 35.0.0 |
| Gradle | 8.9（wrapper 已配好，不用自己装） |
| AGP / Kotlin | 8.6.1 / 2.0.21 |

先让 Gradle 能找到 JDK，两条路任选：

```bash
# ① 设 JAVA_HOME
export JAVA_HOME=/path/to/jdk-21

# ② 或者写进用户级配置（推荐，不动仓库）
# ~/.gradle/gradle.properties
# org.gradle.java.home=/path/to/jdk-21
```

SDK 路径写在 `local.properties`（该文件不入库）：

```properties
sdk.dir=/path/to/Android/Sdk
# ⚠️ 必须用正斜杠。写成 D\:\Android\Sdk 会被 properties 当转义序列吞掉反斜杠
```

然后：

```bash
# 跑单元测试（41 个，纯 JVM，不需要设备）
./gradlew testDebugUnitTest --rerun-tasks --no-build-cache
# ⚠️ 这两个参数不能省：不加的话 Gradle 会把改代码之前的旧报告原样喂回来，
#    看起来全绿，实际新代码没跑。报告在 app/build/reports/tests/testDebugUnitTest/
```

```bash
# 出 debug APK
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# 出 release APK（开 R8 混淆 + 资源压缩）
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk（未配密钥时是 app-release-unsigned.apk）
```

### 想自己出签名版

在工程根目录建 `keystore.properties`（已在 `.gitignore` 里）：

```properties
storeFile=/path/to/your-release.jks
keyAlias=your-alias
storePassword=...
keyPassword=...
```

文件不存在时构建不报错，只是产出 unsigned APK。签名密钥用 `keytool` 自己生成：

```bash
keytool -genkeypair -v -keystore your-release.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias your-alias
```

> ⚠️ **keystore 一旦丢失，就永久失去对同一包名发布升级版的能力**（签名不匹配，只能让用户卸载重装）。生成后立刻备份到至少两个地方。

### 网络（中国大陆）

`repo1.maven.org` 实测直连 15 秒超时。`settings.gradle.kts` 首选阿里云镜像，官方源兜底。

Gradle 发行包默认走腾讯镜像，并已写死 `distributionSha256Sum` 做完整性校验（实测与官方发布值**完全一致**）：

```
d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab
```

想换回官方源，工程里已经放了一份现成的替代配置：

```bash
cp gradle/wrapper/gradle-wrapper-official.properties gradle/wrapper/gradle-wrapper.properties
```

## 已知的硬限制

| 限制 | 说明 |
|---|---|
| **`EXTRA_SKIP_UI` 官方不保证被遵守** | 定义是"被请求"跳过 UI。本机实测被遵守，但换机或系统升级后结论可能失效，所以 L2 降级路径必须保留 |
| 闹钟精度只有分钟 | 系统闹钟 API 只收 `EXTRA_HOUR` + `EXTRA_MINUTES`，秒无处安放 |
| 无法确认是否真的静默设置成功 | `startActivity` 单向，系统时钟不回传结果。App 只能靠"自己被暂停了多久"间接判断 |
| **无法读取或删除系统时钟里的闹钟** | 以 ColorOS 为例，`ClockProvider` 要求系统级权限 `oppo.permission.OPPO_COMPONENT_SAFE`，App 和 adb shell 都拿不到；也没有 `ACTION_DELETE_ALARM` 这类接口 |
| 取整口径 | 秒向上取整（`04:32:59` → `04:33`），闹钟晚响 ≤ 59 秒 |

### 目标机型实测结论（2026-10-03）

| 项 | 结果 |
|---|---|
| 机型 / 系统 | PMB110（一加 Ace 6 至尊版）/ ColorOS 16.0.9.400 / Android 16（SDK 36） |
| 时钟 App | `com.coloros.alarmclock`（不是 AOSP DeskClock） |
| `ACTION_SET_ALARM` 处理器 | 全系统仅 `com.oplus.alarmclock.cts.HandleApiActivity` |
| **`EXTRA_SKIP_UI = true`** | ✅ **被遵守** —— 无界面弹出，闹钟直接写入 |
| `EXTRA_SKIP_UI = false`（对照） | 界面如期弹出，证明上一条不是锁屏导致的假象 |
| 时钟 handler 的 theme | `Theme.Translucent`（**不是** `NoDisplay`），所以"主题注定不弹界面"的推断不成立 |

## 验证边界声明

### 已验证（在本机真实跑出来的）

| 项 | 方法 | 结果 |
|---|---|---|
| 工程可编译 | `./gradlew assembleDebug` | ✅ `BUILD SUCCESSFUL` |
| **41 个单元测试**（29 计算 + 12 日志/判定） | `--rerun-tasks --no-build-cache` 强制真跑 | ✅ `tests="41" failures="0" errors="0"` |
| 核心算式（真 JVM） | `_verify/CeilVerify.java` 在 JDK 21 上运行 | ✅ 16/16 断言通过 |
| 全量边界扫描 | Java 版扫 432 起点 × 9 周期 = 3888 结果 | ✅ 越界 0、取整方向异常 0、dayDiff 不一致 0 |
| `SKIP_UI` 真机行为 | 真机 `am start` + `dumpsys alarm` 对照实验 | ✅ 遵守：无界面 + 闹钟正确写入 |
| 无网络权限 | `aapt2 dump permissions` 直接读 APK | ✅ **确认无 `INTERNET`** |
| 安装到真机 | `adb install -r` | ✅ `Success`（注意：设备需已解锁，锁屏时会挂起） |
| App 界面渲染 | 真机截屏 + `uiautomator` 取真实节点树 | ✅ 9 行齐全，大字时间逐秒跳动，日期正确 |
| 取整逻辑（真机） | 逐行比对节点文本与算式 | ✅ 9/9 行与「向上取整」完全一致 |
| 大字体不裁切 | `font_scale` 1.3 / 1.5 | ✅ 两档均无截断、无重叠 |
| 深色 / 浅色模式 | `cmd uimode night no` 切换 | ✅ 两种模式配色均正常 |
| **端到端：按钮 → 系统闹钟** | 点「设置」+ `logcat` + `dumpsys alarm` + 时钟列表 | ✅ 无界面、230ms 静默完成、闹钟落库且系统时钟列表可见 |
| 诊断日志落盘 | 真机点一次，`run-as` 读 `files/diagnostic.log` | ✅ 两类记录均正确写入 |
| 日志面板渲染 | 真机点 ⓘ + `uiautomator dump` | ✅ 标题/说明/日志正文/清空/导出/关闭 六项齐全 |
| 运行时权限 | `dumpsys package` + `dumpsys netstats` | ✅ 仅 2 项权限、无运行时弹窗、**零网络记录** |
| release 构建 / R8 混淆 | `./gradlew assembleRelease` | ✅ `BUILD SUCCESSFUL`，R8 minify + 资源压缩 + lint-vital 全通过 |
| APK 体积 | debug vs release（各自构建期实测） | 9,497,612 B → **1,146,076 B**（签名版，R8 缩掉约 88%） |
| 签名有效性 | `apksigner verify` | ✅ v2 方案通过，`CN=r90-alarm` |
| Android API 断言 | 逐条核对官方文档 | ✅ 见 `docs/方案.md` 附录 A |
| 独立复核 | 由另一名审阅者独立重跑构建 + 独立重算 | ✅ 揪出 4 处 P0 + 6 处 P1，含 1 处编造的 API 事实、1 处代码位置错误、1 处**测试缓存假阳性**（作者此前判断错） |

> 界面与链路的完整证据见 `docs/真机验证-App运行.md`；`SKIP_UI` 的证据见 `docs/真机验证-ColorOS16-SKIPUI.md`。

### 未验证（本机做不到，需要你来做）

- ❌ **40 项测试计划未逐条执行**。已覆盖：界面渲染、取整、大字体、深浅色、系统闹钟端到端链路、权限六类。
  未覆盖：时区切换、跨年闰年、免打扰/静音、重启后闹钟是否留存、省电模式 + 锁屏、
  **跨天「明天/后天」标签**（测试时段在凌晨，9 档全落在同一天）、Level 3 降级路径（触发需临时禁用系统时钟，属侵入操作）。
- ❌ **秒级心跳的功耗没测过**。只在代码层做了 `resume` 时启动、`pause` 时停止心跳，没实测耗电。
- ❌ **R8 的运行时行为只验证了"构建不报错"**，有没有被误删的类，仍需真机多跑几轮才算数。
- ❌ **只在一台设备（ColorOS 16）上验证过**。其他厂商的 `SKIP_UI` 行为全是推测。

> **「编译通过 + 测试全绿」不等于「App 正确」。**
> 这一版已经真机跑通、看过界面、点过按钮、清理过测试残留。
> 但上表那些没覆盖的边界就是**没验证的部分**，不能按「应该没问题」处理。

### 构建期其余观察（非阻断）

- Kotlin 编译守护进程启动时偶发 `The daemon has terminated unexpectedly on startup attempt #1`，
  自动重试后成功。属噪声，不影响产物，只是首次构建会慢一些。
- `stripDebugDebugSymbols` 提示 `Unable to strip ... libandroidx.graphics.path.so`（无对应 strip 工具），
  按原样打包。对功能无影响。

## 目录结构

```
r90-alarm/
├── app/src/main/
│   ├── AndroidManifest.xml              权限 + 包可见性 queries
│   └── java/com/yanfei/r90alarm/
│       ├── R90Calculator.kt             计算核心（纯逻辑，可单测）
│       ├── SystemAlarmLauncher.kt       系统闹钟调用 + 三层降级
│       ├── DiagLog.kt                   诊断日志写盘 + 环境快照 + 暂停观测器
│       ├── DiagFormat.kt                日志格式化与「界面是否弹出」判定（纯逻辑，可单测）
│       ├── MainActivity.kt              Compose 界面
│       └── R90Theme.kt                  主题（含暗色）
├── app/src/test/java/com/yanfei/r90alarm/
│   ├── R90CalculatorTest.kt             29 个计算单元测试
│   └── DiagFormatTest.kt                12 个日志/判定单元测试
├── docs/
│   ├── 方案.md                          完整技术方案（技术选型 / API 事实核对 / 测试计划）
│   ├── 兼容性说明.md                    兼容性评估（API 等级 / 厂商行为 / 系统行为变更）
│   ├── 真机验证-ColorOS16-SKIPUI.md     系统能力实测（含 dex 反编译）
│   ├── 真机验证-App运行.md              App 本体真机实测
│   ├── 开源清单.md                      开源过程清单（历史记录）
│   └── screenshots/                     真机截图
├── _verify/CeilVerify.java              真 JVM 交叉验证程序（Java 镜像实现，16 项断言）
├── .gitignore / .gitattributes
└── LICENSE                              MIT
```

> 文档目前只有中文版。

## 构建期踩过的坑

都是"只有真跑构建才会暴露"的类型，静态读代码看不出来。换个机器会再遇到：

| # | 坑 | 症状 | 修法 |
|---|---|---|---|
| 1 | `build.gradle.kts` 用了 `#` 注释 | Kotlin DSL 只认 `//`，脚本编译期报 `Unresolved reference` | 全改 `//` |
| 2 | 缺 Compose 编译器插件 | Kotlin 2.0 起它是独立插件，报 `Compose Compiler Gradle plugin is required` | 声明 `org.jetbrains.kotlin.plugin.compose`，版本与 Kotlin 严格一致 |
| 3 | `local.properties` 路径用了反斜杠 | `D\:\Android\Sdk` 被解析成 `D:AndroidSdk`，报错却指向 `compileDebugJavaWithJavac`（极易误判） | 用正斜杠 |
| 4 | 工程路径含中文 | **中文路径不影响 `assembleDebug`，但会让 `testDebugUnitTest` 直接失败**（Windows 测试 worker 传不了非 ASCII classpath → `ClassNotFoundException`） | 工程放纯 ASCII 目录 |
| 5 | 测试结果假阳性 | `testDebugUnitTest` 报 `BUILD SUCCESSFUL`，但那批测试是**改代码之前**的旧报告 | 加 `--rerun-tasks --no-build-cache`，并比对报告时间戳与源码修改时间 |

第 4 条一开始被我判断错了。早期结论是"中文路径完全正常，不用搬家"—— 那是对 `assembleDebug` 成立、对 `testDebugUnitTest` 不成立，是缓存假阳性掩盖了真相。加参数强制真跑后才暴露。

## 许可

[MIT](LICENSE)。

第三方依赖只有 androidx / Jetpack Compose 系列，全部为 Apache-2.0，无 GPL 传染风险。

本工程不含任何用户数据采集、统计 SDK 或广告 SDK。
