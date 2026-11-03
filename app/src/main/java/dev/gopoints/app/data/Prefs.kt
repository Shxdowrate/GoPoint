package dev.gopoints.app.data

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("gopoints", Context.MODE_PRIVATE)
    }

    /** Двойное нажатие «громкость вниз» — отметка. */
    var volumeTrigger: Boolean
        get() = sp.getBoolean("volume", true)
        set(v) = sp.edit().putBoolean("volume", v).apply()

    /** Спрашивать и отмечать самому, если стоишь на месте. */
    var autoMark: Boolean
        get() = sp.getBoolean("auto", true)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    /** Через сколько минут стоянки спрашивать. */
    var autoMarkMin: Int
        get() = sp.getInt("auto_min", 5)
        set(v) = sp.edit().putInt("auto_min", v).apply()
}
