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

    // Line icons sliced from the FreeBrush design (24-unit grid, 2-unit round strokes).
    val Battery = line("battery", "M5 6H17A2 2 0 0 1 19 8V16A2 2 0 0 1 17 18H5A2 2 0 0 1 3 16V8A2 2 0 0 1 5 6ZM19 10H21V14H19M6.5 9V15M10 9V15M13.5 9V15")
    val Bell = line("bell", "M12 3V4.5M6 10.5A6 6 0 0 1 18 10.5V15L20 18H4L6 15V10.5ZM10 20A2.2 2.2 0 0 0 14 20")
    val Bluetooth = line("bluetooth", "M11 3L18 8.5L6 18M6 6L18 15.5L11 21V3")
    // Slim electric brush as drawn on the design sheet (the sliced one came out stocky): handle with a button,
    // neck, head with bristles.
    val Brush = line("brush", "M9.5,13.5a2.5,2.5 0,0 1,5 0v6a2.5,2.5 0,0 1,-5 0z", "M12,11V8.5", "M11.25,2.5h1.5v5h-1.5z", "M15.5,3h2M15.5,6.5h2", "M12,15v1.5")
    val BrushHead = line("brush_head", "M10 3A2 2 0 0 1 12 5V8.5A2 2 0 0 1 8 8.5V5A2 2 0 0 1 10 3ZM15.5 4H18.5M15.5 7H18.5M15.5 10H18.5", "M9.5 10.5L9 16C8.8 18 8.2 19.5 7.5 21H13.5C12.8 19.5 12.2 18 12 16L11.5 10.5")
    val Check = line("check", "M4 12.5L9.5 18L20 6")
    val Chevron = line("chevron_down", "M5 9L12 16L19 9")
    val ChevronRight = line("chevron_right", "M9 5L16 12L9 19")
    val Clock = line("clock", "M21 12A9 9 0 1 1 3 12A9 9 0 1 1 21 12ZM12 6V12L16 15")
    val Download = line("download", "M12 3V16M6 10L12 16L18 10M4 21H20")
    val Edit = line("edit", "M4.5 15.5L16 4A2 2 0 0 1 18.8 4L20 5.2A2 2 0 0 1 20 8L8.5 19.5L3 21L4.5 15.5ZM14 6L18 10")
    val Flame = line("flame", "M12 3C13 7 8.2 8.6 6.5 12.5C4.2 18 7.8 20.6 10 21C9 18.5 9.7 16 11.5 14C11 18 15.7 18 14 21C20.5 19.6 22 13.4 18 8.7C17.5 11 16.4 11.8 15.6 12C16.5 7.5 14.3 4.5 12 3Z")
    val History = line("history", "M4.5 14H6.5A1 1 0 0 1 7.5 15V20A1 1 0 0 1 6.5 21H4.5A1 1 0 0 1 3.5 20V15A1 1 0 0 1 4.5 14ZM11 9H13A1 1 0 0 1 14 10V20A1 1 0 0 1 13 21H11A1 1 0 0 1 10 20V10A1 1 0 0 1 11 9ZM17.5 3H19.5A1 1 0 0 1 20.5 4V20A1 1 0 0 1 19.5 21H17.5A1 1 0 0 1 16.5 20V4A1 1 0 0 1 17.5 3Z")
    val Moon = line("moon", "M20.5 13.4A8.9 8.9 0 0 1 10.6 3.5A8.9 8.9 0 1 0 20.5 13.4Z")
    val PersonAdd = line("person_add", "M12.5 6.5A3.5 3.5 0 1 1 5.5 6.5A3.5 3.5 0 1 1 12.5 6.5ZM13 13.5H9A6 6 0 0 0 3 19.5V21H15M18 12V18M15 15H21")
    val Plus = line("plus", "M12 4V20M4 12H20")
    val Share = line("share", "M12 15V3M7.5 7.5L12 3L16.5 7.5M8 11H5A1 1 0 0 0 4 12V20A1 1 0 0 0 5 21H19A1 1 0 0 0 20 20V12A1 1 0 0 0 19 11H16")
    val Star = line("star", "M12 3L14.75 8.6L21 9.5L16.5 13.9L17.6 20.1L12 17.2L6.4 20.1L7.5 13.9L3 9.5L9.25 8.6Z")
    val Swap = line("swap", "M3 7H21M17 3L21 7L17 11M21 17H3M7 13L3 17L7 21")
    val Sync = line("sync", "M3.8 9A8.6 8.6 0 0 1 19.8 7.6M20.5 3.8L19.8 7.6L16 6.7M20.2 15A8.6 8.6 0 0 1 4.2 16.4M3.5 20.2L4.2 16.4L8 17.3")
    val Today = line("today", "M17 12A5 5 0 1 1 7 12A5 5 0 1 1 17 12ZM12 3V5M12 19V21M3 12H5M19 12H21M5.6 5.6L7 7M17 17L18.4 18.4M18.4 5.6L17 7M7 17L5.6 18.4")
    val Trash = line("trash", "M3.5 6.5H20.5M9 6.5V4.5A1.5 1.5 0 0 1 10.5 3H13.5A1.5 1.5 0 0 1 15 4.5V6.5M5.5 6.5L6.5 19A2 2 0 0 0 8.5 21H15.5A2 2 0 0 0 17.5 19L18.5 6.5M10 10.5V17M14 10.5V17")
    val Tune = line("tune", "M3 7H5.5M10.5 7H21M10.5 7A2.5 2.5 0 1 1 5.5 7A2.5 2.5 0 1 1 10.5 7ZM3 17H13.5M18.5 17H21M18.5 17A2.5 2.5 0 1 1 13.5 17A2.5 2.5 0 1 1 18.5 17Z")
    val Warning = line("warning", "M10.7 4A1.5 1.5 0 0 1 13.3 4L20.8 18.8A1.5 1.5 0 0 1 19.5 21H4.5A1.5 1.5 0 0 1 3.2 18.8L10.7 4ZM12 9V13M12 17.2V17.3")
}
