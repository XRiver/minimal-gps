package com.example.gpsshow

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.gpsshow.location.GpsFix
import com.example.gpsshow.location.GpsState
import com.example.gpsshow.location.SatInfo
import com.example.gpsshow.theme.GPSShowTheme
import com.example.gpsshow.ui.GpsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 打卡画面与详情页的渲染回归测试。
 *
 * 两个关键点：
 *  1) GpsScreen 用 LocalConfiguration.orientation 判方向，所以测试必须显式覆盖它，
 *     光把容器改成横向尺寸是没用的。
 *  2) 海拔列宽度只占屏宽一小部分，珠峰级高度（8848.86）是最坏输入，
 *     必须确认不会因为测量约束冲突而崩溃。
 */
@RunWith(AndroidJUnit4::class)
class CheckInLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun fix(
        lat: Double = 32.0607,
        lon: Double = 118.8039,
        altitude: Double = 448.6,
    ) = GpsFix(
        latitude = lat,
        longitude = lon,
        altitude = altitude,
        altitudeMsl = Double.NaN,
        accuracyM = 3.5f,
        verticalAccuracyM = 6.0f,
        speedMps = 1.4f,
        bearingDeg = 137.5f,
        bearingAccuracyDeg = 2.0f,
        elapsedRealtimeNanos = System.nanoTime(),
        timeMillis = System.currentTimeMillis(),
        provider = "gps",
        isMock = false,
        satsVisible = 24,
        satsUsedInFix = 9,
        satsByConstellation = mapOf("GPS" to 8, "北斗" to 10, "Galileo" to 6),
        meanCn0 = 31.5f,
        maxCn0 = 42.0f,
        satellites = listOf(
            SatInfo(3, "GPS", 42.0f, 61.0f, 138.0f, true),
            SatInfo(12, "北斗", 38.5f, 55.0f, 210.0f, true),
        ),
    )

    /** landscape=true 时按横屏真实像素比例渲染（2510x1156 @480dpi）。 */
    private fun render(gpsFix: GpsFix?, landscape: Boolean) {
        composeRule.setContent {
            val base = LocalConfiguration.current
            val fake = Configuration(base).apply {
                orientation = if (landscape) {
                    Configuration.ORIENTATION_LANDSCAPE
                } else {
                    Configuration.ORIENTATION_PORTRAIT
                }
                screenWidthDp = if (landscape) 837 else 411
                screenHeightDp = if (landscape) 385 else 837
            }
            CompositionLocalProvider(LocalConfiguration provides fake) {
                GPSShowTheme {
                    Box(
                        modifier = Modifier.size(
                            width = if (landscape) 837.dp else 411.dp,
                            height = if (landscape) 385.dp else 837.dp,
                        ),
                    ) {
                        GpsScreen(
                            state = GpsState(
                                fix = gpsFix,
                                error = null,
                                activeProviders = listOf("gps"),
                                gpsPowerHint = "高耗电",
                                hasGpsHardware = true,
                            ),
                            placeLabel = "紫金山头陀岭",
                            onPlaceLabelChange = {},
                        )
                    }
                }
            }
        }
    }

    @Test
    fun 珠峰级海拔不会让横屏打卡画面崩溃() {
        // 8848.86 是能出现的最宽海拔值，用来压测打卡画面排版
        render(fix(altitude = 8848.86), landscape = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("紫金山头陀岭").assertIsDisplayed()
        composeRule.onNodeWithText("8848.9", substring = true).assertIsDisplayed()
    }

    @Test
    fun 无高程时横屏显示无高程提示而不是零() {
        render(fix(altitude = Double.NaN), landscape = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("无高程", substring = true).assertIsDisplayed()
    }

    @Test
    fun 竖屏能够渲染坐标与海拔详情() {
        render(fix(), landscape = false)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("定位信息").assertIsDisplayed()
        composeRule.onNodeWithText("32.0607000").assertIsDisplayed()
        composeRule.onNodeWithText("448.60 m").assertIsDisplayed()
    }

    @Test
    fun 尚未定位时给出等待提示() {
        render(null, landscape = false)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("正在搜索卫星…").assertIsDisplayed()
    }

    @Test
    fun 横屏未定位时不会显示伪造的数字() {
        render(null, landscape = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("点这里填写地名").assertIsDisplayed()
    }
}
