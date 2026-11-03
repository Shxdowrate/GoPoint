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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

private enum class Phase { IDLE, SPEAKING, LISTENING, THINKING }

private data class Line(val mine: Boolean, val text: String, val options: List<String> = emptyList())

private const val HELLO = "Называйте магазин и адрес. Например: Магнит, Зейская сто двадцать."

/**
 * Голосовой ввод маршрута: разговор по кругу без касаний.
 * «Магнит, Зейская 120» → «Магнит, Зейская, 120. Добавить?» → «да» → «Добавлено, точка 1. Следующая?»
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
    LaunchedEffect(Unit) { if (hasMic && available) { active = true; runId++ } }

    fun close() {
        io.stopAll()
        onClose()
    }

    // Сам разговор. Кнопка микрофона ставит на паузу (active = false — корутина отменяется) и продолжает.
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
                if (attempt == 0) say("Не расслышал. Повторите.")
            }
            return null
        }

        suspend fun ask(text: String, options: List<String> = emptyList()): String? {
            say(text, options)
            return hear()
        }

        fun add(name: String, address: String, place: Place?) {
            val s = Stop(
                id = System.currentTimeMillis(),
                name = name.ifBlank { "Без названия" },
                address = place?.address ?: address,
                lat = place?.lat, lon = place?.lon,
                geo = if (place != null) GeoState.OK else GeoState.MISSING,
            )
            RouteStore.add(s)
            added += s
        }

        var prompt = if (added.isEmpty()) HELLO else "Следующая?"
        while (true) {
            val u = ask(prompt) ?: break
            prompt = "Следующая?"
            when (VoiceParse.command(u)) {
                Cmd.DONE -> {
                    if (added.isEmpty() && RouteStore.state.value.stops.isEmpty()) {
                        say("Пока ни одной точки. Закрываю.")
                    } else {
                        say("Готово. Строю маршрут.")
                        onBuild()
                    }
                    close()
                    return@LaunchedEffect
                }
                Cmd.UNDO -> {
                    val last = added.lastOrNull()
                    if (last != null) {
                        RouteStore.remove(last.id)
                        added.removeAt(added.lastIndex)
                        say("Удалил: ${last.name}.")
                    } else say("Удалять нечего.")
                    continue
                }
                Cmd.REPEAT -> { prompt = log.lastOrNull { !it.mine }?.text ?: HELLO; continue }
                Cmd.STOP -> { close(); return@LaunchedEffect }
                else -> {}
            }

            val spoken = VoiceParse.split(u)
            if (spoken.house == null) {
                prompt = "Не услышал номер дома. Скажите магазин, улицу и дом."
                continue
            }
            phase = Phase.THINKING
            val (name, sayAddr, places) = VoiceParse.search(ctx, spoken)
            val title = if (name.isNotBlank()) "$name, " else ""

            when {
                places.isEmpty() -> {
                    val a = ask("На карте не нашёл. Записать как сказано: $title$sayAddr?", listOf("Да", "Нет"))
                    if (a != null && VoiceParse.yesNo(a) == Cmd.YES) {
                        add(name, sayAddr, null)
                        prompt = "Записал со слов, точка ${added.size}. Следующая?"
                    } else prompt = "Не добавляю. Следующая?"
                }
                places.size == 1 -> {
                    val p = places.first()
                    val a = ask("$title${p.address}. Добавить?", listOf("Да", "Нет"))
                    if (a != null && VoiceParse.yesNo(a) != Cmd.NO) {
                        add(name, sayAddr, p)
                        prompt = "Добавлено, точка ${added.size}. Следующая?"
                    } else prompt = "Не добавляю. Следующая?"
                }
                else -> {
                    val opts = places.take(3)
                    val ordinals = listOf("первый", "второй", "третий")
                    val text = "Нашёл ${opts.size}: " + opts.mapIndexed { i, p -> "${ordinals[i]} — ${p.address}" }.joinToString(", ") + ". Какой?"
                    val a = ask(text, opts.mapIndexed { i, p -> "${i + 1} · ${p.address}" })
                    val i = a?.let { VoiceParse.choice(it) }
                    if (i != null && i < opts.size) {
                        add(name, sayAddr, opts[i])
                        prompt = "Добавлено, точка ${added.size}. Следующая?"
                    } else prompt = "Не понял, какой. Не добавляю. Следующая?"
                }
            }
        }
        log += Line(false, "Пауза. Нажмите на микрофон, чтобы продолжить.")
        active = false
    }

    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(C.Bg)) {
            AmbientGlow(x = 0.5f, y = 0.95f)
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                // шапка
                Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp)) {
                    Row(
                        Modifier.clickable(onClick = ::close).padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        GIcon(Ico.BACK, C.Acc, size = 18.dp, stroke = 2.2f)
                        Text("Маршрут", color = C.Acc, fontSize = 16.sp)
                    }
                    Text("Голосом", color = C.Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
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

                // диалог
                val listState = rememberLazyListState()
                LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1) }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                ) {
                    items(log) { line -> Bubble(line, onOption = { io.inject(it) }) }
                    if (partial.isNotBlank()) item { Bubble(Line(true, "$partial…"), onOption = {}) }
                }

                // низ: команды и микрофон
                Column(
                    Modifier.fillMaxWidth().background(Color(0xCC0A0A0C)).padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CommandChip("«Да» / «Нет»") { io.inject("да") }
                        CommandChip("«Удали последнюю»") { io.inject("удали последнюю") }
                        CommandChip("«Готово»", strong = true) { io.inject("готово") }
                    }
                    when {
                        !available -> Text(
                            "На телефоне нет распознавания речи. Установите или обновите приложение Google.",
                            color = C.Warn, fontSize = 14.sp,
                        )
                        !hasMic -> GlowButton("Разрешить микрофон", { askMic.launch(Manifest.permission.RECORD_AUDIO) }, height = 64.dp, icon = Ico.MIC)
                        else -> MicButton(phase, level) {
                            if (active) active = false else { active = true; runId++ }
                        }
                    }
                }
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
            Column(Modifier.padding(top = 6.dp).widthIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                line.options.forEachIndexed { i, o ->
                    OutlineButton(o, {
                        onOption(
                            when (o) {
                                "Да" -> "да"
                                "Нет" -> "нет"
                                else -> listOf("первый", "второй", "третий").getOrElse(i) { "первый" }
                            }
                        )
                    }, Modifier.fillMaxWidth(), height = 44.dp)
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

/** Большая кнопка микрофона: кольца расходятся, пока слушаем; полоски прыгают от громкости голоса. */
@Composable
private fun MicButton(phase: Phase, level: Float, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "mic")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "ring")
    val wave by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "wave")
    val lv by animateFloatAsState(((level + 2f) / 12f).coerceIn(0.15f, 1f), tween(120), label = "lv")
    val listening = phase == Phase.LISTENING
    val label = when (phase) {
        Phase.IDLE -> "Нажмите и говорите"
        Phase.SPEAKING -> "Говорю…"
        Phase.LISTENING -> "Слушаю…"
        Phase.THINKING -> "Ищу на карте…"
    }
    Box(Modifier.fillMaxWidth().height(68.dp)) {
        if (listening) {
            Canvas(Modifier.fillMaxSize()) {
                listOf(ring, (ring + 0.5f) % 1f).forEach { p ->
                    val grow = 1f + p * 0.35f
                    val w = size.width * (1f + (grow - 1f) * 0.15f)
                    val h = size.height * grow
                    drawRoundRect(
                        C.Acc.copy(alpha = 0.6f * (1f - p)),
                        topLeft = androidx.compose.ui.geometry.Offset((size.width - w) / 2f, (size.height - h) / 2f),
                        size = androidx.compose.ui.geometry.Size(w, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f),
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
                            val phaseK = if (i % 2 == 0) wave else 1f - wave
                            Box(Modifier.width(3.dp).height((6 + 16 * lv * k * (0.5f + 0.5f * phaseK)).dp).clip(CircleShape).background(C.OnAcc))
                        }
                    }
                } else Spacer(Modifier.size(0.dp))
            }
        }
    }
}
