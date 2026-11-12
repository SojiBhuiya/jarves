package com.sojibhuiya.jarves.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sojibhuiya.jarves.ui.icons.JarvesIcons
import com.sojibhuiya.jarves.ui.theme.JarvesColors
import com.sojibhuiya.jarves.ui.theme.JarvesDimens
import com.sojibhuiya.jarves.ui.theme.JarvesTypography

/**
 * Shared building blocks. Screens compose these instead of restyling primitives, which keeps
 * spacing, radii, type scale and the monochrome icon language identical everywhere.
 */

// ---------------------------------------------------------------------------- icons

/** The only way icons should be drawn: one colour, one size scale, optional description. */
@Composable
fun JarvesIcon(
    imageVector: ImageVector,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = JarvesColors.IconDefault,
    size: androidx.compose.ui.unit.Dp = JarvesDimens.IconSize
) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

/**
 * Icon-only action: a perfectly circular surface (never a rounded rectangle) with the glyph
 * optically centred, a 44 dp touch target, and a subtle pressed state. Every icon-only control
 * in the app — Back, Menu, Edit, Delete, Close — comes from this one component.
 */
@Composable
fun JarvesIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = JarvesColors.IconDefault,
    size: androidx.compose.ui.unit.Dp = JarvesDimens.IconSize,
    background: Color = JarvesColors.ButtonNeutral,
    borderColor: Color = JarvesColors.ButtonNeutralBorder
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = modifier
            .size(JarvesDimens.IconButtonSize)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, borderColor, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClickLabel = contentDescription,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        JarvesIcon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            size = size
        )
        // Very subtle pressed overlay — no shadow, no bright colour.
        if (pressed) {
            Box(
                Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.10f))
            )
        }
    }
}

/** Icon + short label on one button surface (e.g. Settings' Edit action). */
@Composable
fun JarvesIconTextAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = JarvesColors.Accent
) {
    Row(
        modifier = modifier
            .height(JarvesDimens.CompactButtonHeight)
            .clip(RoundedCornerShape(JarvesDimens.ControlRadius))
            .background(JarvesColors.ButtonNeutral)
            .border(1.dp, JarvesColors.ButtonNeutralBorder, RoundedCornerShape(JarvesDimens.ControlRadius))
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        JarvesIcon(icon, contentDescription = null, tint = tint, size = JarvesDimens.IconSmall)
        Spacer(Modifier.width(5.dp))
        Text(label, color = tint, fontSize = 12.5.sp)
    }
}

// ---------------------------------------------------------------------------- surfaces

/** Card/row surface: slightly lighter than the background, 1 dp subtle border, no shadow. */
@Composable
fun JarvesCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JarvesDimens.CardRadius))
            .background(JarvesColors.Surface)
            .border(1.dp, JarvesColors.Border, RoundedCornerShape(JarvesDimens.CardRadius)),
        content = content
    )
}

/** Small uppercase group heading used above each section (the original Settings label). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    JarvesSectionHeader(text = text, modifier = modifier)

/** Small uppercase group heading used above each section. */
@Composable
fun JarvesSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = JarvesTypography.SectionHeader,
        modifier = modifier
    )
}

/** 1 dp divider in the shared border colour. */
@Composable
fun JarvesDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(JarvesColors.Border)
    )
}

// ---------------------------------------------------------------------------- actions

/**
 * Compact back affordance: the arrow glyph alone (no text label) on the same surface as every
 * other icon button, so the control is clearly visible on Settings, API Providers and Chats.
 * Delegates to [JarvesIconButton] — one implementation, one look.
 */
@Composable
fun JarvesBackRow(onBack: () -> Unit, modifier: Modifier = Modifier) {
    JarvesIconButton(
        icon = JarvesIcons.ArrowLeft,
        contentDescription = "Back",
        onClick = onBack,
        modifier = modifier,
        tint = JarvesColors.IconDefault,
        size = JarvesDimens.IconLarge
    )
}

/**
 * Compact header action (e.g. "+ Add"): small icon + short label, outlined, understated.
 * Never full-width, never filled.
 */
@Composable
fun CompactActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(JarvesDimens.HeaderButtonHeight)
            .clip(RoundedCornerShape(JarvesDimens.ControlRadius))
            .background(JarvesColors.ButtonNeutral)
            .border(1.dp, JarvesColors.ButtonNeutralBorder, RoundedCornerShape(JarvesDimens.ControlRadius))
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        JarvesIcon(icon, contentDescription = null, size = JarvesDimens.IconSmall)
        Spacer(Modifier.width(6.dp))
        Text(label, style = JarvesTypography.ItemTitle, fontSize = 13.sp)
    }
}

/** Compact labelled action on its own small surface (Save, Cancel, Show/Hide, Edit). */
@Composable
fun JarvesTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = JarvesColors.Accent,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .height(JarvesDimens.CompactButtonHeight)
            .clip(RoundedCornerShape(JarvesDimens.ControlRadius))
            .background(if (enabled) JarvesColors.ButtonNeutral else JarvesColors.SurfaceMuted)
            .border(
                1.dp,
                if (enabled) JarvesColors.ButtonNeutralBorder else JarvesColors.Border,
                RoundedCornerShape(JarvesDimens.ControlRadius)
            )
            .clickable(enabled = enabled, onClickLabel = text, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) color else JarvesColors.TextMuted,
            fontSize = 12.5.sp
        )
    }
}

/**
 * Primary action: its own subtle surface with a faint accent border and accent label — clearly
 * more prominent than a secondary action, but never a bright filled block.
 */
@Composable
fun JarvesPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier = modifier
            .height(JarvesDimens.ButtonHeight)
            .clip(RoundedCornerShape(JarvesDimens.ControlRadius))
            .background(if (enabled) JarvesColors.ButtonPrimary else JarvesColors.SurfaceMuted)
            .border(
                1.dp,
                if (enabled) JarvesColors.ButtonPrimaryBorder else JarvesColors.Border,
                RoundedCornerShape(JarvesDimens.ControlRadius)
            )
            .clickable(enabled = enabled, onClickLabel = text, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) JarvesColors.Accent else JarvesColors.TextMuted,
            fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
        )
    }
}

/** Secondary action: same surface language, neutral border and quieter label. */
@Composable
fun JarvesSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(JarvesDimens.ButtonHeight)
            .clip(RoundedCornerShape(JarvesDimens.ControlRadius))
            .background(JarvesColors.ButtonNeutral)
            .border(1.dp, JarvesColors.ButtonNeutralBorder, RoundedCornerShape(JarvesDimens.ControlRadius))
            .clickable(onClickLabel = text, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = JarvesColors.TextSecondary, fontSize = 13.sp)
    }
}

/** Provider enable/disable control: quiet track, accent thumb only while enabled. */
@Composable
fun CompactSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        colors = SwitchDefaults.colors(
            checkedThumbColor = JarvesColors.Accent,
            checkedTrackColor = JarvesColors.AccentSoft,
            checkedBorderColor = JarvesColors.Accent.copy(alpha = 0.45f),
            uncheckedThumbColor = JarvesColors.IconMuted,
            uncheckedTrackColor = JarvesColors.SurfaceMuted,
            uncheckedBorderColor = JarvesColors.Border
        )
    )
}

// ---------------------------------------------------------------------------- inputs

/** Shared field colours so every input in the app looks identical. */
@Composable
fun jarvesFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = JarvesColors.TextPrimary,
    unfocusedTextColor = JarvesColors.TextPrimary,
    focusedContainerColor = JarvesColors.SurfaceMuted,
    unfocusedContainerColor = JarvesColors.SurfaceMuted,
    errorContainerColor = JarvesColors.SurfaceMuted,
    cursorColor = JarvesColors.Accent,
    focusedBorderColor = JarvesColors.Accent.copy(alpha = 0.65f),
    unfocusedBorderColor = JarvesColors.Border,
    errorBorderColor = JarvesColors.Danger,
    focusedLabelColor = JarvesColors.TextSecondary,
    unfocusedLabelColor = JarvesColors.TextMuted,
    errorLabelColor = JarvesColors.Danger,
    focusedPlaceholderColor = JarvesColors.TextMuted,
    unfocusedPlaceholderColor = JarvesColors.TextMuted,
    focusedSupportingTextColor = JarvesColors.Danger,
    unfocusedSupportingTextColor = JarvesColors.TextMuted,
    errorSupportingTextColor = JarvesColors.Danger
)

/** Compact single-line text field used by every form in the app. */
@Composable
fun JarvesTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true
) {
    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
            placeholder = { Text(placeholder, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = singleLine,
            isError = isError,
            shape = RoundedCornerShape(JarvesDimens.FieldRadius),
            visualTransformation = visualTransformation,
            trailingIcon = trailing,
            colors = jarvesFieldColors()
        )
        if (supportingText != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = supportingText,
                color = if (isError) JarvesColors.Danger else JarvesColors.TextMuted,
                fontSize = 11.sp
            )
        }
    }
}

/** Inline status/error line. */
@Composable
fun JarvesStatusLine(text: String, isError: Boolean, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (isError) JarvesColors.Danger else JarvesColors.Success)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            color = if (isError) JarvesColors.Danger else JarvesColors.TextSecondary,
            fontSize = 11.5.sp
        )
    }
}

// ---------------------------------------------------------------------------- navigation

/**
 * One drawer row. Monochrome icon, label, selected state shown with a soft accent background
 * and accent label — icons themselves never change colour by category.
 */
@Composable
fun NavigationItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = JarvesDimens.DrawerRowHeight)
            .clip(RoundedCornerShape(JarvesDimens.ControlRadius))
            .background(if (selected) JarvesColors.AccentSoft else Color.Transparent)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        JarvesIcon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) JarvesColors.Accent else JarvesColors.IconDefault,
            size = JarvesDimens.DrawerIconSize
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            style = JarvesTypography.ItemTitle,
            fontSize = 15.sp,
            color = if (selected) JarvesColors.Accent else JarvesColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (badge != null) {
            Text(badge, style = JarvesTypography.Caption)
        }
    }
}

/**
 * The app navigation drawer — deliberately minimal, in the established Jarves dark theme:
 * brand at the top, an empty flexible area reserved for future destinations, and Settings
 * pinned to the very bottom above a subtle divider so it can never scroll away.
 */
@Composable
fun NavigationDrawer(
    assistantName: String,
    currentRoute: String,
    onNavigate: (route: String) -> Unit,
    onClose: () -> Unit
) {
    ModalDrawerSheet(
        drawerContainerColor = JarvesColors.Background,
        drawerContentColor = JarvesColors.TextPrimary,
        drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
        modifier = Modifier
            .width(JarvesDimens.DrawerWidth)
            .fillMaxHeight()
    ) {
        Column(Modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding()) {
            // ---- Header: only the saved Assistant Name (reactive DataStore value)
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = assistantName.ifBlank { "Jarves" },
                    style = JarvesTypography.DrawerTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                JarvesIconButton(
                    icon = JarvesIcons.X,
                    contentDescription = "Close navigation",
                    onClick = onClose,
                    tint = JarvesColors.IconMuted,
                    size = JarvesDimens.IconSmall
                )
            }

            // ---- Main area (scrollable when it grows), Settings stays pinned below
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(JarvesDimens.DrawerRowGap)
            ) {
                NavigationItem(
                    icon = JarvesIcons.MessageCircle,
                    label = "Chats",
                    selected = currentRoute == JarvesNavigation.CHATS,
                    onClick = { onNavigate(JarvesNavigation.CHATS) }
                )
            }

            // ---- Settings, permanently anchored to the bottom
            JarvesDivider(Modifier.padding(horizontal = 14.dp))
            Column(
                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(JarvesDimens.DrawerRowGap)
            ) {
                NavigationItem(
                    icon = JarvesIcons.Settings,
                    label = "Settings",
                    selected = currentRoute == JarvesNavigation.SETTINGS ||
                        currentRoute == JarvesNavigation.API_PROVIDERS,
                    onClick = { onNavigate(JarvesNavigation.SETTINGS) }
                )
            }
        }
    }
}

/** Route constants shared by the drawer and the content host. */
object JarvesNavigation {
    const val HOME = "home"
    const val CHATS = "chats"
    const val SETTINGS = "settings"
    const val API_PROVIDERS = "api_providers"
}

// ---------------------------------------------------------------------------- compatibility

/**
 * Thin aliases kept so screens written against the earlier component names keep compiling.
 * They delegate to [CompactActionButton] / [JarvesIconButton], so there is still exactly one
 * implementation of each visual style.
 */
@Composable
fun CompactAddButton(onClick: () -> Unit, label: String = "Add", modifier: Modifier = Modifier) =
    CompactActionButton(icon = JarvesIcons.Add, label = label, onClick = onClick, modifier = modifier)

@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = JarvesColors.IconDefault,
    modifier: Modifier = Modifier,
    background: Color = JarvesColors.ButtonNeutral,
    borderColor: Color = JarvesColors.ButtonNeutralBorder
) = JarvesIconButton(
    icon = icon,
    contentDescription = contentDescription,
    onClick = onClick,
    modifier = modifier,
    tint = tint,
    background = background,
    borderColor = borderColor
)
