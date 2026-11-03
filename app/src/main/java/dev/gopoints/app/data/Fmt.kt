package dev.gopoints.app.data

import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

val RU: Locale = Locale.forLanguageTag("ru-RU")

/** 5:42 */
fun hm(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(0)
    return "${m / 60}:${(m % 60).toString().padStart(2, '0')}"
}

/** 13:05 */
fun clock(t: Long): String = SimpleDateFormat("HH:mm", RU).format(Date(t))

fun mins(ms: Long): String = "${(ms / 60_000).coerceAtLeast(0)} мин"

fun km(m: Double): String = when {
    m < 1000 -> "${m.roundToInt()} м"
    m < 10_000 -> String.format(RU, "%.1f км", m / 1000)
    else -> "${(m / 1000).roundToInt()} км"
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val n10 = n % 10
    val n100 = n % 100
    return when {
        n10 == 1 && n100 != 11 -> one
        n10 in 2..4 && n100 !in 12..14 -> few
        else -> many
    }
}

fun points(n: Int) = "$n ${plural(n, "точка", "точки", "точек")}"

fun String.cap(): String = replaceFirstChar { it.titlecase(RU) }

/** «Среда, 4 ноября» */
fun dayTitle(t: Long): String = SimpleDateFormat("EEEE, d MMMM", RU).format(Date(t)).cap()

fun dayTitle(date: java.time.LocalDate): String {
    val t = date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    return dayTitle(t)
}

/** Текст отчёта за день — для отправки в мессенджер. */
fun dayReport(d: DayLog, now: Long = System.currentTimeMillis()): String = buildString {
    val date = java.time.LocalDate.parse(d.date)
    append("GoPoints · ").append(dayTitle(date)).append('\n')
    if (d.shifts.isNotEmpty()) {
        val start = d.shifts.first().start
        val end = d.shifts.last().end
        append("Смена ").append(clock(start)).append("–").append(end?.let { clock(it) } ?: "идёт")
        append(" (").append(hm(d.workedMs(now))).append(")\n")
    }
    append("Точек: ").append(d.marks.size).append(" · Пробег: ").append(km(d.distanceM)).append("\n\n")
    d.marks.forEachIndexed { i, m ->
        append(i + 1).append(". ").append(clock(m.time)).append(" — ")
        append(m.address ?: String.format(Locale.US, "%.5f, %.5f", m.lat, m.lon))
        d.stayMs(m, now)?.let { append(" (").append(mins(it)).append(")") }
        if (m.auto) append(" · авто")
        append('\n')
        m.note?.let { append("   ").append(it).append('\n') }
    }
}

fun shareText(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, "Отправить отчёт").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
