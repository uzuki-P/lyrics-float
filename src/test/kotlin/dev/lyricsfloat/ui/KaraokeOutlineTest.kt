package dev.lyricsfloat.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import dev.lyricsfloat.lyrics.WordTimestamp
import kotlin.test.Test
import kotlin.test.assertEquals

class KaraokeOutlineTest {
    @Test
    fun matchedWordsLeaveNoStationaryOutlineBehind() {
        val density = Density(1f)
        val measurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)
        for (text in listOf("g", "p", "y", "j", "This morning (yeah), I'll wake up to sunlight (to the light, ah)")) {
            val style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.6.sp, textAlign = TextAlign.Center)
            val layout = measurer.measure(text, style, constraints = Constraints(minWidth = 440, maxWidth = 440))
            val words = text.split(' ').map { WordTimestamp(it, 0.0, 1.0) }
            val segments = buildSegments(text, words, layout, style, measurer, withShadow = false)
            val image = ImageBitmap(440, layout.size.height + 12)
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(image.width.toFloat(), image.height.toFloat())) {
                clipPath(karaokeBaseHoles(segments, outlinePx = 3.08f), ClipOp.Difference) {
                    drawText(layout, color = Color.Black, drawStyle = Stroke(width = 3.08f))
                }
            }
            val pixels = image.toPixelMap()
            val leftovers = (0 until image.height).sumOf { y -> (0 until image.width).count { x -> pixels[x, y].alpha > 0f } }
            assertEquals(0, leftovers, "Stationary outline pixels under animated words in '$text'")
        }
    }
}
