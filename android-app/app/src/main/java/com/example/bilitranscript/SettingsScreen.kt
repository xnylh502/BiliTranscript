package com.example.bilitranscript

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.bilitranscript.theme.ClaudeAccent
import com.example.bilitranscript.theme.ClaudeBorder
import com.example.bilitranscript.theme.ClaudeInputBorder
import com.example.bilitranscript.theme.ClaudeInputFocusBorder
import com.example.bilitranscript.theme.ClaudeSurface
import com.example.bilitranscript.theme.ClaudeSurfaceHover
import com.example.bilitranscript.theme.ClaudeTextPrimary
import com.example.bilitranscript.theme.ClaudeTextSecondary
import com.example.bilitranscript.theme.ClaudeTextTertiary

/**
 * 设置页。重组性能设计：
 * 每个 Section 是**独立 composable 且只收原始类型参数 + viewModel 引用**，
 * 拨某个开关时 Compose 靠参数稳定性自动 skip 未变化的区块——
 * 不再出现「拨一行开关整页重组（含文本框/玻璃卡）」的卡顿。
 * 文本输入与滑杆均为「本地暂存 + 提交时落库」，打字/拖动过程不触发设置回写。
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onLaunchFloatingBall: () -> Unit = {}
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val modelStatuses by viewModel.modelStatuses.collectAsStateWithLifecycle()
    val appContext = LocalContext.current.applicationContext

    // 模型就绪检查放 IO（assets 遍历是文件 IO，主线程跑会卡设置页打开）
    val separationReady by produceState(initialValue = false) {
        value = withContext(Dispatchers.IO) {
            runCatching { AppGraph.separator(appContext).isAvailable() }.getOrDefault(false)
        }
    }
    val vadReady by produceState(initialValue = false) {
        value = withContext(Dispatchers.IO) {
            runCatching { VadSegmenter(appContext).isAvailable() }.getOrDefault(false)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 页面标题
        Text("设置", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = ClaudeTextPrimary)

        ModelStoreSection(
            viewModel = viewModel,
            useCloudModel = s.useCloudModel,
            statuses = modelStatuses,
            selectedModelId = s.selectedModelId,
            cloudApiUrl = s.cloudApiUrl,
            cloudApiKey = s.cloudApiKey,
            cloudModelName = s.cloudModelName
        )

        AccuracySection(
            viewModel = viewModel,
            subtitleFirst = s.subtitleFirst,
            vocalSeparation = s.vocalSeparation,
            separationReady = separationReady,
            vadSegment = s.vadSegment,
            vadReady = vadReady,
            language = s.language
        )

        PerformanceSection(
            viewModel = viewModel,
            numThreads = s.numThreads,
            useNnapi = s.useNnapi,
            lowBitrateAudio = s.lowBitrateAudio
        )

        GeneralSection(
            viewModel = viewModel,
            autoCopy = s.autoCopy,
            saveHistory = s.saveHistory,
            sessdata = s.sessdata,
            onLaunchFloatingBall = onLaunchFloatingBall
        )

        Text(
            "文案绫波 · 本地离线识别 · v2",
            color = ClaudeTextTertiary,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

// =============================================================================
// 模型管理库（本地/云端 Tab + 模型下拉栏 / 云配置）
// =============================================================================

@Composable
private fun ModelStoreSection(
    viewModel: MainViewModel,
    useCloudModel: Boolean,
    statuses: List<ModelStatus>,
    selectedModelId: String,
    cloudApiUrl: String,
    cloudApiKey: String,
    cloudModelName: String
) {
    val context = LocalContext.current
    var importTarget by remember { mutableStateOf<AsrModelSpec?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val target = importTarget
        importTarget = null
        if (uri != null && target != null) {
            Toast.makeText(context, "开始导入 ${target.name}…", Toast.LENGTH_SHORT).show()
            viewModel.importModel(target, uri)
        }
    }

    SettingsSection("模型管理库") {
        // 本地/云端 Tab 切换
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ClaudeSurfaceHover)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (!useCloudModel) ClaudeSurface else Color.Transparent)
                    .clickable { viewModel.updateSettings { it.copy(useCloudModel = false) } }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "本地离线模型",
                    color = if (!useCloudModel) ClaudeTextPrimary else ClaudeTextTertiary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (useCloudModel) ClaudeSurface else Color.Transparent)
                    .clickable { viewModel.updateSettings { it.copy(useCloudModel = true) } }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "云端 API 引擎",
                    color = if (useCloudModel) ClaudeTextPrimary else ClaudeTextTertiary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (!useCloudModel) {
            // 本地离线模型：液态玻璃下拉选择栏（轻/中/重档分组，长按看详情）
            LiquidGlassModelPicker(
                statuses = statuses,
                selectedId = selectedModelId,
                onSelect = { viewModel.selectModel(it.id) },
                onDownload = { viewModel.downloadModel(it) },
                onImport = { spec ->
                    importTarget = spec
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                },
                onDelete = { viewModel.deleteModel(it) }
            )
            ClaudeHintText("模型存在手机存储、不在 APK 里，可随时删/换。下不动时：电脑/浏览器下好模型压缩包（.zip / .tar.bz2）→ 长按模型 →「导入压缩包」。")
        } else {
            // 云端 API 配置面板（本地暂存 + 提交时落库，打字不卡）
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("云端 API 配置 (标准 OpenAI 兼容)", color = ClaudeTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)

                Text("API URL 地址", color = ClaudeTextSecondary, fontSize = 12.sp)
                SettingsTextField(cloudApiUrl, "https://api.openai.com/v1/audio/transcriptions") { v ->
                    viewModel.updateSettings { it.copy(cloudApiUrl = v) }
                }

                Text("API Key 密钥", color = ClaudeTextSecondary, fontSize = 12.sp)
                SettingsTextField(cloudApiKey, "sk-...") { v ->
                    viewModel.updateSettings { it.copy(cloudApiKey = v) }
                }

                Text("ASR 模型名称", color = ClaudeTextSecondary, fontSize = 12.sp)
                SettingsTextField(cloudModelName, "whisper-1") { v ->
                    viewModel.updateSettings { it.copy(cloudModelName = v) }
                }

                ClaudeHintText("云端模式下，APP 下载完成视频音频后将自动转换格式并上传至云端接口进行高精度听写。")
            }
        }
    }
}

// =============================================================================
// 准确率
// =============================================================================

@Composable
private fun AccuracySection(
    viewModel: MainViewModel,
    subtitleFirst: Boolean,
    vocalSeparation: Boolean,
    separationReady: Boolean,
    vadSegment: Boolean,
    vadReady: Boolean,
    language: String
) {
    val context = LocalContext.current
    SettingsSection("准确率") {
        SwitchRow("优先用现成字幕（非语音识别）", "关：始终语音识别他说的话。开：有自带字幕时直接拿来用", subtitleFirst) { v ->
            viewModel.updateSettings { it.copy(subtitleFirst = v) }
        }
        ClaudeDivider()
        SwitchRow(
            "人声分离（去背景音乐）",
            if (separationReady) "已就绪：识别前用 GT-CRN 剥离背景噪声/BGM，提升带音乐视频准确率"
            else "未检测到人声分离模型",
            vocalSeparation
        ) { v ->
            if (v && !separationReady) {
                Toast.makeText(context, "未检测到人声分离模型，请重新安装 App", Toast.LENGTH_LONG).show()
            } else {
                viewModel.updateSettings { it.copy(vocalSeparation = v) }
            }
        }
        ClaudeDivider()
        SwitchRow(
            "VAD 智能切句（跳过纯音乐段）",
            if (vadReady) "识别前按语音活动切段：纯 BGM/静音段不再产生幻觉歌词，逐句带时间轴可导出 SRT"
            else "未检测到 VAD 模型",
            vadSegment
        ) { v ->
            if (v && !vadReady) {
                Toast.makeText(context, "未检测到 VAD 模型，请重新安装 App", Toast.LENGTH_LONG).show()
            } else {
                viewModel.updateSettings { it.copy(vadSegment = v) }
            }
        }
        ClaudeDivider()
        Text("识别语言", color = ClaudeTextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        LanguageChips(language) { code -> viewModel.updateSettings { it.copy(language = code) } }
    }
}

// =============================================================================
// 性能
// =============================================================================

@Composable
private fun PerformanceSection(
    viewModel: MainViewModel,
    numThreads: Int,
    useNnapi: Boolean,
    lowBitrateAudio: Boolean
) {
    SettingsSection("性能") {
        // 滑杆本地暂存，拖动结束才提交（拖动中提交会连续触发 239MB 引擎重载）
        ThreadsSlider(numThreads) { v ->
            viewModel.updateSettings { it.copy(numThreads = v) }
        }
        ClaudeDivider()
        SwitchRow("NNAPI 硬件加速", "部分机型更快，个别机型反而更慢", useNnapi) { v ->
            viewModel.updateSettings { it.copy(useNnapi = v) }
        }
        ClaudeDivider()
        SwitchRow("下载最低码率音频", "更快、更省流量，识别精度无损", lowBitrateAudio) { v ->
            viewModel.updateSettings { it.copy(lowBitrateAudio = v) }
        }
    }
}

@Composable
private fun ThreadsSlider(numThreads: Int, onCommit: (Int) -> Unit) {
    var local by remember(numThreads) { mutableStateOf(numThreads.toFloat()) }
    Column {
        Text("识别线程数：${local.toInt()}", color = ClaudeTextSecondary, fontSize = 14.sp)
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local.toInt().coerceIn(1, 8)) },
            valueRange = 1f..8f,
            steps = 6,
            colors = SliderDefaults.colors(
                thumbColor = ClaudeAccent,
                activeTrackColor = ClaudeAccent,
                inactiveTrackColor = ClaudeBorder
            )
        )
    }
}

// =============================================================================
// 通用
// =============================================================================

@Composable
private fun GeneralSection(
    viewModel: MainViewModel,
    autoCopy: Boolean,
    saveHistory: Boolean,
    sessdata: String,
    onLaunchFloatingBall: () -> Unit
) {
    val context = LocalContext.current
    SettingsSection("通用") {
        SwitchRow("识别完成自动复制", "完成后自动复制到剪贴板", autoCopy) { v ->
            viewModel.updateSettings { it.copy(autoCopy = v) }
        }
        ClaudeDivider()
        SwitchRow("自动保存历史", "每次提取自动入库", saveHistory) { v ->
            viewModel.updateSettings { it.copy(saveHistory = v) }
        }
        ClaudeDivider()
        Text("B站 Cookie（SESSDATA，选填）", color = ClaudeTextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        ClaudeHintText("用于获取需要登录的字幕，留空则不使用")
        SettingsTextField(sessdata, "粘贴 SESSDATA…") { v ->
            viewModel.updateSettings { it.copy(sessdata = v) }
        }
        ClaudeDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ClaudeOutlineButton("✦ 悬浮球", Modifier.weight(1f), tint = ClaudeAccent, onClick = onLaunchFloatingBall)
            ClaudeOutlineButton("🧹 清缓存", Modifier.weight(1f), tint = ClaudeAccent, onClick = {
                TranscriptionPipeline.sweepCache(context)
                Toast.makeText(context, "缓存已清理", Toast.LENGTH_SHORT).show()
            })
        }
    }
}

// =============================================================================
// 共享小组件
// =============================================================================

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title)
        ClaudeCard {
            Column(Modifier.padding(16.dp), content = content)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = ClaudeTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = ClaudeTextTertiary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ClaudeSurface,
                checkedTrackColor = ClaudeAccent,
                uncheckedThumbColor = ClaudeTextSecondary,
                uncheckedTrackColor = ClaudeBorder
            )
        )
    }
}

/**
 * 设置用文本框：本地暂存输入，IME Done 或失焦时才提交落库——
 * 避免每打一个字都回写设置并触发整页重组/引擎检查。
 */
@Composable
private fun SettingsTextField(
    value: String,
    placeholder: String,
    onCommit: (String) -> Unit
) {
    var local by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = local,
        onValueChange = { local = it },
        placeholder = { Text(placeholder, color = ClaudeTextTertiary, fontSize = 14.sp) },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { f ->
                if (!f.isFocused && local.trim() != value) onCommit(local.trim())
            },
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            if (local.trim() != value) onCommit(local.trim())
        }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = ClaudeTextPrimary,
            unfocusedTextColor = ClaudeTextPrimary,
            focusedBorderColor = ClaudeInputFocusBorder,
            unfocusedBorderColor = ClaudeInputBorder,
            focusedContainerColor = ClaudeSurface,
            unfocusedContainerColor = ClaudeSurface,
            cursorColor = ClaudeAccent
        )
    )
}

@Composable
private fun LanguageChips(current: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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
            .background(if (selected) ClaudeAccent else ClaudeSurfaceHover)
            .clickable { onSelect(lang.code) }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            lang.label,
            color = if (selected) ClaudeSurface else ClaudeTextSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/**
 * 字节/秒 → 「MB/s」/「KB/s」人类可读。
 */
fun humanSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1024L * 1024 -> "%.1f MB/s".format(bytesPerSec / 1024.0 / 1024.0)
        bytesPerSec >= 1024L -> "%.0f KB/s".format(bytesPerSec / 1024.0)
        else -> "$bytesPerSec B/s"
    }
}

/**
 * 秒 → 「剩 5 分 23 秒」/「剩 38 秒」/「剩 1 时 12 分」。
 */
fun humanDuration(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}时${m}分"
        m > 0 -> "${m}分${s}秒"
        else -> "${s}秒"
    }
}
