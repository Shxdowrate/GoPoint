package dev.gopoints.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import dev.gopoints.app.route.Cmd
import dev.gopoints.app.route.GeoState
import dev.gopoints.app.route.Place
import dev.gopoints.app.route.RouteStore
import dev.gopoints.app.route.Stop
import dev.gopoints.app.route.VoiceIO
import dev.gopoints.app.route.VoiceParse
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class LineState { SEARCHING, FOUND, MISSING }

/** Строка диктовки: что сказал и что нашлось на карте. */
private data class DLine(
    val id: Long,
    val said: String,
    val name: String = "",
    val place: Place? = null,
    val state: LineState = LineState.SEARCHING,
    val reason: String? = null,
)

/**
 * Непрерывная диктовка: называешь адреса подряд, каждый — отдельная строка,
 * сразу проверяется по карте. В конце «Добавить» — всё уходит в маршрут.
 * Без переспросов: что не нашлось — правится нажатием на строку.
 */
@Composable
fun VoiceRoute(onClose: () -> Unit, onBuild: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val io = remember { VoiceIO(ctx) }
    val lines = remember { mutableStateListOf<DLine>() }
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    var editing by remember { mutableStateOf<DLine?>(null) }
    var hasMic by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val available = remember { VoiceIO.available(ctx) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasMic = ok
        if (ok) listening = true
    }

    DisposableEffect(Unit) {
        io.onPartial = { partial = it }
        io.onLevel = { level = it }
        onDispose { io.release() }
    }
    LaunchedEffect(Unit) { if (hasMic && available) listening = true }

    fun update(id: Long, f: (DLine) -> DLine) {
        val i = lines.indexOfFirst { it.id == id }
        if (i >= 0) lines[i] = f(lines[i])
    }

    fun resolve(line: DLine) {
        scope.launch {
            val spoken = VoiceParse.split(line.said)
            if (spoken.house == null) {
                update(line.id) { it.copy(state = LineState.MISSING, reason = "нет номера дома") }
                return@launch
            }
            val (name, _, places) = VoiceParse.search(ctx, spoken)
            update(line.id) {
                if (places.isEmpty()) it.copy(name = name, state = LineState.MISSING, reason = "не нашёл на карте")
                else it.copy(name = name, place = places.first(), state = LineState.FOUND, reason = null)
            }
        }
    }

    fun commit() {
        listening = false
        io.stopAll()
        var t = System.currentTimeMillis()
        lines.forEach { l ->
            val p = l.place
            RouteStore.add(
                Stop(
                    id = t++,
                    name = l.name.ifBlank { p?.address ?: l.said },
                    address = p?.address ?: l.said,
                    lat = p?.lat, lon = p?.lon,
                    geo = if (p != null) GeoState.OK else GeoState.MISSING,
                )
            )
        }
        if (lines.any { it.place != null }) onBuild()
        onClose()
    }

    // Слушаем без остановки: каждая фраза — одна или несколько строк.
    LaunchedEffect(listening) {
        if (!listening || !hasMic || !available) {
            io.stopAll()
            partial = ""
            return@LaunchedEffect
        }
        while (listening) {
            val r = io.listen()
            partial = ""
            if (r.isNullOrBlank()) {
                delay(150)
                continue
            }
            when (VoiceParse.command(r)) {
                Cmd.DONE -> { if (lines.isNotEmpty()) commit(); return@LaunchedEffect }
                Cmd.UNDO -> { if (lines.isNotEmpty()) lines.removeAt(lines.lastIndex); io.beep(ok = false); continue }
                Cmd.STOP -> { listening = false; return@LaunchedEffect }
                else -> {}
            }
            VoiceParse.chunks(r).forEach { c ->
                val line = DLine(System.nanoTime(), c)
                lines += line
                resolve(line)
            }
            io.beep(ok = true)
        }
    }

    val found = lines.count { it.state == LineState.FOUND }
    val missing = lines.count { it.state == LineState.MISSING }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp)) {
            Row(
                Modifier.clickable { io.stopAll(); onClose() }.padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GIcon(Ico.BACK, C.Acc, size = 18.dp, stroke = 2.2f)
                Text("Маршрут", color = C.Acc, fontSize = 16.sp)
            }
            Text("Диктовка", color = C.Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(
                "Называйте адреса подряд: «Колосок Ленина 145… Марково Центральная 5…». Каждый — отдельной строкой.",
                color = C.Muted, fontSize = 14.sp,
            )
        }

        val listState = rememberLazyListState()
        val rows = lines.size + if (partial.isNotBlank()) 1 else 0
        LaunchedEffect(rows) { if (rows > 0) listState.animateScrollToItem(rows - 1) }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(lines, key = { _, l -> l.id }) { i, l ->
                LineRow(i + 1, l, onEdit = { editing = l }, onDelete = { lines.remove(l) })
            }
            if (partial.isNotBlank()) item {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Card).padding(14.dp)) {
                    Text("$partial…", color = C.Soft, fontSize = 16.sp)
                }
            }
            if (lines.isEmpty() && partial.isBlank()) item {
                Text(
                    if (listening) "Слушаю. Говорите адрес." else "Нажмите на микрофон и говорите.",
                    color = C.Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 20.dp),
                )
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (lines.isNotEmpty()) {
                Text(
                    "Найдено $found из ${lines.size}" + if (missing > 0) " · $missing — нажмите, чтобы исправить" else "",
                    color = if (missing > 0) C.Warn else C.Muted, fontSize = 13.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    !available -> Text(
                        "На телефоне нет распознавания речи (нужно приложение Google).",
                        Modifier.weight(1f), color = C.Warn, fontSize = 14.sp,
                    )
                    !hasMic -> OutlineButton("Разрешить микрофон", { askMic.launch(Manifest.permission.RECORD_AUDIO) }, Modifier.weight(1f), height = 64.dp, icon = Ico.MIC)
                    else -> MicToggle(listening, level, Modifier.weight(if (lines.isEmpty()) 1f else 0.42f)) { listening = !listening }
                }
                if (lines.isNotEmpty()) {
                    GlowButton("Добавить ${lines.size}", ::commit, Modifier.weight(0.58f), height = 64.dp, icon = Ico.ROUTE, fontSize = 17.sp)
                }
            }
        }
    }

    editing?.let { l ->
        EditLineDialog(l, onDone = { addr, place ->
            update(l.id) {
                if (place != null) it.copy(said = addr, place = place, state = LineState.FOUND, reason = null)
                else it.copy(said = addr, state = LineState.SEARCHING)
            }
            if (place == null) lines.firstOrNull { it.id == l.id }?.let { resolve(it) }
            editing = null
        }, onClose = { editing = null })
    }
}

@Composable
private fun LineRow(n: Int, l: DLine, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().rise(0).clip(RoundedCornerShape(14.dp)).background(C.Card).clickable(onClick = onEdit)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Tile("$n", color = if (l.state == LineState.MISSING) C.Warn else C.Acc)
        Column(Modifier.weight(1f)) {
            Text(l.name.ifBlank { l.said }, color = C.Ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (l.state) {
                LineState.SEARCHING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CircularProgressIndicator(Modifier.size(12.dp), color = C.Acc, strokeWidth = 1.5.dp)
                    Text("ищу на карте…", color = C.Muted, fontSize = 13.sp)
                }
                LineState.FOUND -> Text("✓ ${l.place?.address}", color = C.Ok, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LineState.MISSING -> Text("⚠ ${l.reason ?: "не нашёл"} — исправить", color = C.Warn, fontSize = 13.sp)
            }
        }
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onDelete), contentAlignment = Alignment.Center) {
            GIcon(Ico.TRASH, C.Muted, size = 18.dp)
        }
    }
}

@Composable
private fun EditLineDialog(l: DLine, onDone: (String, Place?) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(l.place?.address ?: l.said) }
    var picked by remember { mutableStateOf(l.place) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth()
                .shadow(24.dp, CardShape, ambientColor = C.Acc, spotColor = C.AccDeep)
                .clip(CardShape).background(C.Card).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Исправить адрес", color = C.Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Сказано: «${l.said}»", color = C.Muted, fontSize = 14.sp)
            AddressField(text, picked, onChange = { text = it; if (picked?.address != it) picked = null }, onPick = { picked = it; text = it.address })
            GlowButton("Готово", { if (text.isNotBlank()) onDone(text, picked) }, height = 54.dp, fontSize = 17.sp)
        }
    }
}

/** Кнопка микрофона: кольца и полоски, пока слушаем. */
@Composable
private fun MicToggle(on: Boolean, level: Float, modifier: Modifier, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "mic")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "ring")
    val wave by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "wave")
    val lv by animateFloatAsState(((level + 2f) / 12f).coerceIn(0.15f, 1f), tween(120), label = "lv")
    Box(modifier.height(64.dp)) {
        if (on) {
            Canvas(Modifier.fillMaxSize()) {
                listOf(ring, (ring + 0.5f) % 1f).forEach { p ->
                    val w = size.width * (1f + p * 0.08f)
                    val h = size.height * (1f + p * 0.35f)
                    drawRoundRect(
                        C.Acc.copy(alpha = 0.6f * (1f - p)),
                        topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
                        size = Size(w, h), cornerRadius = CornerRadius(h / 2f), style = Stroke(2.dp.toPx()),
                    )
                }
            }
        }
        Box(
            Modifier.fillMaxSize()
                .shadow(if (on) 16.dp else 0.dp, Pill, ambientColor = C.Acc, spotColor = C.AccDeep)
                .clip(Pill)
                .then(if (on) Modifier.background(C.Card2) else Modifier.background(C.Card2))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GIcon(Ico.MIC, if (on) C.Acc else C.Ink, size = 24.dp, stroke = 2.2f)
                if (on) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        listOf(0.5f, 0.9f, 1f, 0.7f, 0.85f).forEachIndexed { i, k ->
                            val pk = if (i % 2 == 0) wave else 1f - wave
                            Box(Modifier.width(3.dp).height((6 + 16 * lv * k * (0.5f + 0.5f * pk)).dp).clip(CircleShape).background(C.Acc))
                        }
                    }
                } else {
                    Text("Пауза", color = C.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(0.dp))
            }
        }
    }
}
