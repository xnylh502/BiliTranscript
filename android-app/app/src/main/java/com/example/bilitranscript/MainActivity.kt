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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bilitranscript.theme.*

/**
 * 单 Activity + 三页底部导航（提取 / 历史 / 设置）。
 * 支持：从 B站「分享到本应用」、剪贴板自动识别、TXT/SRT 下载、悬浮球。
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val REQ_OVERLAY = 1001
    }

    // 待下载内容（等存储权限）
    private var pendingTitle = ""
    private var pendingContent = ""
    private var pendingExt = "txt"
    private var pendingMime = "text/plain"

    // 外部进入的链接（分享 / 打开链接）
    private val sharedUrl = mutableStateOf<String?>(null)

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) doDownload()
        else Toast.makeText(this, "需要存储权限才能下载文件", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    // ---- 下载 ----
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

    // ---- 悬浮球 ----
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
    Home("提取", "✨"),
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

    // 分享 / 外部链接进入：预填并自动开提
    LaunchedEffect(sharedUrl.value) {
        val url = sharedUrl.value
        if (!url.isNullOrBlank()) {
            screen = AppScreen.Home
            vm.prefill(url, autoStart = true)
            onConsumedSharedUrl()
        }
    }

    // 首次进入：剪贴板里有 B站 链接则预填（不自动开提）
    LaunchedEffect(Unit) {
        val clip = clipboardBiliLink(context)
        if (clip != null && vm.uiState.value.videoUrl.isBlank() && vm.uiState.value.transcript == null) {
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
            GlassBottomBar(screen) { screen = it }
        }
    }
}

@Composable
private fun GlassBottomBar(current: AppScreen, onSelect: (AppScreen) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AppScreen.entries.forEach { s ->
            val selected = s == current
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) AuroraCyan.copy(0.18f) else Color.Transparent)
                    .clickable { onSelect(s) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.icon, fontSize = 18.sp)
                    Text(
                        s.label,
                        color = if (selected) LightCyan else TextMuted,
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
