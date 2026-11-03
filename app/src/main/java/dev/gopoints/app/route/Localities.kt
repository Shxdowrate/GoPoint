package dev.gopoints.app.route

import dev.gopoints.app.data.RU

/** Населённый пункт: как ищем на карте и как его называют в речи (с падежами). */
data class Locality(val name: String, val forms: Set<String>) {
    val isCity get() = name == "Благовещенск"
}

/**
 * Благовещенск, сёла вокруг и города Амурской области.
 * Сравниваем целые слова, а не начало слова — иначе «улица Шимановского» станет Шимановском.
 */
object Localities {
    private fun ka(stem: String) = listOf("а", "е", "и", "у", "ой").map { stem + it }
    private fun oe(stem: String) = listOf("ое", "ом", "ого", "ому").map { stem + it }
    private fun sk(stem: String) = listOf("", "е", "а", "у", "ом").map { stem + it }
    private fun o(word: String) = listOf(word)

    private fun loc(name: String, vararg forms: List<String>) =
        Locality(name, forms.flatMap { it }.map { norm(it) }.toSet() + norm(name))

    val CITY = loc("Благовещенск", sk("благовещенск"))

    val ALL: List<Locality> = listOf(
        CITY,
        loc("Верхнеблаговещенское", oe("верхнеблаговещенск"), sk("верхблаговещенск"), oe("верхблаговещенск")),
        loc("Белогорье", listOf("белогорье", "белогорья", "белогорью", "белогорьем")),
        loc("Марково", o("марково")),
        loc("Чигири", listOf("чигири", "чигирях", "чигирей", "чигирям")),
        loc("Плодопитомник", sk("плодопитомник")),
        loc("Садовое", oe("садов")),
        loc("Новотроицкое", oe("новотроицк")),
        loc("Владимировка", ka("владимировк")),
        loc("Грибское", oe("грибск")),
        loc("Волково", o("волково")),
        loc("Михайловка", ka("михайловк")),
        loc("Усть-Ивановка", ka("усть-ивановк"), ka("устьивановк")),
        loc("Ивановка", ka("ивановк")),
        loc("Тамбовка", ka("тамбовк")),
        loc("Константиновка", ka("константиновк")),
        loc("Сергеевка", ka("сергеевк")),
        loc("Березовка", ka("березовк")),
        loc("Гродеково", o("гродеково")),
        loc("Игнатьево", o("игнатьево")),
        loc("Каникурган", sk("каникурган")),
        loc("Лозовое", oe("лозов")),
        loc("Белогорск", sk("белогорск")),
        loc("Свободный", listOf("свободный", "свободном", "свободного")),
        loc("Райчихинск", sk("райчихинск")),
        loc("Шимановск", sk("шимановск")),
        loc("Зея", listOf("зея", "зее", "зеи", "зею")),
        loc("Тында", listOf("тында", "тынде", "тынды", "тынду")),
        loc("Завитинск", sk("завитинск")),
        loc("Серышево", o("серышево")),
        loc("Прогресс", sk("прогресс")),
        loc("Новобурейский", listOf("новобурейский", "новобурейском")),
        loc("Екатеринославка", ka("екатеринославк")),
        loc("Поярково", o("поярково")),
        loc("Архара", listOf("архара", "архаре", "архары")),
        loc("Магдагачи", o("магдагачи")),
        loc("Сковородино", o("сковородино")),
        loc("Ромны", listOf("ромны", "ромнах")),
    )

    /** Слова «село», «пос.» и т. п. перед названием — выбрасываем. */
    val PREFIXES = setOf("с", "с.", "село", "селе", "п", "п.", "пос", "пос.", "посёлок", "поселок", "посёлке", "поселке", "пгт", "г", "г.", "город", "городе", "д", "деревня")

    fun norm(s: String) = s.lowercase(RU).replace('ё', 'е').trim(',', '.', ' ')

    /** Находит пункт среди слов; возвращает его и индексы слов (с «село», «в» перед ним), которые нужно убрать. */
    fun find(words: List<String>): Pair<Locality, Set<Int>>? {
        for ((i, w) in words.withIndex()) {
            val n = norm(w)
            val l = ALL.firstOrNull { n in it.forms } ?: continue
            val drop = mutableSetOf(i)
            var j = i - 1
            while (j >= 0 && (norm(words[j]) in PREFIXES || norm(words[j]) in setOf("в", "во", "на"))) { drop += j; j-- }
            return l to drop
        }
        return null
    }

    /** Пункт в тексте адреса («с. Марково, ул. Центральная, 5»). */
    fun inText(text: String): Locality? =
        find(text.split(Regex("[\\s,]+")).filter { it.isNotBlank() })?.first

    /** Убирает название пункта из начала адреса — для красивой записи. */
    fun stripFrom(text: String, l: Locality): String {
        val parts = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return parts.filterNot { p -> p.split(Regex("\\s+")).any { norm(it) in l.forms } }.joinToString(", ")
    }
}
