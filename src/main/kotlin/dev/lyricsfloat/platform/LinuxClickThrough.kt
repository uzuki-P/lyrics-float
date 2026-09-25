package dev.lyricsfloat.platform

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.unix.X11
import java.awt.Window

// The Shape extension lives in libXext; jna-platform's X11 binding does not
// carry XShape*. `mask` is declared Pointer so null maps to None, which JNA
// would refuse for an IntegerType parameter.
private interface X11ShapeExt : X11 {
    fun XShapeCombineMask(
        display: X11.Display,
        window: X11.Window,
        destKind: Int,
        xOff: Int,
        yOff: Int,
        mask: Pointer?,
        op: Int,
    ): Int

    fun XShapeCombineRectangles(
        display: X11.Display,
        window: X11.Window,
        destKind: Int,
        xOff: Int,
        yOff: Int,
        rectangles: X11.XRectangle?,
        nRects: Int,
        op: Int,
        ordering: Int,
    ): Int
}

// Shape extension constants: ShapeInput kind, ShapeSet op, YXBanded ordering.
private const val SHAPE_INPUT = 2
private const val SHAPE_SET = 0
private const val YX_BANDED = 3

/**
 * Click pass-through for the lyrics pill, via the X Shape extension: a
 * stripped-down input region makes XWayland deliver clicks on the rest of the
 * pill to the windows below (KWin forwards them to the Wayland surface
 * underneath). A narrow strip beside the hover menu keeps its input shape so the
 * hover header - and its pass-through action - stays reachable.
 */
object LinuxClickThrough {
    private val lock = Any()
    private var xext: X11ShapeExt? = null
    private var display: X11.Display? = null

    // Loading JNA's native side is slow; same warm-up pattern as
    // LinuxWindowMover so the first toggle does not stutter.
    fun warmUp() {
        try {
            connection()
        } catch (_: Throwable) {
        }
    }

    /**
     * Shapes the window's input region. With [passThrough] on, only the menu
     * [rescueStripPx] remain interactive; with it off, the input shape is
     * removed entirely (mask None + ShapeSet restores the default region).
     */
    fun apply(window: Window, passThrough: Boolean, rescueStripPx: Int, menuAtBottom: Boolean = false) {
        try {
            val (link, conn) = connection() ?: return
            val target = X11.Window(Native.getWindowID(window))
            if (passThrough) {
                val strip = X11.XRectangle(
                    0,
                    (if (menuAtBottom) (window.height - rescueStripPx).coerceAtLeast(0) else 0)
                        .coerceAtMost(Short.MAX_VALUE.toInt()).toShort(),
                    window.width.coerceAtMost(Short.MAX_VALUE.toInt()).toShort(),
                    rescueStripPx.coerceAtMost(Short.MAX_VALUE.toInt()).toShort(),
                )
                link.XShapeCombineRectangles(conn, target, SHAPE_INPUT, 0, 0, strip, 1, SHAPE_SET, YX_BANDED)
            } else {
                link.XShapeCombineMask(conn, target, SHAPE_INPUT, 0, 0, null, SHAPE_SET)
            }
            link.XFlush(conn)
        } catch (_: Throwable) {
        }
    }

    // Same open-and-keep display pattern as LinuxWindowMover: the X server
    // cleans up the socket when the process exits.
    private fun connection(): Pair<X11ShapeExt, X11.Display>? {
        synchronized(lock) {
            val link = xext ?: Native.load("Xext", X11ShapeExt::class.java).also { xext = it }
            val conn = display ?: link.XOpenDisplay(null)?.also { display = it } ?: return null
            return link to conn
        }
    }
}
