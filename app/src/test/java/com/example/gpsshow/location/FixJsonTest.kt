package com.example.gpsshow.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史定位 JSON 序列化的回归测试。
 *
 * 对应真实崩溃（Redmi / Android 16 实测）：
 *   org.json.JSONException: Forbidden numeric value: NaN
 *       at org.json.JSONObject.put(JSONObject.java:230)
 *       at com.example.gpsshow.location.FixCache.save
 *
 * 触发条件是**任何真实三维定位**：altitudeMsl 在 API 34 以下恒为 NaN。
 * 也就是说这个 bug 会让用户在室外好不容易拿到 GPS 锁的瞬间闪退。
 */
class FixJsonTest {

    private fun fix(
        altitude: Double = 1268.4,
        altitudeMsl: Double = Double.NaN,
        accuracy: Float = 3.5f,
        verticalAccuracy: Float = Float.NaN,
    ) = GpsFix(
        latitude = 32.0607,
        longitude = 118.8039,
        altitude = altitude,
        altitudeMsl = altitudeMsl,
        accuracyM = accuracy,
        verticalAccuracyM = verticalAccuracy,
        speedMps = 1.4f,
        bearingDeg = 137.5f,
        bearingAccuracyDeg = Float.NaN,
        elapsedRealtimeNanos = 123L,
        timeMillis = 1_700_000_000_000L,
        provider = "gps",
        isMock = false,
        satsVisible = 24,
        satsUsedInFix = 9,
        satsByConstellation = mapOf("GPS" to 8),
        meanCn0 = 31.5f,
        maxCn0 = 42f,
        satellites = emptyList(),
    )

    @Test
    fun `NaN 的高程字段不会让序列化抛异常`() {
        // 这正是线上崩溃的场景：altitudeMsl = NaN
        val json = FixJson.encode(fix(altitudeMsl = Double.NaN))
        assertNotNull(json)
        assertFalse("JSON 里不该出现裸的 NaN 字面量", json.contains("NaN"))
    }

    @Test
    fun `多个 NaN 字段同时存在也不会崩`() {
        val json = FixJson.encode(
            fix(altitudeMsl = Double.NaN, verticalAccuracy = Float.NaN),
        )
        assertFalse(json.contains("NaN"))
    }

    @Test
    fun `不可用的高程读回来仍然是 NaN 而不是 0`() {
        val decoded = FixJson.decode(FixJson.encode(fix(altitudeMsl = Double.NaN)))
        assertNotNull(decoded)
        assertTrue(
            "缺失的高程必须还原成 NaN，否则界面又会把它当成 0 米海拔",
            decoded!!.altitudeMsl.isNaN(),
        )
        assertTrue(decoded.verticalAccuracyM.isNaN())
    }

    @Test
    fun `有效数值能够完整往返`() {
        val decoded = FixJson.decode(
            FixJson.encode(
                fix(altitude = 448.6, altitudeMsl = 445.2, accuracy = 2.5f, verticalAccuracy = 4.0f),
            ),
        )
        assertNotNull(decoded)
        assertEquals(32.0607, decoded!!.latitude, 1e-9)
        assertEquals(118.8039, decoded.longitude, 1e-9)
        assertEquals(448.6, decoded.altitude, 1e-9)
        assertEquals(445.2, decoded.altitudeMsl, 1e-9)
        assertEquals(2.5f, decoded.accuracyM, 1e-6f)
        assertEquals(4.0f, decoded.verticalAccuracyM, 1e-6f)
        assertEquals("gps", decoded.provider)
        assertEquals(9, decoded.satsUsedInFix)
        assertEquals(24, decoded.satsVisible)
        assertEquals(1_700_000_000_000L, decoded.timeMillis)
    }

    @Test
    fun `损坏的数据返回 null 而不是抛异常`() {
        assertNull(FixJson.decode("这不是 JSON"))
        assertNull(FixJson.decode("{"))

        // 缺少必填的坐标字段
        assertNull(FixJson.decode("""{"alt":1.0}"""))
    }
}
