package com.liftosaur.wear

import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import com.liftosaur.wear.engine.WatchSetStatus

object LiftosaurColor {
    val purple500 = Color(0xFF8356F6)
    val purple400 = Color(0xFFA48BFA)
    val purple300 = Color(0xFFCCC1F9)

    val darkgray950 = Color(0xFF0C0819)
    val darkgray900 = Color(0xFF252034)
    val darkgray800 = Color(0xFF332D42)
    val darkgray700 = Color(0xFF393248)
    val darkgray600 = Color(0xFF453D58)

    val lightgray300 = Color(0xFFA4B0BC)
    val lightgray500 = Color(0xFF607284)
    val lightgray700 = Color(0xFF3C5063)

    val green500 = Color(0xFF06C383)
    val green400 = Color(0xFF2BDC9B)

    val red500 = Color(0xFFFF543E)
    val red400 = Color(0xFFFF8066)

    val yellow600 = Color(0xFFDD8E02)
    val yellow400 = Color(0xFFFFD820)

    val neutralGray = Color(0xFF8E8E93)

    val background = Color.Black
    val backgroundSubtle = darkgray950
    val backgroundCard = darkgray900
    val backgroundCardSelected = darkgray600
    val backgroundSet = darkgray800

    val textPrimary = Color.White
    val textSecondary = lightgray300
    val textDisabled = lightgray500

    val buttonPrimary = purple500
    val buttonPrimaryLabel = Color.White
    val buttonSecondary = darkgray700

    val borderNeutral = lightgray700
}

fun statusColor(status: WatchSetStatus, isWarmup: Boolean): Color {
    val base = when (status) {
        WatchSetStatus.SUCCESS -> LiftosaurColor.green400
        WatchSetStatus.IN_RANGE -> LiftosaurColor.yellow600
        WatchSetStatus.FAILED -> LiftosaurColor.red400
        WatchSetStatus.NOT_FINISHED -> LiftosaurColor.neutralGray.copy(alpha = 0.5f)
    }
    return if (isWarmup) base.copy(alpha = base.alpha * 0.5f) else base
}

val LiftosaurColorScheme = ColorScheme(
    primary = LiftosaurColor.purple500,
    primaryDim = LiftosaurColor.purple400,
    primaryContainer = LiftosaurColor.darkgray700,
    onPrimary = LiftosaurColor.buttonPrimaryLabel,
    onPrimaryContainer = LiftosaurColor.purple300,
    secondary = LiftosaurColor.lightgray300,
    secondaryDim = LiftosaurColor.lightgray500,
    secondaryContainer = LiftosaurColor.darkgray800,
    onSecondary = LiftosaurColor.background,
    onSecondaryContainer = LiftosaurColor.textPrimary,
    tertiary = LiftosaurColor.green400,
    tertiaryDim = LiftosaurColor.green500,
    tertiaryContainer = LiftosaurColor.darkgray800,
    onTertiary = LiftosaurColor.background,
    onTertiaryContainer = LiftosaurColor.green400,
    surfaceContainerLow = LiftosaurColor.darkgray950,
    surfaceContainer = LiftosaurColor.darkgray900,
    surfaceContainerHigh = LiftosaurColor.darkgray800,
    onSurface = LiftosaurColor.textPrimary,
    onSurfaceVariant = LiftosaurColor.textSecondary,
    outline = LiftosaurColor.borderNeutral,
    outlineVariant = LiftosaurColor.darkgray800,
    background = LiftosaurColor.background,
    onBackground = LiftosaurColor.textPrimary,
    error = LiftosaurColor.red400,
    errorDim = LiftosaurColor.red500,
    errorContainer = LiftosaurColor.darkgray800,
    onError = LiftosaurColor.background,
    onErrorContainer = LiftosaurColor.red400,
)
