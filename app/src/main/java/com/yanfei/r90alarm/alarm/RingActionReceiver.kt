package com.yanfei.r90alarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 响铃通知里「关闭 / 贪睡」两个按钮的广播接收器。
 *
 * 与 [RingActivity] 里的按钮走同一套动作，保证两种入口行为一致。
 * 用户点击通知按钮属「用户对 App 界面元素的交互」，不受后台限制。
 */
class RingActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmPlaybackService.ACTION_DISMISS ->
                AlarmPlaybackService.dismiss(context, source = "notification")

            AlarmPlaybackService.ACTION_SNOOZE ->
                AlarmPlaybackService.snooze(
                    context = context,
                    label = intent.getStringExtra(AlarmPlaybackService.EXTRA_LABEL).orEmpty(),
                    source = "notification",
                )
        }
    }
}
