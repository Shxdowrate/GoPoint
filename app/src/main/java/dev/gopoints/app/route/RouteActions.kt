package dev.gopoints.app.route

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

object RouteActions {
    /** Идёт построение маршрута — показываем на экране. */
    val building = MutableStateFlow(false)

    @SuppressLint("MissingPermission")
    suspend fun lastLocation(ctx: Context): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null
        return suspendCancellableCoroutine { cont ->
            LocationServices.getFusedLocationProviderClient(ctx).lastLocation
                .addOnSuccessListener { l -> if (cont.isActive) cont.resume(l?.let { it.latitude to it.longitude }) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
    }

    /** Строит порядок объезда от текущего места и сохраняет его. [firstStopId] — «начать с этой точки». */
    suspend fun build(ctx: Context, firstStopId: Long? = null): Pair<Double, Double>? {
        building.value = true
        try {
            val loc = lastLocation(ctx)
            if (firstStopId != null) {
                // «Начать с неё» для отложенной — значит, уже не отложена.
                val visit = Planner.visits(RouteStore.state.value.stops).firstOrNull { v -> v.stops.any { it.id == firstStopId } }
                visit?.let { v -> RouteStore.postpone(v.stops.map { it.id }.toSet(), false) }
            }
            val stops = RouteStore.state.value.stops
            val plan = withContext(Dispatchers.Default) { Planner.plan(stops, loc?.first, loc?.second, firstStopId = firstStopId) }
            RouteStore.setOrder(Planner.ordered(plan, stops))
            return loc
        } finally {
            building.value = false
        }
    }

    /** Добавляет точку и в фоне ищет её на карте. */
    suspend fun addAndLocate(ctx: Context, stop: Stop) {
        RouteStore.add(stop)
        if (stop.hasCoords) return
        val c = Planner.geocode(ctx, stop.address)
        RouteStore.update(stop.id) { it.copy(lat = c?.first, lon = c?.second, geo = if (c != null) GeoState.OK else GeoState.MISSING) }
    }
}
