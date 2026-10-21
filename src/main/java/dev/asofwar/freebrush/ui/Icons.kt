package dev.asofwar.freebrush.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Line icons drawn for this app (24-unit grid, 2-unit round strokes), instead of pulling in the large
 * material-icons-extended library. Icon() tints them, so the stroke colour here does not matter.
 */
object Icons {
    private fun line(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (p in paths) {
                addPath(
                    PathParser().parsePathString(p).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Today = line(
        "today",
        "M12,8a4,4 0,1 1,0 8a4,4 0,1 1,0 -8z",
        "M12,2.5v2M12,19.5v2M2.5,12h2M19.5,12h2M5.3,5.3l1.4,1.4M17.3,17.3l1.4,1.4M5.3,18.7l1.4,-1.4M17.3,6.7l1.4,-1.4",
    )
    val Moon = line("moon", "M20,14.5A8,8 0,1 1,9.5 4a6.5,6.5 0,0 0,10.5 10.5z")
    val History = line("history", "M5,20v-6M12,20v-11M19,20v-15")
    val Brush = line("brush", "M12,11v10", "M9,3h6v8h-6z", "M10.5,5.5h3M10.5,8.5h3")
    val Sync = line("sync", "M20,12a8,8 0,1 1,-2.4,-5.7", "M20,4v4.5h-4.5")
    val Check = line("check", "M5,12.5l4.5,4.5l9.5,-10")
    val Warning = line("warning", "M12,3.5l9,16h-18z", "M12,10v4.5", "M12,17.3v0.2")
    val Flame = line("flame", "M12,21a6,6 0,0 1,-6 -6c0,-3.5 3,-5 3,-9c3,1.5 4,3.5 4,5.5c1,-0.8 1.5,-2 1.5,-3c2,1.7 3.5,4 3.5,6.5a6,6 0,0 1,-6 6z")
    val Star = line("star", "M12,3.5l2.6,5.4l5.9,0.8l-4.3,4.1l1,5.9l-5.2,-2.8l-5.2,2.8l1,-5.9l-4.3,-4.1l5.9,-0.8z")
    val Clock = line("clock", "M12,3a9,9 0,1 1,0 18a9,9 0,1 1,0 -18z", "M12,7.5v5l3,2")
    val Share = line("share", "M12,3v12", "M7.5,7.5l4.5,-4.5l4.5,4.5", "M5,13v5.5a2,2 0,0 0,2 2h10a2,2 0,0 0,2 -2v-5.5")
    val Trash = line("trash", "M4,7h16", "M9.5,7v-3h5v3", "M6,7l1,13.5h10l1,-13.5")
    val Swap = line("swap", "M7,4l-3,3l3,3", "M4,7h13", "M17,14l3,3l-3,3", "M20,17h-13")
    val Bluetooth = line("bluetooth", "M7,7.5l10,9l-5,4.5v-18l5,4.5l-10,9")
    val Download = line("download", "M12,3.5v11", "M7.5,10l4.5,4.5l4.5,-4.5", "M5,19.5h14")
    val Chevron = line("chevron", "M6,9l6,6l6,-6")
    val Battery = line("battery", "M3.5,8h14a1.5,1.5 0,0 1,1.5 1.5v5a1.5,1.5 0,0 1,-1.5 1.5h-14a1.5,1.5 0,0 1,-1.5 -1.5v-5a1.5,1.5 0,0 1,1.5 -1.5z", "M21.5,11v2")
}
