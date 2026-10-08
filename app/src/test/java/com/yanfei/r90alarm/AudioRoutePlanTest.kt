package com.yanfei.r90alarm

import com.yanfei.r90alarm.alarm.AudioRouteMode
import com.yanfei.r90alarm.alarm.AudioRoutePlan
import com.yanfei.r90alarm.alarm.RouteDecision
import com.yanfei.r90alarm.alarm.RouteDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AudioRoutePlan] 纯逻辑单元测试。
 *
 * 覆盖三档路由 × 各种设备组合的降级决策，以及设备分类的前置判定。
 * 纯 Kotlin，跑在 JVM 上，不需要模拟器。
 *
 * 这些用例守住的是用户那句不可动摇的要求：
 *   「可以在耳机内播放而不强制调用手机扬声器」。
 * 一旦有人把 `HEADSET_ONLY` 的「无耳机 = 静音」改成「无耳机 = 外放」，
 * 或让 `PREFER_HEADSET` 在插着耳机时仍然外放，这里必须红。
 */
class AudioRoutePlanTest {

    // ---------- 设备分类 ----------

    @Test
    fun `耳机类设备被正确识别`() {
        val headgear = listOf(
            RouteDevice.WIRED_HEADSET,
            RouteDevice.WIRED_HEADPHONES,
            RouteDevice.USB_HEADSET,
            RouteDevice.BLE_HEADSET,
            RouteDevice.BLUETOOTH_A2DP,
            RouteDevice.BLUETOOTH_SCO,
            RouteDevice.HEARING_AID,
        )
        headgear.forEach { assertTrue("$it 应算耳机", it.isHeadgear) }
    }

    @Test
    fun `外接音箱与底座不算耳机`() {
        // 关键：USB_DEVICE / BLE_SPEAKER 走的是「外放」语义，不能被当成耳机
        val notHeadgear = listOf(
            RouteDevice.USB_DEVICE,
            RouteDevice.USB_ACCESSORY,
            RouteDevice.BLE_SPEAKER,
            RouteDevice.BUILTIN_SPEAKER,
            RouteDevice.BUILTIN_EARPIECE,
            RouteDevice.TELEPHONY,
            RouteDevice.UNKNOWN,
        )
        notHeadgear.forEach { assertFalse("$it 不应算耳机", it.isHeadgear) }
    }

    @Test
    fun `只有内置扬声器被判定为外放`() {
        assertTrue(RouteDevice.BUILTIN_SPEAKER.isBuiltinSpeaker)
        RouteDevice.values()
            .filter { it != RouteDevice.BUILTIN_SPEAKER }
            .forEach { assertFalse("$it 不应算内置扬声器", it.isBuiltinSpeaker) }
    }

    // ---------- PREFER_HEADSET（默认档） ----------

    @Test
    fun `默认档 插着耳机时首选耳机`() {
        val decision = AudioRoutePlan.decide(
            AudioRouteMode.PREFER_HEADSET,
            listOf(RouteDevice.BUILTIN_SPEAKER, RouteDevice.BLUETOOTH_A2DP),
        )
        assertTrue(decision is RouteDecision.Play)
        decision as RouteDecision.Play
        assertEquals("首选必须是耳机", RouteDevice.BLUETOOTH_A2DP, decision.targets.first())
        assertTrue("默认档允许回落外放", decision.speakerFallback)
    }

    @Test
    fun `默认档 无耳机时回落扬声器`() {
        val decision = AudioRoutePlan.decide(
            AudioRouteMode.PREFER_HEADSET,
            listOf(RouteDevice.BUILTIN_SPEAKER),
        )
        decision as RouteDecision.Play
        assertEquals(listOf(RouteDevice.BUILTIN_SPEAKER), decision.targets)
        assertFalse("无耳机时不应静音", AudioRoutePlan.shouldMute(decision))
    }

    @Test
    fun `默认档 有线优先于蓝牙`() {
        val decision = AudioRoutePlan.decide(
            AudioRouteMode.PREFER_HEADSET,
            listOf(
                RouteDevice.BLUETOOTH_A2DP,
                RouteDevice.WIRED_HEADSET,
                RouteDevice.BLE_HEADSET,
            ),
        ) as RouteDecision.Play
        assertEquals(
            "有线耳机应排在最前",
            listOf(
                RouteDevice.WIRED_HEADSET,
                RouteDevice.BLE_HEADSET,
                RouteDevice.BLUETOOTH_A2DP,
            ),
            decision.targets,
        )
    }

    @Test
    fun `默认档 候选链是 耳机然后扬声器`() {
        val decision = AudioRoutePlan.decide(
            AudioRouteMode.PREFER_HEADSET,
            listOf(RouteDevice.BUILTIN_SPEAKER, RouteDevice.USB_HEADSET),
        ) as RouteDecision.Play
        assertEquals(listOf(RouteDevice.USB_HEADSET, RouteDevice.BUILTIN_SPEAKER), decision.targets)
    }

    @Test
    fun `默认档 不把外接音箱当耳机优先`() {
        // 只有 USB 音箱、没有耳机时，默认档应回落扬声器，而不是走 USB 音箱
        val decision = AudioRoutePlan.decide(
            AudioRouteMode.PREFER_HEADSET,
            listOf(RouteDevice.USB_DEVICE, RouteDevice.BUILTIN_SPEAKER),
        ) as RouteDecision.Play
        assertEquals(listOf(RouteDevice.BUILTIN_SPEAKER), decision.targets)
    }

    // ---------- HEADSET_ONLY（只走耳机） ----------

    @Test
    fun `只走耳机档 有耳机时只给耳机且禁止外放`() {
        val decision = AudioRoutePlan.decide(
            AudioRouteMode.HEADSET_ONLY,
            listOf(RouteDevice.BUILTIN_SPEAKER, RouteDevice.WIRED_HEADSET),
        ) as RouteDecision.Play
        assertEquals(listOf(RouteDevice.WIRED_HEADSET), decision.targets)
        assertFalse("只走耳机档不得回落外放", decision.speakerFallback)
    }

    @Test
    fun `只走耳机档 无耳机时静音 绝不外放`() {
        val decision = AudioRoutePlan.decide(
            AudioRouteMode.HEADSET_ONLY,
            listOf(RouteDevice.BUILTIN_SPEAKER),
        )
        assertSame(RouteDecision.Silent, decision)
        assertTrue(AudioRoutePlan.shouldMute(decision))
    }

    @Test
    fun `只走耳机档 空设备列表也静音`() {
        val decision = AudioRoutePlan.decide(AudioRouteMode.HEADSET_ONLY, emptyList())
        assertSame(RouteDecision.Silent, decision)
    }

    // ---------- SPEAKER（强制外放） ----------

    @Test
    fun `强制外放档 即使插着耳机也走扬声器`() {
        val decision = AudioRoutePlan.decide(
            AudioRouteMode.SPEAKER,
            listOf(RouteDevice.WIRED_HEADSET, RouteDevice.BUILTIN_SPEAKER),
        ) as RouteDecision.Play
        assertEquals(listOf(RouteDevice.BUILTIN_SPEAKER), decision.targets)
    }

    @Test
    fun `强制外放档 枚举不到扬声器时仍尝试默认扬声器常量`() {
        val decision = AudioRoutePlan.decide(
            AudioRouteMode.SPEAKER,
            listOf(RouteDevice.WIRED_HEADSET),
        ) as RouteDecision.Play
        assertEquals(listOf(RouteDevice.BUILTIN_SPEAKER), decision.targets)
    }

    // ---------- 空列表兜底 ----------

    @Test
    fun `默认档 空设备列表不静音 交给系统默认`() {
        val decision = AudioRoutePlan.decide(
            AudioRouteMode.PREFER_HEADSET,
            emptyList(),
        ) as RouteDecision.Play
        assertTrue("拿不到设备时不指定首选，交给系统", decision.targets.isEmpty())
        assertFalse(AudioRoutePlan.shouldMute(decision))
    }

    // ---------- 去重 ----------

    @Test
    fun `同类设备多个实例被去重`() {
        val decision = AudioRoutePlan.decide(
            AudioRouteMode.PREFER_HEADSET,
            listOf(
                RouteDevice.BLUETOOTH_A2DP,
                RouteDevice.BLUETOOTH_A2DP,
                RouteDevice.BLUETOOTH_A2DP,
            ),
        ) as RouteDecision.Play
        assertEquals(listOf(RouteDevice.BLUETOOTH_A2DP), decision.targets)
    }

    // ---------- 档位持久化往返 ----------

    @Test
    fun `档位名往返解析正确`() {
        AudioRouteMode.values().forEach { mode ->
            assertEquals(mode, AudioRouteMode.fromStorage(mode.name))
        }
    }

    @Test
    fun `未知或空档位名回落默认档`() {
        assertEquals(AudioRouteMode.DEFAULT, AudioRouteMode.fromStorage(null))
        assertEquals(AudioRouteMode.DEFAULT, AudioRouteMode.fromStorage(""))
        assertEquals(AudioRouteMode.DEFAULT, AudioRouteMode.fromStorage("GARBAGE"))
    }

    @Test
    fun `默认档就是耳机优先`() {
        assertEquals(AudioRouteMode.PREFER_HEADSET, AudioRouteMode.DEFAULT)
    }
}
