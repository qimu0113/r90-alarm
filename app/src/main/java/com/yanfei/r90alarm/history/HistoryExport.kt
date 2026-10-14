package com.yanfei.r90alarm.history

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 把使用历史导成 **Markdown**。**纯逻辑**，不依赖 android.* / org.json，可 JVM 单测。
 *
 * 为什么是 Markdown：
 *   纯文本、零依赖、任何编辑器 / 笔记软件都能开；
 *   竖线表格在 Obsidian / Typora / GitHub 里直接渲染成人看的表格；
 *   同时它仍是「人能读的纯文本」，万一对方不支持渲染也不影响阅读。
 *
 * 时区由调用方传入（[zone]），而不是内部取 [ZoneId.systemDefault] ——
 * 这样单测可以钉死时区，断言稳定不随构建机时区漂移。
 */
object HistoryExport {

    /** 导出时间戳格式。与诊断日志保持同一种「一眼可读」的形态。 */
    private val STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    /**
     * 生成整份 Markdown。
     *
     * @param records       按时间**升序**排好的记录（调用方负责排序）。
     * @param exportedAtMillis 导出时刻（epoch 毫秒）。
     * @param zone          时间展示时区。
     * @param appVersion    App 版本号，形如 `0.2`。
     */
    fun toMarkdown(
        records: List<UsageRecord>,
        exportedAtMillis: Long,
        zone: ZoneId,
        appVersion: String,
    ): String = buildString {
        append("# R90 闹钟 · 使用历史\n\n")
        append("> 导出时间：").append(formatStamp(exportedAtMillis, zone)).append("  \n")
        append("> App 版本：").append(appVersion).append("  \n")
        append("> 记录条数：").append(records.size).append("\n\n")

        if (records.isEmpty()) {
            append("（暂无记录）\n")
            return@buildString
        }

        // 列名「计划周期」而非「周期」：这里存的是**用户设定时选的 R90 周期档位**，
        // 不是实际睡满的周期数（那需要按入睡时刻反推，本表并不记录）。
        // 用「计划」二字把语义钉死，避免被读成推算出的实际周期。
        append("| 时间 | 目标 | 计划周期 | 准备入睡 | 提醒方式 | 结果 |\n")
        append("| --- | --- | --- | --- | --- | --- |\n")
        for (r in records) {
            append("| ").append(formatStamp(r.epochMillis, zone))
                .append(" | ").append(escape(r.targetText))
                .append(" | ").append(r.cycles)
                .append(" | ").append(r.fallAsleepMin).append(" 分")
                .append(" | ").append(escape(r.mode.display))
                .append(" | ").append(escape(r.resultText))
                .append(" |\n")
        }
    }

    private fun formatStamp(epochMillis: Long, zone: ZoneId): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone).format(STAMP)

    /**
     * Markdown 表格里 `|` 是列分隔符，原样输出会把一列拆成两列。
     * 转义成 `\|`。当前字段都是固定中文/时间，属于防御性处理。
     */
    private fun escape(s: String): String = s.replace("|", "\\|")
}
