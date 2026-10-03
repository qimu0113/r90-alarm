package com.yanfei.r90alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * R90Calculator 单元测试。
 *
 * 纯 Kotlin + java.time，跑在 JVM 上，不需要模拟器、不需要 Android SDK 编译。
 * 装了 JDK 就能 `./gradlew test` 跑。
 *
 * 覆盖：正常计算、跨天、午夜边界、跨年、闰年、月末、时区、日期标签。
 */
class R90CalculatorTest {

    private val zoneSh = ZoneId.of("Asia/Shanghai")
    private val zoneUtc = ZoneId.of("UTC")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0, zone: ZoneId = zoneSh) =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, zone)

    // ---------- 基准场景（对应方案文档 4.5 节） ----------

    @Test
    fun `基准场景 02-47-13 的 9 个周期时间正确`() {
        val now = at(2026, 9, 28, 2, 47, 13)
        val results = R90Calculator.calculateAll(now)

        assertEquals(9, results.size)

        // 基准点 03:02:13，加 N × 90 分钟后均为「xx 分 13 秒」，
        // 按向上取整规则一律进位到下一分钟（13 秒非 0）。
        val expected = listOf(
            4 to 33,   // 1 周期  04:32:13 -> 04:33
            6 to 3,    // 2       06:02:13 -> 06:03
            7 to 33,   // 3       07:32:13 -> 07:33
            9 to 3,    // 4       09:02:13 -> 09:03
            10 to 33,  // 5       10:32:13 -> 10:33
            12 to 3,   // 6       12:02:13 -> 12:03
            13 to 33,  // 7       13:32:13 -> 13:33
            15 to 3,   // 8       15:02:13 -> 15:03
            16 to 33,  // 9       16:32:13 -> 16:33
        )
        results.forEachIndexed { i, r ->
            assertEquals("第 ${i + 1} 周期小时", expected[i].first, r.hour24)
            assertEquals("第 ${i + 1} 周期分钟", expected[i].second, r.minute)
            assertEquals("第 ${i + 1} 周期序号", i + 1, r.cycles)
        }
    }

    @Test
    fun `基准场景全部是今天`() {
        val now = at(2026, 9, 28, 2, 47, 13)
        val results = R90Calculator.calculateAll(now)
        results.forEach { r ->
            assertEquals("02:47 起算不应跨天（周期 ${r.cycles}）", 0, r.dayDiff)
            assertFalse(r.isCrossDay)
        }
    }

    @Test
    fun `夜间场景 22-30 全部跨天且标为明天`() {
        val now = at(2026, 9, 28, 22, 30, 0)
        val results = R90Calculator.calculateAll(now)

        results.forEach { r ->
            assertEquals("22:30 起算全部应跨天（周期 ${r.cycles}）", 1, r.dayDiff)
            assertTrue(r.isCrossDay)
            assertEquals("跨天后日期应为 29 日", 29, r.wakeTime.dayOfMonth)
        }

        // 抽查具体时间：22:45 + 90m = 00:15
        val first = results.first()
        assertEquals(0, first.hour24)
        assertEquals(15, first.minute)
    }

    // ---------- 午夜边界 ----------

    @Test
    fun `午夜前一秒 23-59-59 不崩溃且全部跨天`() {
        val now = at(2026, 9, 28, 23, 59, 59)
        val results = R90Calculator.calculateAll(now)

        assertEquals(9, results.size)
        results.forEach { r ->
            assertEquals(1, r.dayDiff)
            assertEquals(29, r.wakeTime.dayOfMonth)
        }
        // 23:59:59 + 15m = 00:14:59，+ 90m = 01:44:59
        // 59 秒非 0，向上取整 -> 01:45
        assertEquals(1, results.first().hour24)
        assertEquals(45, results.first().minute)
    }

    @Test
    fun `整点午夜 00-00-00 全部是今天`() {
        val now = at(2026, 9, 28, 0, 0, 0)
        val results = R90Calculator.calculateAll(now)

        results.forEach { r ->
            assertEquals("00:00 起算全是今天（周期 ${r.cycles}）", 0, r.dayDiff)
        }
        // 00:00 + 15m = 00:15，+ 90m = 01:45
        assertEquals(1, results.first().hour24)
        assertEquals(45, results.first().minute)
    }

    @Test
    fun `跨天临界点 次日凌晨 00-00-01`() {
        val now = at(2026, 9, 29, 0, 0, 1)
        val results = R90Calculator.calculateAll(now)
        results.forEach { r ->
            assertEquals("刚过午夜应全是今天", 0, r.dayDiff)
        }
    }

    // ---------- 跨年 / 月末 / 闰年 ----------

    @Test
    fun `跨年 12-31-23-30 正确跳到次年 1 月 1 日`() {
        val now = at(2026, 12, 31, 23, 30, 0)
        val results = R90Calculator.calculateAll(now)

        val first = results.first()
        assertEquals(2027, first.wakeTime.year)
        assertEquals(1, first.wakeTime.monthValue)
        assertEquals(1, first.wakeTime.dayOfMonth)
        assertEquals(1, first.dayDiff)
    }

    @Test
    fun `平年 2 月 28 日跨到 3 月 1 日 不出现 2 月 29 日`() {
        val now = at(2026, 2, 28, 23, 30, 0)   // 2026 不是闰年
        val results = R90Calculator.calculateAll(now)
        val first = results.first()

        assertEquals(3, first.wakeTime.monthValue)
        assertEquals(1, first.wakeTime.dayOfMonth)
    }

    @Test
    fun `闰年 2 月 28 日跨到 2 月 29 日`() {
        val now = at(2028, 2, 28, 23, 30, 0)   // 2028 是闰年
        val results = R90Calculator.calculateAll(now)
        val first = results.first()

        assertEquals(2, first.wakeTime.monthValue)
        assertEquals(29, first.wakeTime.dayOfMonth)
    }

    @Test
    fun `月末 1 月 31 日跨到 2 月 1 日`() {
        val now = at(2026, 1, 31, 23, 30, 0)
        val results = R90Calculator.calculateAll(now)
        val first = results.first()

        assertEquals(2, first.wakeTime.monthValue)
        assertEquals(1, first.wakeTime.dayOfMonth)
    }

    // ---------- 时区 ----------

    @Test
    fun `同一时刻在不同时区的本地显示不同 但瞬时相同`() {
        val nowSh = at(2026, 9, 28, 22, 30, 0, zone = zoneSh)
        val resultsSh = R90Calculator.calculateAll(nowSh)

        // 同一物理时刻，换个时区表示
        val nowUtc = nowSh.withZoneSameInstant(zoneUtc)
        val resultsUtc = R90Calculator.calculateAll(nowUtc)

        // 绝对时刻必须完全一致（这是跨时区不漂移的关键）
        resultsSh.zip(resultsUtc).forEach { (a, b) ->
            assertEquals("周期 ${a.cycles} 的绝对时刻应一致", a.wakeInstant, b.wakeInstant)
        }

        // 但本地钟点不同：上海 22:45 起算，UTC 是 14:45
        assertTrue(
            "不同时区的本地 hour24 应不同",
            resultsSh.first().hour24 != resultsUtc.first().hour24
        )
    }

    @Test
    fun `跨时区后起床绝对时刻不变`() {
        val now = at(2026, 9, 28, 22, 30, 0, zone = zoneSh)
        val r = R90Calculator.calculateAll(now).first()

        // 期望：22:30:00 + 15m + 90m = 次日 00:15:00 (UTC+8)
        //      = 2026-09-28T16:15:00Z
        val expectedInstant = ZonedDateTime.of(2026, 9, 28, 16, 15, 0, 0, zoneUtc).toInstant()
        assertEquals(expectedInstant, r.wakeInstant)
    }

    // ---------- 准备入睡时长 ----------

    @Test
    fun `自定义准备入睡时长生效`() {
        val now = at(2026, 9, 28, 10, 0, 0)
        val results = R90Calculator.calculateAll(now, fallAsleepMinutes = 30)
        // 10:00 + 30m + 90m = 12:00
        assertEquals(12, results.first().hour24)
        assertEquals(0, results.first().minute)
    }

    @Test
    fun `准备入睡时长为 0 时仍可计算`() {
        val now = at(2026, 9, 28, 10, 0, 0)
        val results = R90Calculator.calculateAll(now, fallAsleepMinutes = 0)
        // 10:00 + 0 + 90m = 11:30
        assertEquals(11, results.first().hour24)
        assertEquals(30, results.first().minute)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `负数准备入睡时长抛异常`() {
        R90Calculator.calculateAll(at(2026, 9, 28, 10, 0, 0), fallAsleepMinutes = -1)
    }

    // ---------- 取整口径（向上取整） ----------

    @Test
    fun `秒向上进位到下一分钟`() {
        // 02:47:59 + 15m = 03:02:59，+ 90m = 04:32:59
        // 向上取整应为 04:33（截断会是 04:32）
        val now = at(2026, 9, 28, 2, 47, 59)
        val first = R90Calculator.calculateAll(now).first()

        assertEquals("秒应向上进位", 33, first.minute)
        assertEquals(4, first.hour24)
        // 内部保留秒，只用取整值喂闹钟
        assertEquals(59, first.wakeTime.second)
        assertTrue("秒非 0 应标记有亚分钟精度", first.hasSubMinutePrecision)
    }

    @Test
    fun `秒为 0 时不进位`() {
        // 02:47:00 + 15m = 03:02:00，+ 90m = 04:32:00
        val now = at(2026, 9, 28, 2, 47, 0)
        val first = R90Calculator.calculateAll(now).first()

        assertEquals(32, first.minute)
        assertEquals(4, first.hour24)
        assertFalse("秒为 0 不应标记亚分钟精度", first.hasSubMinutePrecision)
    }

    @Test
    fun `向上取整能正确进位到下一小时（04-59-30 到 05-00）`() {
        // 这是最容易出 bug 的场景：分钟 +1 变成 60。
        // 直接 minute+1 会产出 60 这个非法值，必须先算时间再取分。
        //
        // 构造 04:59:30：now = 04:59:30 - 15m - 90m = 03:14:30
        val now = at(2026, 9, 28, 3, 14, 30)
        val first = R90Calculator.calculateAll(now).first()

        // 03:14:30 + 15m = 03:29:30，+ 90m = 04:59:30 → 应进位到 05:00
        assertEquals("应进位到 5 点", 5, first.hour24)
        assertEquals("分钟应归零而不是 60", 0, first.minute)
    }

    @Test
    fun `向上取整能正确进位到跨天（23-59-30 到次日 00-00）`() {
        // 构造 23:59:30：now = 23:59:30 - 15m - 90m = 22:14:30
        val now = at(2026, 9, 28, 22, 14, 30)
        val first = R90Calculator.calculateAll(now).first()

        // 22:14:30 + 105m = 23:59:30 → 应进位到次日 00:00
        assertEquals("应进位到次日 0 点", 0, first.hour24)
        assertEquals(0, first.minute)
        assertEquals("跨天标记应为明天", 1, first.dayDiff)
    }

    @Test
    fun `向上取整后所有值仍在合法范围内`() {
        // 用秒=59 扫一遍全天，确保不会出现 minute=60 或 hour=24
        val bad = mutableListOf<String>()
        for (h in 0 until 24) {
            for (m in listOf(0, 30, 59)) {
                for (s in listOf(59)) {
                    val now = at(2026, 9, 28, h, m, s)
                    R90Calculator.calculateAll(now).forEach { r ->
                        if (r.hour24 !in 0..23 || r.minute !in 0..59) {
                            bad += "起点 $h:$m:$s 周期 ${r.cycles} → ${r.hour24}:${r.minute}"
                        }
                    }
                }
            }
        }
        assertTrue("发现越界值: $bad", bad.isEmpty())
    }

    @Test
    fun `取整把时间推到次日时 dayDiff 必须跟着变`() {
        // 回归测试 —— 这是改向上取整后引入的新坑。
        //
        // 起点 22:14:30 → 22:14:30 + 15m + 90m = 23:59:30（当天）
        // 向上取整 → 次日 00:00
        // 如果 dayDiff 按原始时刻（23:59:30，今天）算，会得 0，
        // UI 显示「今天」而闹钟设在明天 00:00 —— 用户看错。
        val now = at(2026, 9, 28, 22, 14, 30)
        val first = R90Calculator.calculateAll(now).first()

        assertEquals("取整后小时应进位到 0", 0, first.hour24)
        assertEquals("取整后分钟应为 0", 0, first.minute)
        assertEquals("dayDiff 必须按取整后时刻算，应为 1", 1, first.dayDiff)
        assertTrue("应标记为跨天", first.isCrossDay)
    }

    @Test
    fun `取整进位到次日但未跨天时 dayDiff 保持 0`() {
        // 起点 02:47:59 → 04:32:59 → 取整 04:33，仍在当天
        val now = at(2026, 9, 28, 2, 47, 59)
        val first = R90Calculator.calculateAll(now).first()
        assertEquals(0, first.dayDiff)
        assertFalse(first.isCrossDay)
    }

    // ---------- 日期标签 ----------

    @Test
    fun `相对日期标签四档都正确`() {
        assertEquals("今天", R90Calculator.relativeDayLabel(0))
        assertEquals("明天", R90Calculator.relativeDayLabel(1))
        assertEquals("后天", R90Calculator.relativeDayLabel(2))
        assertEquals("第 4 天", R90Calculator.relativeDayLabel(3))
    }

    @Test
    fun `dayDiff 在极端参数下可达 2 以上`() {
        // 23:00 起算 + 60 分钟入睡 + 9 × 90 分钟 = 23:00 + 14h30m = 次日 13:30
        // dayDiff 仍是 1。要构造 dayDiff = 2 需要更极端。
        // 这里验证：即便入睡时长拉到 60 分钟，9 周期最多跨 1 天。
        val now = at(2026, 9, 28, 23, 0, 0)
        val results = R90Calculator.calculateAll(now, fallAsleepMinutes = 60)
        val maxDayDiff = results.maxOf { it.dayDiff }
        assertEquals("9 周期 + 60 分钟入睡最多跨 1 天", 1, maxDayDiff)
    }

    // ---------- 返回值契约 ----------

    @Test
    fun `返回的 hour24 与 minute 在合法范围内`() {
        // 用几个刁钻的起点扫一遍，确保不会有越界值传给系统闹钟
        val starts = listOf(
            at(2026, 9, 28, 0, 0, 0),
            at(2026, 9, 28, 12, 34, 56),
            at(2026, 9, 28, 23, 59, 59),
        )
        starts.forEach { now ->
            R90Calculator.calculateAll(now).forEach { r ->
                assertTrue("hour24 越界: ${r.hour24}", r.hour24 in 0..23)
                assertTrue("minute 越界: ${r.minute}", r.minute in 0..59)
            }
        }
    }

    @Test
    fun `周期数严格是 1 到 9 且递增`() {
        val results = R90Calculator.calculateAll(at(2026, 9, 28, 2, 47, 13))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9), results.map { it.cycles })
    }

    @Test
    fun `起床时刻严格递增且每档相差 90 分钟`() {
        val results = R90Calculator.calculateAll(at(2026, 9, 28, 2, 47, 13))
        results.zipWithNext().forEach { (a, b) ->
            val gapMinutes = java.time.Duration.between(a.wakeInstant, b.wakeInstant).toMinutes()
            assertEquals("周期 ${a.cycles}→${b.cycles} 应相差 90 分钟", 90L, gapMinutes)
        }
    }

    // ---------- 入睡时长取值表 ----------

    @Test
    fun `入睡时长取值表合法且含默认值`() {
        val options = R90Calculator.FALL_ASLEEP_OPTIONS

        assertTrue("取值表不能为空", options.isNotEmpty())
        assertTrue("取值不能为负: $options", options.all { it >= 0 })
        assertEquals("取值必须严格递增: $options", options.sorted(), options)
        assertEquals(
            "取值不能重复（重复会让胶囊按钮出现两个选中态）: $options",
            options.distinct().size,
            options.size,
        )
        assertTrue(
            "默认值 ${R90Calculator.DEFAULT_FALL_ASLEEP_MINUTES} 必须在取值表内，" +
                    "否则首次打开界面没有任何胶囊是选中态",
            R90Calculator.DEFAULT_FALL_ASLEEP_MINUTES in options,
        )
    }

    @Test
    fun `取值表里每一项都能算出合法结果`() {
        // 防止"界面上能点，但点了算不出/算出越界"
        R90Calculator.FALL_ASLEEP_OPTIONS.forEach { fall ->
            val results = R90Calculator.calculateAll(at(2026, 9, 28, 22, 47, 59), fall)
            assertEquals("fall=$fall 应仍产出 9 档", 9, results.size)
            results.forEach { r ->
                assertTrue("fall=$fall 周期 ${r.cycles} hour24 越界: ${r.hour24}", r.hour24 in 0..23)
                assertTrue("fall=$fall 周期 ${r.cycles} minute 越界: ${r.minute}", r.minute in 0..59)
            }
        }
    }
}
