package com.example.gpsshow.settings

import android.content.Context

/** 打卡地名等用户输入的持久化。存 SharedPreferences，卸载即清除。 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("gps_show", Context.MODE_PRIVATE)

    var placeLabel: String
        get() = prefs.getString(KEY_LABEL, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_LABEL, value).apply()
        }

    companion object {
        private const val KEY_LABEL = "place_label"
    }
}
