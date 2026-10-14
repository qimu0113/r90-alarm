package com.yanfei.r90alarm.alarm

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** 自愈心跳间隔：静音期间每 5 秒重算一次路由，耳机回来就解除静音。 */
private const val RETRY_INTERVAL_MILLIS = 5_000L

/**
 * `setPreferredDevice` 之后的宽限窗口。
 *
 * 设首选设备后系统会异步回调一次 [AudioRouting.OnRoutingChangedListener]，
 * 但此刻 `MediaPlayer.routedDevice` 可能仍指向旧设备（异步未生效）。
 * 若旧值是内置扬声器，会被误判成「漏音」而错误静音 —— 故用这段窗口放行。
 */
private const val APPLY_GRACE_MILLIS = 300L

/**
 * 把 [AudioRoutePlan] 的纯决策落到真实的 `MediaPlayer` 上。
 *
 * 职责：
 *   1. 枚举当前输出设备，映射成 [RouteDevice]
 *   2. 交给 [AudioRoutePlan.decide] 拿决策
 *   3. 调 `MediaPlayer.setPreferredDevice` 指定首选设备（或静音）
 *   4. 回读 `MediaPlayer.routedDevice` 记录实际落点
 *   5. 监听热插拔：耳机拔出/插入时立刻重算并应用（默认档拔出回落扬声器，只走耳机档拔出卖声）
 *
 * ⚠️ API 等级（本文件最容易踩的坑，逐条守卫）：
 *   - `AudioRouting` **接口**的方法（`setPreferredDevice` / `getRoutedDevice` /
 *     `addOnRoutingChangedListener`）声明于 API 24；
 *   - 但 `MediaPlayer` **实现** `AudioRouting` 是 **API 28** 起。
 *   本项目 minSdk=26，所以在 MediaPlayer 实例上调用这些方法**必须**加
 *   `Build.VERSION.SDK_INT >= 28` 守卫，否则 Android 8.0/8.1（26/27）上抛
 *   `NoSuchMethodError` 崩溃。
 *   - `MediaPlayer.getRoutedDevices()`（复数）是 API 36。本项目 compileSdk=35，
 *     引用它**编不过**，故本文件只用单数的 `getRoutedDevice()`（API 28，返回值即当前生效设备）。
 */
class AlarmAudioRouter(
    context: Context,
    private val player: MediaPlayer,
    private val mode: AudioRouteMode,
    private val onSummary: (String) -> Unit = {},
) {

    private val appContext: Context = context.applicationContext
    private val audioManager: AudioManager? =
        appContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var deviceCallback: AudioDeviceCallback? = null
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null

    private var started = false

    /** 最近一次决策，供路由回调判定「是否漏音」（用决策语义，不用 mode 硬判）。 */
    private var lastDecision: RouteDecision? = null

    /** `setPreferredDevice` 后的宽限截止时刻（`elapsedRealtime`）。窗口内路由变化只记录不拦截。 */
    private var graceUntilMillis: Long = 0L

    /** 静音起点（`elapsedRealtime`）。未静音时为 0。 */
    private var mutedSinceMillis: Long = 0L

    /** 自愈心跳的重试序号，仅用于日志/文案区分。 */
    private var retryCount: Int = 0

    /** 当前是否处于「静音」态（只走耳机档且无耳机）。 */
    @Volatile
    var isMuted: Boolean = false
        private set

    /**
     * 已静音的持续时长（毫秒）；未静音时为 0。
     *
     * 必须用 [SystemClock.elapsedRealtime]：它随系统运行单调递增，不受用户手动改系统时间影响。
     * 用 `currentTimeMillis` 的话，用户中途改了时间就能让「已静音多久」算成负数或暴涨。
     */
    val mutedDurationMillis: Long
        get() {
            val since = mutedSinceMillis
            if (!isMuted || since == 0L) return 0L
            return (SystemClock.elapsedRealtime() - since).coerceAtLeast(0L)
        }

    private var lastSummary: String = "未初始化"

    /** 最近一次路由摘要，供通知文案使用。 */
    val summary: String get() = lastSummary

    /**
     * 自愈心跳。
     *
     * 为什么需要它：路由恢复原本**完全依赖系统回调**（[AudioDeviceCallback] /
     * [AudioRouting.OnRoutingChangedListener]），实测这两个回调在 bug 现场会隔很久才来一次
     * （最长记录 85 秒），中间闹钟哑着不会自愈。这里用定时重算兜底。
     *
     * 只在 `isMuted == true` 时运行 —— 非静音时系统回调够用，跑心跳纯属白耗电。
     */
    private val retryTick = object : Runnable {
        override fun run() {
            // `!started` ⟺ 已 stop()（服务已销毁）；`!isMuted` ⟺ 已解除静音。
            // 两者任一成立都不该继续跑心跳 —— 尤其 `started` 这道，堵的是
            // 「迟到路由回调在 stop() 之后把心跳复活」那个时序窗口（见 startHeartbeat 注释）。
            if (!started || !isMuted) return
            retryCount += 1
            reapply("retry#$retryCount")
            // reapply 可能已解除静音（耳机回来了）→ 不再续期，心跳自然停。
            if (isMuted) {
                mainHandler.postDelayed(this, RETRY_INTERVAL_MILLIS)
            }
        }
    }

    /** 起播后调用：应用一次路由并开始监听热插拔。 */
    fun start() {
        if (started) return
        started = true
        reapply("start")
        registerDeviceCallback()
        registerRoutingListener()
    }

    /** 收尾调用：注销监听 + 停心跳。放在 onDestroy。 */
    fun stop() {
        if (!started) return
        started = false
        // ⚠️ 必须先停心跳：否则服务销毁后 `postDelayed` 的回调还会在主线程跑，
        //    既泄漏 Handler/实例，又可能在 player 已 release 后 setVolume 而崩溃。
        stopHeartbeat()
        unregisterDeviceCallback()
        unregisterRoutingListener()
    }

    // ------------------------------------------------------------------
    // 决策 → 应用
    // ------------------------------------------------------------------

    /**
     * 重新枚举 → 决策 → 应用。
     *
     * 这是「设备变化」的唯一入口。刻意不在 `onRoutingChanged` 里调用它 ——
     * 那会和 `setPreferredDevice` 形成回环（设了首选 → 路由变化 → 再设 → ...）。
     * `onRoutingChanged` 只回读不重设。
     */
    private fun reapply(trigger: String) {
        val entries = enumerate()
        val decision = AudioRoutePlan.decide(mode, entries.map { it.kind })
        // 存下最近一次决策，供 onRoutingChanged 用「决策语义」判漏音
        // （只看 mode == HEADSET_ONLY 是硬编码，语义上不等于「绝不外放」）。
        lastDecision = decision

        when (decision) {
            is RouteDecision.Silent -> {
                applyMute(true)
                report(RouteStatusText.muted(retryCount, mutedDurationMillis))
            }

            is RouteDecision.Play -> {
                applyMute(false)
                val target = decision.targets.firstNotNullOfOrNull { want ->
                    entries.firstOrNull { it.kind == want }?.info
                }
                if (target != null) applyPreferred(target)
                report(describePlay(target, decision))
            }
        }
    }

    private fun describePlay(target: AudioDeviceInfo?, decision: RouteDecision.Play): String {
        val actual = readback()
        return when {
            target != null && actual != null -> "目标=${label(target)} 实际=${label(actual)}"
            target != null -> "目标=${label(target)}"
            actual != null -> "系统默认→${label(actual)}"
            else -> "系统默认路由"
        }
    }

    /** 指定首选输出设备（API 28+）。 */
    private fun applyPreferred(info: AudioDeviceInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching { player.setPreferredDevice(info) }
        // 开一段宽限窗口：设首选后系统必然异步回调一次路由变化，此刻 readback 可能仍是旧设备。
        // 若旧值是扬声器会被误判成漏音，故窗口内的路由变化事件不触发拦截，只记录。
        graceUntilMillis = SystemClock.elapsedRealtime() + APPLY_GRACE_MILLIS
    }

    /**
     * 静音开关：把播放音量归零 / 复位，并挂上自愈心跳的启停。
     *
     * 语义澄清：静音**不是**停播放 —— 播放仍在跑（音量归零），耳机一旦回来就能立刻出声。
     */
    private fun applyMute(mute: Boolean) {
        val wasMuted = isMuted
        when {
            // 进入静音（上升沿）：记录起点 + 启动心跳。
            // 只在上升沿记起点，否则心跳每 5 秒重算时会把时长反复清零，「已静音多久」永远不增长。
            mute && !wasMuted -> {
                mutedSinceMillis = SystemClock.elapsedRealtime()
                retryCount = 0
                startHeartbeat()
            }
            // 解除静音（下降沿）：立刻停心跳 + 清计时，交给系统回调即可。
            !mute && wasMuted -> {
                stopHeartbeat()
                retryCount = 0
                mutedSinceMillis = 0L
            }
        }
        isMuted = mute
        val v = if (mute) 0f else 1f
        runCatching { player.setVolume(v, v) }
    }

    /**
     * 启动自愈心跳（幂等：先清后挂）。
     *
     * ⚠️ `if (!started) return` 是**必需护栏**，不是防御性冗余：
     *   [stop] 里的 `unregisterRoutingListener()` 只能取消「尚未触发」的监听，
     *   **取消不了已经 post 进 mainHandler 队列、还没执行的 Runnable**（Handler 固有语义）。
     *   于是存在这样的时序窗口：
     *     路由回调已入队 → 用户点关闭 → stop()（started=false、心跳停）
     *     → 迟到回调执行 → readback 仍拿到扬声器 → applyMute(true) 上升沿 → 本方法。
     *   没有这道护栏，心跳会在服务销毁后复活并无限自续
     *   （后果：Handler 泄漏 + 已停的通知被反复重发）。
     */
    private fun startHeartbeat() {
        if (!started) return
        mainHandler.removeCallbacks(retryTick)
        mainHandler.postDelayed(retryTick, RETRY_INTERVAL_MILLIS)
    }

    /** 停掉自愈心跳。 */
    private fun stopHeartbeat() {
        mainHandler.removeCallbacks(retryTick)
    }

    /**
     * 回读当前生效的路由设备。
     *
     * API 36 有复数的 `getRoutedDevices()`，但 compileSdk=35 下引用它编不过；
     * 单数的 `getRoutedDevice()` 返回当前生效设备，对我们（一次只指定一个首选）足够。
     */
    fun readback(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching { player.routedDevice }.getOrNull()
    }

    // ------------------------------------------------------------------
    // 设备枚举
    // ------------------------------------------------------------------

    private data class Entry(val info: AudioDeviceInfo, val kind: RouteDevice)

    private fun enumerate(): List<Entry> {
        val am = audioManager ?: return emptyList()
        val infos = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }.getOrDefault(emptyArray())
        return infos.map { Entry(it, mapType(it.type)) }
    }

    // ------------------------------------------------------------------
    // 热插拔监听
    // ------------------------------------------------------------------

    private fun registerDeviceCallback() {
        val am = audioManager ?: return
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                reapply("added")
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                reapply("removed")
            }
        }
        deviceCallback = cb
        // remove 回调（API 23 起，minSdk 26 恒定可用）
        runCatching { am.registerAudioDeviceCallback(cb, mainHandler) }
    }

    private fun unregisterDeviceCallback() {
        val am = audioManager ?: return
        deviceCallback?.let { cb -> runCatching { am.unregisterAudioDeviceCallback(cb) } }
        deviceCallback = null
    }

    /**
     * MediaPlayer 的路由变化监听（API 28+）。
     *
     * ⛔ 防回环铁律：此回调里**只允许做「收紧」动作**（跑扬声器 → 静音），
     * **绝不允许** `setPreferredDevice` / [applyPreferred]（重设首选）。
     * 反方向（耳机回来 → 解除静音）由 5 秒自愈心跳负责。
     * 违反这条会形成 `setPreferredDevice → 路由变化 → 再 setPreferredDevice` 死循环。
     *
     * 职责分离：**回调只收紧，心跳只放松。**
     */
    private fun registerRoutingListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val listener = AudioRouting.OnRoutingChangedListener { _ ->
            val actual = readback() ?: return@OnRoutingChangedListener
            val actualKind = mapType(actual.type)

            val decision = lastDecision
            val withinGrace = SystemClock.elapsedRealtime() < graceUntilMillis
            // 「最近一次决策要求只走耳机」（Play + speakerFallback=false 即「绝不外放」档），
            // 但实际却落到了内置扬声器 → 漏音，立刻收紧。
            val leaksToSpeaker = decision is RouteDecision.Play &&
                !decision.speakerFallback &&
                actualKind == RouteDevice.BUILTIN_SPEAKER

            when {
                withinGrace -> report("路由变化→${label(actual)}(宽限)")
                leaksToSpeaker -> {
                    applyMute(true)
                    report(RouteStatusText.leakBlocked())
                }
                else -> report("路由变化→${label(actual)}")
            }
        }
        routingListener = listener
        runCatching { player.addOnRoutingChangedListener(listener, mainHandler) }
    }

    private fun unregisterRoutingListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        routingListener?.let { l ->
            runCatching { player.removeOnRoutingChangedListener(l) }
        }
        routingListener = null
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun report(text: String) {
        lastSummary = text
        onSummary(text)
    }

    private fun label(info: AudioDeviceInfo): String = mapType(info.type).name

    /** 把 `AudioDeviceInfo.TYPE_*` 映射为逻辑类别。 */
    private fun mapType(type: Int): RouteDevice = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> RouteDevice.WIRED_HEADSET
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> RouteDevice.WIRED_HEADPHONES
        AudioDeviceInfo.TYPE_USB_HEADSET -> RouteDevice.USB_HEADSET
        AudioDeviceInfo.TYPE_USB_DEVICE -> RouteDevice.USB_DEVICE
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> RouteDevice.USB_ACCESSORY
        AudioDeviceInfo.TYPE_BLE_HEADSET -> RouteDevice.BLE_HEADSET
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> RouteDevice.BLE_SPEAKER
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> RouteDevice.BLUETOOTH_A2DP
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> RouteDevice.BLUETOOTH_SCO
        AudioDeviceInfo.TYPE_HEARING_AID -> RouteDevice.HEARING_AID
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> RouteDevice.BUILTIN_SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> RouteDevice.BUILTIN_EARPIECE
        AudioDeviceInfo.TYPE_TELEPHONY -> RouteDevice.TELEPHONY
        else -> RouteDevice.UNKNOWN
    }
}
