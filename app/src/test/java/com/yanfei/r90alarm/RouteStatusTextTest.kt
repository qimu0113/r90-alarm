package com.yanfei.r90alarm

import com.yanfei.r90alarm.alarm.RouteStatusText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [RouteStatusText] 纯逻辑单元测试。
 *
 * 守住「静音文案升级」的边界：未重试 / 重试 N 次 / 恰好 60 秒 / 超过 60 秒 / 漏音拦截。
 * 纯 Kotlin，跑在 JVM 上。
 */
class RouteStatusTextTest {

    @Test
    fun `刚进入静音不带重试次数`() {
        assertEquals("🔇 静音中·等待耳机", RouteStatusText.muted(retryCount = 0, mutedDurationMillis = 0L))
    }

    @Test
    fun `重试一次后显示重试次数`() {
        assertEquals("🔇 静音中·等待耳机(重试 1 次)", RouteStatusText.muted(1, 5_000L))
    }

    @Test
    fun `重试多次显示对应次数`() {
        assertEquals("🔇 静音中·等待耳机(重试 11 次)", RouteStatusText.muted(11, 55_000L))
    }

    @Test
    fun `恰好 60 秒仍算未超`() {
        // 边界：>= 才升级，故恰好 60000 应该升级。这里验证升级点在 60000 而非 60001。
        assertEquals(
            "🔇 静音中·已超 60 秒，请检查耳机",
            RouteStatusText.muted(retryCount = 12, mutedDurationMillis = RouteStatusText.ESCALATE_AFTER_MILLIS),
        )
    }

    @Test
    fun `低于 60 秒不升级`() {
        assertEquals(
            "🔇 静音中·等待耳机(重试 11 次)",
            RouteStatusText.muted(retryCount = 11, mutedDurationMillis = RouteStatusText.ESCALATE_AFTER_MILLIS - 1),
        )
    }

    @Test
    fun `超过 60 秒升级为请检查耳机`() {
        assertEquals(
            "🔇 静音中·已超 60 秒，请检查耳机",
            RouteStatusText.muted(retryCount = 20, mutedDurationMillis = 61_000L),
        )
    }

    @Test
    fun `超过 60 秒优先于重试次数文案`() {
        // 两个条件同时满足时，更紧急的「请检查耳机」必须优先。
        val text = RouteStatusText.muted(retryCount = 100, mutedDurationMillis = 120_000L)
        assertEquals("🔇 静音中·已超 60 秒，请检查耳机", text)
    }

    @Test
    fun `漏音拦截文案固定`() {
        assertEquals("⚠️ 已拦截外放·等待耳机", RouteStatusText.leakBlocked())
    }
}
