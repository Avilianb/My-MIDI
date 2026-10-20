package com.remoteesp32midi.glassmidi.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

val GlassAccent = Color(0xFF7DE3F4)
val GlassAccentWarm = Color(0xFFB7A4FF)
val GlassText = Color(0xFFF5F7FF)
val GlassMuted = Color(0xFF9BA9C2)
val GlassDanger = Color(0xFFFF8B9B)

@Composable
fun GlassSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    tint: Color = Color.White.copy(alpha = 0.055f),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(6.dp.toPx())
                    lens(10.dp.toPx(), 18.dp.toPx())
                },
                onDrawSurface = {
                    drawRect(tint)
                },
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
    GlassSurface(
        backdrop = backdrop,
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(50),
        tint = if (selected) GlassAccent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.055f),
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
