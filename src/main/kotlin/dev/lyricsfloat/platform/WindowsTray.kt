package dev.lyricsfloat.platform

import java.awt.EventQueue
import java.awt.MouseInfo
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.imageio.ImageIO

/**
 * Notification-area icon through AWT's SystemTray, the Windows counterpart of
 * [LinuxSniTray]: left click toggles the lyrics, right click opens the
 * app-drawn menu (`ui/TrayMenu.kt`) through [TrayActions.onOpenMenu]. AWT's
 * own PopupMenu is a plain Win32 menu that cannot use the app's look.
 */
internal class WindowsTray(private val actions: TrayActions) : AppTray {
    private var icon: TrayIcon? = null

    override fun start() {
        EventQueue.invokeLater {
            runCatching { install() }.onFailure { println("Lyrics Float: tray failed to start: ${it.message}") }
        }
    }

    override fun stop() {
        EventQueue.invokeLater {
            icon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
            icon = null
        }
    }

    private fun install() {
        if (icon != null || !SystemTray.isSupported()) return
        val image = requireNotNull(
            WindowsTray::class.java.classLoader
                .getResourceAsStream("composeResources/dev.lyricsfloat.resources/drawable/lyrics_float_tray.png"),
        ) { "missing tray icon" }.use(ImageIO::read)

        val trayIcon = TrayIcon(image, "Lyrics Float").apply {
            isImageAutoSize = true
            addMouseListener(object : MouseAdapter() {
                // Windows reports the popup trigger on release.
                override fun mouseReleased(e: MouseEvent) {
                    if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3) {
                        // TrayIcon event coordinates are device pixels on
                        // scaled displays; MouseInfo matches window coordinates.
                        val point = MouseInfo.getPointerInfo()?.location ?: e.locationOnScreen
                        actions.onOpenMenu(point)
                    }
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1 && e.clickCount == 1) actions.onToggleOverlay()
                }
            })
        }
        SystemTray.getSystemTray().add(trayIcon)
        icon = trayIcon
    }
}
