package com.yanfei.r90alarm.alarm

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 系统铃声选择器（[RingtoneManager.ACTION_RINGTONE_PICKER]）的封装。
 *
 * 为什么用系统选择器而不是自建列表：
 *   1. 拿到的 URI 天然是「当前设备上确实存在的铃声」，不会指向不存在的资源导致静音；
 *   2. 零 UI 开发成本，且用户熟悉；
 *   3. 与项目「不引入多余自建能力」的口径一致。
 *
 * 注意：Android 11+ 包可见性，manifest 的 `<queries>` 里要声明
 * `android.intent.action.RINGTONE_PICKER`，否则 resolveActivity 可能拿不到。
 */
object RingtonePickerHelper {

    /** startActivityForResult 的请求码。 */
    const val REQUEST_PICK_RINGTONE = 1001

    /** 选择器 action 字符串（供 `<queries>` 与意图构建共用）。 */
    const val ACTION_RINGTONE_PICKER = "android.intent.action.RINGTONE_PICKER"

    private const val PICKER_TITLE = "选择闹钟铃声"

    /** 未选铃声时的展示文案。 */
    const val DEFAULT_LABEL = "系统默认"

    /** 构造选择器意图。[currentUri] 非空时会预选中当前铃声。 */
    fun buildPickerIntent(currentUri: Uri?): Intent =
        Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            // 不提供「静音」——闹钟静音没有意义，只会让人误设
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, PICKER_TITLE)
            if (currentUri != null) {
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
            }
        }

    /**
     * 解析选择结果。
     *
     * @return 选中的铃声 URI；null 表示「没选 / 选了静音」——调用方应保持原值。
     */
    fun parsePickedUri(data: Intent?): Uri? {
        if (data == null) return null
        val key = RingtoneManager.EXTRA_RINGTONE_PICKED_URI
        if (!data.hasExtra(key)) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableExtra(key, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            data.getParcelableExtra(key)
        }
    }

    /** 系统默认闹钟铃声 URI。极少数设备 `getDefaultUri` 拿不到时，退回系统默认闹钟声音常量。 */
    fun defaultAlarmUri(): Uri =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI

    /** 人类可读的铃声名。取不到就退回首段 / 兜底文案。 */
    fun displayName(context: Context, uri: Uri?): String {
        if (uri == null) return DEFAULT_LABEL
        return runCatching {
            RingtoneManager.getRingtone(context, uri)?.getTitle(context)
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
            ?: "已选择"
    }
}
