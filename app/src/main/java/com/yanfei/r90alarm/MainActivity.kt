package com.yanfei.r90alarm

import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.Settings
import androidx.core.content.ContextCompat
import com.yanfei.r90alarm.alarm.AlarmScheduler
import com.yanfei.r90alarm.alarm.AudioRouteMode
import com.yanfei.r90alarm.alarm.RingtonePickerHelper
import com.yanfei.r90alarm.history.UsageHistory
import com.yanfei.r90alarm.history.UsageRecord
import com.yanfei.r90alarm.settings.AppSettings
import com.yanfei.r90alarm.ui.AboutScreen
import com.yanfei.r90alarm.ui.HistoryScreen
import com.yanfei.r90alarm.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 每次冷启动记一条环境快照。
        // 用途：把后面那些「无界面 / 有界面」的判定归因到具体的机型 + 系统版本；
        // 顺带也能看出 App 是不是被系统杀过（启动次数异常偏多）。
        DiagLog.append(this, "APP_START", DiagLog.envSnapshot(this))

        setContent {
            R90Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    R90App()
                }
            }
        }
    }
}

/**
 * 从任意 Context 向上找宿主 Activity。
 *
 * 为什么不直接 `context as ComponentActivity`：
 * Compose 拿到的 Context 常被 ContextThemeWrapper 包过一层，
 * 直接强转在部分 ROM（尤其带定制主题/多窗口的）上会抛 ClassCastException 崩溃。
 * 沿 ContextWrapper 链往上找是版本无关的稳妥做法。
 */
private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * 秒级心跳。
 *
 * 关键：对齐整秒，而不是死循环 delay(1000)。
 * delay(1000) 会累积误差 —— 每次循环的实际耗时略多于 1000ms，
 * 几小时后秒数跳变会肉眼可见地漂到和系统时间差几秒。
 *
 * 做法：睡「距离下一个整秒还差多少毫秒」，误差永远清零。
 *
 * 另外用 repeatOnLifecycle(RESUMED) 包住：
 * App 退到后台（含熄屏）时停掉心跳。
 * 不然躺下前把 App 放后台，一夜要空醒 2.8 万次，白耗电还容易被厂商省电策略盯上。
 */
@Composable
private fun rememberTickingNow(): State<ZonedDateTime> {
    val now = remember { mutableStateOf(ZonedDateTime.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val current = ZonedDateTime.now()
                now.value = current
                val msToNextSecond = 1000L - (current.nano / 1_000_000L)
                delay(msToNextSecond.coerceIn(1L, 1000L))
            }
        }
    }
    return now
}

/**
 * 顶层页面路由。
 *
 * 只有三个页面：首页（主链路）/ 使用历史 / 关于。
 * 用最朴素的 Int 状态机，而不是 Navigation 库 —— 三个页面不值得引入导航依赖，
 * 与项目「尽量少的外部状态、零新增依赖」的口径一致。
 */
@Composable
private fun R90App() {
    // 0 = 首页；1 = 使用历史；2 = 关于。
    // 用 Int 而不是枚举：rememberSaveable 能直接存取基本类型，跨进程重建不丢。
    var screen by rememberSaveable { mutableStateOf(0) }

    // 子页面按返回键回首页，而不是直接退出 App（否则一次误触就退到桌面）
    BackHandler(enabled = screen != 0) { screen = 0 }

    when (screen) {
        1 -> HistoryScreen(onBack = { screen = 0 })
        2 -> AboutScreen(onBack = { screen = 0 })
        else -> R90Screen(
            onOpenHistory = { screen = 1 },
            onOpenAbout = { screen = 2 },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun R90Screen(
    onOpenHistory: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // 冷启动静默查一次更新（24h 节流）。不弹任何 UI，结果只缓进 UpdateChecker，
    // 供「关于」页展示。走 IO 线程 + 整体 runCatching：无网 / 超时 / 解析失败都不影响主界面。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { UpdateChecker.checkIfDue(context, BuildConfig.VERSION_NAME) }
        }
    }

    // 观测「点击之后本 App 有没有被暂停、暂停多久」——用来间接判断时钟界面弹没弹。
    // 原理与局限见 DiagLog.kt 里 UiProbe 的注释。
    val probe = remember { UiProbe() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> probe.onPause()
                Lifecycle.Event.ON_RESUME -> probe.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val now by rememberTickingNow()
    val is24Hour = remember { DateFormat.is24HourFormat(context) }

    // 顶栏「更多」溢出菜单（使用历史 / 关于）
    var menuOpen by remember { mutableStateOf(false) }

    // 诊断日志面板
    var logOpen by remember { mutableStateOf(false) }

    // ---- 应用内闹钟接线（不动既有系统时钟路径） ----

    // 设置：铃声 / 路由档 / 最长响铃 / 贪睡。存 filesDir/settings.json（零新增依赖）。
    var settings by remember { mutableStateOf(AppSettings.load(context)) }

    // 提醒方式：false = 系统时钟（既有行为）；true = 本机闹钟（前台服务响铃）。
    var inAppMode by rememberSaveable { mutableStateOf(false) }

    // 闹钟设置对话框开关
    var alarmSettingsOpen by remember { mutableStateOf(false) }

    // F3：全屏提醒能力。API 34+ 用户可在系统设置里关闭；false 时首页展示一行静默引导（不弹窗）。
    var fullScreenOk by remember { mutableStateOf(canUseFullScreen(context)) }

    // 每次回到前台重读一次：用户可能刚才去系统设置里开启了它，返回本行应自动消失。
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            fullScreenOk = canUseFullScreen(context)
        }
    }

    // 写入即持久化，避免每处改动都手写 save。
    //
    // 改的是「增量」（对当前磁盘值套用 transform），而不是把内存里的旧快照整份写回 ——
    // 否则会把后台线程（UpdateChecker，IO 线程）刚写入的 lastUpdateCheckMillis
    // 或者 AlarmScheduler 刚写入的 pendingAlarm 一起冲掉。见 AppSettings.update。
    val applySettings: ((AppSettings) -> AppSettings) -> Unit = { transform ->
        settings = AppSettings.update(context, transform)
    }

    // 通知权限（API 33+）：仅声明不够，还需运行时申请，否则通知不出现在抽屉里。
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝也不影响响铃，只影响通知可见性 */ }

    fun ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // App 一打开就申请通知权限。
    //
    // 为什么不能只靠「点击设为本机闹钟」那一处：真机上用户可能没走那个按钮，
    // 或经 adb 广播触发响铃 —— 那样 POST_NOTIFICATIONS 永远是 granted=false，
    // 带「关闭 / 贪睡」按钮的响铃通知根本不显示，用户找不到关闭入口。
    // ensureNotifPermission() 内部有 checkSelfPermission 短路，重复调用无害。
    LaunchedEffect(Unit) { ensureNotifPermission() }

    // 系统铃声选择器返回
    val ringtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            RingtonePickerHelper.parsePickedUri(result.data)?.let { uri ->
                applySettings { it.copy(ringtoneUri = uri.toString()) }
            }
        }
    }

    val ringtoneLabel = remember(settings.ringtoneUri) {
        RingtonePickerHelper.displayName(context, settings.ringtoneUri?.let(Uri::parse))
    }

    // Q3：准备入睡时长可调。默认 15 分钟。
    // 用 rememberSaveable 而不是 remember —— 万一被系统回收重建，选择不会丢。
    var fallAsleepMinutes by rememberSaveable {
        mutableStateOf(R90Calculator.DEFAULT_FALL_ASLEEP_MINUTES)
    }

    // now 每秒变一次，结果也跟着重算。9 行 × 6 次算术，单帧 < 0.1ms，
    // 不需要更细的缓存策略。
    val results = remember(now, fallAsleepMinutes) {
        R90Calculator.calculateAll(now, fallAsleepMinutes)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Level 3 状态：设备上找不到任何能处理 SET_ALARM 的 App。
    // 非 null 时弹出补救对话框。
    var noClockDialogTime by remember { mutableStateOf<String?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("R90 闹钟") },
                actions = {
                    IconButton(onClick = { alarmSettingsOpen = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "闹钟设置")
                    }
                    IconButton(onClick = { logOpen = true }) {
                        Icon(Icons.Filled.Info, contentDescription = "诊断日志")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.menu_more),
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_history)) },
                                onClick = {
                                    menuOpen = false
                                    onOpenHistory()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_about)) },
                                onClick = {
                                    menuOpen = false
                                    onOpenAbout()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ClockHeader(now = now, is24Hour = is24Hour)

            FallAsleepSelector(
                selected = fallAsleepMinutes,
                onSelect = { fallAsleepMinutes = it },
            )

            // 提醒方式切换：默认「系统时钟」= 完全保留既有行为
            AlarmModeSelector(
                inAppMode = inAppMode,
                onSelect = { inAppMode = it },
            )

            // F3：全屏提醒未开启时，静默提示一行（可点跳系统设置）—— 不弹对话框。
            if (!fullScreenOk) {
                FullScreenHintRow(onClick = { openFullScreenIntentSettings(context) })
            }

            Spacer(Modifier.height(4.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(results, key = { it.cycles }) { result ->
                    CycleRow(
                        result = result,
                        is24Hour = is24Hour,
                        onClick = {
                            val timeText = formatTime(result.hour24, result.minute, is24Hour)

                            // ---- 应用内闹钟路径（与既有系统时钟路径互不影响）----
                            if (inAppMode) {
                                val triggerAt = computeTriggerMillis(now, result)
                                ensureNotifPermission()
                                val scheduleResult = AlarmScheduler.schedule(
                                    context, triggerAt, "R90 · ${result.cycles} 周期",
                                )
                                when (scheduleResult) {
                                    AlarmScheduler.Result.Scheduled -> scope.launch {
                                        snackbarHostState.showSnackbar(
                                            "已设置本机闹钟 $timeText",
                                            duration = SnackbarDuration.Short,
                                        )
                                    }

                                    AlarmScheduler.Result.NeedExactAlarmPermission -> {
                                        openExactAlarmSettings(context)
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                "需要「闹钟与提醒」权限，已打开系统设置",
                                                duration = SnackbarDuration.Long,
                                            )
                                        }
                                    }

                                    is AlarmScheduler.Result.Failed -> scope.launch {
                                        snackbarHostState.showSnackbar(
                                            "本机闹钟设置失败：${scheduleResult.reason}",
                                            duration = SnackbarDuration.Long,
                                        )
                                    }
                                }
                                // 记一条使用历史。只有真的排进 AlarmManager 才算成功。
                                UsageHistory.append(
                                    context,
                                    UsageRecord(
                                        epochMillis = System.currentTimeMillis(),
                                        targetHour = result.hour24,
                                        targetMinute = result.minute,
                                        cycles = result.cycles,
                                        fallAsleepMin = fallAsleepMinutes,
                                        mode = UsageRecord.Mode.IN_APP,
                                        success = scheduleResult == AlarmScheduler.Result.Scheduled,
                                    ),
                                )
                                DiagLog.append(
                                    context, "SET_INAPP_ALARM",
                                    "目标=$timeText 周期=${result.cycles} at=$triggerAt",
                                )
                                return@CycleRow
                            }

                            // Activity 拿不到就没法无栈跳转，直接说清原因
                            if (activity == null) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "无法获取界面上下文，请重开 App 再试",
                                        duration = SnackbarDuration.Short,
                                    )
                                }
                                return@CycleRow
                            }

                            // 先架好观测器，再发 Intent —— 顺序不能反，
                            // 否则时钟界面可能在我们开始计时之前就已经起来了。
                            probe.arm()

                            val outcome = SystemAlarmLauncher.setAlarm(
                                activity = activity,
                                hour24 = result.hour24,
                                minute = result.minute,
                            )

                            when (outcome) {
                                is SystemAlarmLauncher.Outcome.SilentRequested ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            "已请求系统时钟设置 $timeText",
                                            duration = SnackbarDuration.Short,
                                        )
                                    }

                                is SystemAlarmLauncher.Outcome.UiFallback ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            "已打开系统时钟，请确认 $timeText",
                                            duration = SnackbarDuration.Short,
                                        )
                                    }

                                is SystemAlarmLauncher.Outcome.NoHandler ->
                                    // 失败必须说清原因 + 给替代方案，不能只弹一句"失败"
                                    noClockDialogTime = timeText
                            }

                            // 记一条使用历史。NoHandler 才是失败；SilentRequested / UiFallback
                            // 都算「已成功把请求交给系统时钟」。
                            UsageHistory.append(
                                context,
                                UsageRecord(
                                    epochMillis = System.currentTimeMillis(),
                                    targetHour = result.hour24,
                                    targetMinute = result.minute,
                                    cycles = result.cycles,
                                    fallAsleepMin = fallAsleepMinutes,
                                    mode = UsageRecord.Mode.SYSTEM_CLOCK,
                                    success = outcome !is SystemAlarmLauncher.Outcome.NoHandler,
                                ),
                            )

                            // 落诊断日志。
                            //
                            // 刻意等 1.5 秒再取快照：时钟界面若真的弹出来，本 App 会在
                            // 这 1.5 秒内被顶到后台（onPause）。太早取会读成"没暂停"。
                            // 这个协程挂在组合作用域上，1.5 秒内用户不会切走，不会被取消。
                            scope.launch {
                                delay(1500)
                                DiagLog.append(
                                    context,
                                    "SET_ALARM",
                                    formatAlarmAttempt(
                                        toAlarmAttempt(
                                            timeText = timeText,
                                            result = result,
                                            fallAsleepMin = fallAsleepMinutes,
                                            outcome = outcome,
                                            snap = probe.snapshot(),
                                        )
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }

        // 找不到系统时钟时的补救对话框
        noClockDialogTime?.let { timeText ->
            NoClockAppDialog(
                timeText = timeText,
                onOpenAlarmList = {
                    val ok = SystemAlarmLauncher.openAlarmList(context)
                    noClockDialogTime = null
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (ok) "已打开系统时钟" else "系统时钟列表也打不开",
                            duration = SnackbarDuration.Short,
                        )
                    }
                },
                onOpenAppSettings = {
                    noClockDialogTime = null
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                "打不开应用管理页",
                                duration = SnackbarDuration.Short,
                            )
                        }
                    }
                },
                onCopyTime = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("R90 起床时间", timeText))
                    noClockDialogTime = null
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "已复制 $timeText，可手动到系统时钟里设",
                            duration = SnackbarDuration.Short,
                        )
                    }
                },
                onDismiss = { noClockDialogTime = null },
            )
        }

        if (logOpen) {
            DiagLogDialog(context = context, onDismiss = { logOpen = false })
        }

        if (alarmSettingsOpen) {
            AlarmSettingsDialog(
                settings = settings,
                ringtoneLabel = ringtoneLabel,
                onPickRingtone = {
                    ringtoneLauncher.launch(
                        RingtonePickerHelper.buildPickerIntent(settings.ringtoneUri?.let(Uri::parse))
                    )
                },
                onRouteChange = { mode -> applySettings { it.copy(routeMode = mode) } },
                onMaxRingChange = { millis -> applySettings { it.copy(maxRingMillis = millis) } },
                onSnoozeChange = { minutes -> applySettings { it.copy(snoozeMinutes = minutes) } },
                onDismiss = { alarmSettingsOpen = false },
            )
        }
    }
}

/**
 * 把一次设置尝试 + 观测快照，摊平成诊断日志需要的那条记录。
 *
 * 单独抽出来是为了让「点击处理」那段保持可读 —— 那里已经有三层
 * `when` 分支了，再塞字段映射会糊成一团。
 */
private fun toAlarmAttempt(
    timeText: String,
    result: R90Calculator.Result,
    fallAsleepMin: Int,
    outcome: SystemAlarmLauncher.Outcome,
    snap: ProbeSnapshot,
): AlarmAttempt {
    val level: Int
    val handler: String?
    val error: String?
    val elapsed: Long

    when (outcome) {
        is SystemAlarmLauncher.Outcome.SilentRequested -> {
            level = 1; handler = outcome.handler; error = null; elapsed = outcome.elapsedMs
        }

        is SystemAlarmLauncher.Outcome.UiFallback -> {
            level = 2; handler = outcome.handler; error = null; elapsed = outcome.elapsedMs
        }

        is SystemAlarmLauncher.Outcome.NoHandler -> {
            level = 3
            handler = outcome.handler
            error = outcome.reason.removePrefix("error:").takeIf { outcome.reason.startsWith("error:") }
            elapsed = 0L
        }
    }

    return AlarmAttempt(
        target = timeText,
        cycles = result.cycles,
        fallAsleepMin = fallAsleepMin,
        level = level,
        outcome = outcome::class.simpleName ?: "Unknown",
        handler = handler,
        error = error,
        elapsedMs = elapsed,
        pauseCount = snap.pauseCount,
        firstPauseDelayMs = snap.firstPauseDelayMs,
        pausedMs = snap.pausedMs,
        stillPaused = snap.stillPaused,
    )
}

/**
 * 诊断日志面板。
 *
 * 只做三件事：看、导出、清空。
 * 导出用系统分享（微信 / 文件传输助手 / 邮件都行），**不经网络、不经服务器** ——
 * 日志发不发、发给谁，完全由用户自己决定。
 */
@Composable
private fun DiagLogDialog(context: Context, onDismiss: () -> Unit) {
    // 清空之后要能立刻看到变化，所以用可变状态而不是每次重读
    var text by remember { mutableStateOf(DiagLog.readAll(context)) }
    var hint by remember { mutableStateOf<String?>(null) }

    val showEmpty = text.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("诊断日志") },
        text = {
            Column {
                Text(
                    "记录每次设闹钟的结果与设备环境，供离线排查用。" +
                            "不联网、不上传，内容只在本机。",
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                if (showEmpty) {
                    Text("（暂无记录。去点一次「设置」就会有了）", fontSize = 13.sp)
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = text,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                hint?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val activity = context.findActivity()
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "R90 闹钟 · 诊断日志")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    val ok = runCatching {
                        activity?.startActivity(Intent.createChooser(send, "导出日志"))
                    }.isSuccess
                    if (!ok) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("R90 诊断日志", text))
                        hint = "分享面板打不开，已复制到剪贴板"
                    }
                },
                enabled = !showEmpty,
            ) { Text("导出") }
        },
        dismissButton = {
            Column {
                TextButton(
                    onClick = {
                        DiagLog.clear(context)
                        text = DiagLog.readAll(context)
                        hint = "已清空"
                    },
                    enabled = !showEmpty,
                ) { Text("清空") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

/**
 * Level 3 补救对话框。
 *
 * 设计原则：失败时不只说"失败了"，必须给出
 * ① 具体原因 ② 至少三条可执行的替代路径。
 * 单一路径会让人卡死——不同失败原因需要不同解法。
 */
@Composable
private fun NoClockAppDialog(
    timeText: String,
    onOpenAlarmList: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onCopyTime: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("找不到系统时钟应用") },
        text = {
            Column {
                Text(
                    "你的手机上没有能响应「设置闹钟」的应用。" +
                            "可能没装时钟、或时钟被系统禁用了。",
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "要设置的时间是 $timeText。可以试试：",
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                Text("1. 打开系统时钟的闹钟列表，手动添加", fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text("2. 去应用管理里看「时钟」是不是被禁用了", fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text("3. 复制时间，稍后自己设", fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenAlarmList) { Text("打开时钟") }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onOpenAppSettings) { Text("应用管理") }
                TextButton(onClick = onCopyTime) { Text("复制时间") }
            }
        },
    )
}

@Composable
private fun ClockHeader(now: ZonedDateTime, is24Hour: Boolean) {
    val timeFormat = remember(is24Hour) {
        // 24 小时制用 HH（补零），12 小时制用 h（不补零）。
        // 不要用 hh —— 那是 12 小时制补零，会出现「04:32」这种在 12 制下很怪的结果。
        DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm:ss" else "h:mm:ss", Locale.getDefault())
    }
    val dateFormat = remember {
        DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = now.format(timeFormat),
            fontSize = 60.sp,
            fontWeight = FontWeight.Light,
            // 等宽字体：不然 11:11:11 和 22:22:22 宽度不同，秒跳时整行会左右晃
            fontFamily = FontFamily.Monospace,
            letterSpacing = (-2).sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = now.format(dateFormat),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 「准备入睡」时长选择。
 *
 * 用胶囊按钮而不是滑杆：躺床上时滑杆不好精确拖，
 * 大块可点区域盲按也能中。
 */
@Composable
private fun FallAsleepSelector(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "准备入睡",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        R90Calculator.FALL_ASLEEP_OPTIONS.forEach { minutes ->
            FilterChip(
                selected = minutes == selected,
                onClick = { onSelect(minutes) },
                label = {
                    Text(
                        text = if (minutes == 0) "立即" else "$minutes 分",
                        fontSize = 13.sp,
                    )
                },
            )
        }
    }
}

@Composable
private fun CycleRow(
    result: R90Calculator.Result,
    is24Hour: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 整行可点 = 等价于点按钮，单手操作时不用精确瞄按钮
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = result.cycles.toString(),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(36.dp),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatTime(result.hour24, result.minute, is24Hour),
                    fontSize = 25.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = buildDateLabel(result),
                    fontSize = 12.sp,
                    // 跨天用强调色，让「明天」一眼跳出来
                    color = if (result.isCrossDay) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Button(onClick = onClick) {
                Text("设置")
            }
        }
    }
}

private fun buildDateLabel(r: R90Calculator.Result): String {
    val dayText = R90Calculator.relativeDayLabel(r.dayDiff)
    val weekText = when (r.wakeTime.dayOfWeek.value) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }
    return if (r.dayDiff == 0) {
        "$dayText · $weekText"
    } else {
        val md = "%d月%d日".format(r.wakeTime.monthValue, r.wakeTime.dayOfMonth)
        "$dayText $md · $weekText"
    }
}

/** 显示层的时间格式化。传进来的 hour24 永远是 0..23。 */
private fun formatTime(hour24: Int, minute: Int, is24Hour: Boolean): String {
    if (is24Hour) return "%02d:%02d".format(hour24, minute)
    val h12 = when (val h = hour24 % 12) {
        0 -> 12
        else -> h
    }
    val suffix = if (hour24 < 12) "AM" else "PM"
    return "%d:%02d %s".format(h12, minute, suffix)
}

/**
 * 把某周期的起床时刻换算成 epoch 毫秒（应用内闹钟的触发点）。
 *
 * 复用 [R90Calculator] 已经算好的 dayDiff + hour24 + minute，
 * 用 java.time 落到本地时区 —— 不手写「日期 + 1」，避免月末 / 跨年 / 闰年翻车。
 */
private fun computeTriggerMillis(now: ZonedDateTime, result: R90Calculator.Result): Long {
    val date = now.toLocalDate().plusDays(result.dayDiff.toLong())
    return date.atTime(result.hour24, result.minute).atZone(now.zone).toInstant().toEpochMilli()
}

/** 精确闹钟能力缺失时，跳到系统的「闹钟与提醒」设置页让用户开启。 */
private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * F3：本 App 当前能否使用全屏提醒（`USE_FULL_SCREEN_INTENT`）。
 *
 * `NotificationManager.canUseFullScreenIntent()` 是 **API 34** 引入 —— 必须按版本守卫，
 * 否则低版本上会 `NoSuchMethodError`。低于 34 直接视为可用：
 * 该版本根本没有 FSI 机制，没有「被关闭」这回事（普通通知照常）。
 * 系统服务取不到（极端环境）也按可用处理，宁可不多提示一行。
 */
private fun canUseFullScreen(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 34) return true
    val nm = context.getSystemService(NotificationManager::class.java) ?: return true
    return nm.canUseFullScreenIntent()
}

/**
 * 跳到本 App 的「全屏提醒」系统设置页。
 *
 * `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` 同为 API 34 常量；用 intent 显式带上本包名，
 * 直达本 App 的开关项。部分 ROM 无此页 → 整体 `runCatching`，跳不过去就静默算了（不弹错）。
 */
private fun openFullScreenIntentSettings(context: Context) {
    if (Build.VERSION.SDK_INT < 34) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * 提醒方式切换。
 *
 * 默认落「系统时钟」—— 完全保留 v0.1 的既有行为；
 * 切到「本机闹钟」才走前台服务响铃这条新路径。两条路互不影响。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmModeSelector(
    inAppMode: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "提醒方式",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilterChip(
            selected = !inAppMode,
            onClick = { onSelect(false) },
            label = { Text("系统时钟", fontSize = 13.sp) },
        )
        FilterChip(
            selected = inAppMode,
            onClick = { onSelect(true) },
            label = { Text("本机闹钟", fontSize = 13.sp) },
        )
    }
}

/**
 * F3：全屏提醒能力缺失时的一行静默提示。
 *
 * 为什么是「静默一行」而不是对话框：全屏提醒只是体验增强 —— 缺失时响铃通知、关闭/贪睡
 * 按钮、声音、振动全部照常，用户依然能正常看到并关闭闹钟，不存在功能失败。
 * 不值得用一个打断式弹窗。用户在系统设置里开启后返回，本行会自行消失。
 */
@Composable
private fun FullScreenHintRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "⚠️ 全屏提醒未开启：锁屏时闹钟可能只弹通知、不占满屏",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "去开启",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 闹钟设置对话框：铃声 / 音频路由 / 最长响铃 / 贪睡。
 *
 * 用胶囊按钮而非滑杆 —— 与首页「准备入睡」保持一致的交互语言。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmSettingsDialog(
    settings: AppSettings,
    ringtoneLabel: String,
    onPickRingtone: () -> Unit,
    onRouteChange: (AudioRouteMode) -> Unit,
    onMaxRingChange: (Long) -> Unit,
    onSnoozeChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("闹钟设置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SettingsSectionLabel("铃声")
                TextButton(onClick = onPickRingtone, contentPadding = PaddingValues(0.dp)) {
                    Text("$ringtoneLabel（点击更换）", fontSize = 14.sp)
                }

                SettingsSectionLabel("音频路由")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingChip(
                        text = "耳机优先",
                        selected = settings.routeMode == AudioRouteMode.PREFER_HEADSET,
                        onClick = { onRouteChange(AudioRouteMode.PREFER_HEADSET) },
                    )
                    SettingChip(
                        text = "只走耳机",
                        selected = settings.routeMode == AudioRouteMode.HEADSET_ONLY,
                        onClick = { onRouteChange(AudioRouteMode.HEADSET_ONLY) },
                    )
                    SettingChip(
                        text = "外放",
                        selected = settings.routeMode == AudioRouteMode.SPEAKER,
                        onClick = { onRouteChange(AudioRouteMode.SPEAKER) },
                    )
                }

                SettingsSectionLabel("最长响铃（兜底自动停）")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppSettings.MAX_RING_MINUTES_OPTIONS.forEach { minutes ->
                        SettingChip(
                            text = "$minutes 分",
                            selected = settings.maxRingMillis == minutes * 60_000L,
                            onClick = { onMaxRingChange(minutes * 60_000L) },
                        )
                    }
                }

                SettingsSectionLabel("贪睡时长")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppSettings.SNOOZE_MINUTES_OPTIONS.forEach { minutes ->
                        SettingChip(
                            text = "$minutes 分",
                            selected = settings.snoozeMinutes == minutes,
                            onClick = { onSnoozeChange(minutes) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(
        text = text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, fontSize = 13.sp) },
    )
}
