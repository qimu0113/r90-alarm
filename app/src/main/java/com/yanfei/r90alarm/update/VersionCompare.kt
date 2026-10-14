package com.yanfei.r90alarm.update

/**
 * 版本号比对。**纯逻辑**，不依赖 android.* / org.json，可 JVM 单测。
 *
 * 需要处理的真实形态（GitHub Release 的 `tag_name` 五花八门）：
 *   - 带或不带 `v` 前缀：`0.3` / `v0.3`
 *   - 段数不一致：`0.2` / `0.2.1` / `1.0.0`
 *   - 预发布后缀：`0.3-beta` / `v0.3-rc1`
 *
 * 比较规则：
 *   1. 逐个数字段比大小；段数不足的按 0 补齐（`0.2` == `0.2.0`）。
 *   2. 数字段全等时，**无预发布后缀 > 有后缀**
 *      （`0.3` 比 `0.3-beta` 新，因为正式版在 beta 之后发布）。
 *   3. 双方都有后缀时，按字典序比（`rc1` > `beta`）。
 *
 * 脏输入（空串 / 非数字段）一律当 0 处理，绝不抛异常 ——
 * 这个函数的调用点在最外层网络回调里，抛异常会毁掉「检查更新」整条路。
 */
object VersionCompare {

    /**
     * 三态比较。
     * @return a<b 返回负数；a==b 返回 0；a>b 返回正数。
     */
    fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)

        val n = maxOf(pa.numbers.size, pb.numbers.size)
        for (i in 0 until n) {
            val x = pa.numbers.getOrElse(i) { 0 }
            val y = pb.numbers.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }

        // 数字段相同 —— 用预发布后缀定胜负。
        return when {
            pa.preRelease == null && pb.preRelease == null -> 0
            pa.preRelease == null -> 1      // a 是正式版，更新
            pb.preRelease == null -> -1     // b 是正式版，更新
            else -> pa.preRelease.compareTo(pb.preRelease)
        }
    }

    /** [candidate] 是否比 [current] 新。 */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    private data class Parsed(val numbers: List<Int>, val preRelease: String?)

    private fun parse(raw: String): Parsed {
        var s = raw.trim()
        // 去掉 v / V 前缀（GitHub 上两种都有人用）
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)

        // 第一个 '-' 之后是预发布后缀
        val dash = s.indexOf('-')
        val base = if (dash >= 0) s.substring(0, dash) else s
        val pre = if (dash >= 0) s.substring(dash + 1).takeIf { it.isNotBlank() } else null

        // 数字段：只取每段的前导数字，容忍 "2a" 这种脏输入；取不到当 0
        val numbers = base.split('.').map { seg ->
            seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        return Parsed(numbers, pre)
    }
}
