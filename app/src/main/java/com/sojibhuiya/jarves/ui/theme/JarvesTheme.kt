package com.sojibhuiya.jarves.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Global design tokens for Jarves.
 *
 * One background, one surface, one border, one accent. Cyan is reserved for primary actions,
 * selected states and interactive highlights — never for icon decoration. Every icon uses
 * [IconDefault]/[IconMuted] so the whole app has a single, monochrome icon language.
 */
object JarvesColors {
    val Background = Color(0xFF0A0F16)
    val Surface = Color(0xFF111823)
    val SurfaceMuted = Color(0xFF0D141E)
    val Border = Color(0xFF1E2A36)

    // ---- Interactive surfaces -------------------------------------------------
    // Every button/icon button gets its own visible surface. Measured contrast against
    // Background (#0A0F16): neutral 1.35:1, primary tint 1.58:1 — clearly distinguishable
    // while staying subtle. Declared here only, so a light palette can be added by
    // swapping this block without touching any screen.
    /** Neutral surface behind secondary buttons, icon buttons and compact action chips. */
    val ButtonNeutral = Color(0xFF202C39)
    /** Border that separates a neutral button surface from the page. */
    val ButtonNeutralBorder = Color(0xFF334555)
    /** Very subtle cyan-tinted surface behind primary actions. */
    val ButtonPrimary = Color(0xFF163A45)
    /** Border used with [ButtonPrimary]. */
    val ButtonPrimaryBorder = Color(0x8C45CFDE)
    /** Surface behind destructive icon buttons (Delete) — red-tinted, same visibility as neutral. */
    val ButtonDanger = Color(0xFF33222A)
    val ButtonDangerBorder = Color(0x73CF6B72)
    val BorderFocused = Color(0xFF2E4256)

    val TextPrimary = Color(0xFFE9EFF6)
    val TextSecondary = Color(0xFF93A2B2)
    val TextMuted = Color(0xFF67798C)

    /** Accent — primary actions, selected states, interactive highlights only. */
    val Accent = Color(0xFF45CFDE)
    val AccentSoft = Color(0x1F45CFDE)
    /** Text/icon colour placed on top of a filled accent surface. */
    val OnAccent = Color(0xFF04161B)

    /** Destructive — muted, never bright red. */
    val Danger = Color(0xFFCF6B72)
    val DangerSoft = Color(0x1FCF6B72)

    val Success = Color(0xFF6FBF9B)
    val SuccessSoft = Color(0x1F6FBF9B)

    /** The single icon colour used across Home, drawer, Settings and API Providers. */
    val IconDefault = Color(0xFFC9D4E0)
    /** Lower-emphasis icon colour for secondary affordances. */
    val IconMuted = Color(0xFF8A99A8)
}

/** Spacing, radius and size scale shared by every screen. */
object JarvesDimens {
    val ScreenPadding: Dp = 16.dp
    val CardPadding: Dp = 12.dp
    val CardRadius: Dp = 12.dp
    val ControlRadius: Dp = 10.dp
    val FieldRadius: Dp = 10.dp

    /** Section spacing between blocks. */
    val SectionGap: Dp = 22.dp
    /** Spacing between items inside a section. */
    val ItemGap: Dp = 8.dp

    val IconSize: Dp = 18.dp
    val IconSmall: Dp = 15.dp
    val IconLarge: Dp = 22.dp

    /** Minimum touch target for any interactive control. */
    val TouchTarget: Dp = 44.dp
    val IconActionSize: Dp = 44.dp
    val HeaderButtonHeight: Dp = 36.dp
    /** Shared button heights so every action in the app lines up. */
    val ButtonHeight: Dp = 40.dp
    val CompactButtonHeight: Dp = 34.dp
    /** Minimum gap between two adjacent button surfaces, so they never read as one control. */
    val ButtonGap: Dp = 8.dp
    val RowMinHeight: Dp = 52.dp
    val NavRowHeight: Dp = 44.dp

    val DrawerWidth: Dp = 288.dp
    /** Drawer rows and glyphs sit slightly larger than the rest of the app for comfortable taps. */
    val DrawerRowHeight: Dp = 52.dp
    val DrawerRowGap: Dp = 4.dp
    val DrawerIconSize: Dp = 20.dp

    /**
     * Consistent stroke width for every icon in the app — Lucide's medium weight (2.0),
     * so icons read clear and strong instead of thin/lightweight.
     */
    const val IconStrokeWidth: Float = 2.05f
    /** Diameter of every icon-only button's circular surface. */
    val IconButtonSize: Dp = 44.dp
}

/** Typography scale — four sizes only, so hierarchy stays obvious and compact. */
object JarvesTypography {
    val ScreenTitle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = JarvesColors.TextPrimary)
    val DrawerTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = JarvesColors.TextPrimary)
    val ItemTitle = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = JarvesColors.TextPrimary)
    val Body = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, color = JarvesColors.TextSecondary)
    val Caption = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, color = JarvesColors.TextMuted)
    val SectionHeader = TextStyle(
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.9.sp,
        color = JarvesColors.TextMuted
    )
}
