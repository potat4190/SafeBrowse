package com.safebrowse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Night-vigil indigo with gilt. Matches the Windows build so the two feel
// like one product. Dark by default because this gets opened late.
val Night  = Color(0xFF0B1022)
val Depth  = Color(0xFF151C36)
val LineC  = Color(0xFF232B4A)
val Pale   = Color(0xFFE6EAF6)
val Muted  = Color(0xFF8B95B5)
val Gilt   = Color(0xFFC8A24A)
val Garnet = Color(0xFF8C4A52)

private val SafeBrowseColors = darkColorScheme(
    primary = Gilt,
    onPrimary = Night,
    primaryContainer = Depth,
    onPrimaryContainer = Pale,
    secondary = Pale,
    onSecondary = Night,
    background = Night,
    onBackground = Pale,
    surface = Night,
    onSurface = Pale,
    surfaceVariant = Depth,
    onSurfaceVariant = Muted,
    outline = LineC,
    error = Garnet,
    onError = Pale
)

@Composable
fun SafeBrowseTheme(content: @Composable () -> Unit) {
    // Deliberately not using dynamic color: the palette carries the identity.
    MaterialTheme(colorScheme = SafeBrowseColors, typography = Typography(), content = content)
}
