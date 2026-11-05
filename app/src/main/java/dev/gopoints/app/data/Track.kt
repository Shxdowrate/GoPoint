package dev.gopoints.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Locale

data class TrackPoint(val t: Long, val lat: Double, val lon: Double)

/**
 * Трек — путь за день: files/track/2026-11-05.csv, строка «время,широта,долгота».
 * Пишем только точные точки с шагом от 20 м, так что за день — пара тысяч строк.
 */
object Track {
    private lateinit var dir: File

    /** Меняется при каждой новой точке — карта на экране обновляется. */
    val updated = MutableStateFlow(0L)

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "track").apply { mkdirs() }
    }

    @Synchronized
    fun append(date: String, t: Long, lat: Double, lon: Double) {
        File(dir, "$date.csv").appendText(String.format(Locale.US, "%d,%.6f,%.6f\n", t, lat, lon))
        updated.value = t
    }

    fun load(date: String, from: Long = 0L, to: Long = Long.MAX_VALUE): List<TrackPoint> {
        val f = File(dir, "$date.csv")
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val p = line.split(',')
            if (p.size < 3) return@mapNotNull null
            val t = p[0].toLongOrNull() ?: return@mapNotNull null
            if (t < from || t > to) return@mapNotNull null
            TrackPoint(t, p[1].toDoubleOrNull() ?: return@mapNotNull null, p[2].toDoubleOrNull() ?: return@mapNotNull null)
        }
    }
}
