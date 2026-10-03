package com.yanfei.r90alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断日志格式化与「界面是否弹出」判定的单元测试。
 *
 * 为什么值得测：这段逻辑决定了我拿到日志后能不能正确区分
 * 「厂商遵守了 SKIP_UI」和「厂商弹了界面」—— 判错方向会得出完全相反的兼容性结论。
 */
class DiagFormatTest {

    private fun attempt(
        pauseCount: Int = 0,
        firstPauseDelayMs: Long = -1L,
        pausedMs: Long = 0L,
        stillPaused: Boolean = false,
        handler: String? = "com.coloros.alarmclock/com.oplus.alarmclock.cts.HandleApiActivity",
        error: String? = null,
        level: Int = 1,
        outcome: String = "SilentRequested",
        elapsedMs: Long = 23L,
    ) = AlarmAttempt(
        target = "07:30",
        cycles = 4,
        fallAsleepMin = 15,
        level = level,
        outcome = outcome,
        handler = handler,
        error = error,
        elapsedMs = elapsedMs,
        pauseCount = pauseCount,
        firstPauseDelayMs = firstPauseDelayMs,
        pausedMs = pausedMs,
        stillPaused = stillPaused,
    )

    // ---------- 判定：无界面 ----------

    @Test
    fun `从未暂停 判为无界面`() {
        val verdict = judgeUiAppeared(attempt(pauseCount = 0))
        assertTrue(verdict, verdict.startsWith("无界面"))
    }

    @Test
    fun `瞬时暂停 判为无界面`() {
        // 静默路径下 ColorOS 的 handler 是 translucent 且立即 finish，
        // 实测约 200-400ms。这种不能算「弹了界面」。
        val verdict = judgeUiAppeared(
            attempt(pauseCount = 1, firstPauseDelayMs = 180L, pausedMs = 230L, stillPaused = false)
        )
        assertTrue(verdict, verdict.startsWith("无界面"))
    }

    @Test
    fun `刚好低于阈值 仍判为无界面`() {
        val verdict = judgeUiAppeared(
            attempt(pauseCount = 1, pausedMs = UI_PAUSE_THRESHOLD_MS - 1, stillPaused = false)
        )
        assertTrue(verdict, verdict.startsWith("无界面"))
    }

    // ---------- 判定：有界面 ----------

    @Test
    fun `暂停达到阈值 判为有界面`() {
        val verdict = judgeUiAppeared(
            attempt(pauseCount = 1, pausedMs = UI_PAUSE_THRESHOLD_MS, stillPaused = false)
        )
        assertTrue(verdict, verdict.startsWith("有界面"))
    }

    @Test
    fun `记录时仍被暂停 判为有界面`() {
        // 用户还没从时钟页回来 —— 这一定是有界面
        val verdict = judgeUiAppeared(
            attempt(pauseCount = 1, firstPauseDelayMs = 150L, pausedMs = 1500L, stillPaused = true)
        )
        assertTrue(verdict, verdict.startsWith("有界面"))
    }

    @Test
    fun `仍被暂停优先于时长短 不因阈值未到而误判成无界面`() {
        // 极端情况：1.5s 采样点刚好落在暂停早期（例如 device 卡了一下）
        val verdict = judgeUiAppeared(
            attempt(pauseCount = 1, pausedMs = 10L, stillPaused = true)
        )
        assertTrue(verdict, verdict.startsWith("有界面"))
    }

    // ---------- 格式化 ----------

    @Test
    fun `格式化结果必须是单行`() {
        val line = formatAlarmAttempt(attempt())
        assertFalse("日志一条记录不能含换行，否则导出后对不齐", line.contains("\n"))
        assertFalse(line.contains("\r"))
    }

    @Test
    fun `格式化包含全部关键字段`() {
        val line = formatAlarmAttempt(
            attempt(
                pauseCount = 1, firstPauseDelayMs = 155L, pausedMs = 1520L,
                stillPaused = false, elapsedMs = 42L,
            )
        )
        listOf(
            "目标=07:30", "周期=4", "入睡=15m",
            "level=1", "SilentRequested",
            "handler=com.coloros.alarmclock", "elapsed=42ms", "err=-",
            "ui=pause1", "first=155ms", "total=1520ms", "still=false",
            "判定=有界面",
        ).forEach { assertTrue("缺少字段：$it\n实际：$line", line.contains(it)) }
    }

    @Test
    fun `handler 为空时用短横线占位`() {
        val line = formatAlarmAttempt(attempt(handler = null, level = 3, outcome = "NoHandler"))
        assertTrue(line, line.contains("handler=-"))
    }

    @Test
    fun `从未暂停时首次暂停延迟显示为短横线`() {
        val line = formatAlarmAttempt(attempt(pauseCount = 0, firstPauseDelayMs = -1L))
        assertTrue(line, line.contains("first=-"))
    }

    @Test
    fun `异常类名会写进日志`() {
        val line = formatAlarmAttempt(
            attempt(level = 3, outcome = "NoHandler", error = "SecurityException")
        )
        assertTrue(line, line.contains("err=SecurityException"))
    }

    @Test
    fun `阈值常量没有被改动成不合理值`() {
        // 阈值太低会把静默路径的瞬时暂停误判成"弹了界面"；
        // 太高会把真的弹了界面误判成"静默"。1 秒上下是经过实测的选择。
        assertEquals(1200L, UI_PAUSE_THRESHOLD_MS)
    }
}
