package com.yanfei.r90alarm.alarm

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

/**
 * 响铃页。
 *
 * 用户的核心要求是「到点提醒，**有醒目的关闭方法**」——
 * 所以这里只放两件大事：一个占满宽度、够大的「关闭」，和一个次要的「贪睡」。
 * 大字时间 + 闹钟标签，躺着一眼能看清、单手能按到。
 *
 * ⚠️ 刻意**不用** `USE_FULL_SCREEN_INTENT`（用户明确拒绝）。
 * 本页是普通 Activity，亮屏 / 锁屏可见靠 [showOverLockscreen] 的窗口标志实现。
 */
class RingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockscreen()

        val label = intent.getStringExtra(AlarmPlaybackService.EXTRA_LABEL)
            .orEmpty()
            .ifBlank { "R90 闹钟" }
        val snoozeMinutes = AppSettings.load(this).snoozeMinutes

        setContent {
            R90Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RingScreen(
                        label = label,
                        snoozeMinutes = snoozeMinutes,
                        onDismiss = {
                            AlarmPlaybackService.dismiss(this, source = "activity")
                            finish()
                        },
                        onSnooze = {
                            AlarmPlaybackService.snooze(this, label, source = "activity")
                            finish()
                        },
                    )
                }
            }
        }
    }

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
    val clock = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

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
