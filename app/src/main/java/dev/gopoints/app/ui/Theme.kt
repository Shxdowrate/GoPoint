package dev.gopoints.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Палитра как в макетах: чёрный фон, тёмные карточки, янтарный акцент. */
object C {
    val Bg = Color(0xFF000000)
    val Card = Color(0xFF141416)
    val Card2 = Color(0xFF1C1C1F)
    val Line = Color(0xFF232326)
    val Edge = Color(0xFF3A3A3F)
    val Ink = Color(0xFFF5F5F7)
    val Soft = Color(0xFFC7C7CC)
    val Muted = Color(0xFF8E8E95)
    val Chev = Color(0xFF5C5C63)
    val Acc = Color(0xFFFFB020)
    val AccDeep = Color(0xFFFF8A00)
    val AccLight = Color(0xFFFFCC33)
    val AccTile = Color(0xFF2A1E08)
    val OnAcc = Color(0xFF1A1200)
    val Ok = Color(0xFF8FE0A6)
    val Warn = Color(0xFFFFB27A)
    val Bad = Color(0xFFFF8F80)
}

val AccBrush: Brush = Brush.horizontalGradient(listOf(C.AccDeep, Color(0xFFFFB000), C.AccLight))
val AccBrushV: Brush = Brush.verticalGradient(listOf(Color(0xFFFFE08A), Color(0xFFFFB000), C.AccDeep))

@Composable
fun GoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = C.Bg,
            surface = C.Card,
            primary = C.Acc,
            onPrimary = C.OnAcc,
            onBackground = C.Ink,
            onSurface = C.Ink,
        ),
        content = content,
    )
}
