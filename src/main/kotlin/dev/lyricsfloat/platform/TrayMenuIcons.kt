package dev.lyricsfloat.platform

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

internal enum class TrayMenuIcon { APP, SHOW, HIDE, SEARCH, SETTINGS, PASS_ON, PASS_OFF, QUIT }

/** PNG icon-data avoids mixing unrelated system-theme glyphs in one DBus menu. */
internal object TrayMenuIcons {
    private val cache = mutableMapOf<Pair<TrayMenuIcon, Boolean>, ByteArray>()

    @Synchronized
    fun png(icon: TrayMenuIcon, dark: Boolean): ByteArray =
        cache.getOrPut(icon to dark) { render(icon, dark) }

    private fun render(icon: TrayMenuIcon, dark: Boolean): ByteArray {
        val image = BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.scale(2.0, 2.0)
            g.stroke = BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = if (dark) Color(0xF5, 0xEF, 0xF8) else Color(0x32, 0x20, 0x3E)
            when (icon) {
                TrayMenuIcon.APP -> {
                    val resource = if (dark) "lyrics_float_tray_light.png" else "lyrics_float_tray_dark.png"
                    val stream = TrayMenuIcons::class.java.classLoader.getResourceAsStream(
                        "composeResources/dev.lyricsfloat.resources/drawable/$resource",
                    )
                    stream?.use { g.drawImage(ImageIO.read(it), 1, 1, 18, 18, null) }
                }
                TrayMenuIcon.SHOW, TrayMenuIcon.HIDE -> {
                    val eye = Path2D.Double().apply {
                        moveTo(2.5, 10.0)
                        curveTo(6.5, 4.5, 13.5, 4.5, 17.5, 10.0)
                        curveTo(13.5, 15.5, 6.5, 15.5, 2.5, 10.0)
                    }
                    g.draw(eye)
                    g.draw(Ellipse2D.Double(8.0, 8.0, 4.0, 4.0))
                    if (icon == TrayMenuIcon.HIDE) {
                        g.color = if (dark) Color(0x26, 0x20, 0x30) else Color.WHITE
                        g.stroke = BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                        g.draw(Line2D.Double(3.5, 16.5, 16.5, 3.5))
                        g.color = if (dark) Color(0xF5, 0xEF, 0xF8) else Color(0x32, 0x20, 0x3E)
                        g.stroke = BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                        g.draw(Line2D.Double(3.5, 16.5, 16.5, 3.5))
                    }
                }
                TrayMenuIcon.SEARCH -> {
                    g.draw(Ellipse2D.Double(3.5, 3.5, 9.5, 9.5))
                    g.draw(Line2D.Double(12.0, 12.0, 17.0, 17.0))
                }
                TrayMenuIcon.SETTINGS -> {
                    for ((y, knob) in listOf(5.0 to 7.0, 10.0 to 13.0, 15.0 to 9.0)) {
                        g.draw(Line2D.Double(3.0, y, 17.0, y))
                        g.color = if (dark) Color(0xFF, 0x98, 0xC3) else Color(0xC8, 0x3D, 0x79)
                        g.fill(Ellipse2D.Double(knob - 2.0, y - 2.0, 4.0, 4.0))
                        g.color = if (dark) Color(0xF5, 0xEF, 0xF8) else Color(0x32, 0x20, 0x3E)
                    }
                }
                TrayMenuIcon.PASS_ON, TrayMenuIcon.PASS_OFF -> {
                    val pointer = Path2D.Double().apply {
                        moveTo(4.0, 2.5)
                        lineTo(4.0, 15.5)
                        lineTo(7.5, 12.5)
                        lineTo(10.0, 17.0)
                        lineTo(12.0, 16.0)
                        lineTo(9.5, 11.5)
                        lineTo(14.0, 11.0)
                        closePath()
                    }
                    g.draw(pointer)
                    g.color = if (dark) Color(0xFF, 0x98, 0xC3) else Color(0xC8, 0x3D, 0x79)
                    if (icon == TrayMenuIcon.PASS_ON) {
                        g.draw(Line2D.Double(13.0, 4.5, 15.0, 6.5))
                        g.draw(Line2D.Double(15.0, 6.5, 18.0, 3.0))
                    } else {
                        g.draw(Ellipse2D.Double(13.0, 3.0, 4.0, 4.0))
                    }
                }
                TrayMenuIcon.QUIT -> {
                    g.draw(Line2D.Double(10.0, 2.5, 10.0, 10.5))
                    val arc = Path2D.Double().apply {
                        moveTo(5.0, 5.5)
                        curveTo(1.0, 9.0, 3.5, 17.5, 10.0, 17.5)
                        curveTo(16.5, 17.5, 19.0, 9.0, 15.0, 5.5)
                    }
                    g.draw(arc)
                }
            }
        } finally {
            g.dispose()
        }
        return ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
    }
}
