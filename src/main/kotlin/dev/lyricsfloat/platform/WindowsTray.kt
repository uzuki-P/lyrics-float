package dev.lyricsfloat.platform

import java.awt.CheckboxMenuItem
import java.awt.EventQueue
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.imageio.ImageIO

/**
 * Notification-area icon through AWT's SystemTray, the Windows counterpart of
 * [LinuxSniTray]: left click toggles the lyrics, right click opens the same
 * menu. AWT menus have no "about to show" hook, so labels refresh on every
 * mouse press over the icon, which Windows delivers before the menu opens.
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
        val toggle = MenuItem().apply { addActionListener { actions.onToggleOverlay() } }
        val passThrough = CheckboxMenuItem("Click pass-through").apply {
            addItemListener { actions.onToggleClickPassThrough() }
        }
        fun refresh() {
            toggle.label = if (actions.overlayVisible()) "Hide lyrics" else "Show lyrics"
            passThrough.state = actions.clickPassThroughEnabled()
        }
        refresh()

        val menu = PopupMenu().apply {
            add(MenuItem("Lyrics Float").apply { isEnabled = false })
            addSeparator()
            add(toggle)
            add(MenuItem("Search lyrics").apply { addActionListener { actions.onSearch() } })
            add(MenuItem("Lyrics timing").apply { addActionListener { actions.onTiming() } })
            add(MenuItem("Settings").apply { addActionListener { actions.onSettings() } })
            addSeparator()
            add(passThrough)
            addSeparator()
            add(MenuItem("Quit").apply { addActionListener { actions.onQuit() } })
        }

        val image = requireNotNull(
            WindowsTray::class.java.classLoader
                .getResourceAsStream("composeResources/dev.lyricsfloat.resources/drawable/lyrics_float_tray.png"),
        ) { "missing tray icon" }.use(ImageIO::read)

        val trayIcon = TrayIcon(image, "Lyrics Float", menu).apply {
            isImageAutoSize = true
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = refresh()

                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1 && e.clickCount == 1) {
                        actions.onToggleOverlay()
                        refresh()
                    }
                }
            })
        }
        SystemTray.getSystemTray().add(trayIcon)
        icon = trayIcon
    }
}
