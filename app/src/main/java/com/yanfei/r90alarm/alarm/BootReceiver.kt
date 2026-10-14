package com.yanfei.r90alarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yanfei.r90alarm.DiagLog
import com.yanfei.r90alarm.settings.AppSettings

/**
 * 开机 / 应用更新后**重排**闹钟。
 *
 * ⚠️ 铁律：这里**只能重排**（[AlarmScheduler.schedule]），**绝不能**直接启动响铃服务。
 *
 * 原因（已核实）：以 API 35 为目标平台时，`BOOT_COMPLETED` 接收器不能启动
 * mediaPlayback 前台服务，否则抛 `ForegroundServiceStartNotAllowedException` ——
 * Android 15 对 BOOT_COMPLETED 可启动的前台服务类型做了收窄，白名单是
 * LOCATION / CONNECTED_DEVICE / REMOTE_MESSAGING / HEALTH / SYSTEM_EXEMPTED / SPECIAL_USE，
 * **不含 mediaPlayback**。
 *
 * 为什么需要它：`AlarmManager.setAlarmClock` 排的闹钟**重启后会丢**，
 * 所以排期时把目标时刻落盘到 [AppSettings.pendingAlarm]，这里读出来重排。
 *
 * 只监听 `BOOT_COMPLETED` 与 `MY_PACKAGE_REPLACED`；不监听 `LOCKED_BOOT_COMPLETED` ——
 * 直接启动阶段（用户解锁前）读不到凭据加密存储里的设置文件。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> reschedule(context)
        }
    }

    private fun reschedule(context: Context) {
        val settings = AppSettings.load(context)
        val pending = settings.pendingAlarm
        if (pending == null) {
            DiagLog.append(context, "BOOT_RESCHEDULE", "无待重排闹钟")
            return
        }

        // 已经过点：不再重排，清掉记录即可（避免开机后立刻补响一个早已过去的闹钟）。
        // 走 update 原子「读-改-写」，基于当前磁盘值清 pendingAlarm，不动别的字段。
        if (pending.triggerAtMillis <= System.currentTimeMillis()) {
            AppSettings.update(context) { it.withoutPendingAlarm() }
            DiagLog.append(context, "BOOT_RESCHEDULE", "已过点，清除 at=${pending.triggerAtMillis}")
            return
        }

        val result = AlarmScheduler.schedule(context, pending.triggerAtMillis, pending.label)
        DiagLog.append(
            context,
            "BOOT_RESCHEDULE",
            "重排 at=${pending.triggerAtMillis} label=${pending.label} → $result",
        )
    }
}
