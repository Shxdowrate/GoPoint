package dev.gopoints.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gopoints.app.route.GeoSearch
import dev.gopoints.app.route.Place
import kotlinx.coroutines.delay

@Composable
fun GField(label: String, value: String, hint: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = hint?.let { { Text(it, color = C.Chev) } },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = C.Acc, unfocusedBorderColor = C.Edge,
            focusedTextColor = C.Ink, unfocusedTextColor = C.Ink,
            focusedLabelColor = C.Acc, unfocusedLabelColor = C.Muted,
            cursorColor = C.Acc, focusedContainerColor = Color.Black, unfocusedContainerColor = Color.Black,
        ),
    )
}

/**
 * Поле адреса с подсказками: по мере набора ищем на карте (Благовещенск или названный пункт области)
 * и показываем варианты. Выбранный вариант сразу с координатами.
 */
@Composable
fun AddressField(value: String, picked: Place?, onChange: (String) -> Unit, onPick: (Place) -> Unit) {
    val ctx = LocalContext.current
    var suggestions by remember { mutableStateOf<List<Place>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(value, picked) {
        if (picked != null && picked.address == value) { suggestions = emptyList(); return@LaunchedEffect }
        if (value.trim().length < 4) { suggestions = emptyList(); return@LaunchedEffect }
        delay(450)
        loading = true
        suggestions = GeoSearch.suggest(ctx, value)
        loading = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GField("Адрес", value, hint = "Зейская 120 · Марково, Центральная 5", onChange = onChange)
        if (picked != null && picked.address == value) {
            Text("✓ найдено на карте", color = C.Ok, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
        } else if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 4.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), color = C.Acc, strokeWidth = 2.dp)
                Text("ищу…", color = C.Muted, fontSize = 13.sp)
            }
        }
        if (suggestions.isNotEmpty() && !(picked != null && picked.address == value)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card2)) {
                suggestions.forEachIndexed { i, p ->
                    if (i > 0) Divider(start = 14.dp)
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(p); suggestions = emptyList() }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        GIcon(Ico.PIN, C.Acc, size = 18.dp)
                        Text(p.address, color = C.Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
