package com.example.gpsshow.debug

import android.content.Context

/**
 * release 构建里的空实现：正式包不含任何定位注入能力。
 * 与 debug 源集里的同名对象二选一，保证后门不会进正式包。
 */
object SourceOverride {
    fun parse(context: Context): FixOverride? = null
}
