package dev.gopoints.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import dev.gopoints.app.R
import dev.gopoints.app.data.DayLog
import dev.gopoints.app.data.Mark
import dev.gopoints.app.data.clock
import dev.gopoints.app.data.mins
import dev.gopoints.app.route.Planner
import dev.gopoints.app.route.RouteStore
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import java.util.Locale

/** Название магазина из маршрута, если отметка стоит рядом с его точкой. */
fun routeNameFor(m: Mark): String? = RouteStore.state.value.stops.firstOrNull { s ->
    s.lat != null && s.lon != null && Planner.km(s.lat, s.lon, m.lat, m.lon) < 0.15
}?.name

fun coords(m: Mark) = String.format(Locale.US, "%.5f, %.5f", m.lat, m.lon)

private fun openInMaps(ctx: Context, m: Mark) {
    val lat = String.format(Locale.US, "%.6f", m.lat)
    val lon = String.format(Locale.US, "%.6f", m.lon)
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("yandexmaps://maps.yandex.ru/?pt=$lon,$lat&z=17&l=map")))
    } catch (e: ActivityNotFoundException) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon")))
    }
}

/** Тёмная карта OpenStreetMap с меткой. */
@Composable
fun PointMap(lat: Double, lon: Double, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { c ->
            Configuration.getInstance().apply {
                userAgentValue = c.packageName
                osmdroidBasePath = File(c.cacheDir, "osm")
                osmdroidTileCache = File(c.cacheDir, "osm/tiles")
            }
            MapView(c).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERTED_COLORS)
                controller.setZoom(17.0)
                controller.setCenter(GeoPoint(lat, lon))
                val mk = Marker(this)
                mk.position = GeoPoint(lat, lon)
                mk.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                mk.icon = ContextCompat.getDrawable(c, R.drawable.ic_map_pin)
                mk.setInfoWindow(null)
                overlays.add(mk)
            }
        },
        onRelease = { it.onDetach() },
        modifier = modifier,
    )
}

/** Карточка отметки: карта, время на точке, заметка, действия. */
@Composable
fun MarkSheet(day: DayLog, m: Mark, onNote: ((Long) -> Unit)?, onDelete: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val now = rememberNow(30_000L)
    val stay = day.stayMs(m, now)
    val live = day.isActive && m.leftAt == null && stay != null
    val name = routeNameFor(m)
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(confirm) { if (confirm) { delay(3000); confirm = false } }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(12.dp).navigationBarsPadding().fillMaxWidth()
                .shadow(24.dp, CardShape, ambientColor = C.Acc, spotColor = C.AccDeep)
                .clip(CardShape).background(C.Card),
        ) {
            Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))) {
                PointMap(m.lat, m.lon, Modifier.fillMaxWidth().height(220.dp))
            }
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name ?: m.address ?: coords(m), color = C.Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    if (name != null) Text(m.address ?: coords(m), color = C.Muted, fontSize = 14.sp)
                    if (m.auto) Text("Отмечено автоматически", color = C.Muted, fontSize = 13.sp)
                }
                GCard(Modifier.background(C.Card2, CardShape)) {
                    InfoRow("Прибыл", clock(m.time))
                    Divider()
                    InfoRow("Уехал", m.leftAt?.let { clock(it) } ?: if (live) "ещё на точке" else "—")
                    Divider()
                    InfoRow("Простой", stay?.let { mins(it) } ?: "—", accent = live)
                }
                m.note?.let { Text("«$it»", color = C.Soft, fontSize = 15.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onNote != null) OutlineButton(if (m.note == null) "Заметка" else "Новая заметка", { onNote(m.id) }, Modifier.weight(1f), icon = Ico.MIC)
                    OutlineButton("На карте", { openInMaps(ctx, m) }, Modifier.weight(1f), icon = Ico.NAV)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineButton(if (confirm) "Точно удалить?" else "Удалить", {
                        if (confirm) onDelete() else confirm = true
                    }, Modifier.weight(1f), color = C.Bad, icon = Ico.TRASH)
                    OutlineButton("Закрыть", onClose, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String, accent: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = C.Muted, fontSize = 15.sp)
        Text(value, color = if (accent) C.Acc else C.Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}
