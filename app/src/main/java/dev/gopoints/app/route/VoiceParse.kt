package dev.gopoints.app.route

import android.content.Context
import dev.gopoints.app.data.RU

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

    /**
     * Одна фраза диктовки может содержать несколько адресов подряд:
     * «Колосок Ленина 145 Продукты Зейская 120 Марково Центральная 5» → три строки.
     * Режем после номера дома, если дальше не продолжение номера (а/б, дробь, корпус).
     */
    fun chunks(text: String): List<String> {
        val tokens = text.replace(",", " , ").split(Regex("\\s+")).filter { it.isNotBlank() }
        val out = mutableListOf<MutableList<String>>(mutableListOf())
        val houseRe = Regex("^\\d+[а-яА-Я]?(/\\d+)?$")
        val cont = setOf("а", "б", "в", "г", "дробь", "корпус", "корп", "строение", "стр", "литер")
        for ((i, t) in tokens.withIndex()) {
            if (t == ",") continue
            out.last() += t
            val next = tokens.getOrNull(i + 1)?.lowercase(RU)
            if (houseRe.matches(t) && next != null && next != "," && next !in cont && !houseRe.matches(next)) {
                out += mutableListOf<String>()
            } else if (houseRe.matches(t) && next == ",") {
                out += mutableListOf<String>()
            }
        }
        return out.map { it.joinToString(" ") }.filter { it.isNotBlank() }
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
     * Сначала выделяем населённый пункт («Белогорье», «в Марково», «Верхблаговещенск»), по умолчанию Благовещенск.
     * Потом перебираем, сколько последних слов — улица (1–3), остальное — название магазина.
     * Подходит вариант, где геокодер вернул улицу с тем же корнем.
     */
    suspend fun search(ctx: Context, s: Spoken): Triple<String, String, List<Place>> {
        val found = Localities.find(s.words)
        val loc = found?.first ?: Localities.CITY
        val words = s.words.filterIndexed { i, _ -> found == null || i !in found.second }
        val town = if (loc.isCity) "" else "${loc.name}, "
        val house = s.house ?: return Triple(cap(words.joinToString(" ")), town.trimEnd(',', ' '), emptyList())
        for (k in 1..minOf(3, words.size)) {
            val streetWords = words.takeLast(k).filter { it.lowercase(RU) !in FILLERS }
            if (streetWords.isEmpty()) continue
            val nameWords = words.dropLast(k).filter { it.lowercase(RU) !in FILLERS }
            val stem = streetWords.maxBy { it.length }.lowercase(RU).replace('ё', 'е').take(4)
            val places = GeoSearch.find(ctx, loc, "${streetWords.joinToString(" ")} $house")
                .filter { Localities.norm(it.address).contains(stem) }
            if (places.isNotEmpty()) {
                return Triple(cap(nameWords.joinToString(" ")), "$town${cap(streetWords.joinToString(" "))}, $house", places)
            }
        }
        val name = cap(words.dropLast(1).filter { it.lowercase(RU) !in FILLERS }.joinToString(" "))
        val street = words.lastOrNull()?.let { cap(it) } ?: ""
        return Triple(name, "$town$street, $house", emptyList())
    }
}
