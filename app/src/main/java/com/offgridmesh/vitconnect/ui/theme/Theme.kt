package com.offgridmesh.vitconnect.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val MeshPurple = Color(0xFF6E56CF)
val MeshPurpleLight = Color(0xFF8B76E8)
val MeshTeal = Color(0xFF4CD9A8)
val MeshAmber = Color(0xFFE8A33D)
val MeshRed = Color(0xFFE85D5D)
val MeshBackground = Color(0xFF0F0B1E)
val MeshSurface = Color(0xFF1A1430)
val MeshSurfaceVariant = Color(0xFF2A1F4D)

private val DarkColors = darkColorScheme(
    primary = MeshPurpleLight,
    onPrimary = Color.White,
    secondary = MeshTeal,
    onSecondary = Color.Black,
    tertiary = MeshAmber,
    error = MeshRed,
    background = MeshBackground,
    onBackground = Color(0xFFEDE9F7),
    surface = MeshSurface,
    onSurface = Color(0xFFEDE9F7),
    surfaceVariant = MeshSurfaceVariant,
    onSurfaceVariant = Color(0xFFC9C0E8),
)

private val LightColors = lightColorScheme(
    primary = MeshPurple,
    secondary = MeshTeal,
    tertiary = MeshAmber,
    error = MeshRed,
)

@Composable
fun OffGridMeshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = MeshTypography,
        content = content,
    )
}
