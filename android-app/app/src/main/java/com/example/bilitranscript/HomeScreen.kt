package com.example.bilitranscript

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import com.example.bilitranscript.theme.ClaudeAccent
import com.example.bilitranscript.theme.ClaudeError
import com.example.bilitranscript.theme.ClaudeSurfaceHover
import com.example.bilitranscript.theme.ClaudeSuccess
import com.example.bilitranscript.theme.ClaudeTextPrimary
import com.example.bilitranscript.theme.ClaudeTextSecondary
import com.example.bilitranscript.theme.ClaudeTextTertiary
import com.example.bilitranscript.theme.ClaudeWarning

/**
 * 首页：粘贴链接 → 提取 → 结果。
 * Claude-style layout: clean, warm cream bg, white cards, accent orange buttons.
 */
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onRequestDownload: (title: String, content: String, ext: String, mime: String) -> Unit
) {
    // 状态分区读取：每个区块只 collect 自己那条 flow，
    // 打字/进度刷新/结果展示互不触发整屏重组（按钮与界面流畅的关键）。
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 品牌头 — Claude 风格：简洁大标题
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "文案提取",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = ClaudeTextPrimary
                )
                ClaudePill("AI · 离线", color = ClaudeAccent)
            }
            Text(
                "短视频 → 一键转文字 · 本地离线识别",
                fontSize = 13.sp,
                color = ClaudeTextSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // 引擎状态条
        EngineStatusSection(viewModel)

        // 输入卡
        UrlInputCard(viewModel)

        // 提取按钮 / 进度
        ExtractSection(viewModel)

        // 错误提示
        ErrorSection(viewModel)

        // 结果
        ResultSection(viewModel, onRequestDownload)
    }
}

@Composable
private fun EngineStatusSection(viewModel: MainViewModel) {
    val p by viewModel.progressUi.collectAsStateWithLifecycle()
    EngineStatusRow(p.engineReady, p.engineName)
}

@Composable
private fun UrlInputCard(viewModel: MainViewModel) {
    val url by viewModel.videoUrl.collectAsStateWithLifecycle()
    ClaudeCard {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionLabel("视频链接")
            ClaudeTextField(
                value = url,
                onValueChange = viewModel::onUrlChange,
                placeholder = "粘贴分享链接（B站 / 抖音 / 快手 / 小红书）",
                minLines = 2,
                maxLines = 4
            )
        }
    }
}

@Composable
private fun ExtractSection(viewModel: MainViewModel) {
    val p by viewModel.progressUi.collectAsStateWithLifecycle()
    val url by viewModel.videoUrl.collectAsStateWithLifecycle()
    if (p.isLoading) {
        ProgressCard(p.progress, p.phase)
    } else {
        ClaudePrimaryButton(
            text = "一键提取文案",
            enabled = url.isNotBlank() && p.engineReady,
            onClick = viewModel::extractTranscript
        )
    }
}

@Composable
private fun ErrorSection(viewModel: MainViewModel) {
    val p by viewModel.progressUi.collectAsStateWithLifecycle()
    AnimatedVisibility(visible = p.error != null) {
        ClaudeCard(containerColor = ClaudeError.copy(alpha = 0.08f)) {
            Text(
                p.error ?: "",
                color = ClaudeError,
                fontSize = 14.sp,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
private fun ResultSection(
    viewModel: MainViewModel,
    onRequestDownload: (title: String, content: String, ext: String, mime: String) -> Unit
) {
    val result by viewModel.resultUi.collectAsStateWithLifecycle()
    AnimatedVisibility(visible = result != null) {
        result?.let { r ->
            ResultCard(
                title = r.title,
                sourceLabel = r.sourceLabel,
                transcript = r.transcript,
                wordCount = r.wordCount,
                hasTimeline = r.hasTimeline,
                onCopy = viewModel::copyToClipboard,
                onShare = viewModel::shareTranscript,
                onDownloadTxt = {
                    onRequestDownload(r.title.ifBlank { "文案" }, r.transcript, "txt", "text/plain")
                },
                onDownloadSrt = {
                    viewModel.lastOutcome?.let { oc ->
                        onRequestDownload(
                            r.title.ifBlank { "字幕" },
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

@Composable
private fun EngineStatusRow(ready: Boolean, name: String) {
    val bgColor = if (ready) ClaudeSuccess.copy(alpha = 0.08f) else ClaudeWarning.copy(alpha = 0.08f)
    val dotColor = if (ready) ClaudeSuccess else ClaudeWarning
    val textColor = if (ready) ClaudeSuccess else ClaudeWarning

    ClaudeCard(containerColor = bgColor) {
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
                    .background(dotColor)
            )
            Text(
                if (ready) "$name 引擎已就绪" else "正在初始化模型…",
                color = textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            if (ready) ClaudePill("就绪", ClaudeSuccess) else ClaudePill("加载中", ClaudeWarning)
        }
    }
}

@Composable
private fun ProgressCard(progress: Float, phase: String) {
    val animated by animateFloatAsState(progress, tween(300), label = "p")
    ClaudeCard {
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
                    color = ClaudeAccent
                )
                Text(
                    "${(animated * 100).toInt()}",
                    color = ClaudeTextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Column {
                Text(phase, color = ClaudeTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("处理中，请稍候…", color = ClaudeTextSecondary, fontSize = 12.sp)
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
    ClaudeCard {
        Column(Modifier.padding(16.dp)) {
            // 标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ClaudeTextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text("$wordCount 字", fontSize = 12.sp, color = ClaudeTextSecondary)
                        if (sourceLabel != null) ClaudePill(sourceLabel)
                    }
                }
                IconButton(onClick = onClear) {
                    Text(
                        "✕",
                        color = ClaudeTextTertiary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 文案展示区
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ClaudeSurfaceHover)
            ) {
                SelectionContainer {
                    Text(
                        transcript,
                        fontSize = 15.sp,
                        lineHeight = 25.sp,
                        color = ClaudeTextSecondary,
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // 操作按钮 — 第一行
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ClaudeOutlineButton("📋 复制", Modifier.weight(1f), onClick = onCopy)
                ClaudeOutlineButton("📤 分享", Modifier.weight(1f), onClick = onShare)
            }
            // 操作按钮 — 第二行
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ClaudeOutlineButton(
                    "💾 TXT",
                    Modifier.weight(1f),
                    tint = ClaudeAccent,
                    onClick = onDownloadTxt
                )
                if (hasTimeline) {
                    ClaudeOutlineButton(
                        "🎬 SRT",
                        Modifier.weight(1f),
                        tint = ClaudeAccent,
                        onClick = onDownloadSrt
                    )
                }
            }
        }
    }
}
