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

    /** 当前是否处于「静音」态（只走耳机档且无耳机）。 */
    @Volatile
    var isMuted: Boolean = false
        private set

    private var lastSummary: String = "未初始化"

    /** 最近一次路由摘要，供通知文案使用。 */
    val summary: String get() = lastSummary

    /** 起播后调用：应用一次路由并开始监听热插拔。 */
    fun start() {
        if (started) return
        started = true
        reapply("start")
        registerDeviceCallback()
        registerRoutingListener()
    }

    /** 收尾调用：注销监听。放在 onDestroy。 */
    fun stop() {
        if (!started) return
        started = false
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

        when (decision) {
            is RouteDecision.Silent -> {
                applyMute(true)
                report("静音·无可用耳机(绝不外放)")
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
    }

    /** 静音开关：把播放音量归零 / 复位。 */
    private fun applyMute(mute: Boolean) {
        isMuted = mute
        val v = if (mute) 0f else 1f
        runCatching { player.setVolume(v, v) }
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
     * 只用于**回读记录**，不在这里重设首选设备 —— 否则与 setPreferredDevice 回环。
     */
    private fun registerRoutingListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val listener = AudioRouting.OnRoutingChangedListener { _ ->
            readback()?.let { report("路由变化→${label(it)}") }
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
