package dev.gopoints.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.gopoints.app.data.Prefs
import dev.gopoints.app.data.Store
import dev.gopoints.app.data.Track
import dev.gopoints.app.route.RouteStore

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Store.init(this)
        Track.init(this)
        RouteStore.init(this)
        createChannels()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SHIFT, "Смена", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Постоянное уведомление с кнопкой «Отметить»"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Остановки и отметки", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Вопрос «отметить точку?» на стоянке и подтверждения отметок"
            }
        )
    }

    companion object {
        const val CH_SHIFT = "shift"
        const val CH_ALERTS = "alerts"
    }
}
