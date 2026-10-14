package com.yanfei.r90alarm.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.yanfei.r90alarm.BuildConfig
import com.yanfei.r90alarm.R
import com.yanfei.r90alarm.history.HistoryExport
import com.yanfei.r90alarm.history.UsageHistory
import com.yanfei.r90alarm.history.UsageRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 把一段文本复制到剪贴板。导出/打开链接的**兜底通道**（分享面板打不开时用）。
 * internal 供 [AboutScreen] 复用。
 */
internal fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** 使用历史页：本地记录列表 + Markdown 导出。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // 清空后要能立刻看到变化，所以用可变状态而不是每次重读。
    // 初值先给空表 —— 真正的磁盘读取放到 IO 线程（见下面 LaunchedEffect），
    // 不在组合期做文件 I/O（与 AboutScreen 用 IO 调度器的口径一致）。
    var records by remember { mutableStateOf<List<UsageRecord>>(emptyList()) }
    val isEmpty = records.isEmpty()

    LaunchedEffect(Unit) {
        records = withContext(Dispatchers.IO) { UsageHistory.read(context) }
    }

    /** 用当前列表 + 当前时刻生成 Markdown。 */
    fun buildMarkdown(): String = HistoryExport.toMarkdown(
        records = records,
        exportedAtMillis = System.currentTimeMillis(),
        zone = ZoneId.systemDefault(),
        appVersion = BuildConfig.VERSION_NAME,
    )

    // ---- SAF 另存为（副通道）----
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 先在主线程取好快照并生成文本（只读内存态），文件写盘放到 IO 线程。
        val markdown = buildMarkdown()
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(markdown.toByteArray(Charsets.UTF_8))
                    } ?: error("openOutputStream 返回 null")
                }.isSuccess
            }
            snackbar.showSnackbar(
                context.getString(if (ok) R.string.history_saved else R.string.history_save_failed)
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.history_back)) }
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
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.history_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            // ---- 导出动作行：分享为主、另存为副、清空 ----
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    enabled = !isEmpty,
                    onClick = {
                        scope.launch {
                            val shared = exportAndShare(context)
                            if (!shared) {
                                copyToClipboard(context, "R90 使用历史", buildMarkdown())
                                snackbar.showSnackbar(
                                    context.getString(R.string.history_export_failed_to_clipboard)
                                )
                            }
                        }
                    },
                ) { Text(stringResource(R.string.history_export_share)) }

                OutlinedButton(
                    enabled = !isEmpty,
                    onClick = { saveLauncher.launch(EXPORT_FILE_NAME) },
                ) { Text(stringResource(R.string.history_export_save)) }

                TextButton(
                    enabled = !isEmpty,
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { UsageHistory.clear(context) }
                            records = emptyList()
                            snackbar.showSnackbar(context.getString(R.string.history_cleared))
                        }
                    },
                ) { Text(stringResource(R.string.history_clear)) }
            }

            Spacer(Modifier.height(8.dp))

            if (isEmpty) {
                Text(
                    text = stringResource(R.string.history_empty),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(records) { record -> HistoryRow(record) }
                }
            }
        }
    }
}

/** SAF 建议文件名。 */
private const val EXPORT_FILE_NAME = "r90-usage-history.md"

/**
 * 写临时文件 + FileProvider 分享（主通道）。
 *
 * 刻意用 FileProvider 而不是 ACTION_SEND 纯文本：
 * 微信对纯文本会截断，长表格发过去就残缺；发文件则完整。
 *
 * 读历史 + 写临时文件都是磁盘 I/O，整体放到 [Dispatchers.IO]（本函数是 suspend，
 * 由调用方在协程里 await）。
 *
 * @return 是否成功拉起分享面板。任何一步失败返回 false，由调用方转剪贴板兜底。
 */
private suspend fun exportAndShare(context: Context): Boolean {
    val file = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
                .format(ZonedDateTime.now())
            val f = File(dir, "r90-usage-history-$stamp.md")
            f.writeText(
                HistoryExport.toMarkdown(
                    records = UsageHistory.read(context),
                    exportedAtMillis = System.currentTimeMillis(),
                    zone = ZoneId.systemDefault(),
                    appVersion = BuildConfig.VERSION_NAME,
                ),
                Charsets.UTF_8,
            )
            f
        }.getOrNull()
    } ?: return false

    return runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            // text/markdown 是标准 MIME；个别目标不认时系统会回退成通用文件分享
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.history_export_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 部分接收端（典型是微信）只从 ClipData 里取 URI，
            // 只设 EXTRA_STREAM + 读权限可能拿不到文件。补上以提升兼容性。
            clipData = ClipData.newUri(context.contentResolver, "r90-usage-history", uri)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.history_export_chooser))
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }.isSuccess
}

/** 记录时间展示格式：`MM-dd HH:mm`。 */
private val ROW_STAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)

@Composable
private fun HistoryRow(record: UsageRecord) {
    val whenText = remember(record.epochMillis) {
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(record.epochMillis), ZoneId.systemDefault())
            .format(ROW_STAMP)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.width(88.dp)) {
                Text(whenText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = "${record.cycles} 周期",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.targetText,
                    fontSize = 20.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${record.mode.display} · 入睡 ${record.fallAsleepMin} 分",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = record.resultText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (record.success) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}
