package dev.gopoints.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Линейные иконки из макетов (сетка 24×24). */
enum class Ico { PIN, ROUTE, CAL, BARS, MIC, SLIDERS, CHECK, NAV, LEFT, RIGHT, BACK, TRASH, LINE, AREA, CAMERA, IMAGE, EDIT }

private class IcoSpec(val paths: List<Path>, val circles: List<Triple<Float, Float, Float>>)

private fun p(d: String): Path = PathParser().parsePathString(d).toPath()

private fun spec(i: Ico): IcoSpec = when (i) {
    Ico.PIN -> IcoSpec(listOf(p("M12 22s7-6.5 7-12a7 7 0 0 0-14 0c0 5.5 7 12 7 12z")), listOf(Triple(12f, 10f, 2.5f)))
    Ico.ROUTE -> IcoSpec(listOf(p("M8 19h8a3.5 3.5 0 0 0 0-7H8a3.5 3.5 0 0 1 0-7h8")), listOf(Triple(6f, 19f, 2f), Triple(18f, 5f, 2f)))
    Ico.CAL -> IcoSpec(listOf(p("M6 5h12a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3zM3 10h18M8 3v4M16 3v4")), emptyList())
    Ico.BARS -> IcoSpec(listOf(p("M3 20h18M6 16v-5M11 16V6M16 16v-8")), emptyList())
    Ico.MIC -> IcoSpec(listOf(p("M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3zM5 11a7 7 0 0 0 14 0M12 18v3")), emptyList())
    Ico.SLIDERS -> IcoSpec(listOf(p("M4 7h10M18 7h2M4 17h2M10 17h10")), listOf(Triple(16f, 7f, 2f), Triple(8f, 17f, 2f)))
    Ico.CHECK -> IcoSpec(listOf(p("M5 12.5l4.5 4.5L19 7.5")), emptyList())
    Ico.NAV -> IcoSpec(listOf(p("M3 11l18-8-8 18-2-8z")), emptyList())
    Ico.LEFT -> IcoSpec(listOf(p("M15 5l-7 7 7 7")), emptyList())
    Ico.RIGHT -> IcoSpec(listOf(p("M9 5l7 7-7 7")), emptyList())
    Ico.BACK -> IcoSpec(listOf(p("M15 4l-8 8 8 8")), emptyList())
    Ico.TRASH -> IcoSpec(listOf(p("M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3")), emptyList())
    Ico.LINE -> IcoSpec(listOf(p("M3 17l6-6 4 4 8-8")), emptyList())
    Ico.AREA -> IcoSpec(listOf(p("M3 20v-3l6-6 4 4 8-8v13z")), emptyList())
    Ico.CAMERA -> IcoSpec(
        listOf(p("M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z")),
        listOf(Triple(12f, 13.5f, 3.5f)),
    )
    Ico.IMAGE -> IcoSpec(
        listOf(p("M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM4 16l5-5 4 4 2-2 5 5")),
        listOf(Triple(15.5f, 8.5f, 1.5f)),
    )
    Ico.EDIT -> IcoSpec(listOf(p("M4 20h4L19 9l-4-4L4 16v4zM13 7l4 4")), emptyList())
}

@Composable
fun GIcon(ico: Ico, tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp, stroke: Float = 1.8f) {
    val s = remember(ico) { spec(ico) }
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f
        scale(k, pivot = Offset.Zero) {
            val st = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            s.paths.forEach { drawPath(it, tint, style = st) }
            s.circles.forEach { (x, y, r) -> drawCircle(tint, r, Offset(x, y), style = st) }
        }
    }
}
