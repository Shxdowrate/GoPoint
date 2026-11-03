package dev.gopoints.app.route

/** Что удалось вытащить из распознанного текста накладной. */
data class WaybillInfo(
    val number: String?,
    val name: String?,
    val street: String?,
    val window: String?,
)

/**
 * Разбор текста накладной. Логика отлажена на тестовых накладных (скан и «фото»):
 * подпись и значение часто распознаются разными строками, поэтому есть запасные пути.
 */
object Waybill {
    private val STREET = Regex(
        "(?iu)(?<![А-ЯЁа-яё])(?:ул|улица|пр|пр-т|проспект|пер|переулок|ш|шоссе|пл|площадь|б-р|бульвар|мкр)\\.?\\s+" +
            "([0-9А-ЯЁа-яё][^,\\n]*?)\\s*,\\s*(?:д\\.?|дом)?\\s*(\\d+[А-Яа-я]?(?:/\\d+)?)"
    )
    private val NAME = Regex("[«\"]([^»\"\\n]{2,40})[»\"]")
    private val NUMBER = Regex("(?iu)накладн\\w*\\s*№?\\s*([0-9]{2,})")
    private val WINDOW = Regex("(?iu)(?<![\\d.])до\\s*(\\d{1,2})[:.](\\d{2})(?![\\d.])")
    private val UNIT = Regex("(?iu)\\d+([,.]\\d+)?\\s*(л|г|кг|мл|шт)(?![А-Яа-яё])")
    private val ORG = Regex("(?iu)(?<![А-ЯЁа-яё])(ИП|ООО|АО|магазин|маркет|лавка|супермаркет)")
    private val LABELS = listOf("адрес", "пункт", "основание", "поставщик", "время")

    private fun norm(s: String) = s
        .replace('\u201c', '«').replace('\u201d', '»').replace('„', '«')
        .replace("<<", "«").replace(">>", "»")

    fun parse(text: String): WaybillInfo {
        val lines = text.lines().map { norm(it).trim() }.filter { it.isNotEmpty() }
        val low = lines.map { it.lowercase() }

        fun after(keys: List<String>): String? {
            for (i in lines.indices) for (k in keys) {
                val j = low[i].indexOf(k)
                if (j >= 0) {
                    var rest = lines[i].substring(j + k.length).trim(' ', ':', ';', '.', '-', '—')
                    if (rest.length < 4 && i + 1 < lines.size) rest = lines[i + 1]
                    return rest
                }
            }
            return null
        }

        val number = NUMBER.find(lines.joinToString(" "))?.groupValues?.get(1)

        val supplier = after(listOf("поставщик")) ?: ""
        val supNames = NAME.findAll(supplier).map { it.groupValues[1].trim().lowercase() }.toSet()

        var consignee = after(listOf("грузополучатель", "получатель", "покупатель"))
        if (consignee != null && LABELS.any { consignee!!.lowercase().startsWith(it) }) consignee = null
        var name: String? = consignee?.let { c ->
            NAME.findAll(c).map { it.groupValues[1].trim() }.lastOrNull() ?: c
        }
        if (name == null || name.lowercase() in supNames) {
            val good = lines.filter { "поставщик" !in it.lowercase() && !UNIT.containsMatchIn(it) }
            fun namesOf(ls: List<String>) = ls.flatMap { l -> NAME.findAll(l).map { it.groupValues[1].trim() }.toList() }
                .filter { it.lowercase() !in supNames }
            val cands = namesOf(good.filter { ORG.containsMatchIn(it) }).ifEmpty { namesOf(good) }
            name = cands.lastOrNull() ?: name
        }

        val addrLine = after(listOf("адрес доставки", "пункт разгрузки", "адрес разгрузки", "адрес"))
        var street: String? = null
        for (src in listOfNotNull(addrLine) + lines) {
            if ("игнатьев" in src.lowercase() || "погрузк" in src.lowercase()) continue
            val m = STREET.find(src) ?: continue
            val st = m.groupValues[1].replace(Regex("\\s+"), " ").trim(' ', '.', ',')
            street = st.replaceFirstChar { it.uppercase() } + ", " + m.groupValues[2]
            break
        }

        val window = WINDOW.find(text)?.let { "до " + it.groupValues[1].padStart(2, '0') + ":" + it.groupValues[2] }

        return WaybillInfo(number, name?.take(60), street, window)
    }
}
