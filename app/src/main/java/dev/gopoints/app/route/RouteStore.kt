package dev.gopoints.app.route

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class GeoState { PENDING, OK, MISSING }

/** Точка маршрута — одна накладная (один грузополучатель). */
data class Stop(
    val id: Long,
    val name: String,
    val address: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val waybill: String? = null,
    val window: String? = null,
    val geo: GeoState = GeoState.PENDING,
) {
    val hasCoords get() = lat != null && lon != null

    /** «до 14:00» → минуты от полуночи. */
    val windowMin: Int?
        get() = window?.let { Regex("(\\d{1,2})[:.](\\d{2})").find(it) }?.let {
            it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt()
        }
}

data class RouteState(
    val stops: List<Stop> = emptyList(),
    /** Порядок построен после последнего изменения списка. */
    val optimized: Boolean = false,
)

/** Текущий маршрут (один на телефон), хранится в files/route.json. */
object RouteStore {
    private lateinit var file: File
    private val _state = MutableStateFlow(RouteState())
    val state: StateFlow<RouteState> = _state

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "route.json")
        _state.value = try {
            if (file.exists()) fromJson(JSONObject(file.readText())) else RouteState()
        } catch (e: Exception) {
            RouteState()
        }
    }

    @Synchronized
    private fun set(s: RouteState) {
        _state.value = s
        file.writeText(toJson(s).toString())
    }

    fun add(stop: Stop) = synchronized(this) { set(RouteState(_state.value.stops + stop, optimized = false)) }

    fun remove(id: Long) = synchronized(this) {
        set(_state.value.copy(stops = _state.value.stops.filterNot { it.id == id }))
    }

    fun update(id: Long, change: (Stop) -> Stop) = synchronized(this) {
        set(_state.value.copy(stops = _state.value.stops.map { if (it.id == id) change(it) else it }))
    }

    fun setOrder(stops: List<Stop>) = synchronized(this) { set(RouteState(stops, optimized = true)) }

    fun clear() = synchronized(this) { set(RouteState()) }

    private fun toJson(s: RouteState) = JSONObject()
        .put("optimized", s.optimized)
        .put("stops", JSONArray().apply {
            s.stops.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id); put("name", p.name); put("addr", p.address); put("geo", p.geo.name)
                    p.lat?.let { put("lat", it) }; p.lon?.let { put("lon", it) }
                    p.waybill?.let { put("wb", it) }; p.window?.let { put("win", it) }
                })
            }
        })

    private fun fromJson(o: JSONObject): RouteState {
        val a = o.optJSONArray("stops") ?: JSONArray()
        return RouteState(
            optimized = o.optBoolean("optimized", false),
            stops = (0 until a.length()).map { i ->
                val p = a.getJSONObject(i)
                Stop(
                    id = p.getLong("id"),
                    name = p.optString("name"),
                    address = p.optString("addr"),
                    lat = if (p.isNull("lat")) null else p.getDouble("lat"),
                    lon = if (p.isNull("lon")) null else p.getDouble("lon"),
                    waybill = if (p.isNull("wb")) null else p.getString("wb"),
                    window = if (p.isNull("win")) null else p.getString("win"),
                    geo = runCatching { GeoState.valueOf(p.optString("geo")) }.getOrDefault(GeoState.PENDING),
                )
            },
        )
    }
}
