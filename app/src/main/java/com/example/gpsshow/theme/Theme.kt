package com.example.gpsshow.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 固定深色主题。
 *
 * 这个应用是户外打卡用的：深色背景在阳光下对比度更好，纯白大字在反光屏幕上
 * 会糊成一团；同时也避免跟随系统浅色主题导致打卡画面变白底、与相册观感不符。
 */
private val AppColors = darkColorScheme(
    primary = Color(0xFF8CE99A),
    onPrimary = Color(0xFF06280F),
    secondary = Color(0xFF9BB2C4),
    background = Color(0xFF0B0F12),
    onBackground = Color(0xFFE9EEF2),
    surface = Color(0xFF131A20),
    onSurface = Color(0xFFE9EEF2),
    surfaceVariant = Color(0xFF1D262E),
    onSurfaceVariant = Color(0xFFAFBCC7),
    outline = Color(0xFF3A4650),
)

/** 打卡画面专用的高亮色，和详情页的正文区分开。 */
object AccentPalette {
    val Label = Color(0xFFFFD86B)
    val Coordinate = Color(0xFF8CE99A)
    val Altitude = Color(0xFF9BD1FF)
    val Muted = Color(0xFF8C9AA6)
}

@Composable
fun GPSShowTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, content = content)
}
