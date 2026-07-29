package com.example.bilitranscript

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bilitranscript.theme.BiliTranscriptTheme
import com.example.bilitranscript.theme.ClaudeAccent
import com.example.bilitranscript.theme.ClaudeBackground
import com.example.bilitranscript.theme.ClaudeSurface
import com.example.bilitranscript.theme.ClaudeTextPrimary
import com.example.bilitranscript.theme.ClaudeTextTertiary

/**
 * 单 Activity + 三页底部导航（提取 / 历史 / 设置）。
 * 支持：从 B站「分享到本应用」、剪贴板自动识别、TXT/SRT 下载、悬浮球。
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val REQ_OVERLAY = 1001
    }

    private var pendingTitle = ""
    private var pendingContent = ""
    private var pendingExt = "txt"
    private var pendingMime = "text/plain"

    private val sharedUrl = mutableStateOf<String?>(null)

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) doDownload()
        else Toast.makeText(this, "需要存储权限才能下载文件", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启动 AppGraph（含 Room DB + LogSink 绑定），保证其它模块拿到的都是同一单例
        AppGraph.init(this)
        enableEdgeToEdge()
        sharedUrl.value = parseSharedUrl(intent)
        setContent {
            BiliTranscriptTheme {
                AppRoot(
                    sharedUrl = sharedUrl,
                    onConsumedSharedUrl = { sharedUrl.value = null },
                    onRequestDownload = { title, content, ext, mime ->
                        pendingTitle = title; pendingContent = content
                        pendingExt = ext; pendingMime = mime
                        checkStorageAndDownload()
                    },
                    onLaunchFloatingBall = ::launchFloatingBall
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseSharedUrl(intent)?.let { sharedUrl.value = it }
    }

    private fun parseSharedUrl(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> if (intent.type == "text/plain") intent.getStringExtra(Intent.EXTRA_TEXT) else null
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
    }

    private fun checkStorageAndDownload() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
            ) doDownload()
            else storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            doDownload()
        }
    }

    private fun doDownload() {
        TranscriptSaver.save(this, pendingTitle, pendingContent, pendingExt, pendingMime)
    }

    private fun launchFloatingBall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
        } else {
            startService(Intent(this, FloatingBallService::class.java).setAction(FloatingBallService.ACTION_SHOW))
            Toast.makeText(this, "悬浮球已启动", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                startService(Intent(this, FloatingBallService::class.java).setAction(FloatingBallService.ACTION_SHOW))
                Toast.makeText(this, "悬浮球已启动", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "需要悬浮窗权限才能使用悬浮球", Toast.LENGTH_LONG).show()
            }
        }
    }
}

private enum class AppScreen(val label: String, val icon: String) {
    Home("提取", "✦"),
    History("历史", "🗂"),
    Settings("设置", "⚙")
}

@Composable
private fun AppRoot(
    sharedUrl: State<String?>,
    onConsumedSharedUrl: () -> Unit,
    onRequestDownload: (String, String, String, String) -> Unit,
    onLaunchFloatingBall: () -> Unit
) {
    val vm: MainViewModel = viewModel()
    val context = LocalContext.current
    var screen by remember { mutableStateOf(AppScreen.Home) }

    LaunchedEffect(sharedUrl.value) {
        val url = sharedUrl.value
        if (!url.isNullOrBlank()) {
            screen = AppScreen.Home
            vm.prefill(url, autoStart = true)
            onConsumedSharedUrl()
        }
    }

    LaunchedEffect(Unit) {
        val clip = clipboardBiliLink(context)
        if (clip != null && vm.videoUrl.value.isBlank() && vm.resultUi.value == null) {
            vm.prefill(clip, autoStart = false)
        }
    }

    AppBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Box(Modifier.weight(1f)) {
                when (screen) {
                    AppScreen.Home -> HomeScreen(vm, onRequestDownload)
                    AppScreen.History -> HistoryScreen(vm) { record ->
                        vm.openHistory(record); screen = AppScreen.Home
                    }
                    AppScreen.Settings -> SettingsScreen(vm, onLaunchFloatingBall)
                }
            }
            ClaudeBottomBar(screen) { screen = it }
        }
    }
}

/**
 * Claude-style bottom navigation bar: flat white surface, subtle top border.
 * Active tab uses accent color, inactive uses tertiary gray.
 */
@Composable
private fun ClaudeBottomBar(current: AppScreen, onSelect: (AppScreen) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(ClaudeSurface)
    ) {
        // 顶部细分隔线（1dp hairline）
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(ClaudeBackground)
        )

        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            AppScreen.entries.forEach { s ->
                val selected = s == current
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onSelect(s) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        s.icon,
                        fontSize = 20.sp,
                        color = if (selected) ClaudeAccent else ClaudeTextTertiary
                    )
                    Text(
                        s.label,
                        color = if (selected) ClaudeAccent else ClaudeTextTertiary,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

private fun clipboardBiliLink(context: Context): String? {
    return try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return null
        val looksBili = text.contains("b23.tv") ||
            text.contains("bilibili.com") ||
            Regex("BV[1-9A-HJ-NP-Za-km-z]{10}").containsMatchIn(text)
        if (looksBili) text else null
    } catch (e: Exception) {
        null
    }
}
