package com.yanfei.r90alarm.settings

import android.content.Context
import com.yanfei.r90alarm.alarm.AudioRouteMode
import org.json.JSONObject
import java.io.File

/**
 * 一个「已排期但还没响」的闹钟。
 *
 * 为什么必须落盘：`AlarmManager.setAlarmClock` 排的闹钟**重启后会丢**。
 * 要在开机后重排，就得先记住「原本设在什么时候、标签是什么」。
 * 响铃后由 [AlarmReceiver] 清除；开机时由 BootReceiver 读出来重排。
 */
data class PendingAlarm(
    val triggerAtMillis: Long,
    val requestCode: Int,
    val label: String,
)

/**
 * App 设置持久化。
 *
 * 存储形态：`filesDir/settings.json` 一个小 JSON 文件。
 * 刻意**不用 SharedPreferences** —— 保持项目「尽量少的外部状态」口径，
 * 且 JSON 便于人工查看 / 导出 / 备份。
 *
 * 编码：用平台自带的 `org.json`（零新增第三方依赖），不引入 Gson/Moshi。
 *
 * 容错原则：**任何解析失败都回落默认值，绝不抛异常**。
 * 设置文件损坏不该让 App 起不来 —— 大不了回到默认档。
 */
data class AppSettings(
    /** 铃声 URI 字符串。null = 用系统默认闹钟铃声。 */
    val ringtoneUri: String? = null,

    /** 音频路由档。见 [AudioRouteMode]。 */
    val routeMode: AudioRouteMode = AudioRouteMode.DEFAULT,

    /** 响铃最长时长（毫秒），到点自动停。默认 15 分钟。 */
    val maxRingMillis: Long = DEFAULT_MAX_RING_MILLIS,

    /** 贪睡时长（分钟）。 */
    val snoozeMinutes: Int = DEFAULT_SNOOZE_MINUTES,

    /**
     * 上次检查更新的时刻（epoch 毫秒）。0 = 从未检查过。
     * 用途：更新检查的 24 小时节流 —— 避免一冷启动就打 GitHub。
     */
    val lastUpdateCheckMillis: Long = 0L,

    /** 待重排的闹钟。null = 当前没有已排期的闹钟。 */
    val pendingAlarm: PendingAlarm? = null,
) {

    /** 返回一个清掉待重排闹钟的副本。 */
    fun withoutPendingAlarm(): AppSettings = copy(pendingAlarm = null)

    companion object {
        /** 默认最长响铃时长：15 分钟。 */
        const val DEFAULT_MAX_RING_MILLIS: Long = 15L * 60L * 1000L

        /** 最长响铃可选值（分钟），供设置页渲染。 */
        val MAX_RING_MINUTES_OPTIONS = listOf(1, 5, 10, 15, 30)

        /** 默认贪睡时长（分钟）。 */
        const val DEFAULT_SNOOZE_MINUTES: Int = 5

        /** 贪睡可选值（分钟）。 */
        val SNOOZE_MINUTES_OPTIONS = listOf(1, 5, 9, 10, 15)

        private const val FILE_NAME = "settings.json"

        // ---- JSON key ----
        private const val K_RINGTONE = "ringtoneUri"
        private const val K_ROUTE = "routeMode"
        private const val K_MAX_RING = "maxRingMillis"
        private const val K_SNOOZE = "snoozeMinutes"
        private const val K_LAST_CHECK = "lastUpdateCheckMillis"
        private const val K_PENDING = "pendingAlarm"
        private const val K_PENDING_AT = "triggerAtMillis"
        private const val K_PENDING_RC = "requestCode"
        private const val K_PENDING_LABEL = "label"

        /**
         * 读取设置。文件不存在 / 损坏 / 字段非法，一律回落默认值。
         *
         * [Synchronized]：与 [save] / [update] 共用 **companion 单例**这一把锁
         * （项目里 DiagLog / UsageHistory 也是这个做法）。这样 [update] 里的
         * 「读 → 改 → 写」能在同一把锁内整体串行，不会与别的写者交错。
         */
        @Synchronized
        fun load(context: Context): AppSettings {
            val file = fileOf(context)
            if (!file.exists()) return AppSettings()
            return runCatching {
                val obj = JSONObject(file.readText(Charsets.UTF_8))
                AppSettings(
                    ringtoneUri = obj.optStringOrNull(K_RINGTONE),
                    routeMode = AudioRouteMode.fromStorage(obj.optStringOrNull(K_ROUTE)),
                    maxRingMillis = obj.optLong(K_MAX_RING, DEFAULT_MAX_RING_MILLIS)
                        .coerceAtLeast(MIN_RING_MILLIS),
                    snoozeMinutes = obj.optInt(K_SNOOZE, DEFAULT_SNOOZE_MINUTES)
                        .coerceIn(MIN_SNOOZE_MINUTES, MAX_SNOOZE_MINUTES),
                    lastUpdateCheckMillis = obj.optLong(K_LAST_CHECK, 0L),
                    pendingAlarm = obj.optJSONObject(K_PENDING)?.let { p ->
                        PendingAlarm(
                            triggerAtMillis = p.optLong(K_PENDING_AT, 0L),
                            requestCode = p.optInt(K_PENDING_RC, 0),
                            label = p.optString(K_PENDING_LABEL, ""),
                        ).takeIf { it.triggerAtMillis > 0L }
                    },
                )
            }.getOrElse { AppSettings() }
        }

        /**
         * 写入设置（**整份覆盖**）。
         *
         * 先写临时文件再原子改名 —— 避免写到一半被系统杀进程，
         * 留下半截 JSON 把下次启动的设置全弄丢。
         *
         * [Synchronized]：与 [load] / [update] 共用同一把锁，两次写不会交错、
         * 也不会写到对方正在改名的那份临时文件上。
         *
         * ⚠️ 但**仅给这个方法加锁，挡不住「读-改-写」竞态**：
         * 调用方若先读出快照、改一个字段再整份写回，中途别的写者改过的字段
         * 会被旧快照覆盖掉。需要原子地「改某个字段」的调用方请改用 [update]。
         */
        @Synchronized
        fun save(context: Context, settings: AppSettings) {
            runCatching {
                val tmp = File(context.filesDir, "$FILE_NAME.tmp")
                tmp.writeText(settings.toJson().toString(2), Charsets.UTF_8)
                val dst = fileOf(context)
                if (dst.exists()) dst.delete()
                tmp.renameTo(dst)
            }
        }

        /**
         * 原子「读 → 改 → 写」：整段在同一把锁内完成，返回写回后的完整设置。
         *
         * 为什么需要它（[save] 单独加锁不够）：
         *   settings.json 有多个写者，其中 `UpdateChecker` 跑在 **IO 线程**，
         *   只改 [lastUpdateCheckMillis]；其余写者跑在主线程，改铃声 / 路由 /
         *   待重排闹钟等。若调用方各自「读出快照 → 改字段 → 整份 save」，
         *   两个后果：
         *     1. UI 线程手里是启动时的旧快照，会把 IO 线程刚写入的
         *        [lastUpdateCheckMillis] 一起冲掉 → 24h 更新节流失效；
         *     2. 极端交错下先写到一半的临时文件被对方改名 → 文件损坏，
         *        下次 [load] 回落默认值，用户设置被静默重置。
         *   把「读当前磁盘值 → 套用 [transform] → 写回」全部纳入同一把锁后，
         *   无论谁先谁后，都不会互相覆盖、不会产出半截文件。
         *
         * @param transform 基于**当前磁盘值**产出一份新设置；不要引用调用方
         *                  自己的旧快照，否则仍会丢更新。
         * @return 写回后的完整设置（调用方可拿它同步内存态）。
         */
        @Synchronized
        fun update(context: Context, transform: (AppSettings) -> AppSettings): AppSettings {
            val next = transform(load(context))
            save(context, next)
            return next
        }

        private fun fileOf(context: Context): File = File(context.filesDir, FILE_NAME)

        private const val MIN_RING_MILLIS = 5_000L
        private const val MIN_SNOOZE_MINUTES = 1
        private const val MAX_SNOOZE_MINUTES = 60

        /** JSONObject.optString 会把缺失键变成空串；这里把它还原成 null。 */
        private fun JSONObject.optStringOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return optString(key, "").takeIf { it.isNotBlank() }
        }
    }

    /** 序列化。 */
    private fun toJson(): JSONObject = JSONObject().apply {
        ringtoneUri?.let { put(K_RINGTONE, it) }
        put(K_ROUTE, routeMode.name)
        put(K_MAX_RING, maxRingMillis)
        put(K_SNOOZE, snoozeMinutes)
        put(K_LAST_CHECK, lastUpdateCheckMillis)
        pendingAlarm?.let { p ->
            put(
                K_PENDING,
                JSONObject().apply {
                    put(K_PENDING_AT, p.triggerAtMillis)
                    put(K_PENDING_RC, p.requestCode)
                    put(K_PENDING_LABEL, p.label)
                },
            )
        }
    }
}
