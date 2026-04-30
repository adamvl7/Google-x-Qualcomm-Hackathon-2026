package com.fitform.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.fitform.app.R

/**
 * Distinctive type stack — Anton for compressed jersey-style display,
 * Manrope for body, JetBrains Mono for diagnostic numerals.
 *
 * Loaded via Downloadable Fonts so we don't have to bundle TTFs.
 */
private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val Anton = FontFamily(
    Font(googleFont = GoogleFont("Anton"), fontProvider = provider, weight = FontWeight.Normal),
)

private val Manrope = FontFamily(
    Font(googleFont = GoogleFont("Manrope"), fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("Manrope"), fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("Manrope"), fontProvider = provider, weight = FontWeight.SemiBold),
    Font(googleFont = GoogleFont("Manrope"), fontProvider = provider, weight = FontWeight.Bold),
    Font(googleFont = GoogleFont("Manrope"), fontProvider = provider, weight = FontWeight.ExtraBold),
)

private val Mono = FontFamily(
    Font(googleFont = GoogleFont("JetBrains Mono"), fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("JetBrains Mono"), fontProvider = provider, weight = FontWeight.Bold),
)

object FitFormType {
    val Display = Anton
    val Mono = com.fitform.app.ui.theme.Mono

    /** Massive jersey-number style — used for live score and headline counts. */
    val JerseyNumber = TextStyle(
        fontFamily = Anton,
        fontSize = 96.sp,
        lineHeight = 92.sp,
        letterSpacing = (-2).sp,
    )

    val DisplayHero = TextStyle(
        fontFamily = Anton,
        fontSize = 72.sp,
        lineHeight = 70.sp,
        letterSpacing = (-1.5).sp,
    )

    val DisplayMd = TextStyle(
        fontFamily = Anton,
        fontSize = 44.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.5).sp,
    )

    val Eyebrow = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 2.4.sp,
    )

    val LabelLg = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 1.6.sp,
    )

    val Label = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.2.sp,
    )

    val BodyLg = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )

    val Body = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )

    val Caption = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp,
    )

    val Stat = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.sp,
    )
}

internal val AppTypography = Typography(
    displayLarge = FitFormType.DisplayHero,
    displayMedium = FitFormType.DisplayMd,
    headlineLarge = FitFormType.DisplayMd,
    titleLarge = FitFormType.LabelLg,
    titleMedium = FitFormType.Label,
    bodyLarge = FitFormType.BodyLg,
    bodyMedium = FitFormType.Body,
    labelLarge = FitFormType.LabelLg,
    labelMedium = FitFormType.Label,
    labelSmall = FitFormType.Caption,
)
