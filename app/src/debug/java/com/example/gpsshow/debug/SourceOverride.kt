package com.example.gpsshow.debug

import android.content.Context
import com.example.gpsshow.location.GpsFix
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 仅 debug 构建存在的外部数据源覆盖（测试后门）。
 *
 * 背景：本机（Redmi / HyperOS）无法用 `cmd location` 注入带高程的模拟定位
 * （MIUI 拦截 mock location，且 Android 16 的 set-test-provider-location 不支持
 * --altitude），导致海拔渲染、择优逻辑、历史值缓存都无法自测。
 *
 * 踩过的坑：最初用 `adb shell setprop` + `System.getProperty()` 读取，完全不生效——
 * setprop 设置的是 **Android 系统属性**，而 System.getProperty 读的是 **Java 系统属性**，
 * 两者互不相通。所以改成读文件。
 *
 * 用法（应用需为 debug 包）：
 *   adb shell "run-as com.example.gpsshow sh -c 'cat > files/source_override.json'" < x.json
 *
 * 单源（验证海拔渲染）：
 *   {"altitude":1268.4,"latitude":32.0607,"longitude":118.8039,"allowCacheWrite":true}
 *
 * 多源（验证择优：GPS 带高程 + 网络无高程同时存在，界面必须稳定选 GPS）：
 *   {"sources":[{"provider":"gps","altitude":448.6},
 *               {"provider":"network","altitude":null,"timeMillis":9999999999999}]}
 *
 * 删除文件即关闭：
 *   adb shell run-as com.example.gpsshow rm -f files/source_override.json
 */
object SourceOverride {

    private const val FILE_NAME = "source_override.json"

    fun parse(context: Context): FixOverride? {
        val json = try {
            val f = File(context.filesDir, FILE_NAME)
            if (!f.exists()) return null
            f.readText()
        } catch (_: Exception) {
            return null
        }

        return try {
            val root = JSONObject(json)
            val allowCacheWrite = root.optBoolean("allowCacheWrite", false)

            // 支持两种写法：顶层单条，或 sources 数组
            val entries: List<JSONObject> = if (root.has("sources")) {
                val arr: JSONArray = root.getJSONArray("sources")
                (0 until arr.length()).map { arr.getJSONObject(it) }
            } else {
                listOf(root)
            }

            val fixes = LinkedHashMap<String, GpsFix>()
            var index = 0
            for (o in entries) {
                index++
                // altitude 必填（可以是 null，表示"无高程"）
                if (!o.has("altitude")) continue
                val provider = o.optString("provider", "gps").ifBlank { "gps" }
                val altitude = if (o.isNull("altitude")) Double.NaN else o.getDouble("altitude")
                fixes[provider.ifBlank { "src$index" }] = GpsFix(
                    latitude = o.optDouble("latitude", 32.0607),
                    longitude = o.optDouble("longitude", 118.8039),
                    altitude = altitude,
                    altitudeMsl = Double.NaN,
                    accuracyM = o.optDouble("accuracy", 3.5).toFloat(),
                    verticalAccuracyM = 5.0f,
                    speedMps = 1.4f,
                    bearingDeg = 137.5f,
                    bearingAccuracyDeg = 2.0f,
                    elapsedRealtimeNanos = System.nanoTime(),
                    // 网络解默认给一个"更新"的时间，好复现"更新的差解顶掉好解"这个场景
                    timeMillis = o.optLong(
                        "timeMillis",
                        System.currentTimeMillis() + index,
                    ),
                    provider = provider,
                    isMock = false,
                    satsVisible = o.optInt("satsVisible", 24),
                    satsUsedInFix = o.optInt("satsUsedInFix", if (altitude.isNaN()) 0 else 9),
                    satsByConstellation = mapOf("GPS" to 8, "北斗" to 10, "Galileo" to 6),
                    meanCn0 = 31.5f,
                    maxCn0 = 42.0f,
                    satellites = emptyList(),
                )
            }

            if (fixes.isEmpty()) null else FixOverride(fixes, allowCacheWrite)
        } catch (_: Exception) {
            null
        }
    }
}
