package dev.lyricsfloat.platform

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusBoundProperty
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import java.awt.image.BufferedImage
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.concurrent.thread

// Tray icon colors are picked from the app theme, not a detector library.
private fun trayIconIsDark(): Boolean =
    org.jetbrains.skiko.currentSystemTheme == org.jetbrains.skiko.SystemTheme.DARK

class IconPixmap(
    @field:Position(0) val width: Int,
    @field:Position(1) val height: Int,
    @field:Position(2) val pixels: ByteArray,
) : Struct()

class ToolTip(
    @field:Position(0) val iconName: String,
    @field:Position(1) val iconPixmap: List<IconPixmap>,
    @field:Position(2) val title: String,
    @field:Position(3) val description: String,
) : Struct()

class MenuLayout(
    @field:Position(0) val id: Int,
    @field:Position(1) val properties: Map<String, Variant<Any?>>,
    @field:Position(2) val children: List<Variant<MenuLayout>>,
) : Struct()

// Tuple, not Struct: GetLayout must return two separate out-args (u + (ia{sv}av)),
// which is what Qt's DBusMenuImporter demarshals. A single Struct produces one
// nested out-arg and Qt silently fails to read the layout, leaving the menu empty.
class MenuLayoutResult<A, B>(
    @field:Position(0) val revision: A,
    @field:Position(1) val layout: B,
) : Tuple()

class ItemProperties(
    @field:Position(0) val id: Int,
    @field:Position(1) val properties: Map<String, Variant<Any?>>,
) : Struct()

class EventData(
    @field:Position(0) val id: Int,
    @field:Position(1) val eventId: String,
    @field:Position(2) val data: Variant<Any?>,
    @field:Position(3) val timestamp: UInt32,
) : Struct()

class IdAboutToShow(
    @field:Position(0) val id: Int,
    @field:Position(1) val needUpdate: Boolean,
) : Struct()

@DBusInterfaceName("org.kde.StatusNotifierItem")
interface SniItem : DBusInterface {
    @DBusBoundProperty(access = DBusPropertyAccess.READ)
    fun getCategory(): String

    @DBusBoundProperty(access = DBusPropertyAccess.READ)
    fun getId(): String

    @DBusBoundProperty(access = DBusPropertyAccess.READ)
    fun getTitle(): String

    @DBusBoundProperty(access = DBusPropertyAccess.READ)
    fun getStatus(): String

    @DBusBoundProperty(access = DBusPropertyAccess.READ)
    fun getWindowId(): Int

    @DBusBoundProperty(access = DBusPropertyAccess.READ)
    fun getItemIsMenu(): Boolean

    @DBusBoundProperty(access = DBusPropertyAccess.READ)
    fun getMenu(): DBusPath

    @DBusBoundProperty(access = DBusPropertyAccess.READ)
    fun getIconName(): String

    @DBusBoundProperty(access = DBusPropertyAccess.READ)
    fun getIconPixmap(): List<IconPixmap>

    @DBusBoundProperty(access = DBusPropertyAccess.READ)
    fun getAttentionIconName(): String

    @DBusBoundProperty(access = DBusPropertyAccess.READ)
    fun getAttentionIconPixmap(): List<IconPixmap>

    @DBusBoundProperty(access = DBusPropertyAccess.READ)
    fun getOverlayIconName(): String

    @DBusBoundProperty(access = DBusPropertyAccess.READ)
    fun getOverlayIconPixmap(): List<IconPixmap>

    @DBusBoundProperty(access = DBusPropertyAccess.READ)
    fun getToolTip(): ToolTip

    fun Activate(x: Int, y: Int)
    fun SecondaryActivate(x: Int, y: Int)
    fun Scroll(delta: Int, orientation: String)
    fun ContextMenu(x: Int, y: Int)
}

@DBusInterfaceName("com.canonical.dbusmenu")
interface SniMenu : DBusInterface {
    @DBusBoundProperty(access = DBusPropertyAccess.READ)
    fun getVersion(): UInt32

    @DBusBoundProperty(access = DBusPropertyAccess.READ)
    fun getStatus(): String

    @DBusBoundProperty(access = DBusPropertyAccess.READ)
    fun getIconThemePath(): List<String>

    fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): MenuLayoutResult<UInt32, MenuLayout>

    fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<ItemProperties>

    fun AboutToShow(id: Int): Boolean

    fun Event(id: Int, eventId: String, data: Variant<Any?>, timestamp: UInt32)

    fun EventGroup(events: List<EventData>): List<Int>

    fun AboutToShowGroup(ids: List<Int>): List<IdAboutToShow>
}

@DBusInterfaceName("org.kde.StatusNotifierWatcher")
interface StatusNotifierWatcherRemote : DBusInterface {
    fun RegisterStatusNotifierItem(service: String)
}

private typealias DBusPropertyAccess = org.freedesktop.dbus.annotations.DBusProperty.Access

private const val ITEM_PATH = "/StatusNotifierItem"
private const val MENU_PATH = "/StatusNotifierMenu"

// Menu ids
private const val MENU_TOGGLE = 1
private const val MENU_SEARCH = 3
private const val MENU_SETTINGS = 4
private const val MENU_QUIT = 6
private const val MENU_PASS_THROUGH = 7

/**
 * StatusNotifierItem tray for Plasma/Freedesktop shells over the session bus.
 * Adapted from the pray-time project's LinuxSniTray.
 */
class LinuxSniTray(
    private val onToggleOverlay: () -> Unit,
    private val onSearch: () -> Unit,
    private val onSettings: () -> Unit,
    private val onToggleClickPassThrough: () -> Unit,
    private val clickPassThroughEnabled: () -> Boolean,
    private val onQuit: () -> Unit,
) {
    private var connection: DBusConnection? = null
    private val revision = AtomicInteger(1)

    fun start() {
        thread(name = "lyricsfloat-sni", isDaemon = true) {
            try {
                val conn = DBusConnectionBuilder.forSessionBus().build()
                connection = conn
                val busName = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-lyricsfloat"
                conn.requestBusName(busName)
                conn.exportObject(MENU_PATH, MenuImpl())
                conn.exportObject(ITEM_PATH, ItemImpl())
                val watcher = conn.getRemoteObject(
                    "org.kde.StatusNotifierWatcher",
                    "/StatusNotifierWatcher",
                    StatusNotifierWatcherRemote::class.java,
                )
                watcher.RegisterStatusNotifierItem(busName)
            } catch (t: Throwable) {
                println("Lyrics Float: SNI tray failed to start: ${t.message}")
                stop()
            }
        }
    }

    fun stop() {
        connection?.let { conn ->
            runCatching { conn.disconnect() }
            connection = null
        }
    }

    private fun pixmap(): List<IconPixmap> {
        return runCatching {
            val name = if (trayIconIsDark()) {
                "lyrics_float_tray_light.png"
            } else {
                "lyrics_float_tray_dark.png"
            }
            val bytes: ByteArray = requireNotNull(
                LinuxSniTray::class.java.classLoader
                    .getResourceAsStream("composeResources/dev.lyricsfloat.resources/drawable/$name"),
            ) { "missing tray icon $name" }.use(InputStream::readBytes)
            val img: BufferedImage = ImageIO.read(bytes.inputStream())
            val argb = IntArray(img.width * img.height)
            img.getRGB(0, 0, img.width, img.height, argb, 0, img.width)
            val data = ByteArray(argb.size * 4)
            var index = 0
            for (pixel in argb) {
                data[index++] = (pixel and 0xFF).toByte()
                data[index++] = ((pixel shr 8) and 0xFF).toByte()
                data[index++] = ((pixel shr 16) and 0xFF).toByte()
                data[index++] = ((pixel shr 24) and 0xFF).toByte()
            }
            listOf(IconPixmap(img.width, img.height, data))
        }.getOrDefault(emptyList())
    }

    private fun menuItems(): List<MenuLayout> = listOf(
        MenuLayout(MENU_TOGGLE, itemProps("Show / Hide lyrics", "view-form-text"), emptyList()),
        MenuLayout(2, mapOf("type" to Variant("separator")), emptyList()),
        MenuLayout(MENU_SEARCH, itemProps("Search lyrics", "edit-find"), emptyList()),
        MenuLayout(MENU_SETTINGS, itemProps("Settings", "preferences-system"), emptyList()),
        MenuLayout(MENU_PASS_THROUGH, passThroughProps(), emptyList()),
        MenuLayout(5, mapOf("type" to Variant("separator")), emptyList()),
        MenuLayout(MENU_QUIT, itemProps("Quit", "application-exit"), emptyList()),
    )

    private fun itemProps(label: String, iconName: String? = null): Map<String, Variant<Any?>> {
        val props = linkedMapOf<String, Variant<Any?>>(
            "label" to Variant(label),
            "enabled" to Variant(true),
        )
        if (iconName != null) {
            props["icon-name"] = Variant(iconName)
        }
        return props
    }

    // dbusmenu checkmark toggle; the state is read on every menu open so the
    // checkmark follows toggles made from the settings window.
    private fun passThroughProps(): Map<String, Variant<Any?>> = linkedMapOf(
        "label" to Variant("Click pass-through"),
        "enabled" to Variant(true),
        "toggle-type" to Variant("checkmark"),
        "toggle-state" to Variant(if (clickPassThroughEnabled()) 1 else 0),
    )

    private fun rootLayout(depth: Int): MenuLayout {
        return MenuLayout(
            0,
            mapOf("children-display" to Variant("submenu")),
            menuItems().map { Variant(it) },
        )
    }

    private inner class ItemImpl : SniItem {
        override fun getCategory() = "ApplicationStatus"
        override fun getId() = "Lyrics Float"
        override fun getTitle() = "Lyrics Float"
        override fun getStatus() = "Active"
        override fun getWindowId() = 0
        override fun getItemIsMenu() = false
        // Hand the menu to Plasma: right-click is rendered by plasmashell over
        // DBusMenu, so it works even when the app's AWT windows are stuck.
        override fun getMenu() = DBusPath(MENU_PATH)
        override fun getIconName() = ""
        override fun getIconPixmap() = pixmap()
        override fun getAttentionIconName() = ""
        override fun getAttentionIconPixmap() = emptyList<IconPixmap>()
        override fun getOverlayIconName() = ""
        override fun getOverlayIconPixmap() = emptyList<IconPixmap>()
        override fun getToolTip() = ToolTip("", emptyList(), "Lyrics Float", "Floating synced lyrics for MPRIS players")
        override fun Activate(x: Int, y: Int) {
            onToggleOverlay()
        }
        override fun SecondaryActivate(x: Int, y: Int) {}
        override fun Scroll(delta: Int, orientation: String) {}
        override fun ContextMenu(x: Int, y: Int) {
            onToggleOverlay()
        }
        override fun getObjectPath(): String? = ITEM_PATH
        override fun toString(): String = "LyricsFloatSniItem"
    }

    private inner class MenuImpl : SniMenu {
        override fun getVersion() = UInt32(3L)
        override fun getStatus() = "normal"
        override fun getIconThemePath() = emptyList<String>()

        override fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): MenuLayoutResult<UInt32, MenuLayout> {
            val layout = if (parentId == 0) rootLayout(recursionDepth) else {
                menuItems().firstOrNull { it.id == parentId } ?: rootLayout(0)
            }
            return MenuLayoutResult(UInt32(revision.get().toLong()), layout)
        }

        override fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<ItemProperties> {
            return ids.map { id ->
                val props = when (id) {
                    MENU_TOGGLE -> itemProps("Show / Hide lyrics", "view-form-text")
                    MENU_SEARCH -> itemProps("Search lyrics", "edit-find")
                    MENU_SETTINGS -> itemProps("Settings", "preferences-system")
                    MENU_PASS_THROUGH -> passThroughProps()
                    MENU_QUIT -> itemProps("Quit", "application-exit")
                    else -> emptyMap()
                }
                ItemProperties(id, props)
            }
        }

        // Root always reports changes on open so Plasma refetches the layout;
        // the revision must move or Qt's importer silently drops the update,
        // leaving the pass-through checkmark stale.
        override fun AboutToShow(id: Int): Boolean {
            if (id == 0) {
                revision.incrementAndGet()
                return true
            }
            return false
        }

        override fun Event(id: Int, eventId: String, data: Variant<Any?>, timestamp: UInt32) {
            if (eventId != "clicked") return
            when (id) {
                MENU_TOGGLE -> onToggleOverlay()
                MENU_SEARCH -> onSearch()
                MENU_SETTINGS -> onSettings()
                MENU_PASS_THROUGH -> onToggleClickPassThrough()
                MENU_QUIT -> onQuit()
            }
        }

        override fun EventGroup(events: List<EventData>) = emptyList<Int>()

        override fun AboutToShowGroup(ids: List<Int>) = ids.map { IdAboutToShow(it, false) }

        override fun getObjectPath(): String? = MENU_PATH
        override fun toString(): String = "LyricsFloatSniMenu"
    }
}
