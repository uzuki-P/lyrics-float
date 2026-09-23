package dev.lyricsfloat.platform

import java.awt.Point
import java.awt.Rectangle

enum class WindowAnchor {
    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    MIDDLE_LEFT,
    MIDDLE_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_CENTER,
    BOTTOM_RIGHT,
}

fun anchorPosition(anchor: WindowAnchor, bounds: Rectangle, width: Int, height: Int, margin: Int): Point {
    val x = when (anchor) {
        WindowAnchor.TOP_LEFT, WindowAnchor.MIDDLE_LEFT, WindowAnchor.BOTTOM_LEFT -> bounds.x + margin
        WindowAnchor.TOP_CENTER, WindowAnchor.BOTTOM_CENTER -> bounds.x + (bounds.width - width) / 2
        WindowAnchor.TOP_RIGHT, WindowAnchor.MIDDLE_RIGHT, WindowAnchor.BOTTOM_RIGHT ->
            bounds.x + bounds.width - width - margin
    }
    val y = when (anchor) {
        WindowAnchor.TOP_LEFT, WindowAnchor.TOP_CENTER, WindowAnchor.TOP_RIGHT -> bounds.y + margin
        WindowAnchor.MIDDLE_LEFT, WindowAnchor.MIDDLE_RIGHT -> bounds.y + (bounds.height - height) / 2
        WindowAnchor.BOTTOM_LEFT, WindowAnchor.BOTTOM_CENTER, WindowAnchor.BOTTOM_RIGHT ->
            bounds.y + bounds.height - height - margin
    }
    return Point(x, y)
}
