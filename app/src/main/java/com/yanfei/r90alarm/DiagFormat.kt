package com.yanfei.r90alarm

/**
 * 诊断日志的**纯逻辑**部分：数据模型 + 格式化。
 *
 * 单独放一个文件、且不 import 任何 android.* ——
 * 这样格式化逻辑能在 JVM 上直接跑单元测试（不需要设备），
 * 而读写了文件的 I/O 部分留在 [DiagLog]。
 */

/** 一次「设为系统闹钟」尝试的全部可观测事实。 */
data class AlarmAttempt(
    /** 目标时刻的展示文本，如 `07:30` / `7:30 AM`。 */
    val target: String,
    val cycles: Int,
    val fallAsleepMin: Int,
    /** 1 = 请求静默；2 = 打开了界面让用户保存；3 = 找不到时钟。 */
    val level: Int,
    /** 结果枚举名，如 `SilentRequested`。 */
    val outcome: String,
    /** 响应 SET_ALARM 的组件名；null 表示没有任何 App 响应。 */
    val handler: String?,
    /** startActivity 抛出的异常类名；null 表示没抛。 */
    val error: String?,
    /** startActivity 调用本身耗时（不含时钟 App 的处理时间）。 */
    val elapsedMs: Long,
    /** 本次点击后，本 App 被暂停的次数。 */
    val pauseCount: Int,
    /** 点击 → 首次暂停的间隔（ms）；-1 表示从未暂停。 */
    val firstPauseDelayMs: Long,
    /** 本 App 累计暂停时长（ms，截至记录时刻）。 */
    val pausedMs: Long,
    /** 记录那一刻是否仍处于暂停中。 */
    val stillPaused: Boolean,
)

/**
 * 用「本 App 有没有被暂停」来间接判断系统时钟到底弹没弹界面。
 *
 * 原理：任何 Activity 被拉起，调用方都会先 `onPause`。
 * - 静默路径：ColorOS 的 handler 是 translucent 且立即 finish，我们会**瞬时暂停**（实测约 200–400ms）后立刻回来。
 * - 弹界面路径：时钟页顶在前台，我们会**持续暂停**，直到用户按保存/返回。
 *
 * ⚠️ 这是**启发式**判断，不是系统给出的确定结论。
 * 所以 [AlarmAttempt] 里同时保留原始数值（暂停次数 / 首次延迟 / 累计时长 / 是否仍在暂停），
 * 判定结果只是附带的便捷字段，离线分析时以原始值为准。
 */
fun judgeUiAppeared(a: AlarmAttempt): String = when {
    a.pauseCount == 0 -> "无界面(本App全程未暂停)"
    a.stillPaused -> "有界面(记录时仍被暂停)"
    a.pausedMs >= UI_PAUSE_THRESHOLD_MS -> "有界面(暂停 ${a.pausedMs}ms)"
    else -> "无界面(瞬时暂停 ${a.pausedMs}ms)"
}

/** 大于这个时长才算「时钟界面真的挡在前面了」。 */
const val UI_PAUSE_THRESHOLD_MS = 1200L

/**
 * 序列化成一行日志。
 *
 * 格式刻意做成 `key=value` 空格分隔，方便人肉扫一眼，也方便脚本正则解析。
 * 一行一条，绝不换行 —— 换行会把「一条记录」拆成两行，导出后难对齐。
 */
fun formatAlarmAttempt(a: AlarmAttempt): String = buildString {
    append("目标=").append(a.target)
    append(" 周期=").append(a.cycles)
    append(" 入睡=").append(a.fallAsleepMin).append("m")
    append(" | level=").append(a.level).append(' ').append(a.outcome)
    append(" handler=").append(a.handler ?: "-")
    append(" elapsed=").append(a.elapsedMs).append("ms")
    append(" err=").append(a.error ?: "-")
    append(" | ui=pause").append(a.pauseCount)
    append("/first=").append(if (a.firstPauseDelayMs < 0) "-" else "${a.firstPauseDelayMs}ms")
    append("/total=").append(a.pausedMs).append("ms")
    append("/still=").append(a.stillPaused)
    append(" → 判定=").append(judgeUiAppeared(a))
}
