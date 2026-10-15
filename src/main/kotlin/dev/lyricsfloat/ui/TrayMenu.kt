package dev.lyricsfloat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.lyricsfloat.resources.Res
import dev.lyricsfloat.resources.lyrics_float_tray
import org.jetbrains.compose.resources.painterResource
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * The tray right-click menu drawn with the app's own palette and icons, for
 * platforms whose native tray menu cannot carry them (Windows: AWT's
 * PopupMenu is a plain Win32 menu). Linux keeps Plasma's DBusMenu.
 *
 * [anchor] is the pointer position in screen coordinates; null hides the
 * menu. It closes when it loses focus, on Escape, or after an action.
 */
@Composable
fun TrayMenuWindow(
    anchor: Point?,
    palette: AppPalette,
    overlayVisible: Boolean,
    clickPassThrough: Boolean,
    onDismiss: () -> Unit,
    onToggleOverlay: () -> Unit,
    onSearch: () -> Unit,
    onTiming: () -> Unit,
    onSettings: () -> Unit,
    onToggleClickPassThrough: () -> Unit,
    onQuit: () -> Unit,
) {
    if (anchor == null) return
    Window(
        onCloseRequest = onDismiss,
        title = "Lyrics Float menu",
        undecorated = true,
        transparent = true,
        resizable = false,
        focusable = true,
        alwaysOnTop = true,
        // Unspecified size: the window takes its content's size.
        state = rememberWindowState(
            size = DpSize(Dp.Unspecified, Dp.Unspecified),
            position = WindowPosition(anchor.x.dp, anchor.y.dp),
        ),
        onKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onDismiss()
                true
            } else {
                false
            }
        },
    ) {
        DisposableEffect(window) {
            // Utility windows stay out of the taskbar and still take focus.
            runCatching { window.type = java.awt.Window.Type.UTILITY }
            window.background = java.awt.Color(0, 0, 0, 0)
            // A click anywhere else takes focus away; that dismisses the menu
            // like a native one.
            val listener = object : WindowAdapter() {
                override fun windowDeactivated(e: WindowEvent) = onDismiss()
            }
            window.addWindowListener(listener)
            onDispose { window.removeWindowListener(listener) }
        }

        // Open above and to the left of the pointer (the taskbar usually sits
        // at the bottom), kept inside the screen the pointer is on. The size
        // is only known after the first layout, so place it after a frame.
        LaunchedEffect(anchor) {
            withFrameNanos { }
            val screen = screenBoundsAt(anchor)
            val x = (anchor.x - window.width).coerceIn(screen.x, screen.x + screen.width - window.width)
            val y = (anchor.y - window.height).coerceIn(screen.y, screen.y + screen.height - window.height)
            window.setLocation(x, y)
            window.toFront()
            window.requestFocus()
        }

        fun run(action: () -> Unit): () -> Unit = {
            onDismiss()
            action()
        }

        CompositionLocalProvider(LocalAppPalette provides palette) {
            val shape = RoundedCornerShape(14.dp)
            Column(
                modifier = Modifier
                    .wrapContentSize()
                    .width(232.dp)
                    .clip(shape)
                    .background(palette.surface)
                    .border(1.dp, palette.outline, shape)
                    .padding(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(painterResource(Res.drawable.lyrics_float_tray), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Lyrics Float", style = AppType.title, color = palette.onSurface)
                }
                MenuDivider(palette)
                MenuItem(
                    icon = if (overlayVisible) AppIcons.VisibilityOff else AppIcons.Visibility,
                    label = if (overlayVisible) "Hide lyrics" else "Show lyrics",
                    onClick = run(onToggleOverlay),
                )
                MenuItem(AppIcons.Search, "Search lyrics", run(onSearch))
                MenuItem(AppIcons.Timer, "Lyrics timing", run(onTiming))
                MenuItem(AppIcons.Tune, "Settings", run(onSettings))
                MenuDivider(palette)
                MenuItem(
                    icon = if (clickPassThrough) AppIcons.PointerPassThrough else AppIcons.PointerActive,
                    label = "Click pass-through",
                    onClick = run(onToggleClickPassThrough),
                    trailing = if (clickPassThrough) "On" else "Off",
                    highlighted = clickPassThrough,
                )
                MenuDivider(palette)
                MenuItem(AppIcons.Power, "Quit", run(onQuit))
            }
        }
    }
}

@Composable
private fun MenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    trailing: String? = null,
    highlighted: Boolean = false,
) {
    val palette = LocalAppPalette.current
    val (source, hovered) = rememberHoverSource()
    val tint = if (highlighted) palette.accent else palette.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(if (hovered) palette.cardHover else palette.surface)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = AppType.body, color = palette.onSurface, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (highlighted) palette.accentSoft else palette.card)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(trailing, style = AppType.label, color = if (highlighted) palette.accent else palette.onSurfaceDim)
            }
        }
    }
}

@Composable
private fun MenuDivider(palette: AppPalette) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(palette.divider))
}

private fun screenBoundsAt(point: Point): Rectangle =
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        .map { it.defaultConfiguration.bounds }
        .firstOrNull { it.contains(point) }
        ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
