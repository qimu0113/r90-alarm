package com.yanfei.r90alarm

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

/**
 * 调用系统时钟设置闹钟。
 *
 * 三层降级策略：
 *
 *   Level 1  SKIP_UI = true    请系统时钟静默设置（厂商可以不遵守）
 *   Level 2  SKIP_UI = false   打开时钟并预填时间，用户按保存
 *   Level 3  都没有 handler    返回 NoHandler，UI 给补救路径
 *
 * ⚠️ 重要事实：startActivity 对 ACTION_SET_ALARM 是单向的、无返回值的。
 *    系统时钟不会回传「我跳过 UI 了」还是「我弹界面了」。
 *    所以 SilentRequested 只代表「请求发出去了」，不代表「厂商遵守了」。
 *    想拿确定结论只能真机实测 —— 或者靠 [DiagLog] 记录的暂停行为间接推断。
 *
 * 除了结果本身，本类还额外带出**可观测证据**（handler 组件名、是否抛异常、
 * 调用耗时），供诊断日志落盘。这些字段不影响主流程，只影响日志的可用性。
 */
object SystemAlarmLauncher {

    /** 闹钟标签，写进系统时钟，方便识别来源。 */
    private const val ALARM_LABEL = "R90 闹钟"

    sealed interface Outcome {

        /**
         * Level 1 成功：已请求静默设置。
         * 注意：无法确认厂商是否真的跳过了界面。
         */
        data class SilentRequested(
            val hour24: Int,
            val minute: Int,
            val handler: String?,
            val elapsedMs: Long,
        ) : Outcome

        /** Level 2 成功：已打开系统时钟并预填，等用户按保存。 */
        data class UiFallback(
            val hour24: Int,
            val minute: Int,
            val handler: String?,
            val elapsedMs: Long,
        ) : Outcome

        /**
         * Level 3：设备上没有任何 App 能处理 SET_ALARM。
         *
         * @param reason `no-handler`（压根没有响应者）或 `error:<异常类名>`
         */
        data class NoHandler(val reason: String, val handler: String?) : Outcome
    }

    /**
     * 尝试设置闹钟。
     *
     * @param activity 必须传 Activity（不是 Application Context）。
     *                 用 Application Context 得加 FLAG_ACTIVITY_NEW_TASK，
     *                 会把时钟拉到独立任务栈，返回时体验很怪。
     * @param hour24   0..23，永远 24 小时制
     * @param minute   0..59
     * @param preferSilent 是否先尝试静默设置。默认 true。
     */
    fun setAlarm(
        activity: Activity,
        hour24: Int,
        minute: Int,
        preferSilent: Boolean = true,
    ): Outcome {
        require(hour24 in 0..23) { "hour24 必须在 0..23，实际 $hour24" }
        require(minute in 0..59) { "minute 必须在 0..59，实际 $minute" }

        if (preferSilent) {
            val silent = tryLaunch(activity, hour24, minute, skipUi = true)
            if (silent.state == LaunchState.OK) {
                return Outcome.SilentRequested(hour24, minute, silent.handler, silent.elapsedMs)
            }
            // 静默失败就降级，不直接报错
        }

        val fallback = tryLaunch(activity, hour24, minute, skipUi = false)
        return when (fallback.state) {
            LaunchState.OK -> Outcome.UiFallback(hour24, minute, fallback.handler, fallback.elapsedMs)
            LaunchState.NO_HANDLER -> Outcome.NoHandler("no-handler", fallback.handler)
            LaunchState.ERROR -> Outcome.NoHandler("error:${fallback.error}", fallback.handler)
        }
    }

    private enum class LaunchState { OK, NO_HANDLER, ERROR }

    private data class TryResult(
        val state: LaunchState,
        val handler: String?,
        val error: String?,
        val elapsedMs: Long,
    )

    private fun tryLaunch(
        activity: Activity,
        hour24: Int,
        minute: Int,
        skipUi: Boolean,
    ): TryResult {
        val intent = buildIntent(hour24, minute, skipUi)

        // 预防层：先探测有没有 handler。
        //
        // ⚠️ Android 11+ 必须在 Manifest 里配 <queries>，
        //    否则这里对系统时钟一律返回 null，误判成「没有时钟应用」。
        val resolved = intent.resolveActivity(activity.packageManager)
        if (resolved == null) {
            return TryResult(LaunchState.NO_HANDLER, null, null, 0L)
        }
        val handlerName = resolved.flattenToShortString()

        val t0 = System.currentTimeMillis()
        return try {
            activity.startActivity(intent)
            TryResult(LaunchState.OK, handlerName, null, System.currentTimeMillis() - t0)
        } catch (_: ActivityNotFoundException) {
            // 竞态：resolveActivity 通过后，时钟 App 被禁用/卸载的窄窗口
            TryResult(
                LaunchState.NO_HANDLER, handlerName, "ActivityNotFoundException",
                System.currentTimeMillis() - t0,
            )
        } catch (_: SecurityException) {
            // 极少数 ROM / 企业设备策略会对 SET_ALARM 额外校验
            TryResult(
                LaunchState.ERROR, handlerName, "SecurityException",
                System.currentTimeMillis() - t0,
            )
        } catch (e: Exception) {
            // 兜底：定制 ROM 可能抛别的东西，不能让 App 崩。
            // 这里刻意接住异常对象（而不是 `_`）—— 要把异常类名写进诊断日志。
            TryResult(
                LaunchState.ERROR, handlerName, e.javaClass.simpleName,
                System.currentTimeMillis() - t0,
            )
        }
    }

    private fun buildIntent(hour24: Int, minute: Int, skipUi: Boolean): Intent =
        Intent(AlarmClock.ACTION_SET_ALARM).apply {

            // 关键：官方文档明示「未指定时间时 SKIP_UI 被忽略」。
            // 所以 HOUR/MINUTES 必须传，否则一键设置必然失败。
            //
            // EXTRA_HOUR 传 0-23 的钟点值。官方只定义「ranges from 0 to 23」，
            // 未明说制式；AOSP DeskClock 实现按 0-23 解析。
            putExtra(AlarmClock.EXTRA_HOUR, hour24)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, ALARM_LABEL)
            putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)

            // ⚠️ 刻意不传 EXTRA_IS_PM。
            //
            // 官方文档写的是 "Used by ACTION_DISMISS_ALARM"（用于 dismiss 的查找），
            // 不是 ACTION_SET_ALARM。传它属于非标准用法，多数实现会忽略，
            // 个别实现可能误解析。不传是安全的，传了是自找麻烦。
            //
            // 刻意不传的其他东西：
            //   EXTRA_DAYS     —— R90 是一次性闹钟，不重复
            //   EXTRA_RINGTONE —— 用系统默认铃声，避免指向不存在的 URI 导致静音
            //   EXTRA_VIBRATE  —— 保持默认 true
        }

    /**
     * Level 3 补救动作：打开系统时钟的闹钟列表页。
     * 这个 action 不需要 SET_ALARM 权限。
     */
    fun openAlarmList(context: Context): Boolean {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        if (intent.resolveActivity(context.packageManager) == null) return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            // 时钟 App 存在但启动失败
            false
        }
    }
}
