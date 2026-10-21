package dev.asofwar.freebrush.ui.jaw

import org.junit.Test
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders the jaw from a few angles into build/jaw-preview/ so the model can be looked at without a phone.
 * Zones get distinct colours here to check that surfaces land in the right zone.
 */
class JawPreview {
    private val zoneColors = intArrayOf(
        0xFF2E86DE.toInt(), 0xFF9AC8F5.toInt(), // upper left out / in: blues
        0xFF27AE60.toInt(), 0xFF9BE3B5.toInt(), // lower left out / in: greens
        0xFFE67E22.toInt(), 0xFFF7C58F.toInt(), // upper right out / in: oranges
        0xFF8E44AD.toInt(), 0xFFD2A8E6.toInt(), // lower right out / in: purples
    )

    @Test
    fun renderPreviews() {
        val dir = File("build/jaw-preview").apply { mkdirs() }
        val r = JawRenderer(JawModel.build())
        val views = listOf("front" to (0f to 0.2f), "left45" to (0.8f to 0.25f), "top" to (0f to 1.1f), "back" to (3.14f to 0.2f))
        for ((name, angles) in views) {
            r.render(1000f, 800f, angles.first, angles.second, 1f, zoneColors, 0xFFE7A1A6.toInt(), -1, 0f)
            val img = BufferedImage(1000, 800, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(0x1A2120)
            g.fillRect(0, 0, 1000, 800)
            for (t in 0 until r.triangleCount) {
                val xs = IntArray(3) { r.verts[t * 6 + it * 2].toInt() }
                val ys = IntArray(3) { r.verts[t * 6 + it * 2 + 1].toInt() }
                g.color = Color(r.colors[t * 3])
                g.fillPolygon(xs, ys, 3)
                g.drawPolygon(xs, ys, 3)
            }
            g.color = Color.WHITE
            g.drawLine(495, 400, 505, 400)
            g.drawLine(500, 395, 500, 405)
            g.dispose()
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }
    }
}
