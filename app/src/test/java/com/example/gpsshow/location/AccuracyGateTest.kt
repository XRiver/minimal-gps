package com.example.gpsshow.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 精度门槛的回归测试。
 *
 * 真机上遇到：provider 报 `gps`、altitude 也有值，但水平精度 ±151.9m、
 * 垂直精度 ±51.5m，界面却显示「三维定位已锁定」。那是刚上电、解还没收敛时的
 * 占位结果，海拔误差可能有几十米——对打卡场景是误导。
 *
 * 修法：三维定位额外要求精度收敛，但**依然不依赖卫星数**（那是秒级波动值，
 * 用它当条件会让界面抖动，这是之前修过的另一个 bug）。
 */
class AccuracyGateTest {

    private fun fix(
        altitude: Double = 35.1,
        accuracy: Float = 5f,
        verticalAccuracy: Float = 8f,
        satsUsedInFix: Int = 9,
        provider: String = "gps",
    ) = GpsFix(
        latitude = 32.0,
        longitude = 118.0,
        altitude = altitude,
        altitudeMsl = Double.NaN,
        accuracyM = accuracy,
        verticalAccuracyM = verticalAccuracy,
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
    fun `真机上的未收敛状态不应被判为三维锁定`() {
        // 这就是实测到的那组数值
        val f = fix(altitude = 35.13, accuracy = 151.9f, verticalAccuracy = 51.5f)
        assertTrue("确实有高程", f.hasAltitude)
        assertFalse("但精度没收敛，不能声称三维锁定", f.has3dFix)
    }

    @Test
    fun `精度良好时算三维定位`() {
        val f = fix(altitude = 35.1, accuracy = 4.0f, verticalAccuracy = 6.0f)
        assertTrue(f.has3dFix)
    }

    @Test
    fun `水平精度超限即不算三维定位`() {
        val f = fix(accuracy = GpsFix.HORIZONTAL_ACCURACY_LIMIT_M + 1f, verticalAccuracy = 1f)
        assertFalse(f.has3dFix)
    }

    @Test
    fun `垂直精度超限即不算三维定位`() {
        val f = fix(accuracy = 1f, verticalAccuracy = GpsFix.VERTICAL_ACCURACY_LIMIT_M + 1f)
        assertFalse(f.has3dFix)
    }

    @Test
    fun `垂直精度未知时不应因此否决`() {
        // 很多设备/网络解不给垂直精度，不该因为"没有这个字段"就判失败
        val f = fix(accuracy = 5f, verticalAccuracy = Float.NaN)
        assertTrue(f.accuracyUsable)
        assertTrue(f.has3dFix)
    }

    @Test
    fun `精度未知为零时视为不可用`() {
        // accuracy 为 0 表示"没有有效精度信息"，不能当作"极其精确"
        val f = fix(accuracy = 0f, verticalAccuracy = 0f)
        assertFalse(f.accuracyUsable)
        assertFalse(f.has3dFix)
    }

    @Test
    fun `精度判定不随卫星数变化`() {
        val noSats = fix(accuracy = 5f, verticalAccuracy = 5f, satsUsedInFix = 0)
        val manySats = fix(accuracy = 5f, verticalAccuracy = 5f, satsUsedInFix = 20)
        assertEquals(
            "卫星数是秒级波动值，不能让状态跟着跳",
            manySats.has3dFix,
            noSats.has3dFix,
        )
    }

    @Test
    fun `未收敛但有高程的质量权重高于纯平面解`() {
        val converging = fix(accuracy = 151.9f, verticalAccuracy = 51.5f)
        val planar = fix(altitude = Double.NaN, accuracy = 151.9f, verticalAccuracy = 51.5f)
        assertTrue(
            "同在收敛过程中，带高程的仍比纯平面更有价值",
            converging.quality > planar.quality,
        )
    }

    @Test
    fun `精度达标的三维解权重最高`() {
        val good = fix(accuracy = 3f, verticalAccuracy = 4f)
        val converging = fix(accuracy = 151.9f, verticalAccuracy = 51.5f)
        assertTrue(good.quality > converging.quality)
    }
}
