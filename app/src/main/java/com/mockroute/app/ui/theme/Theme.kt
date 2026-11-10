package com.mockroute.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** One vibrant colour per kind of thing, on a dark base. */
object AppColors {
    val Background = Color(0xFF0D1117)
    val Surface = Color(0xFF161B22)
    val SurfaceHigh = Color(0xFF21262D)
    val Outline = Color(0xFF3D444D)
    val Text = Color(0xFFE6EDF3)
    val TextDim = Color(0xFF9DA7B3)

    val Blue = Color(0xFF4C9AFF)    // routes, primary actions
    val Cyan = Color(0xFF22D3EE)    // street tracing
    val Amber = Color(0xFFFFB020)   // free drawing, warnings
    val Purple = Color(0xFFB07CFF)  // areas (buildings / streets)
    val Green = Color(0xFF2EE6A6)   // trips, "all good"
    val Pink = Color(0xFFFF5C8A)    // errors, stop
}

/** Smooth squares everywhere (buttons, chips, cards, sheets). */
object AppShapes {
    val Button = RoundedCornerShape(10.dp)
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(14.dp),
        extraLarge = RoundedCornerShape(16.dp),
    )
}

/**
 * Motion rule for the whole app: animations ease OUT only (fast start, soft landing; never ease-in) and last at
 * most 100 ms. Use [AppMotion.spec] for every animation we write ourselves.
 */
object AppMotion {
    const val DURATION_MS = 100
    val EaseOut = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    fun <T> spec() = tween<T>(durationMillis = DURATION_MS, easing = EaseOut)
}

private val Scheme = darkColorScheme(
    primary = AppColors.Blue,
    onPrimary = Color(0xFF04121F),
    primaryContainer = Color(0xFF123A6B),
    onPrimaryContainer = Color(0xFFD6E8FF),
    secondary = AppColors.Green,
    onSecondary = Color(0xFF00210F),
    secondaryContainer = Color(0xFF0F3D2E),
    onSecondaryContainer = Color(0xFFBFF7E2),
    tertiary = AppColors.Amber,
    onTertiary = Color(0xFF2B1A00),
    tertiaryContainer = Color(0xFF4A3200),
    onTertiaryContainer = Color(0xFFFFE2B0),
    error = AppColors.Pink,
    onError = Color(0xFF2B000C),
    errorContainer = Color(0xFF5C1227),
    onErrorContainer = Color(0xFFFFD9E0),
    background = AppColors.Background,
    onBackground = AppColors.Text,
    surface = AppColors.Surface,
    onSurface = AppColors.Text,
    surfaceVariant = AppColors.SurfaceHigh,
    onSurfaceVariant = AppColors.TextDim,
    outline = AppColors.Outline,
    outlineVariant = Color(0xFF30363D),
    surfaceContainerLowest = Color(0xFF0A0D12),
    surfaceContainerLow = Color(0xFF12161C),
    surfaceContainer = AppColors.Surface,
    surfaceContainerHigh = AppColors.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF2A3038),
    inverseSurface = AppColors.Text,
    inverseOnSurface = AppColors.Background,
)

/** Always dark with vibrant accents (user preference; no dynamic colour, so each kind of thing keeps its colour). */
@Composable
fun MockRouteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, shapes = AppShapes.shapes, content = content)
}
