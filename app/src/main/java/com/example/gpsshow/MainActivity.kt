package com.example.gpsshow

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.gpsshow.location.FixCache
import com.example.gpsshow.location.hasLocationPermission
import com.example.gpsshow.location.isLocationEnabled
import com.example.gpsshow.location.rememberGpsState
import com.example.gpsshow.settings.SettingsStore
import com.example.gpsshow.theme.AccentPalette
import com.example.gpsshow.theme.GPSShowTheme
import com.example.gpsshow.ui.GpsScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            GPSShowTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    GpsShowApp()
                }
            }
        }
    }
}

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

@Composable
private fun GpsShowApp() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    // 清单里声明了 configChanges，旋转时 Activity 不重建；
    // 但仍以 Configuration 作为方向状态源，保证与系统一致。
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    var hasPermission by remember { mutableStateOf(context.hasLocationPermission()) }
    var locationEnabled by remember { mutableStateOf(context.isLocationEnabled()) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var placeLabel by remember { mutableStateOf(SettingsStore(context).placeLabel) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasPermission = context.hasLocationPermission()
    }

    // 用户可能在系统设置里改权限或关定位，回到前台时重新判定
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasPermission = context.hasLocationPermission()
                locationEnabled = context.isLocationEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 应用在前台时用户从快捷开关关闭定位，也要立刻反映（ON_RESUME 不会触发）
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                locationEnabled = context.isLocationEnabled()
            }
        }
        val filter = IntentFilter(android.location.LocationManager.PROVIDERS_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    // 横屏（打卡画面）隐藏系统栏，拍出来的照片更干净
    DisposableEffect(landscape, activity) {
        val window = activity?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (landscape) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                // 必须恢复，否则回到竖屏会残留无法唤出的系统栏
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 首次进入自动申请一次；之后只走按钮，避免反复弹窗骚扰
    LaunchedEffect(Unit) {
        if (!hasPermission && !permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(LOCATION_PERMISSIONS)
        }
    }

    val gps = rememberGpsState(hasPermission = hasPermission, locationEnabled = locationEnabled)

    // 「上一次有效定位」缓存：拿到过三维定位后就存本机，之后即使室内没信号也能显示历史海拔
    val fixCache = remember { FixCache(context) }
    var cachedFix by remember { mutableStateOf(fixCache.load()) }
    // key 必须用稳定值：早先用 LaunchedEffect(gps.fix) 是错的——
    // GpsFix 里含 elapsedRealtimeNanos，注入路径每次重组都新建对象，key 一变
    // effect 就被取消重启，save() 永远跑不完，缓存始终为空。
    val fixKey = gps.fix?.let { "${it.timeMillis}|${it.latitude}|${it.longitude}|${it.altitude}" }
    LaunchedEffect(fixKey) {
        val f = gps.fix
        // 合成数据默认不写缓存，避免污染真实记录；
        // 只有后门文件里显式 allowCacheWrite 时才写（用于验证缓存链路本身）。
        val allowWrite = !gps.synthetic || gps.syntheticAllowsCacheWrite
        if (f != null && f.has3dFix && allowWrite) {
            fixCache.save(f)
            cachedFix = fixCache.load()
        }
    }

    when {
        !hasPermission -> PermissionGate(
            onRequest = { permissionLauncher.launch(LOCATION_PERMISSIONS) },
            onOpenSettings = { context.openAppSettings() },
        )

        !locationEnabled -> LocationServiceGate(
            onOpenSettings = { context.openLocationSettings() },
        )

        else -> GpsScreen(
            state = gps,
            placeLabel = placeLabel,
            onPlaceLabelChange = { newLabel ->
                placeLabel = newLabel
                SettingsStore(context).placeLabel = newLabel
            },
            cachedFix = cachedFix,
        )
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    GateScaffold(
        title = "需要定位权限",
        body = "本应用要读取 GPS 信息才能显示经纬度和海拔。\n" +
            "只读取本机定位，不联网、不上传、不保存任何位置记录。",
    ) {
        Button(onClick = onRequest) { Text("授予定位权限") }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "已拒绝过？点这里去系统设置里手动打开",
            color = AccentPalette.Coordinate,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clickable(onClick = onOpenSettings),
        )
    }
}

@Composable
private fun LocationServiceGate(onOpenSettings: () -> Unit) {
    GateScaffold(
        title = "系统定位已关闭",
        body = "请先在系统设置里打开「位置信息」开关，否则任何应用都拿不到坐标。",
    ) {
        Button(onClick = onOpenSettings) { Text("打开定位设置") }
    }
}

@Composable
private fun GateScaffold(
    title: String,
    body: String,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = AccentPalette.Label,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(24.dp))
        actions()
    }
}

private fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}

private fun Context.openLocationSettings() {
    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}
