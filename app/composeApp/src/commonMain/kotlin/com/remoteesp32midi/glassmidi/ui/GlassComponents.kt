package com.remoteesp32midi.glassmidi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
val GlassAccent = Color(0xFF0066CC)
val GlassAccentWarm = Color(0xFF6C5CE7)
val GlassText = Color(0xFF1C1C1E)
val GlassMuted = Color(0xFF6E6E73)
val GlassDanger = Color(0xFFFF3B30)
val GlassSurfaceTint = Color(0xFFF5F5F7).copy(alpha = 0.94f)

@Composable
fun GlassSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    tint: Color = GlassSurfaceTint,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(tint)
            .border(
                width = 0.5.dp,
                color = Color.Black.copy(alpha = 0.08f),
                shape = shape,
            )
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun GlassPill(
    text: String,
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(
                if (selected) {
                    GlassAccent.copy(alpha = 0.16f)
                } else {
                    Color.Black.copy(alpha = 0.04f)
                },
            )
            .border(
                width = 0.5.dp,
                color = if (selected) {
                    GlassAccent.copy(alpha = 0.35f)
                } else {
                    Color.Black.copy(alpha = 0.08f)
                },
                shape = shape,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = TextStyle(
                color = if (enabled) GlassText else GlassMuted.copy(alpha = 0.5f),
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
        )
    }
}

@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        singleLine = true,
        textStyle = TextStyle(GlassText, 16.sp),
        cursorBrush = SolidColor(GlassAccent),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    BasicText(placeholder, style = TextStyle(GlassMuted, 16.sp))
                }
                inner()
            }
        },
    )
}
