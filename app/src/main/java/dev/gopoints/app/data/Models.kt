package dev.gopoints.app.data

import org.json.JSONArray
import org.json.JSONObject

/** Одна отметка: где и когда, адрес, заметка; leftAt — когда уехал с точки. */
data class Mark(
    val id: Long,
    val time: Long,
    val lat: Double,
    val lon: Double,
    val address: String? = null,
    val note: String? = null,
    val auto: Boolean = false,
    val leftAt: Long? = null,
)

data class Shift(val start: Long, val end: Long? = null, val distanceM: Double = 0.0) {
    fun workedMs(now: Long = System.currentTimeMillis()) = ((end ?: now) - start).coerceAtLeast(0L)
}

/** Всё, что записано за один день. */
data class DayLog(
    val date: String,
    val shifts: List<Shift> = emptyList(),
    val marks: List<Mark> = emptyList(),
    val distanceM: Double = 0.0,
) {
    val isActive: Boolean get() = shifts.lastOrNull()?.end == null && shifts.isNotEmpty()
    val hasWork: Boolean get() = shifts.isNotEmpty()

    fun workedMs(now: Long = System.currentTimeMillis()): Long = shifts.sumOf { it.workedMs(now) }

    /** Пробег смены (у старых записей с одной сменой он хранился только на день). */
    fun distOf(s: Shift): Double = if (shifts.size == 1 && s.distanceM == 0.0) distanceM else s.distanceM

    /** Отметки, поставленные во время этой смены. */
    fun marksIn(s: Shift): List<Mark> = marks.filter { it.time >= s.start && (s.end == null || it.time <= s.end) }

    /** Время на точках за смену. */
    fun onPointsMs(s: Shift, now: Long = System.currentTimeMillis()): Long = marksIn(s).sumOf { stayMs(it, now) ?: 0L }

    /** Время на точке для отметки; для текущей точки — до «сейчас». */
    fun stayMs(m: Mark, now: Long = System.currentTimeMillis()): Long? {
        val end = m.leftAt ?: if (isActive && m == marks.lastOrNull { it.leftAt == null }) now else null
        return end?.let { (it - m.time).coerceAtLeast(0L) }
    }

    fun onPointsMs(now: Long = System.currentTimeMillis()): Long =
        marks.sumOf { stayMs(it, now) ?: 0L }

    fun toJson(): JSONObject = JSONObject()
        .put("date", date)
        .put("distance", distanceM)
        .put("shifts", JSONArray().apply {
            shifts.forEach { s ->
                put(JSONObject().put("start", s.start).put("dist", s.distanceM).apply { s.end?.let { put("end", it) } })
            }
        })
        .put("marks", JSONArray().apply {
            marks.forEach { m ->
                put(JSONObject().apply {
                    put("id", m.id)
                    put("t", m.time)
                    put("lat", m.lat)
                    put("lon", m.lon)
                    m.address?.let { put("addr", it) }
                    m.note?.let { put("note", it) }
                    put("auto", m.auto)
                    m.leftAt?.let { put("left", it) }
                })
            }
        })

    companion object {
        fun fromJson(o: JSONObject): DayLog {
            val shifts = o.optJSONArray("shifts") ?: JSONArray()
            val marks = o.optJSONArray("marks") ?: JSONArray()
            return DayLog(
                date = o.getString("date"),
                distanceM = o.optDouble("distance", 0.0),
                shifts = (0 until shifts.length()).map { i ->
                    val s = shifts.getJSONObject(i)
                    Shift(s.getLong("start"), if (s.isNull("end")) null else s.getLong("end"), s.optDouble("dist", 0.0))
                },
                marks = (0 until marks.length()).map { i ->
                    val m = marks.getJSONObject(i)
                    Mark(
                        id = m.getLong("id"),
                        time = m.getLong("t"),
                        lat = m.getDouble("lat"),
                        lon = m.getDouble("lon"),
                        address = if (m.isNull("addr")) null else m.getString("addr"),
                        note = if (m.isNull("note")) null else m.getString("note"),
                        auto = m.optBoolean("auto", false),
                        leftAt = if (m.isNull("left")) null else m.getLong("left"),
                    )
                },
            )
        }
    }
}
