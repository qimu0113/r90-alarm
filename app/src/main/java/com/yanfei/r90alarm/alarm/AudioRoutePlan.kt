package com.yanfei.r90alarm.alarm

/**
 * 音频路由的**纯逻辑**部分：档位定义 + 设备分类 + 降级决策。
 *
 * 这个文件**刻意不 import 任何 android.*** ——
 * 这样「耳机优先 / 只走耳机 / 强制外放」的决策规则可以在纯 JVM 上跑单元测试，
 * 不需要模拟器、不需要真机。设备枚举与 MediaPlayer 交互留在 [AlarmAudioRouter]。
 *
 * 用户不可动摇的要求（原话）：
 *   「到点提醒，有醒目的关闭方法，最重要的是可以在耳机内播放而不强制调用手机扬声器」。
 * 所以默认档是 [AudioRouteMode.PREFER_HEADSET]：插着耳机就走耳机，拔了就回落扬声器，
 * 既不漏响，也不会在插着耳机时把声音怼到手机外放上。
 */

/**
 * 路由档位。三档都在设置里可选。
 *
 * | 档位 | 行为 |
 * |---|---|
 * | [PREFER_HEADSET] | 默认。耳机优先；耳机断开自动回落扬声器。保响。 |
 * | [HEADSET_ONLY]   | 只走耳机，绝不外放。无耳机时**静音**（不出声）。 |
 * | [SPEAKER]        | 强制外放。 |
 */
enum class AudioRouteMode {
    PREFER_HEADSET,
    HEADSET_ONLY,
    SPEAKER;

    companion object {
        /** 默认档：耳机优先。 */
        val DEFAULT: AudioRouteMode = PREFER_HEADSET

        /** 从持久化字符串还原。不认识的值一律回落默认档，绝不崩。 */
        fun fromStorage(value: String?): AudioRouteMode =
            values().firstOrNull { it.name == value } ?: DEFAULT
    }
}

/**
 * 与 `android.media.AudioDeviceInfo.TYPE_*` 一一对应的**逻辑设备类别**。
 *
 * 为什么不直接用 `AudioDeviceInfo`：那是 Android 类，纯逻辑测试里构造不出来。
 * 这里只保留「本 App 的决策真正关心的那些类型」，其余统统归到 [UNKNOWN]。
 */
enum class RouteDevice {
    WIRED_HEADSET,
    WIRED_HEADPHONES,
    USB_HEADSET,
    USB_DEVICE,
    USB_ACCESSORY,
    BLE_HEADSET,
    BLE_SPEAKER,
    BLUETOOTH_A2DP,
    BLUETOOTH_SCO,
    HEARING_AID,
    BUILTIN_SPEAKER,
    BUILTIN_EARPIECE,
    TELEPHONY,
    UNKNOWN;

    /**
     * 是不是「戴在耳朵边」的设备 —— 也就是本 App 语义下真正的「耳机」。
     *
     * 注意 USB_DEVICE / USB_ACCESSORY / BLE_SPEAKER **不算**耳机：
     * 它们通常是外接音箱 / 底座 / 转接器，把闹钟怼过去不符合「耳机优先」的直觉。
     */
    val isHeadgear: Boolean
        get() = when (this) {
            WIRED_HEADSET,
            WIRED_HEADPHONES,
            USB_HEADSET,
            BLE_HEADSET,
            BLUETOOTH_A2DP,
            BLUETOOTH_SCO,
            HEARING_AID -> true

            else -> false
        }

    /** 是不是手机自带的扬声器（唯一真正的「外放」）。 */
    val isBuiltinSpeaker: Boolean get() = this == BUILTIN_SPEAKER
}

/**
 * 一次路由决策的结果。
 *
 * 纯数据，不含任何设备对象 —— 由 [AlarmAudioRouter] 把 [Play.targets] 里的
 * 逻辑类别翻译回真实的 `AudioDeviceInfo` 再 `setPreferredDevice`。
 */
sealed interface RouteDecision {

    /**
     * 出声播放。
     *
     * @param targets 按优先级排序的首选设备链（第 1 个优先）。空列表 = 不指定首选设备，
     *                交给系统按默认路由播（仅当连枚举都拿不到设备时的兜底）。
     * @param speakerFallback 当 [targets] 全都不可用时，是否允许回落内置扬声器。
     *                        [AudioRouteMode.HEADSET_ONLY] 下恒为 false。
     */
    data class Play(
        val targets: List<RouteDevice>,
        val speakerFallback: Boolean,
    ) : RouteDecision

    /**
     * 不出声。当前无可用耳机、且档位要求「绝不外放」时命中。
     *
     * 语义澄清：静音**不是**停掉播放 —— 播放仍在跑（音量归零），
     * 这样耳机一旦重新插上就能立刻出声，同时绝不外放。
     */
    data object Silent : RouteDecision
}

/**
 * 路由决策器。无状态，纯函数。
 */
object AudioRoutePlan {

    /**
     * 耳机内部优先级：数字越小越优先。
     *
     * 排序理由：
     *   有线 > USB 耳机 > 助听器 > BLE 耳机 > 蓝牙 A2DP > 蓝牙 SCO。
     * 有线的连接最确定、无编解码延迟，插着就一定在；蓝牙则有连接态与延迟的不确定性。
     * 同插有线和蓝牙时，优先走有线是更符直觉、也更容易被信任的选择。
     */
    private fun headgearRank(d: RouteDevice): Int = when (d) {
        RouteDevice.WIRED_HEADSET -> 0
        RouteDevice.WIRED_HEADPHONES -> 1
        RouteDevice.USB_HEADSET -> 2
        RouteDevice.HEARING_AID -> 3
        RouteDevice.BLE_HEADSET -> 4
        RouteDevice.BLUETOOTH_A2DP -> 5
        RouteDevice.BLUETOOTH_SCO -> 6
        else -> 100
    }

    /**
     * 核心决策。
     *
     * @param mode    路由档
     * @param devices 当前**已连接**的输出设备类别（顺序无关，内部会去重 + 排序）
     */
    fun decide(mode: AudioRouteMode, devices: List<RouteDevice>): RouteDecision {
        // 去重：同一类别可能有多个实例（如两副蓝牙耳机），对决策无意义
        val unique = devices.distinct()

        val headgear = unique.filter { it.isHeadgear }.sortedBy { headgearRank(it) }
        val speaker = unique.filter { it.isBuiltinSpeaker }

        return when (mode) {
            AudioRouteMode.SPEAKER ->
                // 强制外放：目标就是内置扬声器。拿不到就写死 BUILTIN_SPEAKER 让上层尝试。
                RouteDecision.Play(
                    targets = speaker.ifEmpty { listOf(RouteDevice.BUILTIN_SPEAKER) },
                    speakerFallback = true,
                )

            AudioRouteMode.PREFER_HEADSET -> {
                // 耳机优先 → 回落扬声器。刻意不把 USB_DEVICE/BLE_SPEAKER 这类外接音箱
                // 塞进候选，免得闹钟突然从客厅音箱里炸出来。
                val chain = headgear + speaker
                if (chain.isEmpty()) {
                    // 连扬声器都没枚举到（极罕见）——不指定首选，交给系统默认，绝不静音
                    RouteDecision.Play(targets = emptyList(), speakerFallback = true)
                } else {
                    RouteDecision.Play(targets = chain, speakerFallback = true)
                }
            }

            AudioRouteMode.HEADSET_ONLY ->
                // 绝不外放：有耳机就只走耳机；没有就静音。
                if (headgear.isEmpty()) {
                    RouteDecision.Silent
                } else {
                    RouteDecision.Play(targets = headgear, speakerFallback = false)
                }
        }
    }

    /** 这次决策是否要求静音。 */
    fun shouldMute(decision: RouteDecision): Boolean = decision is RouteDecision.Silent
}
