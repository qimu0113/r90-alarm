package com.yanfei.r90alarm

import com.yanfei.r90alarm.history.HistoryExport
import com.yanfei.r90alarm.history.UsageRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Markdown 导出单元测试。
 *
 * 时区钉死为 Asia/Shanghai，时间戳用构造出来的 epoch —— 断言不随构建机时区漂移。
 */
class HistoryExportTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun millisAt(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun record(
        epoch: Long = millisAt(2026, 10, 9, 0, 10),
        hour: Int = 7,
        minute: Int = 30,
        cycles: Int = 5,
        fall: Int = 15,
        mode: UsageRecord.Mode = UsageRecord.Mode.IN_APP,
        success: Boolean = true,
    ) = UsageRecord(
        epochMillis = epoch,
        targetHour = hour,
        targetMinute = minute,
        cycles = cycles,
        fallAsleepMin = fall,
        mode = mode,
        success = success,
    )

    @Test
    fun `空列表导出 标记暂无记录`() {
        val md = HistoryExport.toMarkdown(emptyList(), millisAt(2026, 10, 9, 0, 14), zone, "0.2")
        assertTrue(md, md.contains("（暂无记录）"))
        assertTrue(md, md.contains("# R90 闹钟 · 使用历史"))
        assertTrue(md, md.contains("记录条数：0"))
    }

    @Test
    fun `头部含导出时间与版本`() {
        val md = HistoryExport.toMarkdown(emptyList(), millisAt(2026, 10, 9, 0, 14), zone, "0.2")
        assertTrue(md, md.contains("导出时间：2026-10-09 00:14:00"))
        assertTrue(md, md.contains("App 版本：0.2"))
    }

    @Test
    fun `非空列表含表头与分隔行`() {
        val md = HistoryExport.toMarkdown(listOf(record()), millisAt(2026, 10, 9, 0, 14), zone, "0.2")
        assertTrue(md, md.contains("| 时间 | 目标 | 计划周期 | 准备入睡 | 提醒方式 | 结果 |"))
        assertTrue(md, md.contains("| --- | --- | --- | --- | --- | --- |"))
        assertTrue(md, md.contains("记录条数：1"))
    }

    @Test
    fun `一行数据字段齐全`() {
        val md = HistoryExport.toMarkdown(listOf(record()), millisAt(2026, 10, 9, 0, 14), zone, "0.2")
        // 时间按钉死的时区、24 小时制展示
        assertTrue(md, md.contains("2026-10-09 00:10:00"))
        assertTrue(md, md.contains("07:30"))
        // 周期列取单元格值「| 5 |」，而不是含糊的 contains("5") ——
        // 后者在「15 分」里也能命中，恒真、区分不出列值
        assertTrue(md, md.contains("| 5 |"))      // 计划周期
        assertTrue(md, md.contains("15 分"))      // 准备入睡
        assertTrue(md, md.contains("本机闹钟"))    // 提醒方式
        assertTrue(md, md.contains("成功"))        // 结果
    }

    @Test
    fun `失败记录标注失败 系统时钟方式正确`() {
        val md = HistoryExport.toMarkdown(
            listOf(record(mode = UsageRecord.Mode.SYSTEM_CLOCK, success = false)),
            millisAt(2026, 10, 9, 0, 14), zone, "0.2",
        )
        assertTrue(md, md.contains("系统时钟"))
        assertTrue(md, md.contains("失败"))
    }

    @Test
    fun `多条记录逐行输出`() {
        val md = HistoryExport.toMarkdown(
            listOf(
                record(epoch = millisAt(2026, 10, 9, 0, 10), cycles = 5),
                record(epoch = millisAt(2026, 10, 9, 1, 20), cycles = 6, mode = UsageRecord.Mode.SYSTEM_CLOCK),
            ),
            millisAt(2026, 10, 9, 2, 0), zone, "0.2",
        )
        assertTrue(md, md.contains("记录条数：2"))
        // 两条数据行都以表格行开头
        val dataRows = md.lines().count { it.startsWith("| 2026-") }
        assertEquals(2, dataRows)
    }

    @Test
    fun `相同输入输出稳定`() {
        val args = listOf(
            record(epoch = millisAt(2026, 10, 9, 0, 10)),
            record(epoch = millisAt(2026, 10, 9, 1, 20), mode = UsageRecord.Mode.SYSTEM_CLOCK, success = false),
        )
        val at = millisAt(2026, 10, 9, 2, 0)
        assertEquals(
            HistoryExport.toMarkdown(args, at, zone, "0.2"),
            HistoryExport.toMarkdown(args, at, zone, "0.2"),
        )
    }
}
