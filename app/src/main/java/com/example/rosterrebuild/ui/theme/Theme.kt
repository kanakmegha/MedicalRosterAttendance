package com.example.rosterrebuild.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val ClinicalColorScheme = lightColorScheme(
    primary = IndigoPrimary,
    onPrimary = SurfaceClinical,
    primaryContainer = IndigoContainer,
    onPrimaryContainer = OnIndigoContainer,

    secondary = IndigoLight,
    onSecondary = SurfaceClinical,
    secondaryContainer = LavenderContainer,
    onSecondaryContainer = OnLavenderContainer,

    tertiary = LavenderSurface,
    onTertiary = OnLavenderContainer,
    tertiaryContainer = LavenderContainer,
    onTertiaryContainer = OnLavenderContainer,

    background = BackgroundClinical,
    onBackground = TextPrimary,

    surface = SurfaceClinical,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantClinical,
    onSurfaceVariant = TextSecondary,

    error = ErrorRed,
    onError = SurfaceClinical,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,

    outline = LavenderBorder,
    outlineVariant = Color(0xFFE2E4EC)
)

@Composable
fun MedicalRosterTheme(
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalSpacing provides Spacing()
    ) {
        MaterialTheme(
            colorScheme = ClinicalColorScheme,
            typography = ClinicalTypography,
            shapes = ClinicalShapes,
            content = content
        )
    }
}
