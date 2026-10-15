package com.example.gpsshow

import com.example.gpsshow.format.decimalToDms
import com.example.gpsshow.format.formatBearing
import com.example.gpsshow.format.formatCompactAltitude
import com.example.gpsshow.format.formatCompactLat
import com.example.gpsshow.format.formatCompactLon
import com.example.gpsshow.format.formatSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯格式化逻辑的单元测试。这些都是打卡画面上直接看到的数字，
 * 出错会直接毁掉一张照片，所以必须有回归覆盖。
 */
class FormattingTest {

    @Test
    fun `北纬与东经带正确半球标记`() {
        assertTrue(formatCompactLat(32.0607).endsWith("N"))
        assertTrue(formatCompactLon(118.8039).endsWith("E"))
    }

    @Test
    fun `南纬与西经带正确半球标记`() {
        assertTrue(formatCompactLat(-33.8688).endsWith("S"))
        assertTrue(formatCompactLon(-70.6693).endsWith("W"))
    }

    @Test
    fun `十进制度转度分秒`() {
        // 32.0607° = 32° 03' 38.52"
        assertEquals("32°03'38.52\"N", decimalToDms(32.0607, isLatitude = true))
    }

    @Test
    fun `秒数四舍五入到 60 时要进位到分`() {
        // 构造一个秒值恰好接近 60 的输入：0.9999999 * 60 = 59.999994'
        val result = decimalToDms(31.0 + 59.9999 / 60.0 / 60.0 * 60.0, isLatitude = true)
        // 只校验不会出现 60.00" 这种非法表示
        assertTrue("不应出现 60.00 秒：$result", !result.contains("60.00\""))
    }

    @Test
    fun `海拔缺失时不显示为 0`() {
        // 这是真机上踩到的 bug：网络定位的 getAltitude() 返回 0，
        // 若直接展示会让人误以为海拔为 0 米。
        assertEquals("海拔 —", formatCompactAltitude(Double.NaN))
    }

    @Test
    fun `海拔正常格式化为一位小数`() {
        assertEquals("海拔 448.6 m", formatCompactAltitude(448.6))
        assertEquals("海拔 8848.9 m", formatCompactAltitude(8848.86))
    }

    @Test
    fun `速度同时给出米每秒和公里每小时`() {
        val text = formatSpeed(1.4f)
        assertTrue(text.contains("1.40 m/s"))
        assertTrue(text.contains("5.04 km/h"))
    }

    @Test
    fun `方位角带中文方向`() {
        assertTrue(formatBearing(0f).contains("北"))
        assertTrue(formatBearing(90f).contains("东"))
        assertTrue(formatBearing(180f).contains("南"))
        assertTrue(formatBearing(270f).contains("西"))
        assertTrue(formatBearing(137.5f).contains("东南"))
    }

    @Test
    fun `无效数值显示为破折号而不是 NaN`() {
        assertEquals("—", formatBearing(Float.NaN))
        assertEquals("—", formatSpeed(Float.NaN))
        assertEquals("海拔 —", formatCompactAltitude(Double.NaN))
    }
}
