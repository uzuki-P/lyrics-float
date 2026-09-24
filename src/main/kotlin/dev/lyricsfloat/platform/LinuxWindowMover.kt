package dev.lyricsfloat.platform

import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.platform.unix.X11
import com.sun.jna.ptr.IntByReference
import java.awt.Window

// jna-platform's X11 binding has XUngrabKeyboard but not XUngrabPointer.
private interface X11PointerControl : X11 {
    fun XUngrabPointer(display: X11.Display, time: NativeLong): Int
}

// EWMH _NET_WM_MOVERESIZE data.l[2] hints: 0-7 are the eight resize
// directions (top-left, top, top-right, left, right, bottom-left, bottom,
// bottom-right), 8 is a plain move.
private const val MOVE_HINT = 8L

/**
 * Hands interactive window move/resize to the window manager, adapted from
 * pray-time. Under XWayland a per-event `setLocation` loop jitters badly; the
 * WM's own move/resize tracks the pointer per frame, exactly like dragging a
 * real title bar or edge.
 */
object LinuxWindowMover {
    private val lock = Any()
    private var x11: X11PointerControl? = null
    private var display: X11.Display? = null

    // Loading JNA's native side is slow; doing it on the UI thread during the
    // first drag leaves the window lagging behind the cursor. Call this from a
    // background thread early in the app's life.
    fun warmUp() {
        try {
            connection()
        } catch (_: Throwable) {
        }
    }

    // Asks the window manager to run a native interactive move of the window,
    // as if the user had dragged its title bar. Returns false when it cannot
    // work; callers should fall back to setLocation.
    fun requestInteractiveMove(window: Window): Boolean =
        requestInteractive(window, MOVE_HINT)

    // Same message with a directional hint (0-7): a native interactive resize,
    // as if the user had grabbed the matching window edge or corner.
    fun requestInteractiveResize(window: Window, direction: Int): Boolean {
        require(direction in 0..7) { "resize direction must be an EWMH hint 0-7" }
        return requestInteractive(window, direction.toLong())
    }

    private fun requestInteractive(window: Window, hint: Long): Boolean = try {
        val (link, conn) = connection() ?: return false
        val target = X11.Window(Native.getWindowID(window))
        val root = link.XDefaultRootWindow(conn)
        val atom = link.XInternAtom(conn, "_NET_WM_MOVERESIZE", false) ?: return false
        val pointer = rootPointerPosition(link, conn, root) ?: return false

        val event = X11.XEvent()
        val client = X11.XClientMessageEvent()
        client.type = X11.ClientMessage
        client.window = target
        client.display = conn
        client.message_type = atom
        client.format = 32
        client.data.setType(Array<NativeLong>::class.java)
        client.data.l = arrayOf(
            NativeLong(pointer.first.toLong()),
            NativeLong(pointer.second.toLong()),
            NativeLong(hint),
            NativeLong(0), // button
            NativeLong(0), // source indication: application
        )
        event.xclient = client
        event.setType(X11.XClientMessageEvent::class.java)

        // SubstructureRedirectMask | SubstructureNotifyMask: the root window
        // must forward the message to the window manager.
        val mask = NativeLong((1L shl 20) or (1L shl 21))
        link.XSendEvent(conn, root, 0, mask, event)
        // Compose holds a pointer grab from the pressed button; without
        // releasing it the WM's own grab freezes and the move never starts.
        link.XUngrabPointer(conn, NativeLong(0)) // CurrentTime
        link.XFlush(conn)
        true
    } catch (_: Throwable) {
        false
    }

    // JNA already caches the loaded library, but keeping the display open
    // spares every drag a connection round trip. The X server cleans up the
    // socket when the process exits.
    private fun connection(): Pair<X11PointerControl, X11.Display>? {
        synchronized(lock) {
            val link = x11 ?: Native.load("X11", X11PointerControl::class.java).also { x11 = it }
            val conn = display ?: link.XOpenDisplay(null)?.also { display = it } ?: return null
            return link to conn
        }
    }

    private fun rootPointerPosition(
        link: X11PointerControl,
        conn: X11.Display,
        root: X11.Window,
    ): Pair<Int, Int>? {
        val rootReturn = X11.WindowByReference()
        val childReturn = X11.WindowByReference()
        val rootX = IntByReference()
        val rootY = IntByReference()
        val winX = IntByReference()
        val winY = IntByReference()
        val mask = IntByReference()
        val inside = link.XQueryPointer(conn, root, rootReturn, childReturn, rootX, rootY, winX, winY, mask)
        return if (inside) rootX.value to rootY.value else null
    }
}
