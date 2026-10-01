package dev.lyricsfloat.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.lyricsfloat.platform.LinuxWindowMover
import java.awt.Cursor
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import kotlin.math.roundToInt

/**
 * Move gesture for an undecorated window. setLocation dragging jitters under
 * XWayland; the WM's own move (_NET_WM_MOVERESIZE) tracks the pointer
 * per-frame, so prefer it there and only fall back to accumulated
 * setLocation deltas when the WM refuses.
 *
 * The WM's interactive move consumes the pointer release, so [onDragEnd]
 * fires as a cancel (or not at all) for native moves; callers that persist
 * geometry should also watch AWT component events.
 */
fun Modifier.windowDragHandle(window: Window, onDragEnd: () -> Unit = {}): Modifier = composed {
    val density = LocalDensity.current
    pointerInput(window, density) {
        var base: Point? = null
        var delta = Offset.Zero
        detectDragGestures(
            onDragStart = {
                val native = LinuxWindowMover.requestInteractiveMove(window)
                base = if (native) null else Point(window.x, window.y)
                delta = Offset.Zero
            },
            onDrag = { change, amount ->
                change.consume()
                base?.let { start ->
                    delta += amount
                    window.setLocation(
                        start.x + (delta.x * density.density).roundToInt(),
                        start.y + (delta.y * density.density).roundToInt(),
                    )
                }
            },
            onDragEnd = {
                base = null
                onDragEnd()
            },
            onDragCancel = {
                base = null
                onDragEnd()
            },
        )
    }
}

/**
 * Invisible edge and corner targets with resize cursors, shared by the
 * lyrics overlay and the dialogs. A drag hands off to the WM's interactive
 * resize; when the WM refuses, the zone tracks the drag with setBounds,
 * clamped to the window's AWT minimum size.
 *
 * EWMH _NET_WM_MOVERESIZE direction hints: 0 top-left, 1 top, 2 top-right,
 * 3 left, 4 right, 5 bottom-left, 6 bottom, 7 bottom-right.
 */
@Composable
fun BoxScope.WindowResizeZones(window: Window) {
    val density = LocalDensity.current

    @Composable
    fun Handle(direction: Int, modifier: Modifier) {
        val cursor = when (direction) {
            0, 7 -> Cursor.NW_RESIZE_CURSOR
            2, 5 -> Cursor.NE_RESIZE_CURSOR
            1, 6 -> Cursor.N_RESIZE_CURSOR
            else -> Cursor.E_RESIZE_CURSOR
        }
        var manualBase by remember(window, direction) { mutableStateOf<Rectangle?>(null) }
        var dragDelta by remember(window, direction) { mutableStateOf(Offset.Zero) }
        Box(
            modifier
                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(cursor)))
                .pointerInput(window, direction, density) {
                    detectDragGestures(
                        onDragStart = {
                            dragDelta = Offset.Zero
                            manualBase = if (LinuxWindowMover.requestInteractiveResize(window, direction)) null else window.bounds
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            manualBase?.let { base ->
                                dragDelta += amount
                                val dx = (dragDelta.x * density.density).roundToInt()
                                val dy = (dragDelta.y * density.density).roundToInt()
                                val left = direction == 0 || direction == 3 || direction == 5
                                val top = direction == 0 || direction == 1 || direction == 2
                                val right = direction == 2 || direction == 4 || direction == 7
                                val bottom = direction == 5 || direction == 6 || direction == 7
                                val width = (base.width + (if (right) dx else 0) - (if (left) dx else 0))
                                    .coerceAtLeast(window.minimumSize.width)
                                val height = (base.height + (if (bottom) dy else 0) - (if (top) dy else 0))
                                    .coerceAtLeast(window.minimumSize.height)
                                val x = if (left) base.x + base.width - width else base.x
                                val y = if (top) base.y + base.height - height else base.y
                                window.setBounds(x, y, width, height)
                            }
                        },
                        onDragEnd = { manualBase = null },
                        onDragCancel = { manualBase = null },
                    )
                },
        )
    }

    Handle(0, Modifier.align(Alignment.TopStart).size(20.dp))
    Handle(2, Modifier.align(Alignment.TopEnd).size(20.dp))
    Handle(5, Modifier.align(Alignment.BottomStart).size(20.dp))
    Handle(7, Modifier.align(Alignment.BottomEnd).size(20.dp))
    Handle(1, Modifier.align(Alignment.TopCenter).fillMaxWidth(0.72f).height(10.dp))
    Handle(6, Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.72f).height(10.dp))
    Handle(3, Modifier.align(Alignment.CenterStart).width(10.dp).fillMaxHeight(0.72f))
    Handle(4, Modifier.align(Alignment.CenterEnd).width(10.dp).fillMaxHeight(0.72f))
}
