@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.lyricsfloat.ui

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Shared building blocks for the search and settings dialogs (and the
 * overlay's hover bar), so every control has the same shapes, hover states,
 * cursors and type scale.
 */

internal object AppType {
    val title = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold)
    val body = TextStyle(fontSize = 13.sp)
    val bodyStrong = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val label = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    val caption = TextStyle(fontSize = 11.sp, lineHeight = 15.sp)
    val overline = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
}

private val ControlShape = RoundedCornerShape(10.dp)

/** Hand cursor plus a hover flag for custom clickable surfaces. */
@Composable
internal fun rememberHoverSource(): Pair<MutableInteractionSource, Boolean> {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    return source to hovered
}

/** Wraps [content] with a delayed tooltip, the desktop stand-in for icon labels. */
@Composable
internal fun WithTooltip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val palette = LocalAppPalette.current
    TooltipArea(
        tooltip = {
            Text(
                text,
                style = AppType.caption,
                color = palette.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.surface)
                    .border(1.dp, palette.outline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        },
        modifier = modifier,
        delayMillis = 450,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp)),
        content = content,
    )
}

/** Square icon button with a hover tint and a tooltip naming the action. */
@Composable
internal fun IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalAppPalette.current.onSurfaceDim,
    size: Dp = 30.dp,
    iconSize: Dp = 18.dp,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val palette = LocalAppPalette.current
    val (source, hovered) = rememberHoverSource()
    WithTooltip(label) {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when {
                        selected -> palette.accentSoft
                        hovered && enabled -> palette.cardHover
                        else -> Color.Transparent
                    },
                )
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(
                    interactionSource = source,
                    indication = null,
                    enabled = enabled,
                    onClickLabel = label,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = when {
                    !enabled -> tint.copy(alpha = 0.35f)
                    selected -> palette.accent
                    hovered -> palette.onSurface
                    else -> tint
                },
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

internal enum class ButtonKind { PRIMARY, SECONDARY, GHOST }

@Composable
internal fun AppButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.SECONDARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    compact: Boolean = false,
) {
    val palette = LocalAppPalette.current
    val (source, hovered) = rememberHoverSource()
    val active = enabled && !loading
    val background = when (kind) {
        ButtonKind.PRIMARY -> if (active) palette.accent.copy(alpha = if (hovered) 0.9f else 1f) else palette.accent.copy(alpha = 0.3f)
        ButtonKind.SECONDARY -> if (hovered && active) palette.cardHover else palette.card
        ButtonKind.GHOST -> if (hovered && active) palette.card else Color.Transparent
    }
    val content = when (kind) {
        ButtonKind.PRIMARY -> palette.onAccent
        ButtonKind.SECONDARY -> if (active) palette.onSurface else palette.onSurfaceFaint
        ButtonKind.GHOST -> if (active) palette.accent else palette.onSurfaceFaint
    }
    Row(
        modifier = modifier
            .clip(ControlShape)
            .background(background)
            .pointerHoverIcon(if (active) PointerIcon.Hand else PointerIcon.Default)
            .clickable(interactionSource = source, indication = null, enabled = active, onClick = onClick)
            .padding(
                horizontal = if (compact) 9.dp else 12.dp,
                vertical = if (compact) 5.dp else 7.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = content,
            )
            Spacer(Modifier.width(6.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(label, style = AppType.label, color = content, maxLines = 1)
    }
}

/** Selectable pill; [color] tints the selected state (provider colors). */
@Composable
internal fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = LocalAppPalette.current.accent,
    leadingDot: Color? = null,
) {
    val palette = LocalAppPalette.current
    val (source, hovered) = rememberHoverSource()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    selected -> color.copy(alpha = 0.18f)
                    hovered -> palette.cardHover
                    else -> palette.card
                },
            )
            .border(1.dp, if (selected) color.copy(alpha = 0.7f) else Color.Transparent, RoundedCornerShape(50))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingDot != null) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(leadingDot))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = AppType.label.copy(fontSize = 11.sp),
            color = if (selected) color else palette.onSurfaceDim,
            maxLines = 1,
        )
    }
}

/** Connected single-choice control (theme, alignment, hover-menu edge). */
@Composable
internal fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
) {
    val palette = LocalAppPalette.current
    Row(
        modifier = modifier
            .clip(ControlShape)
            .background(palette.card)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val (source, hovered) = rememberHoverSource()
            Box(
                modifier = Modifier
                    .weight(1f, fill = fill)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        when {
                            isSelected -> palette.accent
                            hovered -> palette.cardHover
                            else -> Color.Transparent
                        },
                    )
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = source, indication = null) { onSelect(value) }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = AppType.label,
                    color = if (isSelected) palette.onAccent else palette.onSurfaceDim,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Titled card that groups related settings. */
@Composable
internal fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalAppPalette.current
    Column(modifier.fillMaxWidth()) {
        Text(
            title.uppercase(),
            style = AppType.overline,
            color = palette.accent.copy(alpha = 0.9f),
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.card)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            content = content,
        )
        if (footer != null) {
            Text(
                footer,
                style = AppType.caption,
                color = palette.onSurfaceDim,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp, end = 4.dp),
            )
        }
    }
}

/** Label + optional description on the left, a control on the right. */
@Composable
internal fun SettingRow(
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    trailing: @Composable RowScope.() -> Unit,
) {
    val palette = LocalAppPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(label, style = AppType.body, color = palette.onSurface)
            if (description != null) {
                Text(description, style = AppType.caption, color = palette.onSurfaceDim)
            }
        }
        trailing()
    }
}

/** Whole row toggles the switch, not only the thumb. */
@Composable
internal fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    val palette = LocalAppPalette.current
    val source = remember { MutableInteractionSource() }
    SettingRow(
        label = label,
        description = description,
        modifier = Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null) { onCheckedChange(!checked) },
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = palette.onAccent,
                checkedTrackColor = palette.accent,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = palette.onSurfaceDim,
                uncheckedTrackColor = palette.onSurface.copy(alpha = 0.12f),
                uncheckedBorderColor = Color.Transparent,
            ),
            modifier = Modifier.scale(0.75f).height(24.dp),
        )
    }
}

@Composable
internal fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    description: String? = null,
) {
    val palette = LocalAppPalette.current
    Column(modifier = Modifier.padding(top = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = AppType.body, color = palette.onSurface, modifier = Modifier.weight(1f))
            ValueBadge(valueText)
        }
        if (description != null) {
            Text(description, style = AppType.caption, color = palette.onSurfaceDim)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = palette.accent,
                activeTrackColor = palette.accent,
                inactiveTrackColor = palette.onSurface.copy(alpha = 0.12f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.height(30.dp).pointerHoverIcon(PointerIcon.Hand),
        )
    }
}

@Composable
internal fun ValueBadge(text: String, modifier: Modifier = Modifier) {
    val palette = LocalAppPalette.current
    Text(
        text,
        style = AppType.label,
        color = palette.onSurface,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(palette.card)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
internal fun GroupDivider() {
    val palette = LocalAppPalette.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
}

/** Text input with an optional leading icon and trailing slot. */
@Composable
internal fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    singleLine: Boolean = true,
    fontSize: TextUnit = 13.sp,
    onSubmit: (() -> Unit)? = null,
    /** Masks the text, for API keys. */
    secret: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val palette = LocalAppPalette.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(palette.card)
            .border(1.dp, palette.outline, RoundedCornerShape(12.dp))
            .padding(start = if (leadingIcon != null) 10.dp else 12.dp, end = 6.dp)
            .padding(vertical = if (singleLine) 5.dp else 10.dp),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = palette.onSurfaceDim, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Search else ImeAction.Default),
            keyboardActions = KeyboardActions(onSearch = { onSubmit?.invoke() }),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = TextStyle(fontSize = fontSize, color = palette.onSurface, lineHeight = fontSize * 1.45f),
            cursorBrush = SolidColor(palette.accent),
            modifier = fieldModifier
                .weight(1f)
                .padding(vertical = if (singleLine) 4.dp else 0.dp)
                .then(if (singleLine) Modifier else Modifier.fillMaxSize()),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(placeholder, style = TextStyle(fontSize = fontSize), color = palette.onSurfaceFaint)
                    }
                    inner()
                }
            },
        )
        trailing?.invoke(this)
    }
}

/**
 * Single-choice dropdown for lists too long for a [SegmentedControl]
 * (languages, providers, models). Shows [placeholder] while nothing matches.
 */
@Composable
internal fun <T> AppDropdown(
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Choose…",
) {
    val palette = LocalAppPalette.current
    var expanded by remember { mutableStateOf(false) }
    val (source, hovered) = rememberHoverSource()
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ControlShape)
                .background(if (hovered) palette.cardHover else palette.card)
                .border(1.dp, palette.outline, ControlShape)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = source, indication = null) { expanded = true }
                .padding(start = 11.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                options.firstOrNull { it.first == selected }?.second ?: placeholder,
                style = AppType.label,
                color = if (options.any { it.first == selected }) palette.onSurface else palette.onSurfaceFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(AppIcons.ExpandMore, contentDescription = null, tint = palette.onSurfaceDim, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = palette.surface,
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            style = if (value == selected) AppType.bodyStrong else AppType.body,
                            color = if (value == selected) palette.accent else palette.onSurface,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
            }
        }
    }
}

internal enum class MessageTone { INFO, ACCENT, ERROR }

/** Inline status line: info, highlighted state, or an error. */
@Composable
internal fun InlineMessage(
    text: String,
    tone: MessageTone,
    modifier: Modifier = Modifier,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    val palette = LocalAppPalette.current
    val color = when (tone) {
        MessageTone.INFO -> palette.onSurfaceDim
        MessageTone.ACCENT -> palette.accent
        MessageTone.ERROR -> palette.danger
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(if (tone == MessageTone.INFO) palette.card else color.copy(alpha = 0.12f))
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = AppType.caption.copy(fontWeight = if (tone == MessageTone.INFO) FontWeight.Normal else FontWeight.SemiBold),
            color = color,
            modifier = Modifier.weight(1f).padding(vertical = 3.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        action?.invoke(this)
    }
}

/**
 * Rounded surface shared by the undecorated dialogs: a draggable header
 * (title, optional back arrow, close) and a body. The 10 dp outer inset
 * leaves room for the invisible resize zones around the card.
 */
@Composable
internal fun DialogFrame(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalAppPalette.current
    val shape = RoundedCornerShape(20.dp)
    Box(modifier.fillMaxSize().padding(10.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(palette.surface)
                .border(1.dp, palette.outline, shape),
        ) {
            Row(
                modifier = dragHandleModifier
                    .fillMaxWidth()
                    .padding(start = if (onBack != null) 8.dp else 18.dp, end = 10.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconAction(AppIcons.Back, "Back", onBack)
                    Spacer(Modifier.width(4.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = AppType.title, color = palette.onSurface, maxLines = 1)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = AppType.caption,
                            color = palette.onSurfaceDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                headerActions?.invoke(this)
                IconAction(AppIcons.Close, "Close", onClose)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                content = content,
            )
        }
    }
}
