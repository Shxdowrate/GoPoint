package dev.gopoints.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.gopoints.app.data.Events
import dev.gopoints.app.data.Prefs
import dev.gopoints.app.data.Store
import dev.gopoints.app.route.RouteActions
import kotlinx.coroutines.launch
import dev.gopoints.app.tracking.TrackingService

class MainActivity : ComponentActivity() {

    private var lastVolume = 0L
    private var noteTarget: Long? = null

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        val id = noteTarget
        if (r.resultCode == RESULT_OK && !text.isNullOrBlank() && id != null) {
            Store.setNote(id, text.replaceFirstChar { it.uppercase() })
        }
    }

    private val locationPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocation()) TrackingService.start(this)
        else Toast.makeText(this, "Без доступа к геолокации смену не начать", Toast.LENGTH_LONG).show()
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GoTheme {
                AppRoot(
                    onStart = ::startShift,
                    onVoiceNote = ::voiceNote,
                    onAskNotifications = ::askNotifications,
                )
            }
        }
        // Смена шла, а сервис убили — поднимаем.
        if (Store.today.value.isActive && !TrackingService.running.value && hasLocation()) {
            TrackingService.start(this)
        }
    }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startShift() {
        if (hasLocation()) {
            TrackingService.start(this)
            if (Build.VERSION.SDK_INT >= 33) askNotifications()
        } else {
            val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            locationPerms.launch(perms.toTypedArray())
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun voiceNote(id: Long) {
        noteTarget = id
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Заметка к точке")
        try {
            speech.launch(i)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "На телефоне нет распознавания речи (нужно приложение Google)", Toast.LENGTH_LONG).show()
        }
    }

    /** В открытом приложении громкость вниз ×2 — тоже отметка. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && Prefs.volumeTrigger && Store.today.value.isActive) {
            val t = SystemClock.elapsedRealtime()
            if (t - lastVolume < 700L) {
                lastVolume = 0L
                TrackingService.send(this, TrackingService.ACT_MARK)
            } else {
                lastVolume = t
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

private data class Tab(val title: String, val ico: Ico)

private val tabs = listOf(
    Tab("Сегодня", Ico.PIN),
    Tab("Маршрут", Ico.ROUTE),
    Tab("Календарь", Ico.CAL),
    Tab("Статистика", Ico.BARS),
)

@Composable
fun AppRoot(onStart: () -> Unit, onVoiceNote: (Long) -> Unit, onAskNotifications: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var voice by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(enabled = MapOverlay.request != null) { MapOverlay.request = null }
    BackHandler(enabled = MapOverlay.request == null && (settings || voice)) { settings = false; voice = false }
    BackHandler(enabled = !settings && !voice && tab != 0) { tab = 0 }
    val overlay = settings || voice

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        AmbientGlow(x = if (voice) 0.5f else if (tab % 2 == 0) 0.15f else 0.9f, y = if (voice) 0.95f else if (tab >= 2) 0.35f else 0.04f)
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            AnimatedContent(
                targetState = when { voice -> -2; settings -> -1; else -> tab },
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
                label = "screens",
            ) { s ->
                when (s) {
                    -2 -> VoiceRoute(onClose = { voice = false }, onBuild = { scope.launch { RouteActions.build(ctx) } })
                    -1 -> SettingsScreen(onBack = { settings = false }, onAskNotifications = onAskNotifications)
                    0 -> TodayScreen(onSettings = { settings = true }, onStart = onStart, onVoiceNote = onVoiceNote)
                    1 -> RouteScreen(onVoice = { voice = true })
                    2 -> CalendarScreen()
                    else -> StatsScreen()
                }
            }
        }
        if (!overlay) BottomBar(tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
        MapOverlay.request?.let { r -> FullMapScreen(r, onClose = { MapOverlay.request = null }) }
        MarkFlash()
    }
}

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(Color(0xF00A0A0C))) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1F1F22)))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp)) {
            tabs.forEachIndexed { i, t ->
                val on = i == selected
                val color = if (on) C.Acc else C.Muted
                Box(
                    Modifier.weight(1f).fillMaxSize()
                        .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) {
                        Box(
                            Modifier.align(Alignment.TopCenter).offset(y = (-1).dp).width(28.dp).height(3.dp)
                                .shadow(10.dp, RoundedCornerShape(3.dp), ambientColor = C.Acc, spotColor = C.Acc)
                                .background(C.Acc, RoundedCornerShape(3.dp))
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        GIcon(t.ico, color, size = 22.dp, stroke = if (on) 2.1f else 1.8f)
                        Text(t.title, color = color, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** Янтарная вспышка по экрану при каждой отметке. */
@Composable
private fun MarkFlash() {
    val flash = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        Events.marked.collect {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(900))
        }
    }
    if (flash.value > 0f) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height * 0.38f)
            drawRect(
                Brush.radialGradient(
                    listOf(C.Acc.copy(alpha = 0.38f * flash.value), Color.Transparent),
                    center = c, radius = size.maxDimension * (0.4f + 0.5f * (1f - flash.value)),
                )
            )
        }
    }
}

