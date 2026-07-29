package com.example.bilitranscript

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.bilitranscript.theme.ClaudeAccent
import com.example.bilitranscript.theme.ClaudeAccentUltraLight
import com.example.bilitranscript.theme.ClaudeBorder
import com.example.bilitranscript.theme.ClaudeError
import com.example.bilitranscript.theme.ClaudeSuccess
import com.example.bilitranscript.theme.ClaudeSurfaceHover
import com.example.bilitranscript.theme.ClaudeTextPrimary
import com.example.bilitranscript.theme.ClaudeTextSecondary
import com.example.bilitranscript.theme.ClaudeTextTertiary
import com.example.bilitranscript.theme.ClaudeWarning

/**
 * 液态玻璃风格的模型下拉选择栏。
 *
 * - 收起态：显示当前选中模型（档位徽标 / 大小 / 安装状态 / 下载进度）。
 * - 点击展开：按「内置 → 轻档 → 中档 → 重档」分组的玻璃面板列表。
 * - 长按任意模型：弹出详情（约 50 字介绍、大小、安装成功率）。
 */
@Composable
fun LiquidGlassModelPicker(
    statuses: List<ModelStatus>,
    selectedId: String,
    onSelect: (AsrModelSpec) -> Unit,
    onDownload: (AsrModelSpec) -> Unit,
    onImport: (AsrModelSpec) -> Unit,
    onDelete: (AsrModelSpec) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var detailSpec by remember { mutableStateOf<AsrModelSpec?>(null) }

    val selected = statuses.firstOrNull { it.spec.id == selectedId }
        ?: statuses.firstOrNull { it.spec.bundled }
        ?: statuses.first()

    Column(Modifier.fillMaxWidth()) {
        // ---- 收起态：当前模型玻璃卡（点击展开/收起）----
        GlassPanel(onClick = { expanded = !expanded }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                selected.spec.name,
                                color = ClaudeTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                            )
                            TierPill(selected.spec.tier)
                        }
                        Text(
                            pickerStateLine(selected),
                            color = if (selected.installed) ClaudeSuccess else ClaudeTextTertiary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                    Text(
                        if (expanded) "▲" else "▼",
                        color = ClaudeTextTertiary, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                if (selected.downloading) {
                    LinearProgressIndicator(
                        progress = { selected.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 9.dp)
                            .clip(RoundedCornerShape(50)),
                        color = ClaudeAccent,
                        trackColor = ClaudeBorder
                    )
                    selected.stats?.let { stats ->
                        val speedStr = if (stats.bytesPerSec > 0) humanSpeed(stats.bytesPerSec) else "—"
                        val etaStr = if (stats.etaSec > 0) "剩 " + humanDuration(stats.etaSec) else "剩余计算中…"
                        Text(
                            "$speedStr · $etaStr · ${stats.mirror.ifBlank { "探测镜像中…" }}",
                            color = ClaudeTextTertiary, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        // ---- 展开态：分组模型面板 ----
        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            GlassPanel(modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    val tierOrder = listOf(ModelTier.BUNDLED, ModelTier.LIGHT, ModelTier.MID, ModelTier.HEAVY)
                    var firstGroup = true
                    tierOrder.forEach { tier ->
                        val group = statuses.filter { it.spec.tier == tier }
                        if (group.isNotEmpty()) {
                            Text(
                                tier.label,
                                color = ClaudeTextTertiary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(top = if (firstGroup) 2.dp else 10.dp, bottom = 2.dp)
                            )
                            firstGroup = false
                            group.forEach { st ->
                                ModelDropItem(
                                    status = st,
                                    selected = st.spec.id == selectedId,
                                    onAction = {
                                        when {
                                            st.downloading -> Unit
                                            st.installed -> onSelect(st.spec)
                                            st.spec.files.isNotEmpty() -> onDownload(st.spec)
                                        }
                                    },
                                    onLongPress = { detailSpec = st.spec }
                                )
                            }
                        }
                    }
                    Text(
                        "长按任意模型查看介绍 · 大小 · 安装成功率",
                        color = ClaudeTextTertiary, fontSize = 11.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 2.dp)
                    )
                }
            }
        }
    }

    // ---- 长按详情弹窗 ----
    detailSpec?.let { spec ->
        val st = statuses.firstOrNull { it.spec.id == spec.id } ?: return@let
        ModelDetailDialog(
            status = st,
            selected = spec.id == selectedId,
            onDismiss = { detailSpec = null },
            onDownload = { onDownload(spec); detailSpec = null },
            onSelect = { onSelect(spec); detailSpec = null },
            onImport = { onImport(spec); detailSpec = null },
            onDelete = { onDelete(spec); detailSpec = null }
        )
    }
}

/** 液态玻璃面板：半透明白 + 细边框 + 柔和投影（与 Claude 浅色主题协调的玻璃感）。 */
@Composable
private fun GlassPanel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    var m = modifier
        .shadow(
            elevation = 10.dp, shape = shape,
            ambientColor = Color(0xFF8A6D4A), spotColor = Color(0xFF8A6D4A)
        )
        .clip(shape)
        .background(Color.White.copy(alpha = 0.88f))
        .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.95f)), shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Box(m) { content() }
}

/** 档位小徽标。 */
@Composable
private fun TierPill(tier: ModelTier) {
    val color = when (tier) {
        ModelTier.BUNDLED -> ClaudeTextSecondary
        ModelTier.LIGHT -> ClaudeSuccess
        ModelTier.MID -> ClaudeAccent
        ModelTier.HEAVY -> ClaudeWarning
    }
    ClaudePill(tier.label, color)
}

/** 收起态的状态行文案。 */
private fun pickerStateLine(st: ModelStatus): String = when {
    st.downloading -> "下载中 ${(st.progress * 100).toInt()}% · 约 ${st.spec.approxSizeMb}MB"
    st.installed -> "已安装 · 约 ${st.spec.approxSizeMb}MB · 成功率 ${st.spec.downloadReliability}%"
    else -> "未安装 · 约 ${st.spec.approxSizeMb}MB · 成功率 ${st.spec.downloadReliability}%"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelDropItem(
    status: ModelStatus,
    selected: Boolean,
    onAction: () -> Unit,
    onLongPress: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) ClaudeAccentUltraLight else Color.Transparent)
            .combinedClickable(onClick = onAction, onLongClick = onLongPress)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(status.spec.name, color = ClaudeTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                when {
                    status.downloading -> "下载中 ${(status.progress * 100).toInt()}%"
                    status.installed -> "已安装 · 约 ${status.spec.approxSizeMb}MB"
                    else -> "未安装 · 约 ${status.spec.approxSizeMb}MB · 成功率 ${status.spec.downloadReliability}%"
                },
                color = if (status.installed) ClaudeSuccess else ClaudeTextTertiary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        when {
            status.downloading ->
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = ClaudeAccent)
            status.installed && selected ->
                Text("✓", color = ClaudeAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            status.installed ->
                Text("选用", color = ClaudeAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            status.spec.files.isNotEmpty() ->
                Text("下载", color = ClaudeAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
    if (status.downloading) {
        LinearProgressIndicator(
            progress = { status.progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .clip(RoundedCornerShape(50)),
            color = ClaudeAccent,
            trackColor = ClaudeBorder
        )
    }
}

/** 长按详情弹窗：约 50 字介绍 + 大小 + 安装成功率（源拉取难度评估）。 */
@Composable
private fun ModelDetailDialog(
    status: ModelStatus,
    selected: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onSelect: () -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit
) {
    val spec = status.spec
    Dialog(onDismissRequest = onDismiss) {
        GlassPanel {
            Column(Modifier.padding(20.dp)) {
                // 标题行
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(spec.name, color = ClaudeTextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TierPill(spec.tier)
                }

                // 约 50 字完整介绍
                Text(
                    spec.longDescription.ifBlank { spec.description },
                    color = ClaudeTextSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )

                // 大小 + 状态
                Text(
                    "体积：约 ${spec.approxSizeMb}MB · " + when {
                        status.downloading -> "下载中 ${(status.progress * 100).toInt()}%"
                        status.installed -> "已安装"
                        else -> "未安装"
                    },
                    color = ClaudeTextTertiary, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )

                // 安装成功率
                val r = spec.downloadReliability.coerceIn(0, 100)
                val rColor = when {
                    r >= 95 -> ClaudeSuccess
                    r >= 85 -> ClaudeAccent
                    else -> ClaudeWarning
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Text("安装成功率", color = ClaudeTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("$r%", color = rColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                LinearProgressIndicator(
                    progress = { r / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(50)),
                    color = rColor,
                    trackColor = ClaudeBorder
                )
                if (spec.reliabilityNote.isNotBlank()) {
                    Text(
                        spec.reliabilityNote,
                        color = ClaudeTextTertiary, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                }
                Text(
                    "成功率 = 从镜像源顺利拉取模型的把握（源可达性/速度/冗余评估），与识别准确率无关。",
                    color = ClaudeTextTertiary, fontSize = 10.sp, lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 5.dp)
                )

                // 操作按钮区
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when {
                        status.downloading -> Text("下载中…", color = ClaudeAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        status.installed && selected -> Text("✓ 使用中", color = ClaudeAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        status.installed -> DialogAction("选用", ClaudeAccent, onSelect)
                        spec.files.isNotEmpty() -> DialogAction("下载", ClaudeAccent, onDownload)
                    }
                    if (!spec.bundled && !status.downloading) {
                        DialogAction("导入压缩包", ClaudeTextSecondary, onImport)
                    }
                    if (status.installed && !spec.bundled && !status.downloading) {
                        DialogAction("删除", ClaudeError.copy(0.85f), onDelete)
                    }
                    Spacer(Modifier.weight(1f))
                    DialogAction("关闭", ClaudeTextTertiary, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun DialogAction(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}
