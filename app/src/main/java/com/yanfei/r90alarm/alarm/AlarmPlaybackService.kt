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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
    private var vibrator: Vibrator? = null
    private var settings: AppSettings = AppSettings()
    private var label: String = DEFAULT_LABEL
    private var routeSummary: String = "初始化中"

    /**
     * 是否正处于「响铃中」。
     *
     * 存在意义：作为 [onRouteSummary] 的最后一道守卫，堵死「服务已销毁仍被回调刷新通知」。
     * `NotificationManager` 是 Application 级服务，**服务 destroy 之后照样能 `notify()`**，
     * 所以「销毁后不该再发通知」这件事只能由我们自己保证，不能指望框架。
     * 与 `AlarmAudioRouter.started` 构成双保险：路由侧不复活心跳，服务侧不诈尸通知。
     */
    private var ringing = false

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
        // 先落「已停止响铃」标记：此后任何迟到的路由回调都不再刷新通知（见 onRouteSummary）。
        ringing = false
        // 同进程镜像，供还开着的 [RingActivity] 自检。与上面的 ringing 是两件事：
        // ringing 管的是「本实例别再刷通知」，isRinging 管的是「外面还有没有在响的闹钟」。
        isRinging = false
        handler.removeCallbacks(autoStop)
        router?.stop()
        router = null
        // 关闹钟必须停振：否则「只走耳机」档的持续振动会一直续下去。
        runCatching { vibrator?.cancel() }
        vibrator = null
        player?.let { mp ->
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
        player = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // 显式再 cancel 一次，不把通知清理全押在 stopForeground(REMOVE) 上：
        // 本服务在响铃期间会用 notify() 反复刷新**同一个 id**（见 onRouteSummary），
        // 此时该通知的清理归属会因 OEM 实现而异。多一次的代价是零。
        runCatching { notificationManager()?.cancel(NOTIF_ID) }
        // 让响铃页自己也关掉。
        //
        // 为什么必须有：关闭闹钟有三条路径 —— ①响铃页按钮（本页自己 finish，无此问题）；
        // ②通知上的「关闭 / 贪睡」；③最长响铃到点自动停。②③ 都只结束本服务，
        // **不会**结束由全屏意图拉起的 [RingActivity] —— 真机实测：从通知关闭后，
        // 服务已停、通知已消失，全屏响铃页却继续留在屏幕上（锁屏场景最明显：
        // 闹钟都不响了，一屏大字还杵着）。这条广播把三条路径统一成同一个收尾动作。
        runCatching { sendBroadcast(Intent(ACTION_RING_STOPPED).setPackage(packageName)) }
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 起播
    // ------------------------------------------------------------------

    private fun startRinging(rawLabel: String) {
        // 重入守卫：必须放在最前面（早于任何字段赋值 / goForeground）。
        // 收到第二次 ACTION_START 时直接忽略，否则：
        //   - startPlayer() 会 new 出第二个 MediaPlayer 覆盖 player 字段 →
        //     旧 player 未 release，继续循环响；
        //   - 新的 AlarmAudioRouter 覆盖 router 字段 →
        //     旧 router 的 deviceCallback / routingListener 未注销，回调泄漏。
        if (ringing) {
            DiagLog.append(this, "ALARM_START", "重复 ACTION_START，忽略（已在响铃）")
            return
        }

        label = rawLabel.ifBlank { DEFAULT_LABEL }

        // 置「响铃中」：必须在 startPlayer()（其内部会 router.start() → 首次 report）之前，
        // 否则首条路由摘要会被 onRouteSummary 的 ringing 守卫挡掉，通知文案停在「初始化中」。
        ringing = true
        // 同上，且必须在 goForeground() **之前**：下面那张通知带全屏意图，
        // SystemUI 可能在下一帧就去拉起 [RingActivity]。若那时 isRinging 还是 false，
        // 响铃页的自检会判定「没在响铃」并立刻自杀，闹钟就真成了「响一声就没了」。
        isRinging = true

        // 先上通知，满足 startForegroundService 的 5 秒契约
        goForeground(buildNotification())

        startPlayer()

        // D3：「只走耳机」档起播即振动 —— 该档无耳机时静音，振动是唯一的兜底提醒，
        // 不能等 60 秒、不能等任何条件。
        startVibrationIfNeeded()

        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, settings.maxRingMillis.coerceAtLeast(MIN_AUTO_STOP_MILLIS))

        maybeLaunchRingActivity()
    }

    // ------------------------------------------------------------------
    // 振动（仅「只走耳机」档）
    // ------------------------------------------------------------------

    /**
     * 「只走耳机」档起播时启动**持续**振动。
     *
     * 为什么只在这一档：默认档（耳机优先）拔出耳机就回落扬声器，有声即可，无需振动兜底；
     * 只有「绝不外放」档在无耳机时是静音的，用户若没插耳机就只剩振动能提醒到。
     */
    private fun startVibrationIfNeeded() {
        if (settings.routeMode != AudioRouteMode.HEADSET_ONLY) return
        val v = obtainVibrator() ?: return
        // 没有振动马达的设备（部分平板 / 模拟器）直接跳过，别指望它。
        if (!runCatching { v.hasVibrator() }.getOrDefault(false)) return
        vibrator = v
        runCatching {
            // repeat=0 ⟺ 从波形下标 0 开始无限循环（唤醒 + 歇 800ms 的节律）。
            // ⚠️ 部分 OEM ROM（含 ColorOS）会忽略 createWaveform 的 repeat 参数 →
            //    全程 runCatching 包裹，失败只记日志，绝不让闹钟因振动失败而崩。
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 800L, 800L), 0))
        }.onFailure {
            DiagLog.append(this, "ALARM_VIBRATE", "振动启动失败: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 取 Vibrator：API 31+ 走 [VibratorManager]，26-30 走旧的 `VIBRATOR_SERVICE`。 */
    private fun obtainVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { getSystemService(VibratorManager::class.java)?.defaultVibrator }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            runCatching { getSystemService(VIBRATOR_SERVICE) as? Vibrator }.getOrNull()
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
        // 守卫提到最前面：服务已销毁（ringing=false）后任何迟到的路由回调都必须**零行为**返回，
        // 不更新 routeSummary、不写 DiagLog —— 否则服务销毁后仍会落日志，噪音会干扰
        // 「心跳是否复活」的验证（本应有且仅有服务存活期间才有日志）。
        if (!ringing) return
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
            // B4：抑制**后续更新**的重复提醒（振动）。
            //
            // 为什么必须有：渠道建时 enableVibration(true)；而 onRouteSummary 每次路由变化都会
            // 用同一 NOTIF_ID notify() 刷新通知。同 id 通知「内容变化 + 未设 setOnlyAlertOnce(true)」
            // 时，系统会把这次更新当成一次新提醒 → 再次振动。静音抖动场景下路由每 5 秒刷一次，
            // 于是退化成「每 5 秒震一下」，还与「只走耳机」档的自有持续振动叠加。
            //
            // 语义边界（务必别记反）：setOnlyAlertOnce(true) 只压后续更新，**首次**显示该通知时
            // 仍会正常提醒。对闹钟正是想要的 —— 响铃那一刻该提醒就提醒，之后的状态刷新不该再响。
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            // F2 全屏提醒：复用上面的 contentIntent，不新建第二个 PendingIntent。
            //
            // 语义：这是「锁屏 / 息屏 / AOD 时直接拉起响铃页」的请求。是否真的全屏由系统决定 ——
            // Android 14+ 行为矩阵（AOSP）：FSI 权限已授予时，**亮屏且已解锁**只弹 HUN 不进全屏；
            // 锁屏 / 息屏 / AOD 才启动全屏。亮屏已解锁时靠通知本身兜底（用户点通知即进本页）。
            // 第二个参数 true = 即使通知未被展开也要触发（闹钟场景必须如此）。
            .setFullScreenIntent(contentIntent, true)
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

        /** 响铃结束时（本服务 onDestroy）发出的**同进程**广播，用于让响铃页自行关闭。 */
        const val ACTION_RING_STOPPED = "com.yanfei.r90alarm.action.RING_STOPPED"

        /**
         * 「当前是否正在响铃」的进程内镜像，供 [RingActivity] 查询。
         *
         * 为什么不让响铃页去问系统：响铃服务与响铃页**跑在同一个进程**里，
         * 一个 volatile 标志就够，且没有时序歧义 —— 它为 false 就一定没有在响的闹钟。
         * 换成 `getRunningServices` / 试探性 `bindService` 反而更重、语义更含糊。
         *
         * 写入点只有两处，且都在 [AlarmPlaybackService] 内部：起播置 true、销毁置 false。
         */
        @Volatile
        var isRinging: Boolean = false
            private set

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
