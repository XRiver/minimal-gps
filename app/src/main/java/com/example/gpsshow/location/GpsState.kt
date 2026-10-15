package com.example.gpsshow.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.Handler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.gpsshow.debug.SourceOverride

/** 一次定位快照。字段全部取自系统 Location，未做加工。 */
data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    /** 海拔。!hasAltitude 时为 NaN —— 网络定位不提供高程，切不可当成 0 展示。 */
    val altitude: Double,
    /** 平均海平面海拔（Android 14+ 才有）。缺失为 NaN。 */
    val altitudeMsl: Double,
    val accuracyM: Float,
    val verticalAccuracyM: Float,
    val speedMps: Float,
    val bearingDeg: Float,
    val bearingAccuracyDeg: Float,
    val elapsedRealtimeNanos: Long,
    val timeMillis: Long,
    val provider: String,
    val isMock: Boolean,
    val satsVisible: Int,
    val satsUsedInFix: Int,
    val satsByConstellation: Map<String, Int>,
    val meanCn0: Float,
    val maxCn0: Float,
    val satellites: List<SatInfo>,
) {
    /**
     * 是否真的拿到了可用高程。
     *
     * 不能只看 Location.hasAltitude()：实测（Redmi / Android 16）在 provider 尚未
     * 提供高程时，hasAltitude() 依然返回 true 而 altitude 是 0.0。
     * 因此额外要求数值非零。
     */
    val hasAltitude: Boolean get() = !altitude.isNaN() && altitude != 0.0

    /** 是否来自 GNSS 而非网络/融合（按 provider 判断，不看卫星数）。 */
    val fromGnss: Boolean get() = provider.contains("gps", ignoreCase = true)

    /**
     * 精度是否已经收敛到可用的程度。
     *
     * 真机上遇到过：provider 报 `gps`、altitude 也有值，但水平精度 ±151.9m、
     * 垂直精度 ±51.5m —— 那是刚上电还没有有效解时的"占位式"结果。此时若显示
     * 「三维定位已锁定」，对打卡场景是误导：海拔误差可能有几十米。
     *
     * 阈值故意放宽（25m/30m），只用来挡掉"明显还没收敛"的状态，不做精修判断。
     * 注意这里同样不看卫星数——那是秒级波动值，用它当条件会让界面抖动。
     */
    val accuracyUsable: Boolean
        get() = accuracyM > 0f && accuracyM <= HORIZONTAL_ACCURACY_LIMIT_M &&
            (verticalAccuracyM.isNaN() || verticalAccuracyM <= VERTICAL_ACCURACY_LIMIT_M)

    /**
     * 是否构成可用的三维定位：有真实高程 **且** 精度已收敛。
     */
    val has3dFix: Boolean get() = hasAltitude && accuracyUsable

    /** 质量权重，越大越好。用于在多个 provider 之间挑最可信的一条。 */
    val quality: Int
        get() = when {
            has3dFix -> 4              // GNSS + 高程 + 精度达标，最理想
            hasAltitude && fromGnss -> 3  // 有高程但精度还不行
            fromGnss -> 2              // GNSS 平面解
            else -> 1                  // 网络/融合
        }

    companion object {
        const val HORIZONTAL_ACCURACY_LIMIT_M = 25f
        const val VERTICAL_ACCURACY_LIMIT_M = 30f
    }
}

/**
 * 从各 provider 的最新定位里挑一条最可信的。
 *
 * 为什么需要这个：原先三个 provider（GPS / FUSED / NETWORK）共用同一个监听器、
 * 且每个回调都无条件覆盖同一个变量。结果是高质量的 GPS 定位（带高程）会被
 * 低质量的网络定位（无高程）立刻顶掉，下一轮又反过来——界面状态以秒级来回跳。
 * 正确做法是按 provider 分别锁存，展示时择优。
 */
fun chooseBestFix(fixes: Collection<GpsFix>): GpsFix? =
    fixes.maxWithOrNull(
        compareBy<GpsFix> { it.quality }.thenBy { it.timeMillis },
    )


/** 单颗卫星快照，用于详情页展示信噪比与仰角方位。 */
data class SatInfo(
    val svid: Int,
    val constellation: String,
    val cn0: Float,
    val elevation: Float,
    val azimuth: Float,
    val usedInFix: Boolean,
)

private data class GnssSnapshot(
    val visible: Int,
    val usedInFix: Int,
    val byConstellation: Map<String, Int>,
    val meanCn0: Float,
    val maxCn0: Float,
    val satellites: List<SatInfo>,
) {
    companion object {
        val EMPTY = GnssSnapshot(0, 0, emptyMap(), 0f, 0f, emptyList())
    }
}

fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * 系统定位总开关是否打开。
 * 注意：androidx.core 1.18 起已移除 LocationManagerCompat（整个 android.location 子包都没了），
 * 所以这里直接用框架 API 并按版本分支。
 */
fun Context.isLocationEnabled(): Boolean {
    val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        lm.isLocationEnabled
    } else {
        @Suppress("DEPRECATION")
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
}

/** 设备是否真的带 GPS 硬件。没有时只能依赖网络定位。 */
fun Context.hasGpsHardware(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)

private fun constellationName(type: Int): String = when (type) {
    GnssStatus.CONSTELLATION_GPS -> "GPS"
    GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
    GnssStatus.CONSTELLATION_BEIDOU -> "北斗"
    GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
    GnssStatus.CONSTELLATION_QZSS -> "QZSS"
    GnssStatus.CONSTELLATION_SBAS -> "SBAS"
    GnssStatus.CONSTELLATION_IRNSS -> "NavIC"
    else -> "其他"
}

/** provider 的耗电等级，用来解释为什么室内收不到星、为什么开着费电。 */
private fun providerPowerHint(lm: LocationManager, provider: String): String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        when (runCatching { lm.getProviderProperties(provider) }.getOrNull()?.powerUsage) {
            ProviderProperties.POWER_USAGE_HIGH -> "高耗电"
            ProviderProperties.POWER_USAGE_MEDIUM -> "中等耗电"
            ProviderProperties.POWER_USAGE_LOW -> "低耗电"
            else -> null
        }
    } else {
        null
    }

data class GpsState(
    val fix: GpsFix?,
    val error: String?,
    val activeProviders: List<String>,
    val gpsPowerHint: String?,
    val hasGpsHardware: Boolean,
    /** debug 构建里是否正在走定位注入（界面据此显示"合成数据"角标）。 */
    val synthetic: Boolean = false,
    /** 仅 debug 后门用：合成数据是否允许写入历史值缓存（默认不允许，避免污染）。 */
    val syntheticAllowsCacheWrite: Boolean = false,
)

@SuppressLint("MissingPermission")
@Composable
fun rememberGpsState(hasPermission: Boolean, locationEnabled: Boolean): GpsState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 按 provider 分别锁存，而不是三个 provider 抢一个变量——
    // 混用会让高质量 GPS 解被网络解顶掉，界面状态来回跳。
    var fixesByProvider by remember { mutableStateOf<Map<String, GpsFix>>(emptyMap()) }
    var gnss by remember { mutableStateOf(GnssSnapshot.EMPTY) }
    var error by remember { mutableStateOf<String?>(null) }
    var activeProviders by remember { mutableStateOf<List<String>>(emptyList()) }
    val synthetic = remember { SourceOverride.parse(context) != null }

    DisposableEffect(hasPermission, locationEnabled, lifecycleOwner) {
        if (!hasPermission || !locationEnabled) {
            fixesByProvider = emptyMap()
            error = null
            activeProviders = emptyList()
            return@DisposableEffect onDispose { }
        }


        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            error = "无法访问系统定位服务"
            return@DisposableEffect onDispose { }
        }

        error = null
        val engaged = mutableListOf<String>()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                // 注册用的 provider 会带在 Location 上；个别实现为空，
                // 空名按网络来源归类（GPS 一定会带 "gps"）。
                val key = location.provider?.takeIf { it.isNotBlank() } ?: "fused"
                fixesByProvider = fixesByProvider + (key to location.toFix(gnss))
            }

            // LocationListener 要求实现；该回调自 API 29 起已不再触发
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        // 用 allProviders 判断可用性比 isProviderEnabled 更宽松：
        // 后者在“provider 存在但总开关关闭”时也会返回 false。
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER)) {
            if (!lm.allProviders.contains(provider)) continue
            val ok = runCatching {
                lm.requestLocationUpdates(provider, 1000L, 0f, listener, context.mainLooper)
            }.isSuccess
            if (ok) engaged += provider
        }

        // 兜底：网络定位。室内收不到星时，只有它能给出一个（较粗的）位置。
        if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
            val ok = runCatching {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 2000L, 0f, listener, context.mainLooper,
                )
            }.isSuccess
            if (ok) engaged += LocationManager.NETWORK_PROVIDER
        }

        activeProviders = engaged

        // 先按 provider 取 lastKnown，避免刚打开时整屏空白。
        // 注意也要分别存：只挑一条最新的会把 GPS 的历史高程丢掉。
        if (fixesByProvider.isEmpty()) {
            val seed = HashMap<String, GpsFix>()
            for (p in engaged) {
                val last = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
                seed[p] = last.toFix(gnss)
            }
            if (seed.isNotEmpty()) fixesByProvider = seed
        }

        val gnssCallback: GnssStatus.Callback? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        val sats = ArrayList<SatInfo>(status.satelliteCount)
                        val byConst = HashMap<String, Int>()
                        var used = 0
                        var cn0Sum = 0f
                        var cn0Max = 0f
                        for (i in 0 until status.satelliteCount) {
                            val type = constellationName(status.getConstellationType(i))
                            byConst[type] = (byConst[type] ?: 0) + 1
                            val cn0 = status.getCn0DbHz(i)
                            val inFix = status.usedInFix(i)
                            if (inFix) {
                                used++
                                if (cn0 > 0f) {
                                    cn0Sum += cn0
                                    if (cn0 > cn0Max) cn0Max = cn0
                                }
                            }
                            sats += SatInfo(
                                svid = status.getSvid(i),
                                constellation = type,
                                cn0 = cn0,
                                elevation = status.getElevationDegrees(i),
                                azimuth = status.getAzimuthDegrees(i),
                                usedInFix = inFix,
                            )
                        }
                        sats.sortWith(
                            compareByDescending<SatInfo> { it.usedInFix }.thenByDescending { it.cn0 },
                        )
                        val snapshot = GnssSnapshot(
                            visible = status.satelliteCount,
                            usedInFix = used,
                            byConstellation = byConst,
                            meanCn0 = if (used > 0) cn0Sum / used else 0f,
                            maxCn0 = cn0Max,
                            satellites = sats,
                        )
                        gnss = snapshot
                        // 卫星状态是独立回调，把最新结果并进 GNSS 来源的定位，
                        // 详情页的卫星字段才是活的。只并 GNSS 那条：
                        // 网络定位没有卫星概念，不该被写入卫星数据。
                        fixesByProvider = fixesByProvider.mapValues { (_, f) ->
                            if (f.fromGnss) {
                                f.copy(
                                    satsVisible = snapshot.visible,
                                    satsUsedInFix = snapshot.usedInFix,
                                    satsByConstellation = snapshot.byConstellation,
                                    meanCn0 = snapshot.meanCn0,
                                    maxCn0 = snapshot.maxCn0,
                                    satellites = snapshot.satellites,
                                )
                            } else {
                                f
                            }
                        }
                    }
                }
            } else {
                null
            }

        if (gnssCallback != null) {
            // 必须传 Handler；API 30 起才有 Executor 重载，这里统一用 Handler 兼容旧版本
            runCatching {
                lm.registerGnssStatusCallback(gnssCallback, Handler(context.mainLooper))
            }
        }

        onDispose {
            runCatching { lm.removeUpdates(listener) }
            if (gnssCallback != null) {
                runCatching { lm.unregisterGnssStatusCallback(gnssCallback) }
            }
        }
    }

    val gpsPowerHint = remember(locationEnabled, activeProviders) {
        if (!locationEnabled || activeProviders.isEmpty()) {
            null
        } else {
            (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
                ?.let { lm -> providerPowerHint(lm, LocationManager.GPS_PROVIDER) }
        }
    }

    // debug 注入优先。刻意放在 DisposableEffect 之外、每次重组都读：
    // 真实定位是通过回调把值写进 State 的，注入则是一次性求值，放进 remember/effect
    // 会因为"首帧读到 null"而永远拿不到值（踩过的坑）。
    val injected = SourceOverride.parse(context)

    return GpsState(
        // 关键：从各 provider 里择优，而不是"谁最后回调谁说了算"。
        // 三种来源同时在线时，后者的行为是按秒级来回覆盖，界面状态会疯狂跳。
        // 注入路径走同一套择优逻辑，才能用它验证这个行为。
        fix = injected?.let { chooseBestFix(it.fixes.values) }
            ?: chooseBestFix(fixesByProvider.values),
        error = error,
        activeProviders = if (injected != null) {
            injected.fixes.keys.toList()
        } else {
            activeProviders
        },
        gpsPowerHint = gpsPowerHint,
        hasGpsHardware = context.hasGpsHardware(),
        synthetic = synthetic,
        syntheticAllowsCacheWrite = injected?.allowCacheWrite == true,
    )
}

private fun Location.toFix(gnss: GnssSnapshot): GpsFix = GpsFix(
    latitude = latitude,
    longitude = longitude,
    // 关键：网络/融合定位没有高程，getAltitude() 会返回 0.0。
    // 必须用 hasAltitude() 判别，否则会把 0 当成真实海拔展示出来。
    altitude = if (hasAltitude()) altitude else Double.NaN,
    altitudeMsl = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        hasMslAltitude()
    ) {
        mslAltitudeMeters
    } else {
        Double.NaN
    },
    accuracyM = accuracy,
    verticalAccuracyM = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        hasVerticalAccuracy()
    ) {
        verticalAccuracyMeters
    } else {
        Float.NaN
    },
    speedMps = if (hasSpeed()) speed else Float.NaN,
    bearingDeg = if (hasBearing()) bearing else Float.NaN,
    bearingAccuracyDeg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasBearingAccuracy()) {
        bearingAccuracyDegrees
    } else {
        Float.NaN
    },
    elapsedRealtimeNanos = elapsedRealtimeNanos,
    timeMillis = time,
    provider = provider ?: "未知",
    isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else false,
    satsVisible = gnss.visible,
    satsUsedInFix = gnss.usedInFix,
    satsByConstellation = gnss.byConstellation,
    meanCn0 = gnss.meanCn0,
    maxCn0 = gnss.maxCn0,
    satellites = gnss.satellites,
)
