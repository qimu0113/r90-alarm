package com.yanfei.r90alarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.yanfei.r90alarm.DiagLog
import com.yanfei.r90alarm.MainActivity
import com.yanfei.r90alarm.settings.AppSettings
import com.yanfei.r90alarm.settings.PendingAlarm

/**
 * 应用内闹钟的排期器，基于 [AlarmManager.setAlarmClock]。
 *
 * 为什么是 `setAlarmClock` 而不是 `set` / `setExact`：
 *   1. 它会点亮状态栏的「闹钟图标」并在**免打扰**下仍可响 —— 这正是闹钟语义。
 *   2. 由它触发的广播接收器启动前台服务，命中 Android 12+ 后台启动限制的
 *      「应用调用精确闹钟完成用户请求的操作」豁免项（官方明示）。
 *
 * 精确闹钟权限：
 *   - API 31/32：靠 `SCHEDULE_EXACT_ALARM`（默认授予，本 App manifest 里 maxSdkVersion=32）。
 *   - API 33+   ：靠 `USE_EXACT_ALARM`（普通权限，安装即得，无弹窗）。
 *   仍保留 [canScheduleExactAlarms] 运行时自检，权限被系统收回时给用户明确路径。
 *
 * 排期成功后同步写 [AppSettings.pendingAlarm]，供开机重排（`setAlarmClock` 重启后会丢）。
 */
object AlarmScheduler {

    /** 单例排期用的固定请求码（R90 同时只维护一个待响闹钟）。 */
    const val REQUEST_CODE = 2001

    /** 响铃广播 action。 */
    const val ACTION_RING = "com.yanfei.r90alarm.action.RING"

    /** 广播里携带的闹钟标签。 */
    const val EXTRA_LABEL = "com.yanfei.r90alarm.extra.LABEL"

    /** 排期结果。 */
    sealed interface Result {
        /** 已成功交给系统。 */
        data object Scheduled : Result

        /** 设备未授予精确闹钟能力，需要引导用户去开。 */
        data object NeedExactAlarmPermission : Result

        /** 其它失败（拿不到 AlarmManager 等），附原因。 */
        data class Failed(val reason: String) : Result
    }

    /**
     * 当前是否具备设置精确闹钟的能力。
     *
     * API < 31 恒为 true（那时没有精确闹钟权限概念）。
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    /**
     * 排一个应用内闹钟。
     *
     * @param triggerAtMillis 触发时刻（epoch 毫秒）
     * @param label           闹钟标签（用于通知与日志）
     */
    fun schedule(context: Context, triggerAtMillis: Long, label: String): Result {
        val am = context.getSystemService(AlarmManager::class.java)
            ?: return Result.Failed("AlarmManager 不可用")

        if (!canScheduleExactAlarms(context)) {
            DiagLog.append(context, "ALARM_SCHEDULE", "失败 无精确闹钟权限 label=$label")
            return Result.NeedExactAlarmPermission
        }

        return try {
            val showIntent = showIntent(context)
            val operation = ringPendingIntent(context, label)
            val info = AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent)
            am.setAlarmClock(info, operation)

            // 落盘供开机重排
            AppSettings.load(context).let { s ->
                AppSettings.save(
                    context,
                    s.copy(
                        pendingAlarm = PendingAlarm(
                            triggerAtMillis = triggerAtMillis,
                            requestCode = REQUEST_CODE,
                            label = label,
                        ),
                    ),
                )
            }
            DiagLog.append(context, "ALARM_SCHEDULE", "已排期 at=$triggerAtMillis label=$label")
            Result.Scheduled
        } catch (e: SecurityException) {
            // 权限在检查之后又被收回的极窄窗口
            DiagLog.append(context, "ALARM_SCHEDULE", "SecurityException ${e.message}")
            Result.NeedExactAlarmPermission
        } catch (e: Exception) {
            DiagLog.append(context, "ALARM_SCHEDULE", "异常 ${e.javaClass.simpleName}: ${e.message}")
            Result.Failed(e.javaClass.simpleName)
        }
    }

    /** 取消当前应用内闹钟（若有），并清掉落盘的待重排记录。 */
    fun cancel(context: Context) {
        runCatching {
            val am = context.getSystemService(AlarmManager::class.java) ?: return@runCatching
            am.cancel(ringPendingIntent(context, null))
        }
        AppSettings.load(context).let { s ->
            if (s.pendingAlarm != null) AppSettings.save(context, s.withoutPendingAlarm())
        }
        DiagLog.append(context, "ALARM_CANCEL", "已取消应用内闹钟")
    }

    /** 读系统里下一个闹钟（含系统时钟 App 排的），供展示。 */
    fun nextAlarmClock(context: Context): AlarmManager.AlarmClockInfo? = runCatching {
        context.getSystemService(AlarmManager::class.java)?.nextAlarmClock
    }.getOrNull()

    // ---------------------------------------------------------------------

    /** 状态栏闹钟图标被点开时进入的界面（由系统在用户点击时启动，不触发后台启动限制）。 */
    private fun showIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, REQUEST_CODE, intent, piFlags())
    }

    /** 到点后触发 [AlarmReceiver] 的广播 PendingIntent。 */
    private fun ringPendingIntent(context: Context, label: String?): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_RING)
            .putExtra(EXTRA_LABEL, label ?: "")
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, piFlags())
    }

    /**
     * IMMUTABLE 是 Android 12+ 的硬要求（带 update-current 的可变 PI 会被拒）。
     * minSdk 26 起 IMMUTABLE 常量即可用，无需版本守卫。
     */
    private fun piFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
