package com.example.bilitranscript

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bilitranscript.theme.*

/** 全屏夜间渐变 + 极光光晕背景。把页面内容放进去即可。 */
@Composable
fun AppBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NightTop, NightMid, NightBottom)))
            .drawBehind {
                // 右上极光
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(AuroraIndigo.copy(alpha = 0.20f), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.08f),
                        radius = size.width * 0.7f
                    ),
                    radius = size.width * 0.7f,
                    center = Offset(size.width * 0.85f, size.height * 0.08f)
                )
                // 左下极光
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(AuroraViolet.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.1f, size.height * 0.95f),
                        radius = size.width * 0.7f
                    ),
                    radius = size.width * 0.7f,
                    center = Offset(size.width * 0.1f, size.height * 0.95f)
                )
            },
        content = content
    )
}

/** 玻璃拟态卡片：半透明底 + 细描边 + 大圆角。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    containerColor: Color = SurfaceGlass,
    cornerRadius: Int = 22,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(containerColor)
            .then(
                Modifier.background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.05f), Color.Transparent)
                    )
                )
            )
            .border(BorderStroke(1.dp, Hairline), RoundedCornerShape(cornerRadius.dp))
    ) {
        content()
    }
}

/** 小节标题（全大写式分组标签） */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = modifier
    )
}

/** 主行动按钮：渐变填充 + 大圆角。 */
@Composable
fun PrimaryGradientButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val brush = if (enabled)
        Brush.horizontalGradient(listOf(AuroraCyan, AuroraIndigo))
    else
        Brush.horizontalGradient(listOf(Color.White.copy(0.08f), Color.White.copy(0.08f)))

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(brush)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) Color(0xFF06121F) else TextFaint
        )
    }
}

/** 描边幽灵按钮 */
@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    tint: Color = TextPrimary,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(BorderStroke(1.dp, Hairline), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 14.dp)
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Text(text, color = tint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** 小药丸标签 */
@Composable
fun Pill(text: String, color: Color = AuroraCyan, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.35f)), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
