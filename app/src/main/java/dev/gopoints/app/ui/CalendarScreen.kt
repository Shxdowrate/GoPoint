package dev.gopoints.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gopoints.app.data.DayLog
import dev.gopoints.app.data.RU
import dev.gopoints.app.data.Store
import dev.gopoints.app.data.cap
import dev.gopoints.app.data.clock
import dev.gopoints.app.data.dayReport
import dev.gopoints.app.data.dayTitle
import dev.gopoints.app.data.hm
import dev.gopoints.app.data.km
import dev.gopoints.app.data.mins
import dev.gopoints.app.data.plural
import dev.gopoints.app.data.points
import dev.gopoints.app.data.shareText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as JTextStyle

/** Загружает дни месяца; сегодняшний берётся «живым» из Store. */
@Composable
fun rememberMonth(ym: YearMonth): Map<Int, DayLog> {
    val today by Store.today.collectAsStateWithLifecycle()
    val version by Store.version.collectAsStateWithLifecycle()
    val days by produceState(emptyMap<Int, DayLog>(), ym, version, today) {
        value = withContext(Dispatchers.IO) {
            (1..ym.lengthOfMonth()).mapNotNull { d ->
                val key = ym.atDay(d).toString()
                val log = if (key == today.date) today else Store.loadOrNull(key)
                log?.let { d to it }
            }.toMap()
        }
    }
    return days
}

@Composable
fun CalendarScreen() {
    val ctx = LocalContext.current
    var ym by remember { mutableStateOf(YearMonth.now()) }
    var sel by remember { mutableIntStateOf(LocalDate.now().dayOfMonth) }
    val days = rememberMonth(ym)
    val now = rememberNow(30_000L)
    val worked = days.values.filter { it.hasWork }
    var sheet by remember { mutableStateOf<Long?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 24.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().rise(0), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${hm(worked.sumOf { it.workedMs(now) })} ч · ${points(worked.sumOf { it.marks.size })} · ${worked.size} ${plural(worked.size, "смена", "смены", "смен")}",
                        color = C.Muted, fontSize = 13.sp,
                    )
                    Text(
                        ym.month.getDisplayName(JTextStyle.FULL_STANDALONE, RU).cap() + if (ym.year != LocalDate.now().year) " ${ym.year}" else "",
                        color = C.Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                    )
                }
                RoundIconButton(Ico.LEFT, { ym = ym.minusMonths(1); sel = 1 })
                HGap(8.dp)
                RoundIconButton(Ico.RIGHT, { ym = ym.plusMonths(1); sel = 1 })
            }
        }
        item {
            GCard(Modifier.rise(80)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                        listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс").forEach {
                            Text(it.uppercase(), Modifier.weight(1f), color = C.Muted, fontSize = 12.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
                        }
                    }
                    val offset = ym.atDay(1).dayOfWeek.value - 1
                    val total = offset + ym.lengthOfMonth()
                    val rows = (total + 6) / 7
                    for (r in 0 until rows) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (c in 0 until 7) {
                                val d = r * 7 + c - offset + 1
                                if (d < 1 || d > ym.lengthOfMonth()) {
                                    Spacer(Modifier.weight(1f).height(52.dp))
                                } else {
                                    DayCell(d, days[d], now, d == sel, Modifier.weight(1f)) { sel = d }
                                }
                            }
                        }
                    }
                }
            }
        }
        val selDay = days[sel]
        item {
            val date = ym.atDay(sel.coerceAtMost(ym.lengthOfMonth()))
            GCard(Modifier.rise(160).shadow(16.dp, CardShape, ambientColor = C.Acc, spotColor = C.AccDeep)) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(dayTitle(date), color = C.Muted, fontSize = 13.sp)
                        if (selDay != null && selDay.hasWork) {
                            Text(
                                hm(selDay.workedMs(now)), color = C.Ink, fontSize = 36.sp, fontWeight = FontWeight.SemiBold,
                                style = TextStyle(fontFeatureSettings = "tnum", shadow = Shadow(C.Acc.copy(alpha = 0.4f), blurRadius = 24f)),
                            )
                            val start = clock(selDay.shifts.first().start)
                            val end = selDay.shifts.last().end?.let { clock(it) } ?: "идёт"
                            Text("${points(selDay.marks.size)} · ${km(selDay.distanceM)} · $start–$end", color = C.Muted, fontSize = 14.sp)
                        } else {
                            Text("Выходной", color = C.Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (selDay != null && selDay.hasWork) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlineButton("Карта дня", {
                                MapOverlay.request = MapRequest(
                                    title = dayTitle(date),
                                    sub = "${points(selDay.marks.size)} · ${km(selDay.distanceM)} · ${hm(selDay.workedMs(now))}",
                                    date = selDay.date, from = 0L, to = null,
                                    marks = selDay.marks, report = dayReport(selDay),
                                )
                            }, Modifier.weight(1f), height = 50.dp, icon = Ico.ROUTE)
                            OutlineButton("Отчёт", { shareText(ctx, dayReport(selDay)) }, Modifier.weight(1f), height = 50.dp, icon = Ico.NAV)
                        }
                    }
                }
            }
        }
        if (selDay != null && selDay.marks.isNotEmpty()) {
            item {
                Column(Modifier.rise(220), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionTitle("Отметки · ${selDay.marks.size}")
                    GCard {
                        selDay.marks.forEachIndexed { i, m ->
                            if (i > 0) Divider(start = 66.dp)
                            MarkRow(selDay, m, now, delayMs = 0) { sheet = m.id }
                        }
                    }
                }
            }
        }
    }
    val sd = days[sel]
    sheet?.let { id ->
        sd?.marks?.firstOrNull { it.id == id }?.let { m ->
            MarkSheet(sd, m, onNote = null, onDelete = { Store.deleteMark(sd.date, id); sheet = null }, onClose = { sheet = null })
        }
    }
}

@Composable
private fun DayCell(d: Int, log: DayLog?, now: Long, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val m = log?.takeIf { it.hasWork }?.workedMs(now)?.div(60_000)?.toInt() ?: 0
    val a = if (m == 0) 0f else (0.16f + (m - 280) / 290f * 0.74f).coerceIn(0.16f, 0.9f)
    val bg = if (m == 0) C.Card2 else C.Acc.copy(alpha = a)
    val fg = if (a > 0.55f) C.OnAcc else if (m == 0) C.Muted else C.Ink
    val shape = RoundedCornerShape(12.dp)
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(60L + d * 14L)
        pop.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
    }
    Column(
        modifier
            .height(52.dp)
            .graphicsLayer {
                alpha = pop.value
                scaleX = 0.6f + 0.4f * pop.value
                scaleY = 0.6f + 0.4f * pop.value
            }
            .then(
                when {
                    selected -> Modifier.shadow(14.dp, shape, ambientColor = C.Acc, spotColor = C.Acc)
                    a > 0.7f -> Modifier.shadow(8.dp, shape, ambientColor = C.Acc, spotColor = C.AccDeep)
                    else -> Modifier
                }
            )
            .clip(shape)
            .background(Color.Black)
            .background(bg)
            .then(if (selected) Modifier.border(2.dp, C.AccLight, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("$d", color = fg.copy(alpha = 0.85f), fontSize = 12.sp)
        Text(if (m == 0) "—" else hm(m * 60_000L), color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = TextStyle(fontFeatureSettings = "tnum"))
    }
}

/** Переиспользуется в статистике: подпись дня недели. */
fun shortDow(date: LocalDate): String = date.dayOfWeek.getDisplayName(JTextStyle.SHORT_STANDALONE, RU).lowercase(RU).take(2)
