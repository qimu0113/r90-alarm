package com.yanfei.r90alarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yanfei.r90alarm.R90Theme
import com.yanfei.r90alarm.settings.AppSettings
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 标签缺失时的回落值。 */
private const val DEFAULT_RING_LABEL = "R90 闹钟"

/**
 * 响铃页。
 *
 * 用户的核心要求是「到点提醒，**有醒目的关闭方法**」——
 * 所以这里只放两件大事：一个占满宽度、够大的「关闭」，和一个次要的「贪睡」。
 * 大字时间 + 闹钟标签，躺着一眼能看清、单手能按到。
 *
 * 本页有两个进入方式：
 *   1. 用户点击响铃通知（其 contentIntent）进入；
 *   2. 系统在全屏意图（`USE_FULL_SCREEN_INTENT`）下直接拉起本页。
 *
 * ⚠️ Android 14+ framework 行为：**亮屏且已解锁**时，即使 FSI 权限已授予，
 * 系统也**不会**启动全屏页，只弹 HUN（悬浮通知）—— 此时靠通知悬浮提示兜底；
 * 锁屏 / 息屏 / AOD 下才会启动全屏。这是系统行为，不是本页能控制的。
 * 亮屏 / 锁屏可见另靠 [showOverLockscreen] 的窗口标志实现。
 */
class RingActivity : ComponentActivity() {

    /**
     * 监听「响铃已结束」的同进程广播，收到就自行关闭。
     *
     * 为什么需要它（真机复现的缺陷）：关闭闹钟共有三条路径 ——
     *   ① 本页的「关闭 / 贪睡」按钮 → 本页自己 `finish()`，本来就对；
     *   ② 通知上的「关闭 / 贪睡」按钮；
     *   ③ 最长响铃时长到点后的自动停。
     * ②③ 只会结束 [AlarmPlaybackService]，**不会结束本 Activity**。
     * 于是出现「闹钟已经不响了、服务也没了，但全屏响铃页仍留在屏幕上」——
     * 锁屏 / 息屏场景下尤其刺眼：一屏大字时间加一个按了没反应的「关闭」。
     * 这条广播把三条路径统一起来：任一路径结束响铃，本页都跟着退场。
     */
    private val ringStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // 双条件：action 对上，且此刻确实没有在响铃。
            //
            // 第二个条件不是多余的 —— 它同时挡掉 API < 33 上的伪造广播面：
            // 低版本 registerReceiver 不带 flag 时，动态接收器默认可被任意 App 投递，
            // 第三方猜到这条 action 就能提前关掉响铃页。而本 App 发这条广播的时机
            // 固定在 AlarmPlaybackService.onDestroy()，那一刻 isRinging 已被置 false，
            // 于是「isRinging == false」对合法发送方恒成立、对伪造方恰好挡住
            // （闹钟还在响时 isRinging 为 true，伪造广播退化成 no-op）。
            if (intent?.action == AlarmPlaybackService.ACTION_RING_STOPPED &&
                !AlarmPlaybackService.isRinging
            ) {
                finish()
            }
        }
    }

    /**
     * 响铃标签。做成可变状态而不是 onCreate 里的局部 val ——
     * 本页是 `singleTop`，可能被「复用」而不是重建（见 [onNewIntent]）。
     */
    private val label = mutableStateOf(DEFAULT_RING_LABEL)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // P3-1：接收器注册窗口从 onStart/onStop 挪到 onCreate/onDestroy。
        //
        // 放在 onStart/onStop 时会漏一条路径：停铃若发生在页面退到后台**之后**，
        // 广播到达时接收器已注销；而本页在清单里是 excludeFromRecents + 独立
        // taskAffinity，用户没法从最近任务把它捞回来，onResume 兜底也没有执行机会
        // —— 页面以「内存里不可见的孤儿」形态留着。注册在整个生命周期内保持有效后，
        // 后台停铃的广播能直接 finish 这个不可见页面。
        val filter = IntentFilter(AlarmPlaybackService.ACTION_RING_STOPPED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ringStoppedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(ringStoppedReceiver, filter)
        }

        showOverLockscreen()

        label.value = readLabel(intent)
        val snoozeMinutes = AppSettings.load(this).snoozeMinutes

        setContent {
            R90Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RingScreen(
                        label = label.value,
                        snoozeMinutes = snoozeMinutes,
                        onDismiss = {
                            AlarmPlaybackService.dismiss(this, source = "activity")
                            finish()
                        },
                        onSnooze = {
                            AlarmPlaybackService.snooze(this, label.value, source = "activity")
                            finish()
                        },
                    )
                }
            }
        }
    }

    /**
     * P3-3：本页是 `singleTop`，被复用时走的是这里而不是 [onCreate]。
     *
     * 触发场景：上次响铃留下个孤儿页面，下一次闹钟到点经 singleTop 复用它 ——
     * 不刷新标签的话，用户看到的是**上一个**闹钟的名字。
     */
    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        label.value = readLabel(newIntent)
    }

    override fun onDestroy() {
        // 与 onCreate 的注册配对。runCatching 兜住「注册当时就失败了」的极罕见情况，
        // 别让关闭路径因为注销抛异常。
        runCatching { unregisterReceiver(ringStoppedReceiver) }
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        // 退到后台的那一刻再问一次：已经不响铃了就就地退场。
        //
        // 有了 onCreate/onDestroy 的注册后这条已属冗余（广播照样能 finish 这个
        // 不可见页面），留着只是让退出更及时 —— 不必等异步广播投递那一跳。
        if (!AlarmPlaybackService.isRinging) finish()
    }

    override fun onResume() {
        super.onResume()
        // 兜底：万一广播投递丢在了注册之前（例如系统在服务刚销毁后才把本页拉起），
        // 这里再问一次。没有在响铃却停在响铃页，是没有意义的页面。
        if (!AlarmPlaybackService.isRinging) finish()
    }

    /** 从 intent 里取标签，空值回落默认。 */
    private fun readLabel(from: Intent?): String =
        from?.getStringExtra(AlarmPlaybackService.EXTRA_LABEL).orEmpty()
            .ifBlank { DEFAULT_RING_LABEL }

    /**
     * 让响铃页在锁屏上直接可见、并点亮屏幕。
     *
     * `setShowWhenLocked` / `setTurnScreenOn` 是 API 27（O_MR1）才有的便捷方法；
     * API 26 退回到等价的窗口标志（`FLAG_SHOW_WHEN_LOCKED` 等，自 API 1 起可用）。
     */
    private fun showOverLockscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }
}

@Composable
private fun RingScreen(
    label: String,
    snoozeMinutes: Int,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
) {
    // P3-3：时钟必须自己走。
    //
    // 原来是在组合期求值一次，而最长响铃可以到 15 分钟 —— 用户半夜被叫醒、
    // 躺了一会儿再看屏幕，大字时间还停在刚响那一刻，比实际慢好几分钟。
    var clock by remember { mutableStateOf(clockText()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            clock = clockText()
            // 对齐到整秒边界再睡，避免「求值 + 组合 + 调度」的耗时累积成漂移。
            delay(1_000L - (System.currentTimeMillis() % 1_000L))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = clock,
            fontSize = 72.sp,
            fontWeight = FontWeight.Light,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(56.dp))

        // 醒目的关闭：占满宽度、够高的主按钮，拇指盲按也能中
        Button(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
        ) {
            Text("关闭", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = onSnooze,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text("贪睡 $snoozeMinutes 分钟", fontSize = 17.sp)
        }
    }
}

/** 大字时钟的当前文本。24 小时制 —— 半夜被叫醒时「AM/PM」只会多添一层辨识成本。 */
private fun clockText(): String =
    LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
