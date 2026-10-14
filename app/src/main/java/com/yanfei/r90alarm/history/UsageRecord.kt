package com.yanfei.r90alarm.history

import java.util.Locale

/**
 * 一条「设闹钟」使用记录。**纯逻辑数据模型**。
 *
 * 刻意不 import `android.*`、也不 import `org.json` —— 这样它能在 JVM 上
 * 直接构造与断言（unit test 不需要设备），而读写 JSONL 的 I/O 留在 [UsageHistory]。
 *
 * 字段只保留「用户回看时想知道的东西」：
 *   - 是什么时候设的（[epochMillis]）
 *   - 设的是几点起床（[targetHour] / [targetMinute]）
 *   - 按几个周期算的（[cycles]）、准备入睡多久（[fallAsleepMin]）
 *   - 走的哪条路（[mode]）、成不成（[success]）
 *
 * 与诊断日志（[com.yanfei.r90alarm.DiagLog]）的分工：
 * 诊断日志是给作者排兼容性用的原始事实；使用历史是给用户自己看的行为流水。
 * 两者各自独立落盘，互不依赖。
 */
data class UsageRecord(
    /** 记录发生时刻（epoch 毫秒）。 */
    val epochMillis: Long,

    /** 目标起床时刻的小时，0..23。 */
    val targetHour: Int,

    /** 目标起床时刻的分钟，0..59。 */
    val targetMinute: Int,

    /** R90 周期数 1..9。 */
    val cycles: Int,

    /** 本次使用的「准备入睡」时长（分钟）。 */
    val fallAsleepMin: Int,

    /** 提醒方式。 */
    val mode: Mode,

    /** 是否成功设置。 */
    val success: Boolean,
) {

    /** 提醒方式。与首页的 Radio 档一一对应，另加一个「未知」兜底。 */
    enum class Mode(val display: String) {
        /** 交给系统时钟（v0.1 既有行为）。 */
        SYSTEM_CLOCK("系统时钟"),

        /** 本机闹钟（前台服务响铃）。 */
        IN_APP("本机闹钟"),
    }

    /**
     * 目标时刻的展示文本，形如 `07:30`。
     *
     * 固定 24 小时制 + [Locale.ROOT]：导出到 Markdown / 复制到剪贴板时
     * 不因系统语言变化而改变形态，跨设备看也一致。
     */
    val targetText: String
        get() = String.format(Locale.ROOT, "%02d:%02d", targetHour, targetMinute)

    /** 结果展示文本。 */
    val resultText: String
        get() = if (success) "成功" else "失败"
}
