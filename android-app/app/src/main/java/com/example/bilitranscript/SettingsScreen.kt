package com.example.bilitranscript

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bilitranscript.theme.*

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onLaunchFloatingBall: () -> Unit = {}
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val modelStatuses by viewModel.modelStatuses.collectAsStateWithLifecycle()

    // 导入模型压缩包（国内下不动时的主力路径）
    var importTarget by remember { mutableStateOf<AsrModelSpec?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val target = importTarget
        importTarget = null
        if (uri != null && target != null) {
            Toast.makeText(context, "开始导入 ${target.name}…", Toast.LENGTH_SHORT).show()
            viewModel.importModel(target, uri)
        }
    }

    val separationReady = remember {
        runCatching { context.assets.list("models/separation")?.any { it.endsWith(".onnx") } == true }
            .getOrDefault(false)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("设置", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextPrimary)

        // ---- 模型仓库 ----
        SettingsSection("模型仓库") {
            modelStatuses.forEachIndexed { i, st ->
                if (i > 0) DividerLine()
                ModelRow(
                    status = st,
                    selected = st.spec.id == s.selectedModelId,
                    onSelect = { viewModel.selectModel(st.spec.id) },
                    onDownload = { viewModel.downloadModel(st.spec) },
                    onImport = {
                        importTarget = st.spec
                        importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                    onDelete = { viewModel.deleteModel(st.spec) }
                )
            }
            HintText("模型存在手机存储、不在 APK 里，可随时删/换。国内推荐：电脑/浏览器下好模型的 .zip → 点「导入压缩包」选它即可，无需联网下载。")
        }

        // ---- 准确率 ----
        SettingsSection("准确率") {
            SwitchRow("优先用现成字幕（非语音识别）", "关：始终语音识别他说的话。开：有自带字幕时直接拿来用", s.subtitleFirst) { v ->
                viewModel.updateSettings { it.copy(subtitleFirst = v) }
            }
            DividerLine()
            SwitchRow(
                "人声分离（去背景音乐）",
                if (separationReady) "已就绪：识别前剥离 BGM" else "未安装分离模型（见 UPGRADE.md）",
                s.vocalSeparation && separationReady
            ) { v ->
                if (v && !separationReady) {
                    Toast.makeText(context, "未检测到人声分离模型，请见 UPGRADE.md", Toast.LENGTH_LONG).show()
                } else {
                    viewModel.updateSettings { it.copy(vocalSeparation = v) }
                }
            }
            DividerLine()
            Text("识别语言", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
            LanguageChips(s.language) { code -> viewModel.updateSettings { it.copy(language = code) } }
        }

        // ---- 性能 ----
        SettingsSection("性能") {
            Text("识别线程数：${s.numThreads}", color = TextSecondary, fontSize = 14.sp)
            Slider(
                value = s.numThreads.toFloat(),
                onValueChange = { viewModel.updateSettings { st -> st.copy(numThreads = it.toInt().coerceIn(1, 8)) } },
                valueRange = 1f..8f,
                steps = 6,
                colors = SliderDefaults.colors(
                    thumbColor = AuroraCyan,
                    activeTrackColor = AuroraCyan,
                    inactiveTrackColor = Hairline
                )
            )
            DividerLine()
            SwitchRow("NNAPI 硬件加速", "部分机型更快，个别机型反而更慢", s.useNnapi) { v ->
                viewModel.updateSettings { it.copy(useNnapi = v) }
            }
            DividerLine()
            SwitchRow("下载最低码率音频", "更快、更省流量，识别精度无损", s.lowBitrateAudio) { v ->
                viewModel.updateSettings { it.copy(lowBitrateAudio = v) }
            }
        }

        // ---- 通用 ----
        SettingsSection("通用") {
            SwitchRow("识别完成自动复制", "完成后自动复制到剪贴板", s.autoCopy) { v ->
                viewModel.updateSettings { it.copy(autoCopy = v) }
            }
            DividerLine()
            SwitchRow("自动保存历史", "每次提取自动入库", s.saveHistory) { v ->
                viewModel.updateSettings { it.copy(saveHistory = v) }
            }
            DividerLine()
            Text("B站 Cookie（SESSDATA，选填）", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
            HintText("用于获取需要登录的字幕，留空则不使用")
            OutlinedTextField(
                value = s.sessdata,
                onValueChange = { viewModel.updateSettings { st -> st.copy(sessdata = it.trim()) } },
                placeholder = { Text("粘贴 SESSDATA…", color = TextFaint, fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AuroraCyan.copy(0.7f),
                    unfocusedBorderColor = Hairline,
                    focusedContainerColor = Color.Black.copy(0.18f),
                    unfocusedContainerColor = Color.Black.copy(0.12f),
                    cursorColor = AuroraCyan
                )
            )
            DividerLine()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("✦ 悬浮球", Modifier.weight(1f), tint = AuroraViolet) { onLaunchFloatingBall() }
                GhostButton("🧹 清缓存", Modifier.weight(1f), tint = LightCyan) {
                    TranscriptionPipeline.sweepCache(context)
                    Toast.makeText(context, "缓存已清理", Toast.LENGTH_SHORT).show()
                }
            }
        }

        Text(
            "文案福特 · 本地离线识别 · v2",
            color = TextFaint,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title)
        GlassCard {
            Column(Modifier.padding(16.dp), content = content)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AuroraCyan,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = Color.White.copy(0.08f),
                uncheckedBorderColor = Hairline
            )
        )
    }
}

@Composable
private fun ModelRow(
    status: ModelStatus,
    selected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit
) {
    val spec = status.spec
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(spec.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    if (selected && status.installed) Pill("使用中", AuroraCyan)
                }
                Text(spec.description, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                Text(
                    when {
                        status.downloading -> "下载中 ${(status.progress * 100).toInt()}%"
                        status.installed -> "已安装 · 约 ${spec.approxSizeMb}MB"
                        else -> "未安装 · 约 ${spec.approxSizeMb}MB"
                    },
                    color = if (status.installed) SuccessGreen else TextFaint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            when {
                status.downloading ->
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = AuroraCyan)
                status.installed && selected ->
                    Text("✓", color = AuroraCyan, fontSize = 20.sp)
                status.installed ->
                    GhostButton("选用", onClick = onSelect)
                spec.files.isNotEmpty() ->
                    GhostButton("下载", tint = LightCyan, onClick = onDownload)
            }
        }
        if (status.downloading) {
            LinearProgressIndicator(
                progress = { status.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(50)),
                color = AuroraCyan,
                trackColor = Hairline
            )
        }
        // 非内置模型：底部给「导入压缩包」（国内主力路径）+（已装时）删除
        if (!spec.bundled && !status.downloading) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "📦 导入压缩包",
                    color = LightCyan,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(0.05f))
                        .clickable(onClick = onImport)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
                Spacer(Modifier.weight(1f))
                if (status.installed) {
                    Text(
                        "删除",
                        color = DangerRed.copy(0.85f),
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onDelete)
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageChips(current: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 一行放下 6 个略挤，分两组：常用 4 个 + 自动
        RecognizeLanguage.entries.take(3).forEach { lang ->
            LangChip(lang, current, Modifier.weight(1f), onSelect)
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RecognizeLanguage.entries.drop(3).forEach { lang ->
            LangChip(lang, current, Modifier.weight(1f), onSelect)
        }
    }
}

@Composable
private fun LangChip(lang: RecognizeLanguage, current: String, modifier: Modifier, onSelect: (String) -> Unit) {
    val selected = lang.code == current
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) AuroraIndigo.copy(0.22f) else Color.White.copy(0.04f))
            .clickable { onSelect(lang.code) }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            lang.label,
            color = if (selected) Color(0xFFCFE0FF) else TextMuted,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun HintText(text: String) {
    Text(text, color = TextFaint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun DividerLine() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .height(1.dp)
            .background(Hairline)
    )
}
