package dev.gopoints.app.tracking

import android.content.Context
import android.location.Geocoder
import dev.gopoints.app.data.RU
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Geo {
    /** «ул. Ленина, 145» по координатам, или null, если нет сети/геокодера. */
    suspend fun address(ctx: Context, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        try {
            if (!Geocoder.isPresent()) return@withContext null
            @Suppress("DEPRECATION")
            val a = Geocoder(ctx, RU).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return@withContext null
            val street = a.thoroughfare
            val house = a.subThoroughfare
            when {
                street != null && house != null -> "$street, $house"
                street != null -> street
                else -> a.getAddressLine(0)?.split(",")?.take(2)?.joinToString(",")?.trim()
            }
        } catch (e: Exception) {
            null
        }
    }
}
