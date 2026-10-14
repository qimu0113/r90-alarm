package com.yanfei.r90alarm.history

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 使用历史落盘：**JSONL**（每行一个 JSON 对象）。
 *
 * 存储形态：`filesDir/usage_history.jsonl`。
 *
 * 为什么是 JSONL 而不是一个大 JSON 数组：
 *   - 追加一条 = 在文件尾 append 一行，**不用**把整个文件读进来再写回去。
 *     文件涨到几百条时，追加仍是 O(1)。
 *   - 单行损坏只坏一条：读取时逐行解析、坏行跳过，其余完好。
 *     JSON 数组则一处语法错整份报废。
 *   - 天然便于 `tail` / 逐行看。
 *
 * 编码用平台自带的 `org.json`（零新增第三方依赖）。
 *
 * ⚠️ 本类**参与 Android 运行时**，但**不参与 JVM 单测** ——
 * 因为 `org.json` 在 JVM 单测环境是空壳 stub，调用会抛
 * `RuntimeException: Method not mocked`。纯逻辑部分（模型 / 导出 / 版本比对）
 * 已拆到 [UsageRecord]、[HistoryExport]、[com.yanfei.r90alarm.update.VersionCompare]。
 *
 * 容错原则：任何 I/O 或解析失败都静默吞掉，绝不抛到调用方 ——
 * 记历史这件事不能影响「设闹钟」主功能。
 */
object UsageHistory {

    private const val FILE_NAME = "usage_history.jsonl"

    /** 保留上限。超了丢最旧的，避免无限膨胀（对齐 DiagLog 的做法）。 */
    private const val MAX_RECORDS = 500

    // ---- JSON key。用短名减少体积（JSONL 是逐行冗余的） ----
    private const val K_TS = "ts"
    private const val K_HOUR = "h"
    private const val K_MIN = "m"
    private const val K_CYCLES = "cycles"
    private const val K_FALL = "fallAsleepMin"
    private const val K_MODE = "mode"
    private const val K_OK = "success"

    /** 追加一条。失败静默。 */
    @Synchronized
    fun append(context: Context, record: UsageRecord) {
        runCatching {
            val f = fileOf(context)
            f.appendText(toJson(record).toString() + "\n", Charsets.UTF_8)
            trim(f)
        }
    }

    /** 读出全部记录，按写入顺序（即时间升序）。坏行跳过。 */
    @Synchronized
    fun read(context: Context): List<UsageRecord> {
        val f = fileOf(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            f.readLines().asSequence().mapNotNull(::parseLine).toList()
        }.getOrDefault(emptyList())
    }

    /** 清空。 */
    @Synchronized
    fun clear(context: Context) {
        runCatching {
            val f = fileOf(context)
            if (f.exists()) f.delete()
        }
    }

    private fun fileOf(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun toJson(r: UsageRecord): JSONObject = JSONObject().apply {
        put(K_TS, r.epochMillis)
        put(K_HOUR, r.targetHour)
        put(K_MIN, r.targetMinute)
        put(K_CYCLES, r.cycles)
        put(K_FALL, r.fallAsleepMin)
        put(K_MODE, r.mode.name)
        put(K_OK, r.success)
    }

    /** 解析一行；空行 / 坏行返回 null（被调用方跳过）。 */
    private fun parseLine(line: String): UsageRecord? {
        if (line.isBlank()) return null
        return runCatching {
            val o = JSONObject(line)
            UsageRecord(
                epochMillis = o.optLong(K_TS, 0L),
                targetHour = o.optInt(K_HOUR, 0),
                targetMinute = o.optInt(K_MIN, 0),
                cycles = o.optInt(K_CYCLES, 0),
                fallAsleepMin = o.optInt(K_FALL, 0),
                mode = parseMode(o.optString(K_MODE, "")),
                success = o.optBoolean(K_OK, false),
            )
        }.getOrNull()
    }

    /** 未知 / 缺失的 mode 一律回落「系统时钟」—— 宁可显示得保守一点。 */
    private fun parseMode(name: String): UsageRecord.Mode =
        UsageRecord.Mode.entries.firstOrNull { it.name == name } ?: UsageRecord.Mode.SYSTEM_CLOCK

    /** 行数超限就只留尾部。 */
    private fun trim(f: File) {
        val lines = f.readLines()
        if (lines.size > MAX_RECORDS) {
            f.writeText(lines.takeLast(MAX_RECORDS).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        }
    }
}
