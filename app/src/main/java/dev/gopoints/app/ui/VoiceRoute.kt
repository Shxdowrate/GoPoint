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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.gopoints.app.data.Prefs
import dev.gopoints.app.route.Cmd
import dev.gopoints.app.route.GeoState
import dev.gopoints.app.route.Place
import dev.gopoints.app.route.RouteStore
import dev.gopoints.app.route.Spoken
import dev.gopoints.app.route.Stop
import dev.gopoints.app.route.VoiceIO
import dev.gopoints.app.route.VoiceParse

private enum class Phase { IDLE, SPEAKING, LISTENING, THINKING }

private data class Line(val mine: Boolean, val text: String, val options: List<String> = emptyList())

/**
 * Голосовой ввод маршрута — коротко, без лишних слов:
 * «Белогорье, Заводская 11» → «Заводская улица, 11?» → «да» → сигнал → следующая.
 */
@Composable
fun VoiceRoute(onClose: () -> Unit, onBuild: () -> Unit) {
    val ctx = LocalContext.current
    val io = remember { VoiceIO(ctx) }
    val log = remember { mutableStateListOf<Line>() }
    val added = remember { mutableStateListOf<Stop>() }
    var phase by remember { mutableStateOf(Phase.IDLE) }
    var partial by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    var active by remember { mutableStateOf(false) }
    var runId by remember { mutableIntStateOf(0) }
    var replies by remember { mutableStateOf(Prefs.voiceReplies) }
    var hasMic by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val available = remember { VoiceIO.available(ctx) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasMic = ok
        if (ok) { active = true; runId++ }
    }

    DisposableEffect(Unit) {
        io.onPartial = { partial = it }
        io.onLevel = { level = it }
        onDispose { io.release() }
    }
    LaunchedEffect(replies) { io.speak = replies }
    LaunchedEffect(Unit) { if (hasMic && available) { active = true; runId++ } }

    fun close() {
        io.stopAll()
        onClose()
    }

    LaunchedEffect(runId, active) {
        if (!active || !hasMic || !available) {
            io.stopAll()
            phase = Phase.IDLE
            partial = ""
            return@LaunchedEffect
        }

        suspend fun say(text: String, options: List<String> = emptyList()) {
            log += Line(false, text, options)
            phase = Phase.SPEAKING
            io.say(text)
        }

        /** Слушаем; не расслышал — сигнал и ещё раз, молча. */
        suspend fun hear(): String? {
            repeat(2) { attempt ->
                phase = Phase.LISTENING
                partial = ""
                val r = io.listen()
                partial = ""
                if (!r.isNullOrBlank()) {
                    log += Line(true, r)
                    return r
                }
                if (attempt == 0) io.beep(ok = false)
            }
            return null
        }

        fun add(name: String, address: String, place: Place?) {
            val s = Stop(
                id = System.currentTimeMillis(),
                name = name.ifBlank { place?.address ?: address },
                address = place?.address ?: address,
                lat = place?.lat, lon = place?.lon,
                geo = if (place != null) GeoState.OK else GeoState.MISSING,
            )
            RouteStore.add(s)
            added += s
        }

        var pending: String? = null
        while (true) {
            val u = pending ?: hear() ?: break
            pending = null

            when (VoiceParse.command(u)) {
                Cmd.DONE -> {
                    if (RouteStore.state.value.stops.isNotEmpty()) {
                        say("Строю маршрут.")
                        onBuild()
                    }
                    close()
                    return@LaunchedEffect
                }
                Cmd.UNDO -> {
                    added.lastOrNull()?.let {
                        RouteStore.remove(it.id)
                        added.removeAt(added.lastIndex)
                        say("Удалил.")
                    }
                    continue
                }
                Cmd.STOP -> { close(); return@LaunchedEffect }
                else -> {}
            }

            var spoken = VoiceParse.split(u)
            if (spoken.house == null) {
                say("Номер дома?")
                val h = hear() ?: break
                val num = VoiceParse.split(h).house
                if (num == null) { io.beep(ok = false); continue }
                spoken = Spoken(spoken.words, num)
            }
            phase = Phase.THINKING
            val (name, sayAddr, places) = VoiceParse.search(ctx, spoken)

            when {
                places.isEmpty() -> {
                    say("Нет на карте. Записать?", listOf("Да", "Нет"))
                    val a = hear() ?: break
                    when {
                        VoiceParse.yesNo(a) == Cmd.YES -> { add(name, sayAddr, null); io.beep(ok = true) }
                        VoiceParse.split(a).house != null -> pending = a
                        else -> io.beep(ok = false)
                    }
                }
                places.size == 1 -> {
                    val p = places.first()
                    say("${p.address}?", listOf("Да", "Нет"))
                    val a = hear() ?: break
                    when {
                        VoiceParse.yesNo(a) == Cmd.YES -> { add(name, sayAddr, p); io.beep(ok = true) }
                        VoiceParse.yesNo(a) == Cmd.NO -> io.beep(ok = false)
                        VoiceParse.split(a).house != null -> pending = a
                        else -> { add(name, sayAddr, p); io.beep(ok = true) }
                    }
                }
                else -> {
                    val opts = places.take(3)
                    say(opts.joinToString(" или ") { it.address } + "?", opts.mapIndexed { i, p -> "${i + 1} · ${p.address}" })
                    val a = hear() ?: break
                    val digits = Regex("\\d+").findAll(a).map { it.value }.toList()
                    val byHouse = opts.indexOfFirst { p -> digits.any { d -> p.address.endsWith(" $d") || p.address.endsWith(",$d") } }
                    val i = VoiceParse.choice(a) ?: byHouse.takeIf { it >= 0 }
                    if (i != null && i < opts.size) { add(name, sayAddr, opts[i]); io.beep(ok = true) }
                    else io.beep(ok = false)
                }
            }
        }
        log += Line(false, "Пауза. Нажмите на микрофон, чтобы продолжить.")
        active = false
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        // шапка
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clickable(onClick = ::close).padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    GIcon(Ico.BACK, C.Acc, size = 18.dp, stroke = 2.2f)
                    Text("Маршрут", color = C.Acc, fontSize = 16.sp)
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Chip(if (replies) "Голос: вкл" else "Голос: выкл", replies) {
                    replies = !replies
                    Prefs.voiceReplies = replies
                    if (!replies) io.stopAll()
                }
            }
            Text("Голосом", color = C.Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("«Колосок, Ленина 145» или «Марково, Центральная 5» — потом «да»", color = C.Muted, fontSize = 14.sp)
        }

        if (added.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                added.forEachIndexed { i, s ->
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(C.Card2).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("${i + 1} · ${s.name}", color = C.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // диалог — занимает всё свободное место, низ всегда на экране
        val listState = rememberLazyListState()
        val rows = log.size + if (partial.isNotBlank()) 1 else 0
        LaunchedEffect(rows) { if (rows > 0) listState.animateScrollToItem(rows - 1) }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
        ) {
            items(log) { line -> Bubble(line, onOption = { io.inject(it) }) }
            if (partial.isNotBlank()) item { Bubble(Line(true, "$partial…"), onOption = {}) }
        }

        Column(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CommandChip("«Удали последнюю»") { io.inject("удали последнюю") }
                CommandChip("«Готово» — построить", strong = true) { io.inject("готово") }
            }
            when {
                !available -> Text(
                    "На телефоне нет распознавания речи. Установите или обновите приложение Google.",
                    color = C.Warn, fontSize = 14.sp,
                )
                !hasMic -> GlowButton("Разрешить микрофон", { askMic.launch(Manifest.permission.RECORD_AUDIO) }, height = 64.dp, icon = Ico.MIC)
                else -> MicButton(phase, level) { if (active) active = false else { active = true; runId++ } }
            }
        }
    }
}

@Composable
private fun Bubble(line: Line, onOption: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (line.mine) Alignment.End else Alignment.Start) {
        Box(
            Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (line.mine) C.Card2 else C.Card)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) { Text(line.text, color = if (line.mine) C.Ink else C.Soft, fontSize = 16.sp) }
        if (line.options.isNotEmpty()) {
            val answers = line.options.mapIndexed { i, o ->
                when (o) {
                    "Да" -> "да"
                    "Нет" -> "нет"
                    else -> listOf("первый", "второй", "третий").getOrElse(i) { "первый" }
                }
            }
            if (line.options.all { it.length <= 3 }) {
                Row(Modifier.padding(top = 6.dp).widthIn(max = 260.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    line.options.forEachIndexed { i, o -> OutlineButton(o, { onOption(answers[i]) }, Modifier.weight(1f), height = 44.dp) }
                }
            } else {
                Column(Modifier.padding(top = 6.dp).widthIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    line.options.forEachIndexed { i, o -> OutlineButton(o, { onOption(answers[i]) }, Modifier.fillMaxWidth(), height = 44.dp) }
                }
            }
        }
    }
}

@Composable
private fun CommandChip(text: String, strong: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp).clip(Pill).background(if (strong) C.Ink else C.Card2).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (strong) Color.Black else C.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

/** Кнопка микрофона: кольца расходятся, пока слушаем; полоски прыгают от громкости голоса. */
@Composable
private fun MicButton(phase: Phase, level: Float, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "mic")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "ring")
    val wave by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "wave")
    val lv by animateFloatAsState(((level + 2f) / 12f).coerceIn(0.15f, 1f), tween(120), label = "lv")
    val listening = phase == Phase.LISTENING
    val label = when (phase) {
        Phase.IDLE -> "Нажмите и говорите"
        Phase.SPEAKING -> "…"
        Phase.LISTENING -> "Слушаю"
        Phase.THINKING -> "Ищу на карте…"
    }
    Box(Modifier.fillMaxWidth().height(68.dp)) {
        if (listening) {
            Canvas(Modifier.fillMaxSize()) {
                listOf(ring, (ring + 0.5f) % 1f).forEach { p ->
                    val w = size.width * (1f + p * 0.05f)
                    val h = size.height * (1f + p * 0.35f)
                    drawRoundRect(
                        C.Acc.copy(alpha = 0.6f * (1f - p)),
                        topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(h / 2f),
                        style = Stroke(2.dp.toPx()),
                    )
                }
            }
        }
        Box(
            Modifier.fillMaxSize()
                .shadow(if (phase == Phase.IDLE) 10.dp else (14 + 14 * wave).dp, Pill, ambientColor = C.Acc, spotColor = C.AccDeep)
                .clip(Pill).background(AccBrush).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GIcon(Ico.MIC, C.OnAcc, size = 24.dp, stroke = 2.2f)
                Text(label, color = C.OnAcc, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                if (listening) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        listOf(0.5f, 0.9f, 1f, 0.7f, 0.85f).forEachIndexed { i, k ->
                            val pk = if (i % 2 == 0) wave else 1f - wave
                            Box(Modifier.width(3.dp).height((6 + 16 * lv * k * (0.5f + 0.5f * pk)).dp).clip(CircleShape).background(C.OnAcc))
                        }
                    }
                }
            }
        }
    }
}
