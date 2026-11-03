package dev.gopoints.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationServices
import dev.gopoints.app.data.Mark
import dev.gopoints.app.data.Store
import dev.gopoints.app.data.dayTitle
import dev.gopoints.app.data.hm
import dev.gopoints.app.data.km
import dev.gopoints.app.data.plural
import dev.gopoints.app.route.GeoState
import dev.gopoints.app.route.Ocr
import dev.gopoints.app.route.Planner
import dev.gopoints.app.route.RouteStore
import dev.gopoints.app.route.Stop
import dev.gopoints.app.route.Visit
import dev.gopoints.app.route.Waybill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** Черновик накладной после распознавания — пользователь проверяет и добавляет. */
private data class Draft(
    val name: String,
    val address: String,
    val waybill: String?,
    val window: String?,
    val complete: Boolean,
    val editId: Long? = null,
)

@SuppressLint("MissingPermission")
private suspend fun lastLocation(ctx: Context): Pair<Double, Double>? {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
    ) return null
    return suspendCancellableCoroutine { cont ->
        LocationServices.getFusedLocationProviderClient(ctx).lastLocation
            .addOnSuccessListener { l -> if (cont.isActive) cont.resume(l?.let { it.latitude to it.longitude }) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }
}

private fun visitDone(v: Visit, marks: List<Mark>): Boolean =
    marks.any { Planner.km(v.lat, v.lon, it.lat, it.lon) < 0.15 }

@Composable
fun RouteScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by RouteStore.state.collectAsStateWithLifecycle()
    val today by Store.today.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf<String?>(null) }
    val drafts = remember { mutableStateListOf<Draft>() }
    var shotUri by rememberSaveable { mutableStateOf<String?>(null) }
    var start by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var expanded by remember { mutableStateOf<Int?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { start = lastLocation(ctx) }
    LaunchedEffect(confirmClear) { if (confirmClear) { delay(3000); confirmClear = false } }

    fun process(uris: List<Uri>) {
        scope.launch {
            uris.forEachIndexed { i, u ->
                busy = if (uris.size > 1) "Читаю накладную ${i + 1} из ${uris.size}…" else "Читаю накладную…"
                try {
                    val w = Waybill.parse(Ocr.recognize(ctx, u))
                    drafts += Draft(w.name ?: "", w.street ?: "", w.number, w.window, w.name != null && w.street != null)
                } catch (e: Exception) {
                    Toast.makeText(ctx, e.message ?: "Не удалось распознать", Toast.LENGTH_LONG).show()
                }
            }
            busy = null
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = shotUri
        if (ok && u != null) process(listOf(Uri.parse(u)))
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) process(uris)
    }

    fun shoot() {
        val dir = File(ctx.cacheDir, "shots").apply { mkdirs() }
        val f = File(dir, "shot-${System.currentTimeMillis()}.jpg")
        val u = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        shotUri = u.toString()
        camera.launch(u)
    }

    fun save(d: Draft) {
        d.editId?.let { RouteStore.remove(it) }
        val s = Stop(
            id = System.currentTimeMillis(),
            name = d.name.trim().ifBlank { "Без названия" },
            address = d.address.trim(),
            waybill = d.waybill,
            window = d.window?.trim()?.takeIf { it.isNotEmpty() },
        )
        RouteStore.add(s)
        scope.launch {
            val c = Planner.geocode(ctx, s.address)
            RouteStore.update(s.id) { it.copy(lat = c?.first, lon = c?.second, geo = if (c != null) GeoState.OK else GeoState.MISSING) }
        }
    }

    fun build() {
        scope.launch {
            busy = "Строю маршрут…"
            val loc = lastLocation(ctx) ?: start
            start = loc
            val stops = RouteStore.state.value.stops
            val plan = withContext(Dispatchers.Default) { Planner.plan(stops, loc?.first, loc?.second) }
            RouteStore.setOrder(Planner.ordered(plan, stops))
            busy = null
        }
    }

    val visits = Planner.visits(st.stops)
    val plan = Planner.measure(visits.filterNot { visitDone(it, today.marks) }, start?.first, start?.second)
    val doneVisits = visits.filter { visitDone(it, today.marks) }
    val ahead = visits.filterNot { visitDone(it, today.marks) }
    val lost = st.stops.filter { !it.hasCoords }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 24.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().rise(0), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(dayTitle(System.currentTimeMillis()), color = C.Muted, fontSize = 13.sp)
                    Text("Маршрут", color = C.Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                }
                if (st.stops.isNotEmpty()) {
                    Text(
                        if (confirmClear) "Точно? Ещё раз" else "Очистить",
                        Modifier.clickable {
                            if (confirmClear) { RouteStore.clear(); confirmClear = false } else confirmClear = true
                        }.padding(8.dp),
                        color = C.Bad, fontSize = 15.sp,
                    )
                }
            }
        }

        item {
            GCard(Modifier.rise(70).shadow(16.dp, CardShape, ambientColor = C.Acc, spotColor = C.AccDeep)) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (st.stops.isEmpty()) {
                        Text("Сфотографируйте накладные", color = C.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "GoPoints прочитает грузополучателя и адрес, найдёт их на карте и построит порядок объезда с учётом времени приёмки.",
                            color = C.Muted, fontSize = 14.sp,
                        )
                    } else {
                        val total = st.stops.size
                        val done = doneVisits.sumOf { it.stops.size }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Выполнено", color = C.Muted, fontSize = 13.sp)
                            Text("$done из $total", color = C.Ink, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (ahead.isEmpty()) "Все точки объехали"
                                else "Осталось ~${km(plan.km * 1000)} · ~${hm(plan.minutes * 60_000L)} с разгрузкой" +
                                    if (plan.lateStops > 0) " · не успеем к ${plan.lateStops}" else "",
                                color = if (plan.lateStops > 0) C.Warn else C.Muted, fontSize = 14.sp,
                            )
                        }
                        ShimmerBar(if (total == 0) 0f else done.toFloat() / total)
                        if (!st.optimized) Text("Список изменился — постройте маршрут заново", color = C.Warn, fontSize = 13.sp)
                    }
                    busy?.let { msg ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = C.Acc, strokeWidth = 2.dp)
                            Text(msg, color = C.Soft, fontSize = 14.sp)
                        }
                    }
                    when {
                        st.stops.isEmpty() -> GlowButton("Сфотографировать накладную", ::shoot, height = 60.dp, icon = Ico.CAMERA, fontSize = 17.sp)
                        !st.optimized -> GlowButton("Построить маршрут", ::build, height = 60.dp, icon = Ico.ROUTE, fontSize = 17.sp)
                        else -> GlowButton("Открыть в Навигаторе", { Planner.openNavigator(ctx, ahead) }, height = 60.dp, icon = Ico.NAV, fontSize = 17.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (st.stops.isNotEmpty()) OutlineButton("Фото", ::shoot, Modifier.weight(1f), icon = Ico.CAMERA)
                        OutlineButton("Галерея", {
                            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }, Modifier.weight(1f), icon = Ico.IMAGE)
                        OutlineButton("Вручную", { drafts += Draft("", "", null, null, complete = true) }, Modifier.weight(1f), icon = Ico.EDIT)
                    }
                    if (st.optimized && st.stops.isNotEmpty()) {
                        Text(
                            "Перестроить от текущего места",
                            Modifier.clickable(onClick = ::build).padding(vertical = 2.dp),
                            color = C.Acc, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        if (ahead.isNotEmpty()) {
            item {
                Column(Modifier.rise(140), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionTitle("Впереди · ${ahead.size} ${plural(ahead.size, "остановка", "остановки", "остановок")}")
                    GCard {
                        ahead.forEachIndexed { i, v ->
                            if (i > 0) Divider(start = 66.dp)
                            VisitRow(
                                number = doneVisits.size + i + 1, v = v, current = i == 0, done = false,
                                expanded = expanded == i, delayMs = 200 + i * 60,
                                onClick = { expanded = if (expanded == i) null else i },
                                onEdit = { s -> drafts += Draft(s.name, s.address, s.waybill, s.window, true, editId = s.id) },
                                onDelete = { s -> RouteStore.remove(s.id) },
                            )
                        }
                    }
                }
            }
        }

        if (lost.isNotEmpty()) {
            item {
                Column(Modifier.rise(180), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionTitle("Нет на карте · ${lost.size}")
                    GCard {
                        lost.forEachIndexed { i, s ->
                            if (i > 0) Divider(start = 66.dp)
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { drafts += Draft(s.name, s.address, s.waybill, s.window, true, editId = s.id) }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Tile("?", color = C.Warn)
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, color = C.Ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (s.geo == GeoState.PENDING) "${s.address} · ищу на карте…" else "${s.address} · не нашёл, нажмите исправить",
                                        color = if (s.geo == GeoState.PENDING) C.Muted else C.Warn, fontSize = 13.sp,
                                    )
                                }
                                GIcon(Ico.EDIT, C.Muted, size = 18.dp)
                            }
                        }
                    }
                }
            }
        }

        if (doneVisits.isNotEmpty()) {
            item {
                Column(Modifier.rise(220), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionTitle("Сделано · ${doneVisits.size}")
                    GCard {
                        doneVisits.forEachIndexed { i, v ->
                            if (i > 0) Divider(start = 66.dp)
                            VisitRow(i + 1, v, current = false, done = true, expanded = false, delayMs = 240 + i * 50, onClick = {}, onEdit = {}, onDelete = {})
                        }
                    }
                }
            }
        }

        if (st.stops.isNotEmpty()) {
            item { Fine("Точка считается сделанной, когда рядом с ней стоит отметка за сегодня.", Modifier.rise(260)) }
        }
    }

    drafts.firstOrNull()?.let { d ->
        DraftDialog(
            d,
            onSave = { save(it); drafts.removeAt(0) },
            onSkip = { drafts.removeAt(0) },
        )
    }
}

@Composable
private fun VisitRow(
    number: Int,
    v: Visit,
    current: Boolean,
    done: Boolean,
    expanded: Boolean,
    delayMs: Int,
    onClick: () -> Unit,
    onEdit: (Stop) -> Unit,
    onDelete: (Stop) -> Unit,
) {
    val first = v.stops.first()
    val window = v.stops.mapNotNull { it.window }.firstOrNull()
    Column(Modifier.rise(delayMs)) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !done, onClick = onClick)
                .then(if (current) Modifier.background(Brush.horizontalGradient(listOf(C.Acc.copy(alpha = 0.10f), Color.Transparent))) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                done -> Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(C.Card2), contentAlignment = Alignment.Center) {
                    GIcon(Ico.CHECK, C.Ok, size = 18.dp, stroke = 2.4f)
                }
                current -> NumberTileLive(number)
                else -> Tile("$number", color = C.Acc)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    v.stops.joinToString(", ") { it.name },
                    color = if (done) C.Soft else C.Ink, fontSize = 16.sp,
                    fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    first.address + if (v.stops.size > 1) " · ${v.stops.size} накладные" else "",
                    color = C.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (window != null && !done) {
                    Box(Modifier.padding(top = 2.dp).clip(Pill).background(Color(0xFF22150D)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                        Text("приёмка $window", color = C.Warn, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 66.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                v.stops.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            (s.waybill?.let { "№$it · " } ?: "") + s.name,
                            Modifier.weight(1f), color = C.Soft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        OutlineButton("Изм.", { onEdit(s) }, height = 34.dp)
                        OutlineButton("Убрать", { onDelete(s) }, color = C.Bad, height = 34.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberTileLive(n: Int) {
    val shape = RoundedCornerShape(12.dp)
    val t = rememberInfiniteTransition(label = "cur")
    val g by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "g")
    Box(
        Modifier.size(40.dp).shadow((6 + 12 * g).dp, shape, ambientColor = C.Acc, spotColor = C.Acc).clip(shape).background(AccBrush),
        contentAlignment = Alignment.Center,
    ) { Text("$n", color = C.OnAcc, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
}

/** Прогресс маршрута: светящаяся переливающаяся полоса. */
@Composable
private fun ShimmerBar(fraction: Float) {
    val t = rememberInfiniteTransition(label = "bar")
    val s by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "s")
    Box(Modifier.fillMaxWidth().height(6.dp).clip(Pill).background(Color(0xFF2A2A2E))) {
        if (fraction > 0f) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp)
                    .shadow(8.dp, Pill, ambientColor = C.Acc, spotColor = C.Acc)
                    .clip(Pill).background(AccBrush)
                    .drawWithContent {
                        drawContent()
                        val x = size.width * s
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.6f), Color.Transparent),
                                start = Offset(x - 40f, 0f), end = Offset(x + 40f, 0f),
                            )
                        )
                    }
            )
        }
    }
}

@Composable
private fun DraftDialog(d: Draft, onSave: (Draft) -> Unit, onSkip: () -> Unit) {
    var name by remember(d) { mutableStateOf(d.name) }
    var address by remember(d) { mutableStateOf(d.address) }
    var window by remember(d) { mutableStateOf(d.window ?: "") }
    Dialog(onDismissRequest = onSkip, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth()
                .shadow(24.dp, CardShape, ambientColor = C.Acc, spotColor = C.AccDeep)
                .clip(CardShape).background(C.Card)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                when {
                    d.editId != null -> "Исправить точку"
                    d.waybill != null -> "Накладная № ${d.waybill}"
                    d.name.isEmpty() && d.address.isEmpty() && d.complete -> "Новая точка"
                    else -> "Накладная"
                },
                color = C.Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            )
            if (!d.complete) Text("Распознано не всё — проверьте и допишите", color = C.Warn, fontSize = 14.sp)
            Field("Грузополучатель", name) { name = it }
            Field("Адрес доставки", address, hint = "ул. Зейская, 120") { address = it }
            Field("Время приёмки", window, hint = "до 14:00") { window = it }
            GlowButton(
                if (d.editId != null) "Сохранить" else "Добавить в маршрут",
                { if (address.isNotBlank()) onSave(d.copy(name = name, address = address, window = window)) },
                height = 54.dp, fontSize = 17.sp,
            )
            OutlineButton(if (d.editId != null) "Отмена" else "Пропустить", onSkip, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Field(label: String, value: String, hint: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = hint?.let { { Text(it, color = C.Chev) } },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = C.Acc, unfocusedBorderColor = C.Edge,
            focusedTextColor = C.Ink, unfocusedTextColor = C.Ink,
            focusedLabelColor = C.Acc, unfocusedLabelColor = C.Muted,
            cursorColor = C.Acc, focusedContainerColor = Color.Black, unfocusedContainerColor = Color.Black,
        ),
    )
}
