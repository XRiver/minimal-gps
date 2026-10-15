package com.example.gpsshow.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gpsshow.format.decimalToDms
import com.example.gpsshow.format.formatAccuracy
import com.example.gpsshow.format.formatAge
import com.example.gpsshow.format.formatAgeSinceWallClock
import com.example.gpsshow.format.formatAltitude
import com.example.gpsshow.format.formatBearing
import com.example.gpsshow.format.formatCn0
import com.example.gpsshow.format.formatCompactLat
import com.example.gpsshow.format.formatCompactLon
import com.example.gpsshow.format.formatDateTime
import com.example.gpsshow.format.formatDecimal
import com.example.gpsshow.format.formatSpeed
import com.example.gpsshow.location.CachedFix
import com.example.gpsshow.location.GpsFix
import com.example.gpsshow.location.GpsState
import com.example.gpsshow.location.SatInfo
import com.example.gpsshow.theme.AccentPalette
import java.util.Locale

/**
 * 海拔取值决策：实时优先，实时没有高程时退回「上一次有效定位」的历史值。
 * 绝不把 0 当成海拔（见 GpsFix.hasAltitude 的说明）。
 */
private data class AltitudeView(
    val meters: Double,
    val isHistorical: Boolean,
    val timeMillis: Long,
    val sourceLabel: String,
)

private fun resolveAltitude(fix: GpsFix?, cached: CachedFix?): AltitudeView? = when {
    fix != null && fix.hasAltitude -> AltitudeView(
        meters = fix.altitude,
        isHistorical = false,
        timeMillis = fix.timeMillis,
        sourceLabel = fix.provider,
    )
    cached != null -> AltitudeView(
        meters = cached.altitude,
        isHistorical = true,
        timeMillis = cached.timeMillis,
        sourceLabel = cached.provider,
    )
    else -> null
}

@Composable
fun GpsScreen(
    state: GpsState,
    placeLabel: String,
    onPlaceLabelChange: (String) -> Unit,
    cachedFix: CachedFix? = null,
) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (landscape) {
        LandscapeCheckIn(
            fix = state.fix,
            state = state,
            placeLabel = placeLabel,
            onPlaceLabelChange = onPlaceLabelChange,
            altitude = resolveAltitude(state.fix, cachedFix),
        )
    } else {
        PortraitDetails(
            fix = state.fix,
            state = state,
            altitude = resolveAltitude(state.fix, cachedFix),
        )
    }
}

// ---------------------------------------------------------------- 竖屏详情

@Composable
private fun PortraitDetails(fix: GpsFix?, state: GpsState, altitude: AltitudeView?) {
    // 注意：这里刻意不放任何「每 N 毫秒刷新一次」的定时器。
    // 定位更新本身大约每秒一条，fix 变了整块内容自然重组，数据新鲜度就是准的。
    // 早先版本用 LaunchedEffect + while(true){ delay(500) } 刷时间戳，后果是：
    //   1) 界面永远处于「不空闲」状态，Compose 的 waitForIdle() 永不返回，仪器测试直接挂死；
    //   2) 生产环境里每 500ms 白重组一次，纯浪费。
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    ) {
        item {
            Text(
                text = "定位信息",
                style = MaterialTheme.typography.titleMedium,
                color = AccentPalette.Muted,
            )
            Spacer(Modifier.height(4.dp))
            StatusHeadline(state = state, fix = fix)
            if (state.synthetic) SyntheticBadge()
            Spacer(Modifier.height(8.dp))
        }

        if (fix == null) {
            item { EmptyHint(state = state) }
        } else {
            item { SectionTitle("坐标") }
            item {
                ValueRow("纬度（十进制）", formatDecimal(fix.latitude))
                ValueRow("经度（十进制）", formatDecimal(fix.longitude))
                ValueRow("纬度（度分秒）", decimalToDms(fix.latitude, isLatitude = true))
                ValueRow("经度（度分秒）", decimalToDms(fix.longitude, isLatitude = false))
            }

            item { SectionTitle("高度") }
            item {
                when {
                    altitude == null -> {
                        ValueRow("海拔", "—  尚无任何含高程的定位")
                    }
                    altitude.isHistorical -> {
                        ValueRow("海拔", formatAltitude(altitude.meters))
                        Text(
                            text = "历史值 —— 取自 ${formatDateTime(altitude.timeMillis)}" +
                                "（${formatAgeSinceWallClock(System.currentTimeMillis(), altitude.timeMillis)}），" +
                                "非当前实测。当前定位无高程。",
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentPalette.Altitude,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    else -> {
                        ValueRow("海拔（WGS84 椭球面）", formatAltitude(altitude.meters))
                        if (fix != null && !fix.altitudeMsl.isNaN()) {
                            ValueRow("海拔（平均海平面）", formatAltitude(fix.altitudeMsl))
                        }
                    }
                }
                ValueRow("垂直精度", formatAccuracy(fix?.verticalAccuracyM ?: Float.NaN))
                if (!fix.hasAltitude && altitude != null && !altitude.isHistorical) {
                    Text(
                        text = "要拿到实时海拔需要 GPS/北斗 实际锁定卫星，请到室外或窗边等待搜星。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentPalette.Muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            item { SectionTitle("精度与信号") }
            item {
                ValueRow("水平精度", formatAccuracy(fix.accuracyM))
                ValueRow("定位方式", fix.provider)
                ValueRow("活跃来源", state.activeProviders.joinToString("  ").ifBlank { "—" })
                ValueRow("可见卫星", "${fix.satsVisible} 颗")
                ValueRow("参与解算", "${fix.satsUsedInFix} 颗")
                ValueRow("平均信噪比", formatCn0(fix.meanCn0))
                ValueRow("最强信噪比", formatCn0(fix.maxCn0))
                if (fix.satsByConstellation.isNotEmpty()) {
                    ValueRow(
                        "星座分布",
                        fix.satsByConstellation.entries.joinToString("  ") { "${it.key} ${it.value}" },
                    )
                }
                if (fix.satsVisible > 0 && fix.satsUsedInFix == 0) {
                    Text(
                        text = "能看到 ${fix.satsVisible} 颗卫星但一颗都没参与解算，" +
                            "通常说明在室内或遮挡严重，信号太弱还定不了位。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentPalette.Muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            item { SectionTitle("运动") }
            item {
                ValueRow("速度", formatSpeed(fix.speedMps))
                ValueRow("方位角", formatBearing(fix.bearingDeg))
                if (!fix.bearingAccuracyDeg.isNaN()) {
                    ValueRow("方位精度", "±${fmt(fix.bearingAccuracyDeg, 1)}°")
                }
            }

            item { SectionTitle("时间") }
            item {
                ValueRow("定位时刻", formatDateTime(fix.timeMillis))
                ValueRow("数据新鲜度", formatAge(System.nanoTime(), fix.elapsedRealtimeNanos))
                ValueRow("系统时间", formatDateTime(System.currentTimeMillis()))
            }

            item { SectionTitle("工作状态") }
            item {
                ValueRow(
                    "已启用的定位源",
                    state.activeProviders.joinToString("  ").ifBlank { "—" },
                )
                ValueRow("GPS 硬件", if (state.hasGpsHardware) "有" else "无（仅网络定位）")
                state.gpsPowerHint?.let { ValueRow("GPS 功耗等级", it) }
                ValueRow("模拟位置", if (fix.isMock) "是（该位置由测试程序注入）" else "否")
            }

            if (fix.satellites.isNotEmpty()) {
                item { SectionTitle("卫星明细（● 参与解算，按信噪比排序）") }
                items(fix.satellites.take(40)) { sat -> SatelliteRow(sat) }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SyntheticBadge() {
    Text(
        text = "⚠ 合成数据（debug 注入，非真实定位）",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun StatusHeadline(state: GpsState, fix: GpsFix?) {
    val text: String
    val color: Color
    when {
        state.error != null -> {
            text = state.error
            color = MaterialTheme.colorScheme.error
        }
        fix == null -> {
            text = "正在搜索卫星…"
            color = AccentPalette.Label
        }
        fix.has3dFix -> {
            text = "GPS 三维定位已锁定"
            color = AccentPalette.Coordinate
        }
        // 有高程但精度还没收敛（真机常见：刚上电时 ±150m 的"占位"结果）。
        // 这种时候绝不能声称"已锁定"，海拔误差可能有几十米。
        fix.fromGnss && fix.hasAltitude -> {
            text = "已收到高程，精度收敛中"
            color = AccentPalette.Label
        }
        fix.fromGnss -> {
            text = "GPS 已定位（缺高程，仅平面坐标）"
            color = AccentPalette.Label
        }
        fix.satsUsedInFix > 0 -> {
            text = "仅平面定位（尚无高程）"
            color = AccentPalette.Label
        }
        else -> {
            text = "仅网络定位（无高程，精度较低）"
            color = AccentPalette.Label
        }
    }
    Text(text = text, style = MaterialTheme.typography.headlineSmall, color = color)
}

@Composable
private fun EmptyHint(state: GpsState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "还没拿到定位结果。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = buildString {
                append("• 首次定位在室内通常很慢甚至失败，建议到窗边或室外\n")
                append("• 冷启动搜星一般需要 10～60 秒\n")
                if (!state.hasGpsHardware) append("• 本机未检测到 GPS 硬件，只能靠网络定位\n")
                state.gpsPowerHint?.let { append("• GPS 属于$it 模块，持续开启会明显耗电\n") }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = AccentPalette.Muted,
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Spacer(Modifier.height(18.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = AccentPalette.Coordinate,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(128.dp),
            style = MaterialTheme.typography.bodySmall,
            color = AccentPalette.Muted,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun SatelliteRow(sat: SatInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (sat.usedInFix) "●" else "○",
            color = if (sat.usedInFix) AccentPalette.Coordinate else AccentPalette.Muted,
            fontSize = 11.sp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = sat.constellation,
            modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.bodySmall,
            color = AccentPalette.Muted,
        )
        Text(
            text = "#${sat.svid}",
            modifier = Modifier.width(50.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = formatCn0(sat.cn0),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Start,
        )
        Text(
            text = "仰角 ${fmt(sat.elevation, 0)}°  方位 ${fmt(sat.azimuth, 0)}°",
            style = MaterialTheme.typography.bodySmall,
            color = AccentPalette.Muted,
        )
    }
}

// ---------------------------------------------------------------- 横屏打卡

/**
 * 横屏打卡画面：字号大、信息少、不出现控件，方便直接举着手机拍照。
 * 地名点一下即可修改，其余全是只读的大字号数据。
 */
@Composable
private fun LandscapeCheckIn(
    fix: GpsFix?,
    state: GpsState,
    placeLabel: String,
    onPlaceLabelChange: (String) -> Unit,
    altitude: AltitudeView?,
) {
    var editing by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 28.dp, vertical = 14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            // 第一区：地名 + 完整坐标小字
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = placeLabel.ifBlank { "点这里填写地名" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { editing = true },
                    color = if (placeLabel.isBlank()) AccentPalette.Muted else AccentPalette.Label,
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "纬度 ${fix?.let { formatDecimal(it.latitude) } ?: "—"}" +
                        "    经度 ${fix?.let { formatDecimal(it.longitude) } ?: "—"}",
                    color = AccentPalette.Muted,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }

            // 第二区：大字号坐标 + 海拔
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    BigLabel("纬度 LAT")
                    BigValue(
                        value = fix?.let { formatCompactLat(it.latitude) } ?: "—",
                        color = AccentPalette.Coordinate,
                    )
                    Spacer(Modifier.height(10.dp))
                    BigLabel("经度 LON")
                    BigValue(
                        value = fix?.let { formatCompactLon(it.longitude) } ?: "—",
                        color = AccentPalette.Coordinate,
                    )
                }
                Column(
                    modifier = Modifier.weight(0.85f),
                    horizontalAlignment = Alignment.End,
                ) {
                    BigLabel("海拔 ALT")
                    BigValue(
                        value = altitude?.let {
                            formatAltitude(it.meters).removeSuffix(" m")
                        } ?: "—",
                        color = AccentPalette.Altitude,
                        suffix = if (altitude != null) "m" else null,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = when {
                            fix == null -> "等待定位"
                            altitude == null -> "无高程 · ±${fmt(fix.accuracyM, 0)}m"
                            altitude.isHistorical -> "历史值 · ±${fmt(fix.accuracyM, 0)}m"
                            else -> "±${fmt(fix.accuracyM, 0)}m · ${fix.satsUsedInFix} 星"
                        },
                        color = if (altitude?.isHistorical == true) {
                            AccentPalette.Altitude
                        } else {
                            AccentPalette.Muted
                        },
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
            }
        }

        // 还没有平面定位成果时才显示状态提示，避免干扰拍照
        if (fix == null || !fix.fromGnss) {
            Column(
                modifier = Modifier.align(Alignment.BottomEnd),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = state.error ?: "正在搜索卫星…",
                    color = AccentPalette.Label,
                    fontSize = 14.sp,
                )
                if (fix != null && fix.satsVisible == 0) {
                    Text(
                        text = "目前收不到卫星信号，需要到室外开阔处",
                        color = AccentPalette.Muted,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }

    if (editing) {
        PlaceLabelDialog(
            initial = placeLabel,
            onDismiss = { editing = false },
            onConfirm = {
                onPlaceLabelChange(it)
                editing = false
            },
        )
    }
}

@Composable
private fun BigLabel(text: String) {
    Text(
        text = text,
        color = AccentPalette.Muted,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
    )
}

@Composable
private fun BigValue(value: String, color: Color, suffix: String? = null) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = value,
            color = color,
            fontSize = 42.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        if (suffix != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = suffix,
                color = color,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

private fun fmt(value: Float, decimals: Int): String =
    if (value.isNaN()) "—" else String.format(Locale.US, "%.${decimals}f", value)
