package com.yanfei.r90alarm

import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 本地诊断日志。
 *
 * 目的：把「这台设备上到底发生了什么」记下来，让作者能拿日志离线分析兼容性，
 * **不必一直连着手机**。
 *
 * 设计约束（继承 App 的整体定位）：
 * - 不联网。日志只写到 App 私有目录，导出靠系统分享/剪贴板，由用户自己决定发给谁。
 * - 不需要任何权限。`filesDir` 是 App 自己的沙盒，读写无需权限。
 * - 不无限增长。超过 [MAX_LINES] 行自动丢掉最旧的。
 */
object DiagLog {

    private const val FILE_NAME = "diagnostic.log"

    /** 保留上限。一次点击一行、一次启动一行，500 行足够用很久。 */
    private const val MAX_LINES = 500

    private val STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /** 追加一行。任何 I/O 失败都静默吞掉 —— 记日志这件事本身不能影响主功能。 */
    @Synchronized
    fun append(context: Context, tag: String, body: String) {
        val line = "[${ZonedDateTime.now().format(STAMP)}] $tag | $body"
        runCatching {
            val f = File(context.filesDir, FILE_NAME)
            f.appendText(line + "\n")
            trim(f)
        }
    }

    @Synchronized
    fun readAll(context: Context): String =
        runCatching {
            val f = File(context.filesDir, FILE_NAME)
            if (f.exists()) f.readText() else ""
        }.getOrDefault("")

    @Synchronized
    fun clear(context: Context) {
        runCatching {
            val f = File(context.filesDir, FILE_NAME)
            if (f.exists()) f.delete()
        }
    }

    /** 行数超限就只留尾部。 */
    private fun trim(f: File) {
        val lines = f.readLines()
        if (lines.size > MAX_LINES) {
            f.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
        }
    }

    /**
     * 环境快照。每次冷启动写一条。
     *
     * 为什么要有：日志单独看是悬空的 —— 得知道「这是哪台机器、哪个版本、什么时区」，
     * 才能把某条「无界面」的判定归因到具体机型 + 系统版本。
     */
    fun envSnapshot(context: Context): String {
        val is24 = runCatching { DateFormat.is24HourFormat(context) }.getOrDefault(false)
        val fontScale = runCatching {
            context.resources.configuration.fontScale
        }.getOrDefault(1f)
        return buildString {
            append("设备=").append(Build.MODEL)
            append(" 安卓=").append(Build.VERSION.RELEASE)
            append("(SDK").append(Build.VERSION.SDK_INT).append(')')
            append(" 系统=").append(Build.DISPLAY)
            append(" app=").append(BuildConfig.VERSION_NAME)
            append(" 时区=").append(ZonedDateTime.now().zone.id)
            append(" 24h=").append(is24)
            append(" fontScale=").append(fontScale)
        }
    }
}

/** [UiProbe.snapshot] 的返回值。 */
internal data class ProbeSnapshot(
    val pauseCount: Int,
    val firstPauseDelayMs: Long,
    val pausedMs: Long,
    val stillPaused: Boolean,
)

/**
 * 观测「本 App 在点击之后有没有被暂停、暂停了多久」。
 *
 * 只在 [arm] 之后才开始计数，避免把无关的 onPause/onResume 混进来
 * （比如用户切走 App、弹系统对话框）。
 */
internal class UiProbe {

    private var armed = false
    private var tapAt = 0L
    private var firstPauseDelayMs = -1L
    private var pauseCount = 0
    private var pausedMs = 0L
    private var pauseStartedAt = 0L
    private var stillPaused = false

    /** 点击「设置」的瞬间调用，重置并开始观测。 */
    @Synchronized
    fun arm() {
        armed = true
        tapAt = System.currentTimeMillis()
        firstPauseDelayMs = -1L
        pauseCount = 0
        pausedMs = 0L
        pauseStartedAt = 0L
        stillPaused = false
    }

    @Synchronized
    fun onPause() {
        if (!armed) return
        val now = System.currentTimeMillis()
        if (firstPauseDelayMs < 0) firstPauseDelayMs = now - tapAt
        pauseCount++
        pauseStartedAt = now
        stillPaused = true
    }

    @Synchronized
    fun onResume() {
        if (!armed) return
        if (pauseStartedAt > 0) {
            pausedMs += System.currentTimeMillis() - pauseStartedAt
            pauseStartedAt = 0
        }
        stillPaused = false
    }

    @Synchronized
    fun snapshot(): ProbeSnapshot {
        // 记录那一刻如果还在暂停中，把「已暂停了多久」也算进去，
        // 否则「一直没回来」这种情况会显示成 0ms，看着像没弹界面。
        val ongoing = if (stillPaused && pauseStartedAt > 0) {
            System.currentTimeMillis() - pauseStartedAt
        } else {
            0L
        }
        return ProbeSnapshot(
            pauseCount = pauseCount,
            firstPauseDelayMs = firstPauseDelayMs,
            pausedMs = pausedMs + ongoing,
            stillPaused = stillPaused,
        )
    }
}
