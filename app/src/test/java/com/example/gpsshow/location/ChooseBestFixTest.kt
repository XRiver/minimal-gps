package com.example.gpsshow.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多 provider 择优逻辑的回归测试。
 *
 * 对应真机现象：界面在「已定位 / 无定位」之间以半秒级来回跳。
 * 根因是 GPS / FUSED / NETWORK 三个 provider 共用一个监听器且互相覆盖，
 * 高质量 GPS 解（带高程）被网络解（无高程）顶掉，下一轮又反过来。
 *
 * 修法：按 provider 分别锁存，展示时用 [chooseBestFix] 择优。
 */
class ChooseBestFixTest {

    private fun fix(
        provider: String,
        altitude: Double,
        satsUsedInFix: Int = 0,
        timeMillis: Long = 1_000L,
        latitude: Double = 32.0,
    ) = GpsFix(
        latitude = latitude,
        longitude = 118.0,
        altitude = altitude,
        altitudeMsl = Double.NaN,
        accuracyM = 5f,
        verticalAccuracyM = Float.NaN,
        speedMps = Float.NaN,
        bearingDeg = Float.NaN,
        bearingAccuracyDeg = Float.NaN,
        elapsedRealtimeNanos = 0L,
        timeMillis = timeMillis,
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
    fun `网络定位不会顶掉带高程的 GPS 定位`() {
        // 这正是界面跳动的原因：网络解比 GPS 解"更新"，但质量更差
        val gpsWithAltitude = fix("gps", altitude = 448.6, timeMillis = 1_000L)
        val netNoAltitude = fix("network", altitude = Double.NaN, timeMillis = 9_999L)

        val best = chooseBestFix(listOf(gpsWithAltitude, netNoAltitude))
        assertSame("必须保留带高程的 GPS 解", gpsWithAltitude, best)
    }

    @Test
    fun `无论网络解何时到达 择优结果都保持不变`() {
        val gps = fix("gps", altitude = 448.6, timeMillis = 1_000L)
        val net = fix("network", altitude = Double.NaN, timeMillis = 2_000L)

        // 模拟两种回调顺序，结果必须一致 —— 否则界面就会来回跳
        val a = chooseBestFix(listOf(gps, net))
        val b = chooseBestFix(listOf(net, gps))
        assertEquals(a?.altitude, b?.altitude)
        assertSame(a, b)
    }

    @Test
    fun `同质量时取更新的那条`() {
        val older = fix("gps", altitude = 100.0, timeMillis = 1_000L, latitude = 1.0)
        val newer = fix("gps", altitude = 200.0, timeMillis = 2_000L, latitude = 2.0)
        val best = chooseBestFix(listOf(older, newer))
        assertSame(newer, best)
    }

    @Test
    fun `质量权重排序正确`() {
        val gnss3d = fix("gps", altitude = 100.0, timeMillis = 1L)
        val gnssPlanar = fix("gps", altitude = Double.NaN, timeMillis = 1L)
        val network = fix("network", altitude = Double.NaN, timeMillis = 1L)

        assertTrue(gnss3d.quality > gnssPlanar.quality)
        assertTrue(gnssPlanar.quality > network.quality)
    }

    @Test
    fun `空集合返回 null`() {
        assertEquals(null, chooseBestFix(emptyList()))
    }

    // ---- 以下是"状态文字不抖"的关键：判定条件不能依赖卫星数 ----

    @Test
    fun `fromGnss 只看 provider 不看卫星数`() {
        // 卫星数是秒级波动的瞬时值。如果 fromGnss 依赖它，
        // 界面文案会随卫星数变化来回切换。
        val gpsZeroSats = fix("gps", altitude = 448.6, satsUsedInFix = 0)
        val gpsManySats = fix("gps", altitude = 448.6, satsUsedInFix = 12)
        assertTrue(gpsZeroSats.fromGnss)
        assertTrue(gpsManySats.fromGnss)
    }

    @Test
    fun `has3dFix 不随卫星数变化`() {
        val zero = fix("gps", altitude = 448.6, satsUsedInFix = 0)
        val many = fix("gps", altitude = 448.6, satsUsedInFix = 12)
        assertEquals(zero.has3dFix, many.has3dFix)
        assertTrue(zero.has3dFix)
    }

    @Test
    fun `网络定位不算 GNSS`() {
        val net = fix("network", altitude = Double.NaN, satsUsedInFix = 0)
        assertFalse(net.fromGnss)
        assertFalse("网络解即便被塞了卫星数也不该算 GNSS", net.has3dFix)
    }

    @Test
    fun `融合定位不算 GNSS`() {
        val fused = fix("fused", altitude = Double.NaN)
        assertFalse(fused.fromGnss)
    }

    @Test
    fun `择优结果本身是有高程的`() {
        val best = chooseBestFix(
            listOf(
                fix("network", altitude = Double.NaN, timeMillis = 3_000L),
                fix("gps", altitude = 448.6, timeMillis = 1_000L),
            ),
        )
        assertNotNull(best)
        assertTrue(best!!.hasAltitude)
    }
}
