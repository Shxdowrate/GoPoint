package dev.gopoints.app.route

import android.content.Context
import android.location.Address
import android.location.Geocoder
import dev.gopoints.app.data.RU
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Поиск адреса на карте с учётом населённого пункта. */
object GeoSearch {
    private val CITY_BOX = doubleArrayOf(50.15, 127.25, 50.55, 127.85)
    private val REGION_BOX = doubleArrayOf(48.8, 119.6, 57.2, 134.9)

    private fun belongs(a: Address, l: Locality): Boolean {
        val names = listOfNotNull(a.locality, a.subLocality, a.subAdminArea).map { Localities.norm(it) }
        val line = Localities.norm(a.getAddressLine(0) ?: "")
        return if (l.isCity) {
            names.any { it == "благовещенск" } && "верхнеблаговещ" !in line && "белогорь" !in line
        } else {
            val stem = Localities.norm(l.name).take(6)
            names.any { it.startsWith(stem) } || line.contains(stem)
        }
    }

    /**
     * [street] — «Заводская 11», «ул. Центральная, 5». Возвращает до 5 вариантов с номером дома.
     * Сначала строго в нужном пункте; если геокодер пункт не указал — что нашлось рядом, с его подписью.
     */
    suspend fun find(ctx: Context, l: Locality, street: String): List<Place> = withContext(Dispatchers.IO) {
        try {
            if (!Geocoder.isPresent()) return@withContext emptyList()
            val q = if (l.isCity) "Благовещенск, $street" else "Амурская область, ${l.name}, $street"
            val b = if (l.isCity) CITY_BOX else REGION_BOX
            @Suppress("DEPRECATION")
            val raw = Geocoder(ctx, RU).getFromLocationName(q, 5, b[0], b[1], b[2], b[3]) ?: emptyList()
            val withStreet = raw.filter { it.thoroughfare != null }
            val strict = withStreet.filter { belongs(it, l) }
            val found = if (strict.isNotEmpty() || l.isCity) {
                strict.map { a ->
                    val house = a.subThoroughfare?.let { ", $it" } ?: ""
                    Place((if (l.isCity) "" else "${l.name}, ") + a.thoroughfare + house, a.latitude, a.longitude)
                }
            } else {
                // В селе улицу не нашли — не подставляем город, ставим точку в центр села.
                center(ctx, l)?.let { c -> listOf(Place("${l.name}, ${street.trim()} (≈ центр села)", c.first, c.second)) } ?: emptyList()
            }
            found.distinctBy { it.address }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Центр населённого пункта (если улицу в нём карта не знает). */
    private fun center(ctx: Context, l: Locality): Pair<Double, Double>? = try {
        @Suppress("DEPRECATION")
        Geocoder(ctx, RU).getFromLocationName("Амурская область, ${l.name}", 3, REGION_BOX[0], REGION_BOX[1], REGION_BOX[2], REGION_BOX[3])
            ?.firstOrNull { a ->
                val stem = Localities.norm(l.name).take(6)
                listOfNotNull(a.locality, a.subLocality, a.featureName, a.getAddressLine(0)).any { Localities.norm(it).contains(stem) }
            }?.let { it.latitude to it.longitude }
    } catch (e: Exception) {
        null
    }

    /** Подсказки для поля адреса. */
    suspend fun suggest(ctx: Context, text: String): List<Place> {
        val l = Localities.inText(text) ?: Localities.CITY
        val rest = clean(text, l)
        return find(ctx, l, rest).take(5)
    }

    private fun clean(address: String, l: Locality) =
        Localities.stripFrom(Localities.stripFrom(address, l), Localities.CITY)
            .replace(Regex("(?iu)^(г|с|п|пос|пгт)\\.?\\s+"), "")
            .ifBlank { address }

    /** Координаты для адреса, введённого текстом («Марково, Центральная 5»). */
    suspend fun locate(ctx: Context, address: String): Place? {
        val l = Localities.inText(address) ?: Localities.CITY
        return find(ctx, l, clean(address, l)).firstOrNull()
    }
}
