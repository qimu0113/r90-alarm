package com.yanfei.r90alarm.update

import android.content.Context
import com.yanfei.r90alarm.settings.AppSettings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * 联网检查 GitHub Releases 是否有新版本。
 *
 * 契约（与「不联网」旧定位的唯一例外，用户已拍板）：
 *   - 只发一个 GET，读公开的 release 元数据；不上传任何本地数据。
 *   - 连接 / 读取超时各 5 秒，绝不长时间吊住后台线程。
 *   - **任何异常（无网 / 超时 / 非 200 / JSON 解析失败）都转成 [Outcome.Failed]**，
 *     绝不抛到 UI —— 检查更新失败不该影响 App 任何其他功能。
 *   - 24 小时节流：冷启动静默查一次，之后不到点不重查，避免一开 App 就打 GitHub。
 *
 * ⚠️ 代理：**强制直连**（[Proxy.NO_PROXY]）。已知网络事实是 api.github.com
 * 直连可达、走代理反而 403，所以即便设备配了系统代理也不绕。
 *
 * 网络调用会阻塞，**必须在后台线程调用**（IO dispatcher / 子线程）。
 */
object UpdateChecker {

    /** GitHub 最新 release 接口。 */
    private const val API_URL =
        "https://api.github.com/repos/qimu0113/r90-alarm/releases/latest"

    /** 用户可见的发布页（手动打开用）。 */
    private const val RELEASES_PAGE =
        "https://github.com/qimu0113/r90-alarm/releases"

    /**
     * GitHub API 强制要求 `User-Agent`，缺失直接 403。
     * 固定一个自定义 UA，别用 OkHttp/curl 的默认值。
     */
    private const val USER_AGENT = "R90Alarm-Android"

    private const val TIMEOUT_MS = 5_000

    /** 节流窗口：24 小时。 */
    const val THROTTLE_MS = 24L * 60L * 60L * 1000L

    /** 一次检查的结果。 */
    sealed interface Outcome {
        /** 远端 tag 比本机版本新。 */
        data class UpdateAvailable(val latestTag: String) : Outcome

        /** 已是最新（远端 tag 不高于本机）。 */
        data object UpToDate : Outcome

        /** 失败。reason 已是「可展示」的短句，不含堆栈。 */
        data class Failed(val reason: String) : Outcome
    }

    /** 一次检查的完整结果（含发布页地址与检查时刻，供 UI 展示）。 */
    data class Info(
        val outcome: Outcome,
        val releaseUrl: String,
        val checkedAtMillis: Long,
    )

    /** 最近一次检查结果缓存（进程内），供 About 页展示而不必再联网。 */
    @Volatile
    var cached: Info? = null
        private set

    /** 发布页地址。 */
    fun releasesPage(): String = RELEASES_PAGE

    /**
     * 节流版检查：距上次检查不足 [THROTTLE_MS] 时直接返回 null（**不联网**）。
     * 返回非 null 表示这次真的发起了网络请求。
     */
    fun checkIfDue(context: Context, currentVersion: String): Info? {
        val last = AppSettings.load(context).lastUpdateCheckMillis
        if (System.currentTimeMillis() - last < THROTTLE_MS) return null
        return checkNow(context, currentVersion)
    }

    /**
     * 强制检查（手动点「检查更新」走这里）。失败也返回 [Info]，由 UI 决定怎么显示。
     */
    fun checkNow(context: Context, currentVersion: String): Info {
        val outcome = runCatching { fetch(currentVersion) }
            .getOrElse { e ->
                Outcome.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
            }
        val info = Info(outcome, RELEASES_PAGE, System.currentTimeMillis())
        cached = info
        // 记下本次检查时刻（成功失败都记 —— 失败也节流，避免断网时反复打）。
        // 用 update 做原子「读-改-写」：只改 lastUpdateCheckMillis，
        // 不会覆盖主线程刚写入的铃声 / 路由 / 待重排闹钟等字段。
        runCatching {
            AppSettings.update(context) { it.copy(lastUpdateCheckMillis = info.checkedAtMillis) }
        }
        return info
    }

    private fun fetch(currentVersion: String): Outcome {
        var conn: HttpURLConnection? = null
        try {
            // 强制直连，不经过任何代理（含系统代理）。
            conn = URL(API_URL).openConnection(Proxy.NO_PROXY) as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/vnd.github+json")

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) return Outcome.Failed("HTTP $code")

            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val tag = JSONObject(body).optString("tag_name", "")
            if (tag.isBlank()) return Outcome.Failed("响应缺少 tag_name")

            return if (VersionCompare.isNewer(tag, currentVersion)) {
                Outcome.UpdateAvailable(tag)
            } else {
                Outcome.UpToDate
            }
        } finally {
            conn?.disconnect()
        }
    }
}
