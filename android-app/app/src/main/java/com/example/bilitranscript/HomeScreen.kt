package com.example.bilitranscript

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bilitranscript.theme.*

/**
 * 首页：粘贴链接 → 提取 → 结果。下载回调带格式（txt / srt）。
 */
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onRequestDownload: (title: String, content: String, ext: String, mime: String) -> Unit
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 品牌头
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("文案提取", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Pill("AI · 离线")
            }
            Text(
                "B站视频 → 一键转文字 · 本地识别",
                fontSize = 13.sp,
                color = TextMuted,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // 引擎状态
        EngineStatusRow(ui.engineReady, ui.engineName)

        // 输入卡
        GlassCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("视频链接")
                OutlinedTextField(
                    value = ui.videoUrl,
                    onValueChange = viewModel::onUrlChange,
                    placeholder = { Text("粘贴 B站 分享链接或 BV 号", color = TextFaint, fontSize = 14.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AuroraCyan.copy(alpha = 0.7f),
                        unfocusedBorderColor = Hairline,
                        focusedContainerColor = Color.Black.copy(alpha = 0.18f),
                        unfocusedContainerColor = Color.Black.copy(alpha = 0.12f),
                        cursorColor = AuroraCyan
                    )
                )
            }
        }

        // 提取按钮 / 进度
        if (ui.isLoading) {
            ProgressCard(ui.progress, ui.progressPhase)
        } else {
            PrimaryGradientButton(
                text = "✨ 一键提取文案",
                enabled = ui.videoUrl.isNotBlank() && ui.engineReady,
                onClick = viewModel::extractTranscript
            )
        }

        // 错误
        AnimatedVisibility(visible = ui.error != null) {
            GlassCard(containerColor = DangerRed.copy(alpha = 0.14f)) {
                Text(
                    ui.error ?: "",
                    color = DangerRed,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        // 结果
        AnimatedVisibility(visible = ui.transcript != null) {
            ui.transcript?.let { text ->
                ResultCard(
                    title = ui.videoTitle ?: "",
                    sourceLabel = ui.sourceLabel,
                    transcript = text,
                    wordCount = ui.wordCount,
                    hasTimeline = ui.hasTimeline,
                    onCopy = viewModel::copyToClipboard,
                    onShare = viewModel::shareTranscript,
                    onDownloadTxt = {
                        onRequestDownload(ui.videoTitle ?: "文案", text, "txt", "text/plain")
                    },
                    onDownloadSrt = {
                        viewModel.lastOutcome?.let { oc ->
                            onRequestDownload(
                                ui.videoTitle ?: "字幕",
                                SrtExporter.toSrt(oc.segments),
                                "srt",
                                "application/x-subrip"
                            )
                        }
                    },
                    onClear = viewModel::clearResult
                )
            }
        }
    }
}

@Composable
private fun EngineStatusRow(ready: Boolean, name: String) {
    GlassCard(containerColor = if (ready) AuroraCyan.copy(0.10f) else WarnAmber.copy(0.12f)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (ready) AuroraCyan else WarnAmber)
            )
            Text(
                if (ready) "$name 引擎已就绪" else "正在初始化模型…",
                color = if (ready) LightCyan else WarnAmber,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            if (ready) Pill("Ready", AuroraCyan) else Pill("Loading", WarnAmber)
        }
    }
}

@Composable
private fun ProgressCard(progress: Float, phase: String) {
    val animated by animateFloatAsState(progress, tween(300), label = "p")
    GlassCard(containerColor = SurfaceGlassStrong) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp)) {
                CircularProgressIndicator(
                    progress = { animated },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 3.dp,
                    color = AuroraCyan,
                    trackColor = Hairline
                )
                Text("${(animated * 100).toInt()}", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Column {
                Text(phase, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("处理中，请稍候…", color = TextMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ResultCard(
    title: String,
    sourceLabel: String?,
    transcript: String,
    wordCount: Int,
    hasTimeline: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDownloadTxt: () -> Unit,
    onDownloadSrt: () -> Unit,
    onClear: () -> Unit
) {
    GlassCard(containerColor = SurfaceGlassStrong) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Text("$wordCount 字", fontSize = 12.sp, color = TextMuted)
                        if (sourceLabel != null) Pill(sourceLabel, AuroraIndigo)
                    }
                }
                IconButton(onClick = onClear) {
                    Text("✕", color = TextMuted, fontSize = 16.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                color = Color.Black.copy(alpha = 0.22f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectionContainer {
                    Text(
                        transcript,
                        fontSize = 15.sp,
                        lineHeight = 25.sp,
                        color = TextSecondary,
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("📋 复制", Modifier.weight(1f), onClick = onCopy)
                GhostButton("📤 分享", Modifier.weight(1f), onClick = onShare)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("💾 TXT", Modifier.weight(1f), tint = LightCyan, onClick = onDownloadTxt)
                if (hasTimeline) {
                    GhostButton("🎬 SRT", Modifier.weight(1f), tint = AuroraViolet, onClick = onDownloadSrt)
                }
            }
        }
    }
}
