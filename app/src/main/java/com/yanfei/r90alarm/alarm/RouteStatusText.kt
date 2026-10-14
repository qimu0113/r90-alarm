package com.yanfei.r90alarm.alarm

/**
 * 路由状态文案的**纯逻辑**部分（无任何 `android.*` / `org.json` 依赖，可跑 JVM 单测）。
 *
 * 为什么单独抽出来：
 *   `AlarmAudioRouter` 里要用 `Handler` / `SystemClock` / `MediaPlayer`，整体没法在纯 JVM 上测；
 *   但「静音文案什么时候升级成『请检查耳机』」「重试几次要不要显示」这类判定是纯字符串逻辑，
 *   抽到这里就能用单测把边界钉死（比如恰好 60 秒、未重试、重试 N 次）。
 */
object RouteStatusText {

    /** 静音持续到该时长后，文案从「等待耳机」升级为「请检查耳机」。 */
    const val ESCALATE_AFTER_MILLIS: Long = 60_000L

    /**
     * 静音中的文案。
     *
     * @param retryCount 自愈心跳的重试次数（0 = 刚进入静音、尚未重试）
     * @param mutedDurationMillis 已静音时长（毫秒，由 `elapsedRealtime` 计算）
     */
    fun muted(retryCount: Int, mutedDurationMillis: Long): String = when {
        mutedDurationMillis >= ESCALATE_AFTER_MILLIS ->
            "🔇 静音中·已超 60 秒，请检查耳机"
        retryCount > 0 ->
            "🔇 静音中·等待耳机(重试 ${retryCount} 次)"
        else ->
            "🔇 静音中·等待耳机"
    }

    /** 漏音拦截文案：决策要求只走耳机，但实际落到了内置扬声器，已主动收紧为静音。 */
    fun leakBlocked(): String = "⚠️ 已拦截外放·等待耳机"
}
