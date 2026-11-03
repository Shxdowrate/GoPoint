package dev.gopoints.app.route

import android.content.Context
import android.location.Geocoder
import dev.gopoints.app.data.RU
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Найденный на карте вариант адреса. */
data class Place(val address: String, val lat: Double, val lon: Double)

/** Фраза «Магнит на Зейской 120», разобранная на части. */
data class Spoken(val words: List<String>, val house: String?)

enum class Cmd { DONE, UNDO, REPEAT, STOP, YES, NO }

object VoiceParse {
    private val FILLERS = setOf("на", "в", "по", "улица", "ул", "ул.", "дом", "д", "д.", "магазин", "адрес")

    // \b в регулярках не дружит с кириллицей — границы слов задаём сами.
    private const val L = "(?<![а-яёa-z0-9])"
    private const val R = "(?![а-яёa-z0-9])"

    private fun has(t: String, words: String) = Regex("$L($words)$R").containsMatchIn(t)

    fun command(text: String): Cmd? {
        val t = text.lowercase(RU).trim()
        return when {
            Regex("^(готово|всё|все|хватит|закончили|строй|построй)$R").containsMatchIn(t) -> Cmd.DONE
            Regex("$L(удали|удалить|отмени|убери)\\s+последн").containsMatchIn(t) -> Cmd.UNDO
            Regex("^(повтори|ещё раз|еще раз)$R").containsMatchIn(t) -> Cmd.REPEAT
            Regex("^(стоп|выход|закрой|закрыть)$").containsMatchIn(t) -> Cmd.STOP
            else -> null
        }
    }

    fun yesNo(text: String): Cmd? {
        val t = text.lowercase(RU)
        return when {
            has(t, "нет|не надо|неверно|неправильно|не то|отмена|пропусти") -> Cmd.NO
            has(t, "да|давай|добавь|добавляй|верно|ага|угу|конечно|правильно|ок|окей|записывай|запиши|записать") -> Cmd.YES
            else -> null
        }
    }

    /** «первый», «второй», «1» → 0, 1 … */
    fun choice(text: String): Int? {
        val t = text.lowercase(RU)
        val words = listOf("перв", "втор", "трет", "четв", "пят")
        words.forEachIndexed { i, w -> if (t.contains(w)) return i }
        Regex("$L([1-5])$R").find(t)?.let { return it.groupValues[1].toInt() - 1 }
        return null
    }

    fun split(text: String): Spoken {
        var tokens = text.replace(",", " ").replace("«", " ").replace("»", " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        // «12 дробь 3» → «12/3», «12 а» → «12а»
        val joined = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.lowercase(RU) == "дробь" && joined.isNotEmpty() && i + 1 < tokens.size) {
                joined[joined.lastIndex] = joined.last() + "/" + tokens[i + 1]; i += 2; continue
            }
            if (Regex("^[а-вА-В]$").matches(t) && joined.isNotEmpty() && joined.last().all { it.isDigit() }) {
                joined[joined.lastIndex] = joined.last() + t.lowercase(RU); i++; continue
            }
            joined += t; i++
        }
        tokens = joined
        val hIdx = tokens.indexOfLast { Regex("^\\d+[а-яА-Я]?(/\\d+)?$").matches(it) }
        if (hIdx <= 0) return Spoken(tokens, null)
        return Spoken(tokens.subList(0, hIdx), tokens[hIdx])
    }

    private fun cap(s: String) = s.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(RU) } }

    /**
     * Перебираем, сколько последних слов — улица (1–3), остальное — название магазина.
     * Подходит вариант, где геокодер вернул улицу с тем же корнем и номер дома.
     */
    suspend fun search(ctx: Context, s: Spoken): Triple<String, String, List<Place>> = withContext(Dispatchers.IO) {
        val house = s.house ?: return@withContext Triple(cap(s.words.joinToString(" ")), "", emptyList<Place>())
        val words = s.words
        for (k in 1..minOf(3, words.size)) {
            val streetWords = words.takeLast(k).filter { it.lowercase(RU) !in FILLERS }
            if (streetWords.isEmpty()) continue
            val nameWords = words.dropLast(k).filter { it.lowercase(RU) !in FILLERS }
            val name = cap(nameWords.joinToString(" "))
            val stem = streetWords.maxBy { it.length }.lowercase(RU).take(4)
            val places = geocodeAll(ctx, "Благовещенск, ${streetWords.joinToString(" ")} $house")
                .filter { it.address.lowercase(RU).contains(stem) }
            if (places.isNotEmpty()) return@withContext Triple(name, "${cap(streetWords.joinToString(" "))}, $house", places)
        }
        val name = cap(words.dropLast(1).filter { it.lowercase(RU) !in FILLERS }.joinToString(" "))
        val street = words.lastOrNull()?.let { cap(it) } ?: ""
        Triple(name, "$street, $house", emptyList())
    }

    private fun geocodeAll(ctx: Context, q: String): List<Place> = try {
        if (!Geocoder.isPresent()) emptyList()
        else {
            @Suppress("DEPRECATION")
            val list = Geocoder(ctx, RU).getFromLocationName(q, 5, 50.15, 127.25, 50.55, 127.85) ?: emptyList()
            list.mapNotNull { a ->
                val st = a.thoroughfare ?: return@mapNotNull null
                val h = a.subThoroughfare ?: return@mapNotNull null
                Place("$st, $h", a.latitude, a.longitude)
            }.distinctBy { it.address }
        }
    } catch (e: Exception) {
        emptyList()
    }
}
