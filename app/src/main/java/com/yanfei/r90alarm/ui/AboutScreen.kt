package com.yanfei.r90alarm.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yanfei.r90alarm.BuildConfig
import com.yanfei.r90alarm.R
import com.yanfei.r90alarm.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 关于页：版本号 + 打开发布页 + 手动检查更新。
 *
 * 手动检查才在 UI 上显示结果；启动时的静默检查（见 MainActivity）不弹任何东西，
 * 结果只缓进 [UpdateChecker.cached]，本页进入时直接读取展示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(false) }
    // 初值取上次检查缓存（可能是启动时静默查到的）
    var outcome by remember { mutableStateOf(UpdateChecker.cached?.outcome) }
    var hint by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
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
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.about_version_label,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.about_intro),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { openUrl(context, UpdateChecker.releasesPage()) { hint = it } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.about_open_releases)) }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                enabled = !checking,
                onClick = {
                    checking = true
                    scope.launch {
                        // 网络调用阻塞，必须离开主线程
                        val info = withContext(Dispatchers.IO) {
                            UpdateChecker.checkNow(context, BuildConfig.VERSION_NAME)
                        }
                        outcome = info.outcome
                        checking = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (checking) R.string.about_checking else R.string.about_check_update
                    )
                )
            }

            // 结果只在这里展示 —— 静默检查不打扰用户
            outcome?.let { o ->
                Spacer(Modifier.height(12.dp))
                val (text, color) = when (o) {
                    is UpdateChecker.Outcome.UpdateAvailable ->
                        stringResource(R.string.about_update_available, o.latestTag) to
                                MaterialTheme.colorScheme.primary

                    is UpdateChecker.Outcome.UpToDate ->
                        stringResource(R.string.about_up_to_date) to
                                MaterialTheme.colorScheme.onSurfaceVariant

                    is UpdateChecker.Outcome.Failed ->
                        stringResource(R.string.about_check_failed, o.reason) to
                                MaterialTheme.colorScheme.error
                }
                Text(text = text, fontSize = 13.sp, color = color)
            }

            hint?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * 打开链接。失败时把链接复制到剪贴板并回调提示 ——
 * 不能只是静默失败，否则用户会以为按钮坏了。
 */
private fun openUrl(context: Context, url: String, onFailure: (String) -> Unit) {
    val ok = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
    if (!ok) {
        copyToClipboard(context, "R90 发布页", url)
        onFailure(context.getString(R.string.about_open_releases_failed))
    }
}
