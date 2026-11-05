package dev.gopoints.app.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.view.drawToBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gopoints.app.data.Mark
import dev.gopoints.app.data.Track
import dev.gopoints.app.data.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/** Инверсия цветов тайлов + чуть приглушить — тёмная карта под тему приложения. */
val DarkTiles = ColorMatrixColorFilter(
    ColorMatrix(
        floatArrayOf(
            -0.85f, 0f, 0f, 0f, 235f,
            0f, -0.85f, 0f, 0f, 235f,
            0f, 0f, -0.8f, 0f, 230f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)

fun newMap(c: Context, interactive: Boolean): MapView {
    Configuration.getInstance().apply {
        userAgentValue = c.packageName
        osmdroidBasePath = File(c.cacheDir, "osm")
        osmdroidTileCache = File(c.cacheDir, "osm/tiles")
    }
    return MapView(c).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(interactive)
        isClickable = interactive
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        overlayManager.tilesOverlay.setColorFilter(DarkTiles)
        minZoomLevel = 4.0
        controller.setZoom(12.0)
        controller.setCenter(GeoPoint(50.2907, 127.5272))
        if (!interactive) setOnTouchListener { _, _ -> true }
    }
}

/** Кружок с номером отметки. */
private fun numberPin(c: Context, n: Int): BitmapDrawable {
    val d = c.resources.displayMetrics.density
    val size = (30 * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val cv = ACanvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0x55FFB020
    cv.drawCircle(size / 2f, size / 2f, size / 2f, p)
    p.color = 0xFFFFB020.toInt()
    cv.drawCircle(size / 2f, size / 2f, size / 2f - 3 * d, p)
    p.color = 0xFF1A1200.toInt()
    p.textAlign = Paint.Align.CENTER
    p.typeface = Typeface.DEFAULT_BOLD
    p.textSize = (if (n < 10) 14 else 12) * d
    cv.drawText("$n", size / 2f, size / 2f - (p.descent() + p.ascent()) / 2f, p)
    return BitmapDrawable(c.resources, bmp)
}

/** Точка старта / текущего места. */
private fun dot(c: Context, color: Int): BitmapDrawable {
    val d = c.resources.displayMetrics.density
    val size = (18 * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val cv = ACanvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0xFF000000.toInt()
    cv.drawCircle(size / 2f, size / 2f, size / 2f, p)
    p.color = color
    cv.drawCircle(size / 2f, size / 2f, size / 2f - 3 * d, p)
    return BitmapDrawable(c.resources, bmp)
}

/** Рисует путь, отметки, старт и конец; масштаб — чтобы всё влезло. */
private fun drawTrack(map: MapView, track: List<TrackPoint>, marks: List<Mark>) {
    val c = map.context
    map.overlays.clear()
    val pts = track.map { GeoPoint(it.lat, it.lon) }
    if (pts.size >= 2) {
        map.overlays.add(Polyline(map).apply {
            setPoints(pts)
            outlinePaint.color = 0x44FF9A00
            outlinePaint.strokeWidth = 9 * c.resources.displayMetrics.density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            setInfoWindow(null)
        })
        map.overlays.add(Polyline(map).apply {
            setPoints(pts)
            outlinePaint.color = 0xFFFFB020.toInt()
            outlinePaint.strokeWidth = 3.5f * c.resources.displayMetrics.density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            setInfoWindow(null)
        })
    }
    pts.firstOrNull()?.let { p ->
        map.overlays.add(Marker(map).apply {
            position = p; icon = dot(c, 0xFF8FE0A6.toInt()); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); setInfoWindow(null)
        })
    }
    if (pts.size >= 2) {
        map.overlays.add(Marker(map).apply {
            position = pts.last(); icon = dot(c, 0xFFF5F5F7.toInt()); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); setInfoWindow(null)
        })
    }
    marks.forEachIndexed { i, m ->
        map.overlays.add(Marker(map).apply {
            position = GeoPoint(m.lat, m.lon)
            icon = numberPin(c, i + 1)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null)
        })
    }
    val all = pts + marks.map { GeoPoint(it.lat, it.lon) }
    val fit = {
        when {
            all.size >= 2 -> {
                val box = BoundingBox.fromGeoPointsSafe(all)
                if (box.latitudeSpan < 0.002 && box.longitudeSpanWithDateLine < 0.002) {
                    map.controller.setZoom(16.0); map.controller.setCenter(box.centerWithDateLine)
                } else map.zoomToBoundingBox(box.increaseByScale(1.35f), false)
            }
            all.size == 1 -> { map.controller.setZoom(16.0); map.controller.setCenter(all.first()) }
            else -> Unit
        }
    }
    if (map.width > 0) fit() else map.addOnFirstLayoutListener { _, _, _, _, _ -> fit() }
    map.invalidate()
}

@Composable
fun TrackMap(
    track: List<TrackPoint>,
    marks: List<Mark>,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    onView: (MapView) -> Unit = {},
) {
    AndroidView(
        factory = { c -> newMap(c, interactive).also(onView) },
        update = { map ->
            val key = "${track.size}:${track.lastOrNull()?.t}:${marks.size}:${marks.lastOrNull()?.id}"
            if (map.tag != key) {
                map.tag = key
                drawTrack(map, track, marks)
            }
        },
        onRelease = { it.onDetach() },
        modifier = modifier,
    )
}

/** Трек за период, обновляется на лету. */
@Composable
fun rememberTrack(date: String, from: Long, to: Long?): List<TrackPoint> {
    val ver by Track.updated.collectAsStateWithLifecycle()
    val pts by produceState(emptyList<TrackPoint>(), date, from, to, ver) {
        value = withContext(Dispatchers.IO) { Track.load(date, from, to ?: Long.MAX_VALUE) }
    }
    return pts
}

/** Что показать на полноэкранной карте. */
data class MapRequest(
    val title: String,
    val sub: String,
    val date: String,
    val from: Long,
    val to: Long?,
    val marks: List<Mark>,
    val report: String,
)

object MapOverlay {
    var request by mutableStateOf<MapRequest?>(null)
}

private fun shareWithMap(ctx: Context, map: MapView?, text: String) {
    val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text)
    try {
        val bmp = map?.drawToBitmap() ?: error("no map")
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "gopoints-map.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        send.setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("map", uri)
    } catch (e: Exception) {
        send.setType("text/plain")
    }
    ctx.startActivity(Intent.createChooser(send, "Отправить отчёт с картой").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Карта на весь экран: путь, номера отметок, «Отправить с картой». */
@Composable
fun FullMapScreen(r: MapRequest, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val track = rememberTrack(r.date, r.from, r.to)
    var mapView by remember { mutableStateOf<MapView?>(null) }
    Box(Modifier.fillMaxSize().background(C.Bg).clickable(remember { MutableInteractionSource() }, indication = null) {}) {
        TrackMap(track, r.marks, Modifier.fillMaxSize(), interactive = true, onView = { mapView = it })
        // шапка поверх карты
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xF0000000), Color(0xA0000000), Color.Transparent)))
                .statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 28.dp),
        ) {
            Row(
                Modifier.clickable(onClick = onClose).padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GIcon(Ico.BACK, C.Acc, size = 18.dp, stroke = 2.2f)
                Text("Назад", color = C.Acc, fontSize = 16.sp)
            }
            Text(r.title, color = C.Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(r.sub, color = C.Soft, fontSize = 14.sp)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xC0000000), Color(0xF0000000))))
                .navigationBarsPadding().padding(start = 18.dp, end = 18.dp, top = 30.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (track.size < 2) {
                Text("Трек пуст: он пишется во время смены, начиная с версии 0.5.", color = C.Soft, fontSize = 14.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Legend(Color(0xFF8FE0A6), "старт")
                Legend(C.Acc, "отметки")
                Legend(C.Ink, "сейчас / финиш")
            }
            GlowButton("Отправить с картой", { shareWithMap(ctx, mapView, r.report) }, height = 58.dp, icon = Ico.NAV, fontSize = 17.sp)
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.padding(top = 1.dp).clip(Pill).background(color).padding(5.dp))
        Text(text, color = C.Soft, fontSize = 13.sp)
    }
}
