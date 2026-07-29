package com.example.bilitranscript

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bilitranscript.theme.ClaudeAccent
import com.example.bilitranscript.theme.ClaudeAccentUltraLight
import com.example.bilitranscript.theme.ClaudeBackground
import com.example.bilitranscript.theme.ClaudeBorder
import com.example.bilitranscript.theme.ClaudeInputBorder
import com.example.bilitranscript.theme.ClaudeInputFocusBorder
import com.example.bilitranscript.theme.ClaudeSuccess
import com.example.bilitranscript.theme.ClaudeSurface
import com.example.bilitranscript.theme.ClaudeTextOnAccent
import com.example.bilitranscript.theme.ClaudeTextPrimary
import com.example.bilitranscript.theme.ClaudeTextSecondary
import com.example.bilitranscript.theme.ClaudeTextTertiary
import com.example.bilitranscript.theme.LocalClaudeTokens

/**
 * Claude-style app background: warm cream with no gradients, no glow effects.
 * Flat, clean, minimal.
 */
@Composable
fun AppBackground(content: @Composable BoxScope.() -> Unit) {
    val tokens = LocalClaudeTokens.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.appBackground),
        content = content
    )
}

/**
 * Claude-style card: white background, subtle border, moderate corner radius.
 * No glassmorphism, no gradients, no blur.
 */
@Composable
fun ClaudeCard(
    modifier: Modifier = Modifier,
    containerColor: Color = ClaudeSurface,
    cornerRadius: Int = 12,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(containerColor)
            .border(BorderStroke(1.dp, ClaudeBorder), RoundedCornerShape(cornerRadius.dp))
    ) {
        content()
    }
}

/**
 * Section label: uppercase-style grouping label, Claude style.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = ClaudeTextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        modifier = modifier
    )
}

/**
 * Claude-style primary button: warm accent background, white text, rounded.
 */
@Composable
fun ClaudePrimaryButton(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tokens = LocalClaudeTokens.current
    val bgColor = if (enabled) ClaudeAccent else ClaudeTextTertiary
    val textColor = if (enabled) ClaudeTextOnAccent else ClaudeTextSecondary

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(tokens.buttonRadius.value))
            .background(bgColor)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
    }
}

/**
 * Claude-style outline/ghost button: transparent bg, accent border, text color customizable.
 */
@Composable
fun ClaudeOutlineButton(
    text: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    tint: Color = ClaudeAccent,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(BorderStroke(1.dp, ClaudeBorder), RoundedCornerShape(10.dp))
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
            Text(text, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * Claude-style pill / badge: rounded tag for status labels.
 */
@Composable
fun ClaudePill(
    text: String,
    color: Color = ClaudeAccent,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Claude-style outlined text field with proper theming.
 */
@Composable
fun ClaudeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = ClaudeTextTertiary, fontSize = 14.sp) },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        shape = RoundedCornerShape(10.dp),
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

/**
 * Thin divider line, Claude style.
 */
@Composable
fun ClaudeDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .height(1.dp)
            .background(ClaudeBorder)
    )
}

/**
 * Hint text: small, muted, Claude style.
 */
@Composable
fun ClaudeHintText(text: String) {
    Text(
        text = text,
        color = ClaudeTextTertiary,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        modifier = Modifier.padding(top = 6.dp)
    )
}

// --- Status pill variants ---

@Composable
fun StatusPill(text: String, isSuccess: Boolean) {
    ClaudePill(
        text = text,
        color = if (isSuccess) ClaudeSuccess else ClaudeAccent
    )
}
