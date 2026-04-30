package com.fitform.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Tactical Performance palette.
 *
 * Single dominant accent (acid lime) on near-black surfaces, with
 * three diagnostic status colors reserved exclusively for live coaching
 * feedback. The brand accent is never used to communicate state.
 */
object FitFormColors {
    // Surfaces
    val Ink = Color(0xFF0B0C0E)            // app background
    val Surface = Color(0xFF15171B)        // raised cards / sheets
    val SurfaceHigh = Color(0xFF1F2228)    // elevated chips
    val Hairline = Color(0xFF2A2D33)       // 1dp dividers

    // Type
    val Bone = Color(0xFFF5F4F0)           // primary text (warm off-white)
    val Mute = Color(0xFF8C8F95)           // secondary text
    val Faint = Color(0xFF555960)          // tertiary / scaffolding

    // Brand accent — a single bold acid lime, used sparingly
    val Acid = Color(0xFFD4FF3A)
    val AcidDeep = Color(0xFF8FB300)
    val AcidGlow = Color(0x33D4FF3A)       // 20% alpha for glow / focus rings

    // Status (only ever for live coaching feedback, not branding)
    val StatusGreen = Color(0xFF3FE17A)
    val StatusAmber = Color(0xFFFFB341)
    val StatusRed = Color(0xFFFF5A5A)

    val ScrimTop = Brush.verticalGradient(
        0f to Color(0xCC000000),
        1f to Color(0x00000000),
    )
    val ScrimBottom = Brush.verticalGradient(
        0f to Color(0x00000000),
        1f to Color(0xCC000000),
    )
}
