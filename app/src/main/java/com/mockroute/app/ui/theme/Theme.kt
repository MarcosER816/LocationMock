package com.mockroute.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One vibrant colour per kind of thing, on a calm blue-black base (1.1.0 redesign). The neutrals step up in small,
 * even tonal steps so panels separate from the map and from each other by tone, not by heavy shadows or lines.
 */
object AppColors {
    val Background = Color(0xFF0B0F14)
    val Surface = Color(0xFF141A21)
    val SurfaceHigh = Color(0xFF1C232C)
    val SurfaceHighest = Color(0xFF252D37)
    val Outline = Color(0xFF3A4350)
    val OutlineSoft = Color(0xFF29313B)
    val Text = Color(0xFFE8EDF2)
    val TextDim = Color(0xFF98A3B0)

    val Blue = Color(0xFF5AA2FF)    // routes, primary actions, the real location
    val Cyan = Color(0xFF2FD8F0)    // street tracing
    val Amber = Color(0xFFFFB627)   // free drawing, warnings, a picked point
    val Purple = Color(0xFFB485FF)  // areas (buildings / streets)
    val Green = Color(0xFF34E3A4)   // trips, "all good", start points
    val Pink = Color(0xFFFF5F8F)    // errors, stop, the simulated location

    /** Dark text/icons on top of a vibrant fill. */
    val OnVibrant = Color(0xFF0B0F14)
    /** Halo / casing around things drawn on the map, so they read on both streets and 3-D buildings. */
    val MapHalo = Color(0xFF0B0F14)
}

/** Smooth squares everywhere (buttons, chips, cards, sheets). */
object AppShapes {
    val Button = RoundedCornerShape(12.dp)
    val Panel = RoundedCornerShape(20.dp)
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(18.dp),
        extraLarge = RoundedCornerShape(22.dp),
    )
}

/** 4-dp spacing grid. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
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
    onPrimary = AppColors.OnVibrant,
    primaryContainer = Color(0xFF16345A),
    onPrimaryContainer = Color(0xFFD7E8FF),
    secondary = AppColors.Green,
    onSecondary = AppColors.OnVibrant,
    secondaryContainer = Color(0xFF113A2D),
    onSecondaryContainer = Color(0xFFC4F8E3),
    tertiary = AppColors.Amber,
    onTertiary = AppColors.OnVibrant,
    tertiaryContainer = Color(0xFF3F2E0A),
    onTertiaryContainer = Color(0xFFFFE3AE),
    error = AppColors.Pink,
    onError = AppColors.OnVibrant,
    errorContainer = Color(0xFF4A1426),
    onErrorContainer = Color(0xFFFFD9E3),
    background = AppColors.Background,
    onBackground = AppColors.Text,
    surface = AppColors.Surface,
    onSurface = AppColors.Text,
    surfaceVariant = AppColors.SurfaceHigh,
    onSurfaceVariant = AppColors.TextDim,
    surfaceTint = Color.Transparent, // tone comes from the containers below, not from a blue tint
    outline = AppColors.Outline,
    outlineVariant = AppColors.OutlineSoft,
    surfaceBright = AppColors.SurfaceHighest,
    surfaceDim = AppColors.Background,
    surfaceContainerLowest = Color(0xFF080B0F),
    surfaceContainerLow = Color(0xFF10151B),
    surfaceContainer = AppColors.Surface,
    surfaceContainerHigh = AppColors.SurfaceHigh,
    surfaceContainerHighest = AppColors.SurfaceHighest,
    inverseSurface = AppColors.Text,
    inverseOnSurface = AppColors.Background,
    inversePrimary = Color(0xFF1F5FB0),
    scrim = Color(0xCC000000),
)

/** Tighter, calmer type: semibold titles, medium labels, slightly open body text. */
private val Type = Typography().let { t ->
    Typography(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
        titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
        titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
        bodyLarge = t.bodyLarge.copy(letterSpacing = 0.2.sp),
        bodyMedium = t.bodyMedium.copy(lineHeight = 20.sp, letterSpacing = 0.2.sp),
        bodySmall = t.bodySmall.copy(lineHeight = 17.sp, letterSpacing = 0.25.sp),
        labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.15.sp),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )
}

/** Always dark with vibrant accents (user preference; no dynamic colour, so each kind of thing keeps its colour). */
@Composable
fun MockRouteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, shapes = AppShapes.shapes, typography = Type, content = content)
}
