package com.fitform.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkScheme = darkColorScheme(
    primary = FitFormColors.Acid,
    onPrimary = FitFormColors.Ink,
    secondary = FitFormColors.Bone,
    onSecondary = FitFormColors.Ink,
    background = FitFormColors.Ink,
    onBackground = FitFormColors.Bone,
    surface = FitFormColors.Surface,
    onSurface = FitFormColors.Bone,
    surfaceVariant = FitFormColors.SurfaceHigh,
    onSurfaceVariant = FitFormColors.Mute,
    outline = FitFormColors.Hairline,
    error = FitFormColors.StatusRed,
    onError = FitFormColors.Bone,
)

@Composable
fun FitFormTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // FitForm is dark-only by design — the aesthetic depends on it.
    val scheme = DarkScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = FitFormColors.Ink.toArgb()
            window.navigationBarColor = FitFormColors.Ink.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        content = content,
    )
}
