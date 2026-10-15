package com.example.gpsshow

import com.example.gpsshow.format.formatCompactAltitude
import com.example.gpsshow.location.GpsFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
/**
 * 高程可用性判定的回归测试。
 *
 * 真机上抓到的 bug：provider 还没提供高程时，Android 的 Location.hasAltitude()
 * 仍然返回 true 而 altitude 是 0.0，界面于是显示
 * 「GPS 三维定位已锁定 + 海拔 0.00 m + 0 颗卫星」——自相矛盾。
 */
class AltitudeAvailabilityTest {

    private fun fix(altitude: Double, satsUsedInFix: Int, provider: String = "gps") = GpsFix(
        latitude = 32.0,
        longitude = 118.0,
        altitude = altitude,
        altitudeMsl = Double.NaN,
        accuracyM = 5f,
        verticalAccuracyM = Float.NaN,
        speedMps = Float.NaN,
        bearingDeg = Float.NaN,
        bearingAccuracyDeg = Float.NaN,
        elapsedRealtimeNanos = 0L,
        timeMillis = 0L,
        provider = provider,
        isMock = false,
        satsVisible = 0,
        satsUsedInFix = satsUsedInFix,
        satsByConstellation = emptyMap(),
        meanCn0 = 0f,
        maxCn0 = 0f,
        satellites = emptyList(),
    )

    @Test
    fun `海拔为 0 且无卫星解算时不算有高程`() {
        val f = fix(altitude = 0.0, satsUsedInFix = 0)
        assertFalse("0.0 是 provider 未提供高程的占位值，不能当成真实海拔", f.hasAltitude)
        assertFalse(f.has3dFix)
    }

    @Test
    fun `海拔缺失为 NaN 时不算有高程`() {
        val f = fix(altitude = Double.NaN, satsUsedInFix = 3)
        assertFalse(f.hasAltitude)
        assertFalse(f.has3dFix)
    }

    @Test
    fun `有真实海拔且有卫星解算才算三维定位`() {
        val f = fix(altitude = 448.6, satsUsedInFix = 9)
        assertTrue(f.hasAltitude)
        assertTrue(f.has3dFix)
    }

    @Test
    fun `有真实海拔即算三维定位 卫星数不影响判定`() {
        // 曾经的实现要求 satsUsedInFix > 0，结果卫星数每次波动都会让界面文字
        // 在「已锁定」和「仅平面」之间来回跳。三维定位的实质是"有高程"，
        // 卫星数是秒级瞬时值，只能作为展示信息，不能当状态条件。
        val noSats = fix(altitude = 448.6, satsUsedInFix = 0)
        val withSats = fix(altitude = 448.6, satsUsedInFix = 9)
        assertTrue(noSats.hasAltitude)
        assertTrue(withSats.hasAltitude)
        assertEquals(
            "卫星数变化不应改变三维定位判定",
            withSats.has3dFix,
            noSats.has3dFix,
        )
    }

    @Test
    fun `网络定位判定为非 GNSS`() {
        val f = fix(altitude = Double.NaN, satsUsedInFix = 0, provider = "network")
        assertFalse(f.fromGnss)
    }

    @Test
    fun `海拔 0 在格式化层也不会被展示成 0 米`() {
        // 格式化层对 NaN 输出破折号；结合 hasAltitude 判定，界面不会出现 0.00 m
        assertEquals("海拔 —", formatCompactAltitude(Double.NaN))
    }
}
