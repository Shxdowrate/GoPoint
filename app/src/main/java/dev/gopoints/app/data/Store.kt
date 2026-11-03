package dev.gopoints.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Хранилище: один JSON-файл на день в памяти телефона (files/days/2026-11-04.json).
 * [today] — день, который сейчас пишется (если смена перешла за полночь — день её начала).
 */
object Store {
    private lateinit var dir: File
    private val _today = MutableStateFlow(DayLog(todayKey()))
    val today: StateFlow<DayLog> = _today

    /** Растёт при правке прошлых дней — календарь и статистика перечитывают файлы. */
    val version = MutableStateFlow(0)

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "days").apply { mkdirs() }
        var cur = load(todayKey())
        if (!cur.isActive) {
            val yesterday = load(LocalDate.now().minusDays(1).toString())
            if (yesterday.isActive) cur = yesterday
        }
        _today.value = cur
    }

    fun todayKey(): String = LocalDate.now().toString()

    fun loadOrNull(key: String): DayLog? {
        val f = File(dir, "$key.json")
        if (!f.exists()) return null
        return try {
            DayLog.fromJson(JSONObject(f.readText()))
        } catch (e: Exception) {
            null
        }
    }

    fun load(key: String): DayLog = loadOrNull(key) ?: DayLog(key)

    fun day(key: String): DayLog? = if (key == _today.value.date) _today.value else loadOrNull(key)

    private fun save(d: DayLog) {
        val f = File(dir, "${d.date}.json")
        val tmp = File(dir, "${d.date}.json.tmp")
        tmp.writeText(d.toJson().toString())
        tmp.renameTo(f)
    }

    @Synchronized
    fun update(change: (DayLog) -> DayLog) {
        val nd = change(_today.value)
        if (nd == _today.value) return
        save(nd)
        _today.value = nd
    }

    @Synchronized
    fun editDay(key: String, change: (DayLog) -> DayLog) {
        if (key == _today.value.date) {
            update(change)
        } else {
            loadOrNull(key)?.let { save(change(it)) }
        }
        version.value++
    }

    /** После полуночи без активной смены — переключаемся на новый день. */
    @Synchronized
    fun refresh() {
        val cur = _today.value
        if (!cur.isActive && cur.date != todayKey()) _today.value = load(todayKey())
    }

    @Synchronized
    fun startShift(now: Long = System.currentTimeMillis()) {
        refresh()
        if (_today.value.isActive) return
        update { it.copy(shifts = it.shifts + Shift(now)) }
    }

    @Synchronized
    fun endShift(now: Long = System.currentTimeMillis()) {
        update { d ->
            if (!d.isActive) return@update d
            d.copy(
                shifts = d.shifts.dropLast(1) + d.shifts.last().copy(end = now),
                marks = d.marks.map { if (it.leftAt == null) it.copy(leftAt = now) else it },
            )
        }
        version.value++
    }

    fun setNote(id: Long, note: String) = update { d ->
        d.copy(marks = d.marks.map { if (it.id == id) it.copy(note = note) else it })
    }

    fun deleteMark(key: String, id: Long) = editDay(key) { d -> d.copy(marks = d.marks.filterNot { it.id == id }) }
}

/** События для интерфейса: вспышка при отметке. */
object Events {
    val marked = MutableSharedFlow<Long>(extraBufferCapacity = 8)
}
