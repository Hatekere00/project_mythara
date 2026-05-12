package com.mythara.ui.theme

/**
 * Crush's text glyph alphabet. These render in JetBrains Mono and replace
 * Material icons in tool-call rendering, status markers, and section heads.
 *
 * Pulled verbatim from `charmbracelet/crush` internal/ui/styles/styles.go —
 * the same characters Crush uses on the terminal so the mobile UI inherits
 * the visual idiom instead of inventing one.
 */
object Glyph {
    const val DiamondOutline    = "◇"   // ◇  secondary action / outline state
    const val DiamondFilled     = "◆"   // ◆  primary action / filled state
    const val Dot               = "●"   // ●  bullet, "active" marker
    const val Check             = "✓"   // ✓  success
    const val Cross             = "×"   // ×  failure / dismiss
    const val Ellipsis          = "⋯"   // ⋯  thinking / pending
    const val Refresh           = "⟳"   // ⟳  retry
    const val AccentBar         = "▌"   // ▌  vertical accent (active section)
    const val ThinDivider       = "│"   // │  divider, sidebar gutter
    const val Arrow             = "→"   // →  flow indicator
    const val LeftArrow         = "←"   // ←  back navigation
    const val DescendingArrow   = "⇣"   // ⇣  output / yield arrow
    const val CircleOutline     = "○"   // ○  inactive state
    const val CircleFilled      = "◉"   // ◉  selected radio
    const val Pipe              = "┃"   // ┃  heavy gutter
}
