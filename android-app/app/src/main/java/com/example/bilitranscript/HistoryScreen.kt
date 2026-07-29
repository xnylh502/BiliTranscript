package com.example.bilitranscript

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bilitranscript.theme.ClaudeAccent
import com.example.bilitranscript.theme.ClaudeError
import com.example.bilitranscript.theme.ClaudeSurface
import com.example.bilitranscript.theme.ClaudeTextPrimary
import com.example.bilitranscript.theme.ClaudeTextSecondary
import com.example.bilitranscript.theme.ClaudeTextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    viewModel: MainViewModel,
    onOpen: (HistoryRecord) -> Unit
) {
    val all by viewModel.history.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }

    val filtered = remember(all, query) {
        if (query.isBlank()) all
        else all.filter { it.title.contains(query, true) || it.text.contains(query, true) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp)
    ) {
        // 标题行
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("历史文案", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = ClaudeTextPrimary)
            Spacer(Modifier.width(8.dp))
            ClaudePill("${all.size}", color = ClaudeAccent)
            Spacer(Modifier.weight(1f))
            if (all.isNotEmpty()) {
                Text(
                    "清空",
                    color = ClaudeError,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showClearDialog = true }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // 搜索框
        ClaudeTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "搜索标题或文案内容",
            singleLine = true
        )

        Spacer(Modifier.height(14.dp))

        if (filtered.isEmpty()) {
            EmptyHistory(query.isNotBlank())
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filtered, key = { it.id }) { record ->
                    HistoryItem(
                        record = record,
                        onClick = { onOpen(record) },
                        onFavorite = { viewModel.toggleFavorite(record.id) },
                        onDelete = { viewModel.deleteHistory(record.id) }
                    )
                }
            }
        }
    }

    // 清空确认对话框
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            confirmButton = {
                TextButton(onClick = { viewModel.clearHistory(); showClearDialog = false }) {
                    Text("清空", color = ClaudeError, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("取消", color = ClaudeTextSecondary)
                }
            },
            title = { Text("清空全部历史？", color = ClaudeTextPrimary, fontWeight = FontWeight.SemiBold) },
            text = { Text("此操作不可恢复。", color = ClaudeTextSecondary) },
            containerColor = ClaudeSurface
        )
    }
}

@Composable
private fun HistoryItem(
    record: HistoryRecord,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    ClaudeCard(modifier = Modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            // 标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    record.title.ifBlank { "未命名" },
                    color = ClaudeTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (record.favorite) "★" else "☆",
                    color = if (record.favorite) ClaudeAccent else ClaudeTextTertiary,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onFavorite)
                        .padding(horizontal = 6.dp)
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                record.text,
                color = ClaudeTextSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ClaudePill(record.source, ClaudeAccent)
                Spacer(Modifier.width(8.dp))
                Text("${record.wordCount} 字", color = ClaudeTextTertiary, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Text(formatDate(record.createdAt), color = ClaudeTextTertiary, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "删除",
                    color = ClaudeError.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDelete)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyHistory(isSearch: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📭", fontSize = 44.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                if (isSearch) "没有匹配的记录" else "还没有历史记录",
                color = ClaudeTextSecondary,
                fontSize = 15.sp
            )
            if (!isSearch) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "提取过的文案会自动保存在这里",
                    color = ClaudeTextTertiary,
                    fontSize = 13.sp
                )
            }
        }
    }
}

private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
private fun formatDate(ts: Long): String = dateFormat.format(Date(ts))
