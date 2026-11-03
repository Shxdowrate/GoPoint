package dev.gopoints.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gopoints.app.BuildConfigInfo
import dev.gopoints.app.data.Prefs
import dev.gopoints.app.tracking.TrackingService

@SuppressLint("BatteryLife")
@Composable
fun SettingsScreen(onBack: () -> Unit, onAskNotifications: () -> Unit) {
    val ctx = LocalContext.current
    var volume by remember { mutableStateOf(Prefs.volumeTrigger) }
    var auto by remember { mutableStateOf(Prefs.autoMark) }
    var autoMin by remember { mutableIntStateOf(Prefs.autoMarkMin) }
    val pm = ctx.getSystemService(PowerManager::class.java)
    val batteryOk = pm.isIgnoringBatteryOptimizations(ctx.packageName)

    fun applyToService() {
        if (TrackingService.running.value) TrackingService.send(ctx, TrackingService.ACT_PREFS)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(Modifier.rise(0)) {
                Row(
                    Modifier.clickable(onClick = onBack).padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    GIcon(Ico.BACK, C.Acc, size = 18.dp, stroke = 2.2f)
                    Text("Назад", color = C.Acc, fontSize = 16.sp)
                }
                Text("Настройки", color = C.Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Column(Modifier.rise(60), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Отметки")
                GCard {
                    SwitchRow("Громкость вниз ×2", "Отметка без экрана. Пока идёт смена, громкость кнопками не меняется", volume) {
                        volume = it; Prefs.volumeTrigger = it; applyToService()
                    }
                    Divider()
                    SwitchRow("Автоотметка на стоянке", "Спрошу «отметить?», без ответа — отмечу сам", auto) {
                        auto = it; Prefs.autoMark = it
                    }
                    Divider()
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Стоянка от", Modifier.weight(1f), color = C.Ink, fontSize = 16.sp)
                        listOf(3, 5, 10).forEach { m ->
                            Chip("$m мин", autoMin == m) { autoMin = m; Prefs.autoMarkMin = m }
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.rise(120), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Чтобы GoPoints не засыпал")
                GCard {
                    ActionRow("Уведомления", "Без них не видно кнопки «Отметить» в шторке", "Разрешить", onAskNotifications)
                    Divider()
                    ActionRow(
                        "Экономия батареи",
                        if (batteryOk) "Отключена — хорошо" else "Иначе Android выгружает GPS в фоне",
                        if (batteryOk) "Готово" else "Отключить",
                    ) {
                        if (!batteryOk) {
                            ctx.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                            )
                        }
                    }
                    Divider()
                    ActionRow("Автозапуск и фон", "Realme: Батарея → «Разрешить фоновую активность», автозапуск — вкл.", "Открыть") {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                        )
                    }
                }
                Fine("Ещё на Realme: в списке недавних приложений опустите карточку GoPoints вниз — появится замочек.")
            }
        }
        item {
            Fine("GoPoints ${BuildConfigInfo.versionName(ctx)} · данные хранятся только на телефоне", Modifier.rise(180))
        }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Ink, fontSize = 16.sp)
            Text(sub, color = C.Muted, fontSize = 13.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = C.Ink, checkedTrackColor = C.AccDeep, checkedBorderColor = C.AccDeep,
                uncheckedThumbColor = C.Ink, uncheckedTrackColor = C.Edge, uncheckedBorderColor = C.Edge,
            ),
        )
    }
}

@Composable
private fun ActionRow(title: String, sub: String, button: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Ink, fontSize = 16.sp)
            Text(sub, color = C.Muted, fontSize = 13.sp)
        }
        OutlineButton(button, onClick, height = 36.dp)
    }
}
