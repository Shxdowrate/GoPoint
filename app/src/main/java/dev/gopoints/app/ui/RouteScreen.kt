package dev.gopoints.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Маршрут — следующий этап. Пока показываем, что будет. */
@Composable
fun RouteScreen() {
    val plans = listOf(
        "Голосовой ввод" to "«Магнит, Зейская сто двадцать» — добавлено, следующая",
        "Поиск по названию" to "Несколько магазинов на одном адресе, «со слов», если нет на карте",
        "Лучший порядок объезда" to "Старт и финиш на складе, окна приёмки",
        "Яндекс Навигатор" to "Маршрут уходит в навигатор с грузовым режимом",
        "План и факт" to "Подъехал — точка отмечается сама",
    )
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 24.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(Modifier.rise(0)) {
                Text("Следующий этап", color = C.Muted, fontSize = 13.sp)
                Text("Маршрут", color = C.Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Column(Modifier.rise(80), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Скоро здесь")
                GCard {
                    plans.forEachIndexed { i, (title, sub) ->
                        if (i > 0) Divider(start = 66.dp)
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp).rise(140 + i * 70),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Tile("${i + 1}", color = C.Acc)
                            Column(Modifier.weight(1f)) {
                                Text(title, color = C.Ink, fontSize = 16.sp)
                                Text(sub, color = C.Muted, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
