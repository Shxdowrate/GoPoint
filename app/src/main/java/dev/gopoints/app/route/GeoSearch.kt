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

    private fun inBlag(a: Address) =
        listOfNotNull(a.locality, a.subAdminArea).any { Localities.norm(it) == "благовещенск" }

    /** Какие пункты карта упомянула в адресе — сверяем целыми словами по справочнику. */
    private fun places(a: Address): Set<Locality> =
        listOfNotNull(a.locality, a.subLocality, a.subAdminArea, a.featureName, a.getAddressLine(0))
            .flatMap { Localities.mentionedIn(it) }.toSet()

    private fun mentions(a: Address, l: Locality): Boolean = l in places(a)

    private fun km(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = FloatArray(1)
        android.location.Location.distanceBetween(aLat, aLon, bLat, bLon, r)
        return r[0] / 1000.0
    }

    /** В каком посёлке лежит точка (по кругу посёлка). */
    private fun districtAt(lat: Double, lon: Double): Locality? = Localities.ALL.firstOrNull { l ->
        l.area?.let { (cLat, cLon, r) -> km(lat, lon, cLat, cLon) <= r } == true
    }

    private fun inArea(a: Address, l: Locality): Boolean =
        l.area?.let { (cLat, cLon, r) -> km(a.latitude, a.longitude, cLat, cLon) <= r } ?: mentions(a, l)

    private fun belongs(a: Address, l: Locality): Boolean =
        if (l.isCity) inBlag(a) else mentions(a, l)

    @Suppress("DEPRECATION")
    private fun geocode(ctx: Context, q: String, box: DoubleArray): List<Address> =
        Geocoder(ctx, RU).getFromLocationName(q, 5, box[0], box[1], box[2], box[3]) ?: emptyList()

    /** Подпись адреса; в городе — с названием посёлка, если точка в нём (видно, какая из одноимённых улиц). */
    private fun label(l: Locality, a: Address): Place {
        val house = a.subThoroughfare?.let { ", $it" } ?: ""
        val town = if (l.isCity) districtAt(a.latitude, a.longitude)?.name else l.name
        return Place((town?.let { "$it, " } ?: "") + a.thoroughfare + house, a.latitude, a.longitude)
    }

    /**
     * [street] — «Заводская 11», «ул. Центральная, 5». До 5 вариантов.
     *  • Благовещенск — поиск в городе.
     *  • Посёлки в черте города (Астрахановка, Моховая Падь…) — в городе; подходит адрес с названием
     *    посёлка, а если карта его не пишет — любой адрес Благовещенска с этой улицей.
     *  • Сёла области — сначала в области, потом через Благовещенск; не нашли улицу — центр села.
     */
    suspend fun find(ctx: Context, l: Locality, street: String): List<Place> = withContext(Dispatchers.IO) {
        try {
            if (!Geocoder.isPresent()) return@withContext emptyList()
            val out: List<Place> = when {
                l.isCity -> geocode(ctx, "Благовещенск, $street", CITY_BOX)
                    .filter { it.thoroughfare != null && belongs(it, l) }.map { label(l, it) }
                l.inCity -> {
                    // Только адреса внутри посёлка: одноимённая улица в городе не подойдёт.
                    val raw = geocode(ctx, "Благовещенск, ${l.name}, $street", CITY_BOX).filter { it.thoroughfare != null } +
                        geocode(ctx, "Амурская область, ${l.name}, $street", CITY_BOX).filter { it.thoroughfare != null }
                    raw.filter { inArea(it, l) }.sortedByDescending { mentions(it, l) }.map { label(l, it) }.ifEmpty {
                        l.area?.let { (cLat, cLon, _) -> listOf(Place("${l.name}, ${street.trim()} (≈ центр посёлка)", cLat, cLon)) } ?: emptyList()
                    }
                }
                else -> {
                    val region = geocode(ctx, "Амурская область, ${l.name}, $street", REGION_BOX)
                        .filter { it.thoroughfare != null && mentions(it, l) }
                    val viaCity = if (region.isEmpty()) geocode(ctx, "Благовещенск, ${l.name}, $street", REGION_BOX)
                        .filter { it.thoroughfare != null && mentions(it, l) } else emptyList()
                    (region + viaCity).map { label(l, it) }.ifEmpty {
                        center(ctx, l)?.let { c -> listOf(Place("${l.name}, ${street.trim()} (≈ центр села)", c.first, c.second)) } ?: emptyList()
                    }
                }
            }
            out.distinctBy { it.address }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Центр населённого пункта (если улицу в нём карта не знает). */
    private fun center(ctx: Context, l: Locality): Pair<Double, Double>? = try {
        (geocode(ctx, "Амурская область, ${l.name}", REGION_BOX) + geocode(ctx, "Благовещенский район, ${l.name}", REGION_BOX))
            .firstOrNull { mentions(it, l) }?.let { it.latitude to it.longitude }
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
