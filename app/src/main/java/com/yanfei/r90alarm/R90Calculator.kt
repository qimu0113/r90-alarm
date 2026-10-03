package com.yanfei.r90alarm

import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * R90 睡眠周期计算核心。
 *
 * 三条设计原则（不要为了"优化"而破坏它们）：
 *
 * 1. 每次计算都基于「当前这一刻」，不缓存时间基准。
 *    缓存 System.currentTimeMillis() 做基准的话，时区一变全部算错。
 *
 * 2. 起床时刻同时给出 ZonedDateTime（用于本地显示）
 *    和 Instant（用于跨时区场景），两者都算，不省。
 *
 * 3. 日期差用 ChronoUnit.DAYS.between，绝不手写「日期 + 1」。
 *    手写会在月末、年末、闰年上翻车。
 */
object R90Calculator {

    /** 单个睡眠周期的分钟数。R90 = 90 分钟。 */
    const val CYCLE_MINUTES = 90

    /** 准备入睡时长，默认值（分钟）。 */
    const val DEFAULT_FALL_ASLEEP_MINUTES = 15

    /**
     * 「准备入睡」的可选值（分钟），0 表示躺下即睡。
     *
     * 放在这里而不是 UI 层：这是**领域约束**（哪些时长对 R90 有意义），
     * 不是展示细节。UI 只负责渲染这个列表，不再自己判断取值范围。
     */
    val FALL_ASLEEP_OPTIONS = listOf(0, 5, 10, 15, 20, 25, 30)

    /** 支持的周期数：1 到 9。 */
    val CYCLES = 1..9

    /**
     * 一个周期档位的计算结果。
     *
     * @param cycles       周期数 1..9
     * @param wakeTime     起床时刻（带本地时区，含秒，用于显示「精确值」）
     * @param wakeInstant  起床时刻的绝对时间点（时区无关）
     * @param hour24       向上取整后的小时 0..23，直接喂给系统闹钟
     * @param minute       向上取整后的分钟 0..59，直接喂给系统闹钟
     * @param dayDiff      与今天相差的天数：0=今天 1=明天 2=后天 ...
     */
    data class Result(
        val cycles: Int,
        val wakeTime: ZonedDateTime,
        val wakeInstant: Instant,
        val hour24: Int,
        val minute: Int,
        val dayDiff: Int,
    ) {
        val isCrossDay: Boolean get() = dayDiff > 0

        /** 秒数是否被舍掉了（用于 UI 提示「实际是 04:32:59」） */
        val hasSubMinutePrecision: Boolean get() = wakeTime.second != 0
    }

    /**
     * 算出 1..9 全部周期的起床时间。
     *
     * @param now 当前时刻。由调用方传入而非内部取，
     *            这样单元测试可以注入固定时间，不受真实时钟影响。
     * @param fallAsleepMinutes 准备入睡时长。可调，默认 15 分钟。
     *                          UI 侧取值范围见 [FALL_ASLEEP_OPTIONS]（0..30 分钟）。
     */
    fun calculateAll(
        now: ZonedDateTime,
        fallAsleepMinutes: Int = DEFAULT_FALL_ASLEEP_MINUTES,
    ): List<Result> {
        require(fallAsleepMinutes >= 0) { "fallAsleepMinutes 不能为负: $fallAsleepMinutes" }

        val today = now.toLocalDate()

        return CYCLES.map { cycles ->
            val wake = now
                .plusMinutes(fallAsleepMinutes.toLong())
                .plusMinutes(CYCLE_MINUTES.toLong() * cycles)

            // ── 向上取整到分钟 ─────────────────────────────
            // 秒不为 0 就把分钟 +1。
            // ⚠️ 陷阱 1：04:59:30 向上取整是 05:00，不是 04:60。
            //    直接 minute+1 会产出 60 这种非法值传给系统闹钟。
            //    用 plusMinutes 做加法，进位由 java.time 处理。
            val rounded = if (wake.second == 0) {
                wake
            } else {
                wake.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
            }

            // ⚠️ 陷阱 2：取整可能把时间推到另一天。
            //    例：原始 23:59:30（今天）→ 取整 次日 00:00（明天）。
            //    如果 dayDiff 按原始时刻算，UI 会显示「今天」，
            //    但闹钟实际设在明天 00:00 —— 用户看错，半夜被坑。
            //
            //    dayDiff 必须基于【取整后】的时刻算，否则显示与闹钟不一致。
            val dayDiff = ChronoUnit.DAYS
                .between(today, rounded.toLocalDate())
                .toInt()

            Result(
                cycles = cycles,
                wakeTime = wake,
                wakeInstant = wake.toInstant(),
                hour24 = rounded.hour,
                minute = rounded.minute,
                dayDiff = dayDiff,
            )
        }
    }

    /**
     * 相对日期文案。
     *
     * 关于 dayDiff 的量级（重要，且与「向上取整」这个口径强相关）：
     *   9 周期 = 15 分 + 810 分 = 825 分 = 13 小时 45 分。
     *   从最晚时刻 23:59:59 起算，起床落在次日 13:44:59，取整后 13:45，dayDiff = 1。
     *   所以在「准备入睡 = 15 分钟」（乃至上限 30 分钟）下，dayDiff 只可能是 0 或 1。
     *
     *   要让 dayDiff 达到 2，需要 ceil(已过分钟 + fall + 810) >= 2880，
     *   最极端起点 23:59:59 解得 **fall >= 630 分钟**（≈10.5 小时）。
     *
     *   ⚠️ 门槛是 630 而不是 631 —— 因为向上取整会把 2879.0x 顶到 2880：
     *      起点 23:59:59 + 630 分 + 810 分 = 次日 23:59:59，取整后变成第三天 00:00。
     *      若换回截断口径，同参数只会停在 23:59，dayDiff 仍是 1。
     *
     *   仍然把四档写全 —— 这是防御性设计，不是过度工程。
     *   但不要期望在 MVP 里看到"后天"，那不是 bug。
     */
    fun relativeDayLabel(dayDiff: Int): String = when (dayDiff) {
        0 -> "今天"
        1 -> "明天"
        2 -> "后天"
        else -> "第 ${dayDiff + 1} 天"
    }
}
