package com.yucl.etfshare.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** A股惯例：份额增加红色、减少绿色（与欧美相反）。 */
object Palette {
    val Up = Color(0xFFD93026)
    val Down = Color(0xFF0A8F4A)
    val Flat = Color(0xFF8A94A3)
    val UpDark = Color(0xFFFF6B5E)
    val DownDark = Color(0xFF3FBF7F)

    @Composable
    fun delta(value: Double): Color {
        val dark = isSystemInDarkTheme()
        return when {
            value > 0 -> if (dark) UpDark else Up
            value < 0 -> if (dark) DownDark else Down
            else -> Flat
        }
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F4E79),
    onPrimary = Color.White,
    secondary = Color(0xFF2F6EA5),
    surface = Color.White,
    background = Color(0xFFF5F6F8),
    surfaceVariant = Color(0xFFF0F2F5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FB2E5),
    onPrimary = Color(0xFF0B2740),
    secondary = Color(0xFF9CC7EE),
    surface = Color(0xFF12151A),
    background = Color(0xFF0D1014),
    surfaceVariant = Color(0xFF1B2027),
)

@Composable
fun EtfShareTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
