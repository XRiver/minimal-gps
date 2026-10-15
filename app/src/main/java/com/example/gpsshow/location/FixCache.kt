package com.example.gpsshow.location

import android.content.Context
import android.content.SharedPreferences

/**
 * 「上一次有效定位」缓存。
 *
 * 动机：室内拿不到 GNSS 锁时，系统只会给出网络定位的平面坐标（无高程）。
 * 但用户很可能在室外已经成功定位过一次，那个含高程的结果不该被丢掉——
 * 否则每次冷启动都要重新跑出去搜星。
 *
 * 设计约束：
 *  - 只缓存「有效」的结果：必须有真实高程，且必须来自卫星解算。
 *    网络定位的结果一律不缓存，避免把 ±30m 的粗位置当成历史成果。
 *  - 读出来时必须带时间戳，界面负责标注成历史值，绝不能伪装成实时数据。
 *  - 序列化交给 [FixJson]（纯函数、可单测），本类只负责 SharedPreferences 读写。
 */
class FixCache(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 缓存一条定位。不符合"有效"条件时直接忽略，避免污染缓存。 */
    fun save(fix: GpsFix) {
        if (!fix.has3dFix) return
        // 用 commit 而非 apply：这条记录是"用户好不容易在室外拿到的一次定位"，
        // 值得同步落盘，避免进程随后被杀而丢失。
        prefs.edit().putString(KEY_FIX, FixJson.encode(fix)).commit()
    }

    /** 读取缓存；不存在或已损坏时返回 null。 */
    fun load(): CachedFix? {
        val raw = prefs.getString(KEY_FIX, null) ?: return null
        val decoded = FixJson.decode(raw)
        if (decoded == null) {
            // 数据损坏就清掉，避免每次启动都解析失败
            prefs.edit().remove(KEY_FIX).commit()
        }
        return decoded
    }

    fun clear() {
        prefs.edit().remove(KEY_FIX).commit()
    }

    companion object {
        private const val PREFS = "gps_show_cache"
        private const val KEY_FIX = "last_valid_fix"
    }
}

/** 缓存的历史定位。字段与 GpsFix 对齐，便于在界面上复用同一套格式化函数。 */
data class CachedFix(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val altitudeMsl: Double,
    val accuracyM: Float,
    val verticalAccuracyM: Float,
    val provider: String,
    val satsUsedInFix: Int,
    val satsVisible: Int,
    val timeMillis: Long,
    val satellites: List<SatInfo>,
)
