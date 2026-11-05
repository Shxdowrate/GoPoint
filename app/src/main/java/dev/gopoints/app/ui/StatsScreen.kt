package dev.gopoints.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gopoints.app.data.DayLog
import dev.gopoints.app.data.RU
import dev.gopoints.app.data.Store
import dev.gopoints.app.data.cap
import dev.gopoints.app.data.hm
import dev.gopoints.app.data.km
import dev.gopoints.app.data.mins
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.time.format.TextStyle as JTextStyle

private class Bucket(val label: String, val days: List<DayLog>) {
    fun minutes(now: Long) = days.sumOf { it.workedMs(now) }.div(60_000).toInt()
    val points get() = days.sumOf { it.marks.size }
    val km get() = days.sumOf { it.distanceM } / 1000.0
    /** Средний простой на точке, мин. */
    fun avgStay(now: Long): Int {
        val st = days.flatMap { d -> d.marks.mapNotNull { d.stayMs(it, now) } }
        return if (st.isEmpty()) 0 else (st.sum() / st.size / 60_000).toInt()
    }
}

private enum class View { BARS, LINE, AREA }

@Composable
fun StatsScreen() {
    var month by rememberSaveable { mutableStateOf(false) }
    var view by rememberSaveable { mutableStateOf(View.BARS) }
    val today by Store.today.collectAsStateWithLifecycle()
    val version by Store.version.collectAsStateWithLifecycle()
    val now = rememberNow(60_000L)

    val buckets by produceState(emptyList<Bucket>(), month, version, today.marks.size, today.shifts.size, today.date) {
        value = withContext(Dispatchers.IO) {
            fun log(d: LocalDate): DayLog? = if (d.toString() == today.date) today else Store.loadOrNull(d.toString())
            if (!month) {
                val mon = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                (0 until 7).map { i ->
                    val d = mon.plusDays(i.toLong())
                    Bucket(shortDow(d), listOfNotNull(log(d)?.takeIf { it.hasWork }))
                }
            } else {
                val ym = YearMonth.now()
                val offset = ym.atDay(1).dayOfWeek.value - 1
                (1..ym.lengthOfMonth()).groupBy { (it - 1 + offset) / 7 }.values.map { ds ->
                    Bucket(
                        if (ds.first() == ds.last()) "${ds.first()}" else "${ds.first()}–${ds.last()}",
                        ds.mapNotNull { log(ym.atDay(it))?.takeIf { l -> l.hasWork } },
                    )
                }
            }
        }
    }

    val all = buckets.flatMap { it.days }
    val shifts = all.size
    val totalMin = buckets.sumOf { it.minutes(now) }
    val totalPts = buckets.sumOf { it.points }
    val stays = all.flatMap { d -> d.marks.mapNotNull { d.stayMs(it, now) } }
    val period = if (month) YearMonth.now().month.getDisplayName(JTextStyle.FULL_STANDALONE, RU).cap() + " ${YearMonth.now().year}"
    else {
        val mon = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        "${mon.dayOfMonth}–${mon.plusDays(6).dayOfMonth} " + mon.plusDays(6).month.getDisplayName(JTextStyle.FULL, RU)
    }
    val key = "$month-$view"

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 24.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(Modifier.rise(0)) {
                Text(period, color = C.Muted, fontSize = 13.sp)
                Text("Статистика", color = C.Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().rise(60), verticalAlignment = Alignment.CenterVertically) {
                Chip("Неделя", !month) { month = false }
                HGap(8.dp)
                Chip("Месяц", month) { month = true }
                Spacer(Modifier.weight(1f))
                Row(Modifier.clip(CircleShape).background(C.Card2).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    ViewButton(Ico.BARS, view == View.BARS) { view = View.BARS }
                    ViewButton(Ico.LINE, view == View.LINE) { view = View.LINE }
                    ViewButton(Ico.AREA, view == View.AREA) { view = View.AREA }
                }
            }
        }
        item {
            ChartCard(
                title = "Отработано",
                total = hm(totalMin * 60_000L),
                sub = "ср. ${hm(if (shifts > 0) totalMin * 60_000L / shifts else 0L)} за смену",
                values = buckets.map { it.minutes(now).toFloat() },
                valueLabels = buckets.map { b -> val m = b.minutes(now); if (m == 0) "—" else if (month) "${(m + 30) / 60} ч" else hm(m * 60_000L) },
                labels = buckets.map { it.label },
                color = C.Acc,
                barBrush = { top, bottom -> Brush.verticalGradient(listOf(Color(0xFFFFE08A), Color(0xFFFFB000), C.AccDeep), startY = top, endY = bottom) },
                view = view, animKey = key, height = 140.dp, month = month,
                modifier = Modifier.rise(120),
            )
        }
        item {
            ChartCard(
                title = "Точек",
                total = "$totalPts",
                sub = "ср. ${if (shifts > 0) totalPts / shifts else 0} за смену",
                values = buckets.map { it.points.toFloat() },
                valueLabels = buckets.map { if (it.points == 0) "—" else "${it.points}" },
                labels = buckets.map { it.label },
                color = Color(0xFFE5E5EA),
                barBrush = { top, bottom -> Brush.verticalGradient(listOf(Color.White, Color(0xFFB9B9C0)), startY = top, endY = bottom) },
                view = view, animKey = key + "p", height = 104.dp, month = month,
                modifier = Modifier.rise(180),
            )
        }
        item {
            ChartCard(
                title = "Простой на точке",
                total = if (stays.isEmpty()) "—" else mins(stays.sum() / stays.size),
                sub = "в среднем",
                values = buckets.map { it.avgStay(now).toFloat() },
                valueLabels = buckets.map { val m = it.avgStay(now); if (m == 0) "—" else "$m" },
                labels = buckets.map { it.label },
                color = Color(0xFF8FE0A6),
                barBrush = { top, bottom -> Brush.verticalGradient(listOf(Color(0xFFC8F5D4), Color(0xFF4CC27A)), startY = top, endY = bottom) },
                view = view, animKey = key + "s", height = 104.dp, month = month,
                modifier = Modifier.rise(240),
            )
        }
        item {
            val totalKm = all.sumOf { it.distanceM }
            ChartCard(
                title = "Пробег",
                total = km(totalKm),
                sub = "ср. ${km(if (shifts > 0) totalKm / shifts else 0.0)} за смену",
                values = buckets.map { it.km.toFloat() },
                valueLabels = buckets.map { if (it.km < 0.5) "—" else "${it.km.roundToInt()}" },
                labels = buckets.map { it.label },
                color = Color(0xFF7FB8FF),
                barBrush = { top, bottom -> Brush.verticalGradient(listOf(Color(0xFFCFE4FF), Color(0xFF4C8DFF)), startY = top, endY = bottom) },
                view = view, animKey = key + "k", height = 104.dp, month = month,
                modifier = Modifier.rise(300),
            )
        }
        item {
            val totalKm = all.sumOf { it.distanceM }
            val longest = all.flatMap { d -> d.marks.mapNotNull { m -> d.stayMs(m, now)?.let { m to it } } }.maxByOrNull { it.second }
            val hours = totalMin / 60.0
            GCard(Modifier.rise(360)) {
                StatRow("Смен", "$shifts")
                Divider()
                StatRow("Средняя смена", if (shifts > 0) hm(totalMin * 60_000L / shifts) else "—")
                Divider()
                StatRow("Точек в час", if (hours > 0.1) String.format(RU, "%.1f", totalPts / hours) else "—")
                Divider()
                StatRow("Км на точку", if (totalPts > 0) String.format(RU, "%.1f", totalKm / 1000 / totalPts) else "—")
                Divider()
                StatRow("Время на точках", hm(stays.sum()) + if (totalMin > 0) " · ${(stays.sum() / 600 / totalMin.coerceAtLeast(1)).coerceAtMost(100)}%" else "")
                Divider()
                StatRow(
                    "Самый долгий простой",
                    longest?.let { (m, ms) -> mins(ms) + " · " + (m.address ?: "") } ?: "—",
                )
            }
        }
    }
}

@Composable
private fun StatRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = C.Ink, fontSize = 16.sp)
        Text(value, color = C.Muted, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun ViewButton(ico: Ico, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp)
            .then(if (selected) Modifier.shadow(10.dp, CircleShape, ambientColor = C.Acc, spotColor = C.AccDeep) else Modifier)
            .clip(CircleShape)
            .background(if (selected) AccBrush else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { GIcon(ico, if (selected) C.OnAcc else C.Muted, size = 18.dp, stroke = 2.4f) }
}

@Composable
private fun ChartCard(
    title: String,
    total: String,
    sub: String,
    values: List<Float>,
    valueLabels: List<String>,
    labels: List<String>,
    color: Color,
    barBrush: (Float, Float) -> Brush,
    view: View,
    animKey: String,
    height: Dp,
    month: Boolean,
    modifier: Modifier = Modifier,
) {
    val progress = remember(animKey, values.size) { Animatable(0f) }
    LaunchedEffect(animKey, values.size) {
        progress.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
    }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = C.Soft, fontSize = 11.sp)

    GCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = C.Muted, fontSize = 13.sp)
                    Text(
                        total, color = C.Ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                        style = TextStyle(fontFeatureSettings = "tnum", shadow = Shadow(color.copy(alpha = 0.35f), blurRadius = 20f)),
                    )
                }
                Text(sub, color = C.Muted, fontSize = 14.sp)
            }
            Canvas(Modifier.fillMaxWidth().height(height)) {
                val n = values.size
                if (n == 0) return@Canvas
                val p = progress.value
                val gap = 8.dp.toPx()
                val colW = (size.width - gap * (n - 1)) / n
                val top = 20.dp.toPx()
                val bottom = size.height - 1.dp.toPx()
                val plot = bottom - top
                val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f) * 1.05f
                val cx = { i: Int -> i * (colW + gap) + colW / 2f }
                drawLine(C.Line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)

                if (view == View.BARS) {
                    val bw = if (month) colW * 0.62f else colW
                    values.forEachIndexed { i, v ->
                        val pi = (p * 1.5f - i * (0.5f / n)).coerceIn(0f, 1f)
                        val full = if (v <= 0f) 4.dp.toPx() else (v / max * plot).coerceAtLeast(6.dp.toPx())
                        val h = full * pi
                        val x = cx(i) - bw / 2f
                        if (v > 0f) {
                            val g = 4.dp.toPx()
                            drawRoundRect(color.copy(alpha = 0.16f * pi), Offset(x - g, bottom - h - g), Size(bw + 2 * g, h + 2 * g), CornerRadius(12.dp.toPx()))
                            drawRoundRect(barBrush(bottom - h, bottom), Offset(x, bottom - h), Size(bw, h), CornerRadius(8.dp.toPx()))
                        } else {
                            drawRoundRect(C.Card2, Offset(x, bottom - h), Size(bw, h), CornerRadius(4.dp.toPx()))
                        }
                        val t = measurer.measure(valueLabels[i], labelStyle)
                        drawText(t, topLeft = Offset(cx(i) - t.size.width / 2f, bottom - h - t.size.height - 4.dp.toPx()), alpha = pi)
                    }
                } else {
                    val pts = values.mapIndexed { i, v -> Offset(cx(i), bottom - (v / max) * plot) }
                    val line = Path().apply {
                        pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
                    }
                    val reveal = size.width * p
                    clipRect(right = reveal) {
                        if (view == View.AREA) {
                            val area = Path().apply {
                                addPath(line)
                                lineTo(pts.last().x, bottom)
                                lineTo(pts.first().x, bottom)
                                close()
                            }
                            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.45f), Color.Transparent), startY = top, endY = bottom))
                        }
                        drawPath(line, color.copy(alpha = 0.22f), style = Stroke(10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        drawPath(line, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    pts.forEachIndexed { i, o ->
                        if (o.x <= reveal + 1f) {
                            drawCircle(color.copy(alpha = 0.3f), 9.dp.toPx(), o)
                            drawCircle(C.Card, 4.5.dp.toPx(), o)
                            drawCircle(color, 4.5.dp.toPx(), o, style = Stroke(2.5.dp.toPx()))
                            val t = measurer.measure(valueLabels[i], labelStyle)
                            drawText(t, topLeft = Offset(o.x - t.size.width / 2f, (o.y - t.size.height - 10.dp.toPx()).coerceAtLeast(0f)))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEach { Text(it, Modifier.weight(1f), color = C.Muted, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 1) }
            }
        }
    }
}
