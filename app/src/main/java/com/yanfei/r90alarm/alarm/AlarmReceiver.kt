package com.yanfei.r90alarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yanfei.r90alarm.DiagLog
import com.yanfei.r90alarm.settings.AppSettings

/**
 * 精确闹钟到点后由 [android.app.AlarmManager] 触发的广播接收器。
 *
 * 它只做两件事：
 *   1. 清掉落盘的「待重排」记录（一次性闹钟已触发，别再让开机重复重排）；
 *   2. 启动响铃前台服务。
 *
 * ⚠️ 为什么这里可以安全地 `startForegroundService`：
 *   本接收器是由 `AlarmManager.setAlarmClock` 触发的，命中 Android 12+ 后台启动限制的
 *   「应用调用精确闹钟完成用户请求的操作」豁免项（官方明示）。这与 BOOT_COMPLETED
 *   **不同** —— 后者在 API 35 下不允许启动 mediaPlayback 前台服务，所以
 *   BootReceiver 只重排、绝不启动响铃服务。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_RING) return

        val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL).orEmpty()

        // 一次性闹钟已触发：清掉待重排记录，避免重启后重复重排
        AppSettings.load(context).let { s ->
            if (s.pendingAlarm != null) {
                AppSettings.save(context, s.withoutPendingAlarm())
            }
        }

        DiagLog.append(context, "ALARM_FIRE", "label=$label")

        AlarmPlaybackService.start(context, label)
    }
}
