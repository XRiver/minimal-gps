package com.example.gpsshow.format

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** 十进制度 → 度分秒字符串，例如 31°13'49.73" */
fun decimalToDms(value: Double, isLatitude: Boolean): String {
    val hemi = when {
        isLatitude && value >= 0 -> "N"
        isLatitude -> "S"
        value >= 0 -> "E"
        else -> "W"
    }
    val abs = abs(value)
    var deg = abs.toInt()
    val minutesFull = (abs - deg) * 60.0
    var min = minutesFull.toInt()
    var sec = (minutesFull - min) * 60.0

    // 处理进位：59.995" 这类值四舍五入后会变成 60.000"
    if (sec >= 59.995) {
        sec = 0.0
        min += 1
    }
    if (min >= 60) {
        min = 0
        deg += 1
    }
    return String.format(Locale.US, "%d°%02d'%05.2f\"%s", deg, min, sec, hemi)
}

/** 十进制度，保留 7 位（约 1cm 分辨率，足以反映 GPS 自身抖动） */
fun formatDecimal(value: Double): String = String.format(Locale.US, "%.7f", value)

fun formatAltitude(meters: Double): String =
    if (meters.isNaN()) "—" else String.format(Locale.US, "%.2f m", meters)

fun formatAccuracy(meters: Float): String =
    if (meters.isNaN() || meters <= 0f) "—" else String.format(Locale.US, "±%.1f m", meters)

fun formatSpeed(mps: Float): String {
    if (mps.isNaN()) return "—"
    val kmh = mps * 3.6f
    return String.format(Locale.US, "%.2f m/s  (%.2f km/h)", mps, kmh)
}

fun formatBearing(deg: Float): String {
    if (deg.isNaN()) return "—"
    val dirs = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
    val idx = (((deg % 360f) + 360f) % 360f / 45f).toInt() % 8
    return String.format(Locale.US, "%.1f°  %s", deg, dirs[idx])
}

fun formatCn0(value: Float): String =
    if (value <= 0f) "—" else String.format(Locale.US, "%.1f dB-Hz", value)

fun formatPercent(value: Float): String = String.format(Locale.US, "%.0f%%", value * 100f)

fun formatTime(millis: Long): String =
    if (millis <= 0L) "—" else SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(millis))

fun formatDateTime(millis: Long): String =
    if (millis <= 0L) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

/** 位置更新距今多久；用 elapsedRealtime 差值才不受系统时间改动影响 */
fun formatAge(nowNanos: Long, fixNanos: Long): String {
    if (fixNanos <= 0L) return "—"
    return formatAgeMillis((nowNanos - fixNanos) / 1_000_000)
}

/** 按墙上时钟计算「多久以前」，用于缓存的历史定位。 */
fun formatAgeSinceWallClock(nowMillis: Long, thenMillis: Long): String {
    if (thenMillis <= 0L) return "—"
    return formatAgeMillis(nowMillis - thenMillis)
}

private fun formatAgeMillis(deltaMillis: Long): String {
    val seconds = (deltaMillis / 1000.0).coerceAtLeast(0.0)
    return when {
        seconds < 1.0 -> "刚刚"
        seconds < 60.0 -> String.format(Locale.US, "%.1f 秒前", seconds)
        seconds < 3600.0 -> String.format(Locale.US, "%.0f 分钟前", seconds / 60.0)
        seconds < 86400.0 -> String.format(Locale.US, "%.1f 小时前", seconds / 3600.0)
        else -> String.format(Locale.US, "%.0f 天前", seconds / 86400.0)
    }
}

/** 给打卡画面用的短格式：31.230416°N */
fun formatCompactLat(lat: Double): String =
    String.format(Locale.US, "%.6f°%s", abs(lat), if (lat >= 0) "N" else "S")

fun formatCompactLon(lon: Double): String =
    String.format(Locale.US, "%.6f°%s", abs(lon), if (lon >= 0) "E" else "W")

fun formatCompactAltitude(meters: Double): String =
    if (meters.isNaN()) "海拔 —" else String.format(Locale.US, "海拔 %.1f m", meters)
