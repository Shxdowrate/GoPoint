package dev.gopoints.app.route

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import dev.gopoints.app.data.RU
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Остановка = один адрес; на нём может быть несколько накладных. */
data class Visit(val lat: Double, val lon: Double, val stops: List<Stop>) {
    val windowMin: Int? get() = stops.mapNotNull { it.windowMin }.minOrNull()
}

data class Plan(val visits: List<Visit>, val km: Double, val minutes: Int, val lateStops: Int)

object Planner {
    /** Средняя скорость грузовика по городу и время на разгрузку одной накладной. */
    private const val SPEED_KMH = 25.0
    private const val UNLOAD_MIN = 15
    /** Дороги длиннее прямой примерно в 1,3 раза. */
    private const val ROAD_FACTOR = 1.3

    fun km(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(h)) * ROAD_FACTOR
    }

    /** Накладные с одним адресом (ближе 40 м) — одна остановка, в порядке списка. */
    fun visits(stops: List<Stop>): List<Visit> {
        val out = mutableListOf<Visit>()
        for (s in stops.filter { it.hasCoords }) {
            val i = out.indexOfFirst { km(it.lat, it.lon, s.lat!!, s.lon!!) / ROAD_FACTOR < 0.04 }
            if (i >= 0) out[i] = out[i].copy(stops = out[i].stops + s)
            else out += Visit(s.lat!!, s.lon!!, listOf(s))
        }
        return out
    }

    private class Eval(val km: Double, val minutes: Int, val late: Int, val cost: Double)

    private fun eval(order: List<Visit>, sLat: Double?, sLon: Double?, startMin: Int): Eval {
        var t = startMin.toDouble()
        var total = 0.0
        var lateMin = 0.0
        var late = 0
        var pLat = sLat
        var pLon = sLon
        for (v in order) {
            val d = if (pLat != null && pLon != null) km(pLat, pLon, v.lat, v.lon) else 0.0
            total += d
            t += d / SPEED_KMH * 60
            v.windowMin?.let { w -> if (t > w) { lateMin += t - w; late += v.stops.size } }
            t += UNLOAD_MIN * v.stops.size
            pLat = v.lat
            pLon = v.lon
        }
        return Eval(total, (t - startMin).toInt(), late, total + lateMin * 2)
    }

    /** Ближайший сосед от старта + улучшения (2-opt и перестановка одной точки). */
    private fun optimize(vs: List<Visit>, startLat: Double?, startLon: Double?, startMin: Int): List<Visit> {
        if (vs.size <= 1) return vs
        val left = vs.toMutableList()
        val order = mutableListOf<Visit>()
        var pLat = startLat ?: left.first().lat
        var pLon = startLon ?: left.first().lon
        while (left.isNotEmpty()) {
            val next = left.minBy { v ->
                val urgency = v.windowMin?.let { (it - startMin).coerceAtLeast(0) / 10.0 } ?: 50.0
                km(pLat, pLon, v.lat, v.lon) + urgency * 0.2
            }
            order += next
            left -= next
            pLat = next.lat
            pLon = next.lon
        }
        var best = order.toList()
        var bestCost = eval(best, startLat, startLon, startMin).cost
        var improved = true
        var guard = 0
        while (improved && guard++ < 200) {
            improved = false
            for (i in 0 until best.size - 1) for (j in i + 1 until best.size) {
                val cand = best.subList(0, i) + best.subList(i, j + 1).reversed() + best.subList(j + 1, best.size)
                val c = eval(cand, startLat, startLon, startMin).cost
                if (c < bestCost - 1e-6) { best = cand; bestCost = c; improved = true }
            }
            for (i in best.indices) for (j in best.indices) {
                if (i == j) continue
                val m = best.toMutableList()
                val v = m.removeAt(i)
                m.add(j, v)
                val c = eval(m, startLat, startLon, startMin).cost
                if (c < bestCost - 1e-6) { best = m; bestCost = c; improved = true }
            }
        }
        return best
    }

    /**
     * Порядок объезда. Опоздание к окну приёмки штрафуется (1 минута = 2 км), так что «до 12:00» уходит в начало.
     * [firstStopId] — «начать с этой точки»: она первая, остальное считается уже от неё.
     * Отложенные точки — в самом конце.
     */
    fun plan(
        stops: List<Stop>,
        startLat: Double?,
        startLon: Double?,
        now: LocalTime = LocalTime.now(),
        firstStopId: Long? = null,
    ): Plan {
        val all = visits(stops)
        val startMin = now.hour * 60 + now.minute
        if (all.isEmpty()) return Plan(emptyList(), 0.0, 0, 0)
        val first = firstStopId?.let { id -> all.firstOrNull { v -> v.stops.any { it.id == id } } }
        val later = all.filter { v -> v != first && v.stops.all { it.postponed } }
        val main = all.filter { it != first && it !in later }

        val head = if (first != null) {
            val toFirst = if (startLat != null && startLon != null) km(startLat, startLon, first.lat, first.lon) / SPEED_KMH * 60 else 0.0
            val t = startMin + toFirst.toInt() + UNLOAD_MIN * first.stops.size
            listOf(first) + optimize(main, first.lat, first.lon, t)
        } else optimize(main, startLat, startLon, startMin)

        val last = head.lastOrNull()
        val tail = optimize(later, last?.lat ?: startLat, last?.lon ?: startLon, startMin + eval(head, startLat, startLon, startMin).minutes)
        val best = head + tail
        val e = eval(best, startLat, startLon, startMin)
        return Plan(best, e.km, e.minutes, e.late)
    }

    /** Километры и время для уже заданного порядка (без перестановок). */
    fun measure(visits: List<Visit>, startLat: Double?, startLon: Double?, now: LocalTime = LocalTime.now()): Plan {
        val e = eval(visits, startLat, startLon, now.hour * 60 + now.minute)
        return Plan(visits, e.km, e.minutes, e.late)
    }

    /** Список накладных в порядке объезда; без координат — в конце. */
    fun ordered(plan: Plan, all: List<Stop>): List<Stop> =
        plan.visits.flatMap { it.stops } + all.filter { !it.hasCoords }

    /** Координаты по адресу, в пределах Благовещенска и окрестностей. */
    suspend fun geocode(ctx: Context, address: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        try {
            if (!Geocoder.isPresent()) return@withContext null
            val q = if (address.contains("Благовещенск", true)) address else "Благовещенск, $address"
            @Suppress("DEPRECATION")
            val list = Geocoder(ctx, RU).getFromLocationName(q, 1, 50.15, 127.25, 50.55, 127.85)
            list?.firstOrNull()?.let { it.latitude to it.longitude }
        } catch (e: Exception) {
            null
        }
    }

    /** Отдаём маршрут в Яндекс Навигатор; если его нет — в Яндекс Карты. */
    fun openNavigator(ctx: Context, visits: List<Visit>) {
        if (visits.isEmpty()) return
        val f = { d: Double -> String.format(Locale.US, "%.6f", d) }
        val last = visits.last()
        val via = visits.dropLast(1)
        val navi = buildString {
            append("yandexnavi://build_route_on_map?lat_to=").append(f(last.lat)).append("&lon_to=").append(f(last.lon))
            via.forEachIndexed { i, v -> append("&lat_via_$i=").append(f(v.lat)).append("&lon_via_$i=").append(f(v.lon)) }
        }
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(navi)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            val rtext = (listOf("") + visits.map { "${f(it.lat)},${f(it.lon)}" }).joinToString("~")
            val maps = "https://yandex.ru/maps/?rtext=$rtext&rtt=auto"
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(maps)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
