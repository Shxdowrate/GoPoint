package dev.gopoints.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gopoints.app.data.DayLog
import dev.gopoints.app.data.Mark
import dev.gopoints.app.data.Store
import dev.gopoints.app.data.clock
import dev.gopoints.app.data.dayReport
import dev.gopoints.app.data.dayTitle
import dev.gopoints.app.data.hm
import dev.gopoints.app.data.km
import dev.gopoints.app.data.mins
import dev.gopoints.app.data.points
import dev.gopoints.app.data.shareText
import dev.gopoints.app.tracking.TrackingService
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun rememberNow(periodMs: Long = 1000L): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(periodMs)
        }
    }
    return now
}

@Composable
fun TodayScreen(onSettings: () -> Unit, onStart: () -> Unit, onVoiceNote: (Long) -> Unit) {
    val day by Store.today.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val now = rememberNow()
    LaunchedEffect(now / 60_000) { Store.refresh() }
    var expanded by remember { mutableStateOf<Long?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 24.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { Header(now, onSettings) }
        item { StatusCard(day, now, onStart, onVoiceNote) }
        item {
            Fine("Ещё способы: громкость вниз ×2, Bluetooth-кнопка, автоотметка после стоянки", Modifier.rise(140))
        }
        item {
            Column(Modifier.rise(200), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Отметки · ${day.marks.size}") {
                    if (day.marks.isNotEmpty()) {
                        Text(
                            "Отправить", color = C.Acc, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { shareText(ctx, dayReport(day)) }.padding(4.dp),
                        )
                    }
                }
                GCard {
                    if (day.marks.isEmpty()) {
                        Text(
                            if (day.isActive) "Пока пусто. На точке нажмите «Отметить» — или просто постойте, GoPoints спросит сам."
                            else "Начните смену — и отметки за день появятся здесь.",
                            Modifier.padding(horizontal = 16.dp, vertical = 22.dp),
                            color = C.Muted, fontSize = 15.sp,
                        )
                    }
                    day.marks.reversed().forEachIndexed { i, m ->
                        if (i > 0) Divider(start = 66.dp)
                        MarkRow(
                            day, m, now,
                            expanded = expanded == m.id,
                            delayMs = 260 + i * 70,
                            onClick = { expanded = if (expanded == m.id) null else m.id },
                            onNote = { onVoiceNote(m.id) },
                            onDelete = { Store.deleteMark(day.date, m.id); expanded = null },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(now: Long, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().rise(0), verticalAlignment = Alignment.Bottom) {
        LogoMark()
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(dayTitle(now), color = C.Muted, fontSize = 13.sp)
            Row {
                Text("Go", color = C.Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Points", color = C.Acc, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                    style = TextStyle(shadow = Shadow(C.Acc.copy(alpha = 0.5f), blurRadius = 18f)),
                )
            }
        }
        RoundIconButton(Ico.SLIDERS, onSettings)
    }
}

@Composable
fun LogoMark() {
    val shape = RoundedCornerShape(11.dp)
    Box(
        Modifier.size(38.dp).shadow(14.dp, shape, ambientColor = C.Acc, spotColor = C.AccDeep).clip(shape).background(AccBrush),
        contentAlignment = Alignment.Center,
    ) { GIcon(Ico.PIN, C.OnAcc, size = 22.dp, stroke = 2.6f) }
}

@Composable
private fun StatusCard(day: DayLog, now: Long, onStart: () -> Unit, onVoiceNote: (Long) -> Unit) {
    val ctx = LocalContext.current
    var confirmEnd by remember { mutableStateOf(false) }
    LaunchedEffect(confirmEnd) {
        if (confirmEnd) {
            delay(3000)
            confirmEnd = false
        }
    }
    val active = day.isActive
    val label = when {
        active -> "Смена идёт · с ${clock(day.shifts.last().start)}"
        day.hasWork -> "Смена завершена"
        else -> "Смена не начата"
    }
    GCard(
        Modifier.rise(70)
            .then(if (active) Modifier.shadow(18.dp, CardShape, ambientColor = C.Acc, spotColor = C.AccDeep) else Modifier)
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (active) PulseDot() else Box(Modifier.size(8.dp).clip(CircleShape).background(C.Chev))
                    Text(label, color = C.Muted, fontSize = 13.sp)
                }
                Text(
                    hm(day.workedMs(now)),
                    color = C.Ink, fontSize = 46.sp, fontWeight = FontWeight.SemiBold,
                    style = TextStyle(
                        fontFeatureSettings = "tnum",
                        shadow = if (active) Shadow(C.Acc.copy(alpha = 0.45f), blurRadius = 28f) else null,
                    ),
                )
                Text(
                    "${points(day.marks.size)} · ${km(day.distanceM)} · на точках ${hm(day.onPointsMs(now))}",
                    color = C.Muted, fontSize = 14.sp,
                )
            }
            if (active) {
                GlowButton("Отметить точку", { TrackingService.send(ctx, TrackingService.ACT_MARK) }, height = 68.dp, icon = Ico.PIN, fontSize = 20.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlineButton("Заметка", {
                        day.marks.lastOrNull()?.let { onVoiceNote(it.id) }
                    }, Modifier.weight(1f), icon = Ico.MIC)
                    OutlineButton(
                        if (confirmEnd) "Точно? Ещё раз" else "Завершить",
                        {
                            if (confirmEnd) {
                                confirmEnd = false
                                TrackingService.send(ctx, TrackingService.ACT_STOP)
                            } else confirmEnd = true
                        },
                        Modifier.weight(1f), color = C.Bad,
                    )
                }
            } else {
                GlowButton(if (day.hasWork) "Начать новую смену" else "Начать смену", onStart, height = 68.dp, icon = Ico.PIN, fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun MarkRow(
    day: DayLog,
    m: Mark,
    now: Long,
    expanded: Boolean,
    delayMs: Int,
    onClick: () -> Unit,
    onNote: () -> Unit,
    onDelete: () -> Unit,
) {
    val stay = day.stayMs(m, now)
    val live = day.isActive && m.leftAt == null && stay != null
    val title = m.address ?: if (now - m.id < 60_000) "Определяю адрес…" else String.format(Locale.US, "%.5f, %.5f", m.lat, m.lon)
    val sub = listOfNotNull(m.note, if (m.auto) "авто" else null).joinToString(" · ")
    Column(Modifier.rise(delayMs)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Tile(clock(m.time), live = live)
            Column(Modifier.weight(1f)) {
                Text(title, color = C.Ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub.isNotEmpty()) Text(sub, color = C.Muted, fontSize = 13.sp, maxLines = if (expanded) 4 else 1, overflow = TextOverflow.Ellipsis)
            }
            if (stay != null) {
                Text(
                    if (live) "сейчас · ${mins(stay)}" else mins(stay),
                    color = if (live) C.Acc else C.Muted, fontSize = 14.sp,
                    style = TextStyle(shadow = if (live) Shadow(C.Acc.copy(alpha = 0.6f), blurRadius = 14f) else null),
                )
            }
        }
        AnimatedVisibility(expanded) {
            Row(
                Modifier.fillMaxWidth().padding(start = 66.dp, end = 14.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlineButton("Заметка", onNote, Modifier.weight(1f), height = 40.dp, icon = Ico.MIC)
                OutlineButton("Удалить", onDelete, Modifier.weight(1f), color = C.Bad, height = 40.dp, icon = Ico.TRASH)
            }
        }
    }
}
