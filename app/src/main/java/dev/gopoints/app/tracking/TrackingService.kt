package dev.gopoints.app.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.gopoints.app.App
import dev.gopoints.app.R
import dev.gopoints.app.data.Events
import dev.gopoints.app.data.Mark
import dev.gopoints.app.data.Prefs
import dev.gopoints.app.data.Store
import dev.gopoints.app.data.clock
import dev.gopoints.app.data.hm
import dev.gopoints.app.data.km
import dev.gopoints.app.data.points
import dev.gopoints.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Смена: держит GPS, пишет пробег, ставит отметки (кнопка, громкость, автоотметка),
 * считает время на точке и показывает постоянное уведомление с кнопкой «Отметить».
 */
class TrackingService : Service() {

    companion object {
        const val ACT_START = "dev.gopoints.START"
        const val ACT_STOP = "dev.gopoints.STOP"
        const val ACT_MARK = "dev.gopoints.MARK"
        const val ACT_STOP_YES = "dev.gopoints.STOP_YES"
        const val ACT_STOP_NO = "dev.gopoints.STOP_NO"
        const val ACT_UNDO = "dev.gopoints.UNDO"
        const val ACT_PREFS = "dev.gopoints.PREFS"
        const val EXTRA_ID = "id"

        private const val NOTIF_ID = 1
        private const val ALERT_ID = 2
        private const val CONFIRM_ID = 3

        /** Радиус «стою на месте», м. */
        private const val STOP_RADIUS = 60f
        /** Отъехал от точки дальше — время на точке закончилось, м. */
        private const val LEAVE_RADIUS = 80f
        /** Ждём ответа на «отметить?» перед автоотметкой. */
        private const val AUTO_TIMEOUT = 120_000L

        /** Только «кнопка гарнитуры» и play/pause: голые PLAY/PAUSE шлют магнитолы при подключении. */
        private val MEDIA_KEYS = setOf(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TrackingService::class.java).setAction(ACT_START))
        }

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, TrackingService::class.java).setAction(action)
            if (_running.value) ctx.startService(i) else ContextCompat.startForegroundService(ctx, i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val fused: FusedLocationProviderClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    private var updating = false
    private var current: Location? = null
    private var lastForDistance: Location? = null

    private var anchor: Location? = null
    private var anchorAt = 0L
    private var stopHandled = false
    private var autoJob: Job? = null
    private var tickJob: Job? = null

    private var session: MediaSession? = null
    private var silence: AudioTrack? = null
    private var lastVolume = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { onLocation(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACT_START -> {
                if (!goForeground()) return START_NOT_STICKY
                Store.startShift()
                startTracking()
            }
            null -> {
                // Система перезапустила сервис: продолжаем, только если смена идёт.
                if (!goForeground()) return START_NOT_STICKY
                if (!Store.today.value.isActive) {
                    shutdown()
                    return START_NOT_STICKY
                }
                startTracking()
            }
            else -> {
                if (!goForeground()) return START_NOT_STICKY
                if (!Store.today.value.isActive) {
                    shutdown()
                    return START_NOT_STICKY
                }
                startTracking()
                when (action) {
                    ACT_MARK -> markNow()
                    ACT_STOP -> {
                        Store.endShift()
                        shutdown()
                        return START_NOT_STICKY
                    }
                    ACT_STOP_YES -> {
                        nm.cancel(ALERT_ID)
                        autoJob?.cancel()
                        markAtAnchor(auto = false)
                    }
                    ACT_STOP_NO -> {
                        nm.cancel(ALERT_ID)
                        autoJob?.cancel()
                        stopHandled = true
                    }
                    ACT_UNDO -> undo(intent.getLongExtra(EXTRA_ID, 0L))
                    ACT_PREFS -> setupVolume()
                }
            }
        }
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        true
    } catch (e: Exception) {
        stopSelf()
        false
    }

    private fun hasLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        if (!updating) {
            if (!hasLocation()) {
                shutdown()
                return
            }
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
                .setMinUpdateIntervalMillis(5_000L)
                .build()
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
            fused.lastLocation.addOnSuccessListener { l -> if (l != null && current == null) current = l }
            updating = true
            setupVolume()
        }
        _running.value = true
        if (tickJob == null) {
            tickJob = scope.launch {
                while (isActive) {
                    updateNotification()
                    delay(30_000L)
                }
            }
        }
    }

    private fun shutdown() {
        if (updating) fused.removeLocationUpdates(callback)
        updating = false
        session?.release()
        session = null
        silence?.let { runCatching { it.stop(); it.release() } }
        silence = null
        tickJob?.cancel()
        tickJob = null
        autoJob?.cancel()
        nm.cancel(ALERT_ID)
        _running.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (updating) fused.removeLocationUpdates(callback)
        session?.release()
        silence?.let { runCatching { it.stop(); it.release() } }
        _running.value = false
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- GPS

    private fun distance(m: Mark, l: Location): Float {
        val r = FloatArray(1)
        Location.distanceBetween(m.lat, m.lon, l.latitude, l.longitude, r)
        return r[0]
    }

    private fun onLocation(loc: Location) {
        current = loc
        if (loc.hasAccuracy() && loc.accuracy > 60f) return
        val now = System.currentTimeMillis()

        // Пробег: шаги меньше 20 м — дрожание GPS, больше 3 км — скачок.
        val prev = lastForDistance
        if (prev == null) {
            lastForDistance = loc
        } else {
            val d = prev.distanceTo(loc)
            if (d >= 20f) {
                if (d < 3000f) Store.addDistance(d.toDouble())
                lastForDistance = loc
            }
        }

        // Уехал с точки — закрываем время на точке (все точки по этому адресу).
        val open = Store.today.value.marks.filter { it.leftAt == null }
        if (open.any { distance(it, loc) > LEAVE_RADIUS }) {
            Store.update { d ->
                d.copy(marks = d.marks.map { if (it.leftAt == null && distance(it, loc) > LEAVE_RADIUS) it.copy(leftAt = now) else it })
            }
        }

        // Стоянка: держимся в радиусе STOP_RADIUS дольше порога — спрашиваем.
        val a = anchor
        if (a == null || a.distanceTo(loc) > STOP_RADIUS) {
            anchor = loc
            anchorAt = now
            stopHandled = false
            autoJob?.cancel()
            autoJob = null
            nm.cancel(ALERT_ID)
        } else if (!stopHandled && Prefs.autoMark && now - anchorAt >= Prefs.autoMarkMin * 60_000L) {
            stopHandled = true
            val already = Store.today.value.marks.any { it.time >= anchorAt - 60_000L && distance(it, a) < 100f }
            if (!already) askStop(now - anchorAt)
        }
    }

    // ---------------------------------------------------------------- отметки

    private fun markNow() {
        val l = current
        if (l == null) {
            vibrate(ok = false)
            notifyConfirm(0L, "Нет GPS", "Подождите, пока определится местоположение", undo = false)
            return
        }
        addMark(l, System.currentTimeMillis(), auto = false)
    }

    private fun markAtAnchor(auto: Boolean) {
        val a = anchor ?: current ?: return
        // Не раньше начала смены — иначе точка «уедет» в прошлую смену.
        val shiftStart = Store.today.value.shifts.lastOrNull()?.start ?: 0L
        val t = if (anchorAt > 0) maxOf(anchorAt, shiftStart) else System.currentTimeMillis()
        addMark(a, t, auto)
    }

    private fun addMark(l: Location, time: Long, auto: Boolean) {
        val id = System.currentTimeMillis()
        Store.update { it.copy(marks = it.marks + Mark(id, time, l.latitude, l.longitude, auto = auto)) }
        stopHandled = true
        autoJob?.cancel()
        vibrate(ok = true)
        Events.marked.tryEmit(id)
        val title = if (auto) "Отмечено автоматически" else "Отмечено"
        notifyConfirm(id, title, "Определяю адрес…", undo = true, loud = auto)
        updateNotification()
        scope.launch {
            val addr = Geo.address(applicationContext, l.latitude, l.longitude) ?: return@launch
            Store.update { d -> d.copy(marks = d.marks.map { if (it.id == id) it.copy(address = addr) else it }) }
            notifyConfirm(id, title, addr, undo = true, loud = false)
            updateNotification()
        }
    }

    private fun undo(id: Long) {
        Store.update { d -> d.copy(marks = d.marks.filterNot { it.id == id }) }
        nm.cancel(CONFIRM_ID)
        updateNotification()
    }

    private fun askStop(ms: Long) {
        val n = NotificationCompat.Builder(this, App.CH_ALERTS)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFFFFB020.toInt())
            .setContentTitle("Стоите здесь ${ms / 60_000} мин — отметить?")
            .setContentText("Без ответа отмечу сам через 2 минуты")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .addAction(0, "Отметить", pending(ACT_STOP_YES))
            .addAction(0, "Не надо", pending(ACT_STOP_NO))
            .build()
        nm.notify(ALERT_ID, n)
        autoJob?.cancel()
        autoJob = scope.launch {
            delay(AUTO_TIMEOUT)
            nm.cancel(ALERT_ID)
            markAtAnchor(auto = true)
        }
    }

    // ---------------------------------------------------------------- громкость

    /**
     * Кнопки громкости при погашенном экране Android отдаёт той медиасессии, которая сейчас
     * «играет». Поэтому, пока идёт смена, крутим беззвучную петлю (нули, без аудиофокуса —
     * музыке не мешает) и держим сессию с «удалённой» громкостью: нажатия приходят сюда.
     * Двойное «вниз» — отметка. Кнопка гарнитуры / BT-пульта (play/pause) — тоже отметка.
     */
    private fun setupVolume() {
        session?.release()
        session = null
        silence?.let { runCatching { it.stop(); it.release() } }
        silence = null
        if (!Prefs.volumeTrigger) return

        silence = runCatching { silentLoop() }.getOrNull()
        session = MediaSession(this, "GoPoints").apply {
            setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) {
                    if (direction < 0) onVolumeDown()
                }
            })
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val ev = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                    if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0 && ev.keyCode in MEDIA_KEYS) {
                        markNow()
                        return true
                    }
                    return ev.keyCode in MEDIA_KEYS
                }
            })
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                    .build()
            )
            isActive = true
        }
    }

    /** Секунда тишины по кругу. */
    private fun silentLoop(): AudioTrack {
        val rate = 8000
        val frames = rate
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(frames * 2)
            .build()
        track.write(ShortArray(frames), 0, frames)
        track.setLoopPoints(0, frames, -1)
        track.play()
        return track
    }

    private fun onVolumeDown() {
        val t = SystemClock.elapsedRealtime()
        if (t - lastVolume < 700L) {
            lastVolume = 0L
            markNow()
        } else {
            lastVolume = t
        }
    }

    // ---------------------------------------------------------------- уведомления

    private fun pending(action: String, id: Long = 0L): PendingIntent =
        PendingIntent.getService(
            this, (action.hashCode() + id).toInt(),
            Intent(this, TrackingService::class.java).setAction(action).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(): Notification {
        val d = Store.today.value
        val b = NotificationCompat.Builder(this, App.CH_SHIFT)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFFFFB020.toInt())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .addAction(0, "Отметить", pending(ACT_MARK))
        if (d.isActive) {
            val sh = d.shifts.last()
            val ms = d.marksIn(sh)
            b.setContentTitle("Смена ${hm(sh.workedMs())} · ${points(ms.size)} · ${km(d.distOf(sh))}")
            val last = ms.lastOrNull()
            b.setContentText(
                if (last != null) "Последняя: ${clock(last.time)} — ${last.address ?: "адрес определяется"}"
                else "Нажмите «Отметить» на точке или громкость вниз ×2"
            )
            b.setWhen(d.shifts.last().start).setUsesChronometer(true).setShowWhen(true)
        } else {
            b.setContentTitle("GoPoints").setContentText("Запуск смены…")
        }
        return b.build()
    }

    private fun updateNotification() {
        if (_running.value) nm.notify(NOTIF_ID, buildNotification())
    }

    private fun notifyConfirm(id: Long, title: String, text: String, undo: Boolean, loud: Boolean = false) {
        val b = NotificationCompat.Builder(this, App.CH_ALERTS)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFFFFB020.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setSilent(!loud)
            .setAutoCancel(true)
            .setTimeoutAfter(8_000L)
            .setContentIntent(openApp())
        if (undo) b.addAction(0, "Отменить", pending(ACT_UNDO, id))
        nm.notify(CONFIRM_ID, b.build())
    }

    private fun vibrate(ok: Boolean) {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        val effect = if (ok) VibrationEffect.createOneShot(70L, VibrationEffect.DEFAULT_AMPLITUDE)
        else VibrationEffect.createWaveform(longArrayOf(0L, 70L, 90L, 70L), -1)
        v.vibrate(effect)
    }
}
