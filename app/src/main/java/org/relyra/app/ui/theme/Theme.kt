package org.relyra.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF2563EB), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF), onPrimaryContainer = Color(0xFF0B2A6B),
    secondary = Color(0xFF0F766E), onSecondary = Color.White,
    background = Color(0xFFF7F9FC), surface = Color(0xFFF7F9FC),
    onBackground = Color(0xFF101828), onSurface = Color(0xFF101828),
    surfaceVariant = Color(0xFFE6EBF3), onSurfaceVariant = Color(0xFF475467),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F4FA),
    surfaceContainer = Color(0xFFEBEFF7), surfaceContainerHigh = Color(0xFFE5EAF3),
    surfaceContainerHighest = Color(0xFFDFE5F0),
    outline = Color(0xFF98A2B3), outlineVariant = Color(0xFFD0D5DD),
    error = Color(0xFFB42318),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8DB1FF), onPrimary = Color(0xFF002A78),
    primaryContainer = Color(0xFF1B3E91), onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Color(0xFF5EEAD4), onSecondary = Color(0xFF003731),
    background = Color(0xFF0B1220), surface = Color(0xFF0B1220),
    onBackground = Color(0xFFE6EAF2), onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF1B2538), onSurfaceVariant = Color(0xFFAAB4C5),
    surfaceContainerLowest = Color(0xFF070C16), surfaceContainerLow = Color(0xFF111A2B),
    surfaceContainer = Color(0xFF152033), surfaceContainerHigh = Color(0xFF1B2740),
    surfaceContainerHighest = Color(0xFF22304D),
    outline = Color(0xFF667085), outlineVariant = Color(0xFF2B3750),
    error = Color(0xFFFDA29B),
)

@Composable
fun RelyraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Typography(),
        content = content,
    )
}
