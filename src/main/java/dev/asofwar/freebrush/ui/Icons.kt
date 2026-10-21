package dev.asofwar.freebrush.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The three tab icons, drawn here to avoid pulling in the large material-icons-extended library. */
object Icons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
            .build()

    // Sun over a horizon: "today".
    val Today = icon(
        "today",
        "M12,7a5,5 0,1 1,0 10a5,5 0,1 1,0 -10zM11,1h2v3h-2zM11,20h2v3h-2zM1,11h3v2h-3zM20,11h3v2h-3z" +
            "M4.2,5.6l1.4,-1.4l2.1,2.1l-1.4,1.4zM16.3,17.7l1.4,-1.4l2.1,2.1l-1.4,1.4z" +
            "M4.2,18.4l2.1,-2.1l1.4,1.4l-2.1,2.1zM16.3,6.3l2.1,-2.1l1.4,1.4l-2.1,2.1z",
    )

    // Three bars of different height: "history".
    val History = icon("history", "M4,13h4v8h-4zM10,7h4v14h-4zM16,3h4v18h-4z")

    // Toothbrush: bristle head on a handle.
    val Brush = icon(
        "brush",
        "M10,9h4v12a2,2 0,0 1,-4 0zM9,4h6a1,1 0,0 1,1 1v3h-8v-3a1,1 0,0 1,1 -1zM9,1h1.5v2.5h-1.5zM11.25,1h1.5v2.5h-1.5zM13.5,1h1.5v2.5h-1.5z",
    )
}
