package dev.gopoints.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

val Pill = RoundedCornerShape(percent = 50)
val CardShape = RoundedCornerShape(16.dp)

/** Мягкое янтарное свечение фоном, медленно «дышит». */
@Composable
fun AmbientGlow(modifier: Modifier = Modifier, x: Float = 0.15f, y: Float = 0.04f) {
    val t = rememberInfiniteTransition(label = "ambient")
    val a by t.animateFloat(
        0.55f, 1f,
        infiniteRepeatable(tween(3500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "ambientA",
    )
    Canvas(modifier.fillMaxSize()) {
        val c = Offset(size.width * x, size.height * y)
        val r = size.width * (0.85f + 0.15f * a)
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFFFF8C00).copy(alpha = 0.24f * a), Color.Transparent), center = c, radius = r),
            radius = r, center = c,
        )
    }
}

/** Появление снизу с задержкой — для карточек и строк. */
fun Modifier.rise(delayMs: Int = 0): Modifier = composed {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        a.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 14.dp.toPx()
    }
}

/** Главная кнопка: градиент, пульсирующее свечение и бегущий блик. */
@Composable
fun GlowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
    icon: Ico? = null,
    fontSize: TextUnit = 19.sp,
) {
    val t = rememberInfiniteTransition(label = "btn")
    val glow by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow",
    )
    val sheen by t.animateFloat(
        -0.6f, 2.2f, infiniteRepeatable(tween(3400, easing = LinearEasing), RepeatMode.Restart), label = "sheen",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .shadow((8 + 16 * glow).dp, Pill, ambientColor = C.Acc, spotColor = C.AccDeep)
            .clip(Pill)
            .background(AccBrush)
            .drawWithContent {
                drawContent()
                val x = size.width * sheen
                val w = size.width * 0.18f
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                        start = Offset(x - w, 0f), end = Offset(x + w, size.height),
                    )
                )
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (icon != null) GIcon(icon, C.OnAcc, size = 24.dp, stroke = 2.2f)
            Text(text, color = C.OnAcc, fontSize = fontSize, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = C.Ink,
    height: Dp = 48.dp,
    icon: Ico? = null,
) {
    Box(
        modifier
            .height(height)
            .clip(Pill)
            .background(Color.Black)
            .border(1.dp, C.Edge, Pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) GIcon(icon, color, size = 18.dp, stroke = 2f)
            Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
fun RoundIconButton(ico: Ico, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(44.dp).clip(CircleShape).background(C.Card2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { GIcon(ico, C.Ink, size = 20.dp) }
}

@Composable
fun GCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(CardShape).background(C.Card.copy(alpha = 0.92f)), content = content)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), color = C.Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun Divider(start: Dp = 16.dp) {
    Box(Modifier.fillMaxWidth().padding(start = start).height(1.dp).background(C.Line))
}

/** Точка статуса с расходящимся кольцом. */
@Composable
fun PulseDot(color: Color = C.Acc) {
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "p")
    Box(
        Modifier.size(8.dp).drawBehind {
            val r = 4.dp.toPx()
            val k = (p / 0.7f).coerceAtMost(1f)
            drawCircle(color.copy(alpha = 0.55f * (1f - k)), radius = r + k * 9.dp.toPx())
            drawCircle(color, radius = r)
        }
    )
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(Pill)
            .background(if (selected) C.Ink else C.Card2)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Color.Black else C.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** Плитка 40×40 в строке списка; live — светится. */
@Composable
fun Tile(text: String, live: Boolean = false, color: Color = C.Ink, bg: Color = C.Card2) {
    val t = rememberInfiniteTransition(label = "tile")
    val g by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "g")
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.size(40.dp)
            .then(if (live) Modifier.shadow((4 + 12 * g).dp, shape, ambientColor = C.Acc, spotColor = C.Acc) else Modifier)
            .clip(shape).background(if (live) C.AccTile else bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (live) C.Acc else color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Fine(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 16.dp), color = C.Muted, fontSize = 12.sp)
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun HGap(w: Dp) = Spacer(Modifier.width(w))
