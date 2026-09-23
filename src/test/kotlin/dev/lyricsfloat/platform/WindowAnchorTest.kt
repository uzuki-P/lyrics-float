package dev.lyricsfloat.platform

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowAnchorTest {
    private val bounds = Rectangle(0, 0, 1000, 800)
    private val width = 200
    private val height = 100
    private val margin = 10

    @Test
    fun bottomCenterIsCenteredAndAboveBottomEdge() {
        val point = anchorPosition(WindowAnchor.BOTTOM_CENTER, bounds, width, height, margin)
        assertEquals(400, point.x)
        assertEquals(800 - 100 - 10, point.y)
    }

    @Test
    fun topLeftRespectsMargin() {
        val point = anchorPosition(WindowAnchor.TOP_LEFT, bounds, width, height, margin)
        assertEquals(10, point.x)
        assertEquals(10, point.y)
    }

    @Test
    fun middleRightIsVerticallyCentered() {
        val point = anchorPosition(WindowAnchor.MIDDLE_RIGHT, bounds, width, height, margin)
        assertEquals(1000 - 200 - 10, point.x)
        assertEquals((800 - 100) / 2, point.y)
    }
}
