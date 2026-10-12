package io.github.du10777.tropa.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Tropa brand colours: golden yellow of the icon plus status colours. */
object TropaColors {
    val Brand = Color(0xFFF2A900)
    val BrandDark = Color(0xFF8A6100)
    val OnBrand = Color(0xFFFFFFFF)
    val Connected = Color(0xFF2E7D32)
    val ConnectedContainer = Color(0xFFE3F1E4)
    val Checking = Color(0xFFF9A825)
    val CheckingContainer = Color(0xFFFFF6DC)
    val Problem = Color(0xFFC62828)
    val ProblemContainer = Color(0xFFFDE8E8)
    val Idle = Color(0xFF78909C)
    val IdleContainer = Color(0xFFECEFF1)
    val Info = Color(0xFF0D47A1)
    val InfoContainer = Color(0xFFE3F2FD)
    val PermissionGranted = Color(0xFF2E7D32)
    val PermissionDenied = Color(0xFFC62828)
}

private val LightScheme = lightColorScheme(
    primary = TropaColors.BrandDark,
    onPrimary = Color.White,
    primaryContainer = TropaColors.Brand,
    onPrimaryContainer = Color(0xFF2A1E00),
    secondary = TropaColors.Brand,
    onSecondary = Color.White,
    background = Color(0xFFF6F6F2),
    onBackground = Color(0xFF1B1F1D),
    surface = Color.White,
    onSurface = Color(0xFF1B1F1D),
    surfaceVariant = Color(0xFFF0F0EA),
    onSurfaceVariant = Color(0xFF5F6B66),
    outline = Color(0xFF9AA39E),
)

private val DarkScheme = darkColorScheme(
    primary = TropaColors.Brand,
    onPrimary = Color(0xFF2A1E00),
    primaryContainer = Color(0xFF6B4B00),
    onPrimaryContainer = Color(0xFFFFDE9C),
    secondary = TropaColors.Brand,
    onSecondary = Color(0xFF2A1E00),
    background = Color(0xFF121412),
    onBackground = Color(0xFFE3E3DD),
    surface = Color(0xFF1E201E),
    onSurface = Color(0xFFE3E3DD),
    surfaceVariant = Color(0xFF2A2D2A),
    onSurfaceVariant = Color(0xFFB9C1BC),
    outline = Color(0xFF7D857F),
)

@Composable
fun TropaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
