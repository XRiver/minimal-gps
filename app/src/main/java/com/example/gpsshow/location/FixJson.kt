package com.example.gpsshow.location

import org.json.JSONObject

/**
 * 历史定位的 JSON 序列化。
 *
 * 刻意做成不依赖 Android 的纯函数，这样才能被普通 JVM 单元测试覆盖——
 * 这里踩过一个真实的崩溃：
 *
 *   org.json.JSONException: Forbidden numeric value: NaN
 *       at org.json.JSONObject.put
 *       at FixCache.save
 *
 * org.json **不接受** NaN / Infinity。而 altitudeMsl 在 API 34 以下永远是 NaN，
 * verticalAccuracyM 在多数设备上也是 NaN，结果是**任何真实三维定位一写入缓存就闪退**。
 * Local unit test 里的 org.json 是抛异常的 stub，只能靠抽取纯逻辑来测。
 */
object FixJson {

    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_ALT = "alt"
    private const val KEY_ALT_MSL = "altMsl"
    private const val KEY_ACCURACY = "accuracy"
    private const val KEY_V_ACCURACY = "vAccuracy"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_SATS_USED = "satsUsed"
    private const val KEY_SATS_VISIBLE = "satsVisible"
    private const val KEY_TIME = "timeMillis"

    /** 序列化为 JSON 字符串。NaN/Infinity 一律转成 JSON null。 */
    fun encode(fix: GpsFix): String = JSONObject().apply {
        put(KEY_LAT, fix.latitude)
        put(KEY_LON, fix.longitude)
        put(KEY_ALT, fix.altitude)
        put(KEY_ALT_MSL, fix.altitudeMsl.toJsonSafe())
        put(KEY_ACCURACY, fix.accuracyM.toDouble().toJsonSafe())
        put(KEY_V_ACCURACY, fix.verticalAccuracyM.toDouble().toJsonSafe())
        put(KEY_PROVIDER, fix.provider)
        put(KEY_SATS_USED, fix.satsUsedInFix)
        put(KEY_SATS_VISIBLE, fix.satsVisible)
        put(KEY_TIME, fix.timeMillis)
    }.toString()

    /** 反序列化；数据损坏或字段缺失时返回 null。 */
    fun decode(raw: String): CachedFix? = try {
        val o = JSONObject(raw)
        CachedFix(
            latitude = o.getDouble(KEY_LAT),
            longitude = o.getDouble(KEY_LON),
            altitude = o.getDouble(KEY_ALT),
            altitudeMsl = o.optDoubleOrNaN(KEY_ALT_MSL),
            accuracyM = o.optDoubleOrNaN(KEY_ACCURACY).toFloat(),
            verticalAccuracyM = o.optDoubleOrNaN(KEY_V_ACCURACY).toFloat(),
            provider = o.optString(KEY_PROVIDER, "gps"),
            satsUsedInFix = o.optInt(KEY_SATS_USED, 0),
            satsVisible = o.optInt(KEY_SATS_VISIBLE, 0),
            timeMillis = o.optLong(KEY_TIME, 0L),
            satellites = emptyList(),
        )
    } catch (_: Exception) {
        null
    }
}

/** org.json 会拒绝 NaN/Infinity，所以先换成 JSON null。 */
internal fun Double.toJsonSafe(): Any = if (isFinite()) this else JSONObject.NULL

/** 读回来时把 null / 非有限值还原成 NaN，保持"不可用"这个语义。 */
internal fun JSONObject.optDoubleOrNaN(key: String): Double {
    if (!has(key) || isNull(key)) return Double.NaN
    val v = optDouble(key, Double.NaN)
    return if (v.isFinite()) v else Double.NaN
}
