package com.yanfei.r90alarm.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yanfei.r90alarm.DiagLog
import com.yanfei.r90alarm.R
import com.yanfei.r90alarm.settings.AppSettings

/**
 * 响铃前台服务。类型 = mediaPlayback。
 *
 * 为什么必须是前台服务：
 *   闹钟响铃时 App 十有八九在后台，普通 Service 会被省电策略随手掐掉。
 *   mediaPlayback 型前台服务**没有运行时长上限**（Android 15 的 FGS 超时只针对
 *   dataSync / mediaProcessing），所以不会被系统按时间点掐断。
 *
 * 响铃链路：
 *   AlarmReceiver → [start] → onCreate(建渠道) → onStartCommand(ACTION_START)
 *   → startForeground(通知) → MediaPlayer(USAGE_ALARM, 循环) → 路由 → 兜底定时器
 *
 * 关闭链路（两个入口行为一致）：
 *   通知按钮 / [RingActivity] 按钮 → [dismiss] / [snooze] → stopService → onDestroy 收尾
 *
 * 亮屏入口（D10）：
 *   通知的 contentIntent 指向 [RingActivity]（用户点击触发，合法）。
 *   后台直接 startActivity 拉起 RingActivity 只是 best-effort —— Android 10+ 限制后台
 *   启动 Activity，纯前台服务不构成豁免，**标记「待验证」**。
 */
class AlarmPlaybackService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var router: AlarmAudioRouter? = null
    private var settings: AppSettings = AppSettings()
    private var label: String = DEFAULT_LABEL
    private var routeSummary: String = "初始化中"

    /** 到点垫底自动停：最长响铃时长（默认 15 分钟）到了就收手，避免响一整夜。 */
    private val autoStop = Runnable {
        DiagLog.append(this, "AUTO_STOP", "达到最长响铃 ${settings.maxRingMillis}ms，自动关闭")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings.load(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            startRinging(intent.getStringExtra(EXTRA_LABEL).orEmpty())
        } else {
            // 兜底：任何非预期入口都要先满足「5 秒内 startForeground」的契约，再退出
            runCatching { goForeground(buildNotification()) }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        router?.stop()
        router = null
        player?.let { mp ->
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
        player = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 起播
    // ------------------------------------------------------------------

    private fun startRinging(rawLabel: String) {
        label = rawLabel.ifBlank { DEFAULT_LABEL }

        // 先上通知，满足 startForegroundService 的 5 秒契约
        goForeground(buildNotification())

        startPlayer()

        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, settings.maxRingMillis.coerceAtLeast(MIN_AUTO_STOP_MILLIS))

        maybeLaunchRingActivity()
    }

    private fun startPlayer() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM) // D2：走闹钟音量通道
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val mp = MediaPlayer()
        mp.setAudioAttributes(attrs)
        mp.isLooping = true
        mp.setOnErrorListener { _, what, extra ->
            DiagLog.append(this, "ALARM_PLAYER", "播放错误 what=$what extra=$extra")
            true
        }

        try {
            mp.setDataSource(this, ringtoneUri())
            mp.setOnPreparedListener { runCatching { it.start() } }
            mp.prepareAsync()
        } catch (e: Exception) {
            DiagLog.append(this, "ALARM_PLAYER", "加载铃声失败 ${e.javaClass.simpleName}: ${e.message}")
        }

        player = mp

        val r = AlarmAudioRouter(this, mp, settings.routeMode) { summary ->
            onRouteSummary(summary)
        }
        router = r
        r.start()
    }

    private fun ringtoneUri(): Uri {
        val stored = settings.ringtoneUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        return stored ?: RingtonePickerHelper.defaultAlarmUri()
    }

    private fun onRouteSummary(summary: String) {
        routeSummary = summary
        DiagLog.append(this, "ALARM_ROUTE", "档=${settings.routeMode.name} $summary")
        // 路由变化时刷新通知文案（同 id 覆盖，不新增通知）
        runCatching { notificationManager()?.notify(NOTIF_ID, buildNotification()) }
    }

    /**
     * D10：后台直接拉起响铃页 —— **best-effort，标「待验证」**。
     *
     * Android 10+ 限制后台启动 Activity；前台服务本身不构成豁免。
     * 所以这里失败是正常预期，靠通知入口兜底。不 crash、只记录。
     */
    private fun maybeLaunchRingActivity() {
        runCatching {
            startActivity(
                Intent(this, RingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(EXTRA_LABEL, label)
            )
        }.onFailure {
            DiagLog.append(this, "RING_ACTIVITY", "后台拉起被拦(预期内): ${it.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private fun ensureChannel() {
        val nm = notificationManager() ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "闹钟响铃",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "到点响铃时的常驻通知，含「关闭」与「贪睡」按钮"
            // 铃声我们自己用 MediaPlayer 播（为了控制音频路由），渠道本身不出声
            setSound(null, null)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            RC_CONTENT,
            Intent(this, RingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_LABEL, label),
            piFlags(),
        )
        val dismissIntent = PendingIntent.getBroadcast(
            this,
            RC_DISMISS,
            Intent(this, RingActionReceiver::class.java).setAction(ACTION_DISMISS),
            piFlags(),
        )
        val snoozeIntent = PendingIntent.getBroadcast(
            this,
            RC_SNOOZE,
            Intent(this, RingActionReceiver::class.java)
                .setAction(ACTION_SNOOZE)
                .putExtra(EXTRA_LABEL, label),
            piFlags(),
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("⏰ $label")
            .setContentText(routeSummary)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(contentIntent)
            .addAction(0, "贪睡 ${settings.snoozeMinutes} 分钟", snoozeIntent)
            .addAction(0, "关闭", dismissIntent)
            .build()
    }

    private fun goForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(NotificationManager::class.java)

    private fun piFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    companion object {
        const val ACTION_START = "com.yanfei.r90alarm.action.RING_START"
        const val ACTION_DISMISS = "com.yanfei.r90alarm.action.RING_DISMISS"
        const val ACTION_SNOOZE = "com.yanfei.r90alarm.action.RING_SNOOZE"
        const val EXTRA_LABEL = "com.yanfei.r90alarm.extra.LABEL"

        const val NOTIF_ID = 0x52_0901
        const val CHANNEL_ID = "r90_alarm_ring"

        private const val DEFAULT_LABEL = "R90 闹钟"
        private const val MIN_AUTO_STOP_MILLIS = 5_000L

        private const val RC_CONTENT = 3101
        private const val RC_DISMISS = 3102
        private const val RC_SNOOZE = 3103

        /** 启动响铃。由 [AlarmReceiver] 调用（精确闹钟豁免后台启动限制）。 */
        fun start(context: Context, label: String) {
            val intent = Intent(context, AlarmPlaybackService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LABEL, label)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    DiagLog.append(context, "ALARM_START", "启动失败: ${it.javaClass.simpleName}: ${it.message}")
                }
        }

        /** 关闭响铃（通知按钮 / 响铃页按钮共用）。 */
        fun dismiss(context: Context, source: String) {
            DiagLog.append(context, "ALARM_DISMISS", "来源=$source")
            stopPlayback(context)
        }

        /** 贪睡：按设置里的分钟数重排一个闹钟，然后关闭当前响铃。 */
        fun snooze(context: Context, label: String, source: String) {
            val minutes = AppSettings.load(context).snoozeMinutes
            val triggerAt = System.currentTimeMillis() + minutes * 60_000L
            val newLabel = label.ifBlank { "$DEFAULT_LABEL·贪睡" }
            val result = AlarmScheduler.schedule(context, triggerAt, newLabel)
            DiagLog.append(context, "ALARM_SNOOZE", "来源=$source ${minutes}分钟 at=$triggerAt")
            if (result != AlarmScheduler.Result.Scheduled) {
                // 重排没成功 = 当前响铃已经停了，但下一声闹钟没排上。
                // 若不告诉用户，用户会以为贪睡生效，实际会一直睡到自然醒。
                DiagLog.append(context, "SNOOZE_FAILED", "来源=$source 重排失败 result=$result label=$newLabel")
                showSnoozeFailedToast(context, result)
            }
            stopPlayback(context)
        }

        /**
         * 贪睡重排失败时的用户可见反馈。
         *
         * 为什么用 Toast 而不是通知：贪睡的两个入口（通知按钮 / 响铃页按钮）都是
         * 「用户刚刚点击」的前台交互，此刻屏幕必然亮着、人正看着，Toast 一定会被看到；
         * 而通知受 POST_NOTIFICATIONS 约束 —— 该权限在本 App 是「用户可拒绝」的运行时权限，
         * 一旦被拒，通知式反馈会静默丢失，正好复现我们要修的那类「无感知」问题。
         */
        private fun showSnoozeFailedToast(context: Context, result: AlarmScheduler.Result) {
            val text = when (result) {
                AlarmScheduler.Result.NeedExactAlarmPermission ->
                    "贪睡未生效：精确闹钟权限已被收回，闹钟没能重排，请重新设置"
                is AlarmScheduler.Result.Failed ->
                    "贪睡未生效：闹钟没能重排（${result.reason}），请重新设置"
                AlarmScheduler.Result.Scheduled -> return
            }
            // 用 applicationContext：两个入口可能在 Activity finish() 前后触发，避免窗口泄漏；
            // Toast 是系统级叠层，不受宿主生命周期影响。两端都在主线程调用，无需切线程。
            runCatching {
                Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
            }
        }

        private fun stopPlayback(context: Context) {
            // stopService → onDestroy 里统一收尾（停播放器、注销路由、移除通知）
            runCatching { context.stopService(Intent(context, AlarmPlaybackService::class.java)) }
        }
    }
}
