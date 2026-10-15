# GPS Show — 极简定位信息查看器

一个只读 GPS 信息展示应用。**不联网、不上传**。唯一落盘的只有两样本机数据：
打卡地名，以及「上一次有效定位」（可清）。

## 两种形态

| 方向 | 用途 | 内容 |
|---|---|---|
| **竖屏** | 查看/调试 | 完整详情：十进制与度分秒坐标、椭球面与海平面海拔、垂直精度、可见/解算卫星数、平均与最强信噪比、星座分布、速度、方位角、定位时刻、数据新鲜度、活跃定位源、是否模拟位置 |
| **横屏** | 拍照打卡 | 超大字号：自定义地名 + 纬经度 + 海拔。自动隐藏状态栏/导航栏，界面上没有任何按钮 |

横屏下**点一下地名**即可编辑，内容存在本机，下次打开自动带出。

## 关键设计决策

### 精度未收敛时不能声称"已锁定"
实测（release 版首次运行）拿到过这样一组数据：

```
定位方式 gps    海拔 35.13 m    水平精度 ±151.9 m    垂直精度 ±51.5 m
参与解算 0 颗    平均信噪比 —
```

那是刚上电、解还没收敛时的"占位式"结果，海拔误差可能有几十米。
界面若显示「三维定位已锁定」，对打卡场景是**误导**。

修法：三维定位额外要求精度收敛，但**依然不依赖卫星数**——
后者是秒级波动值，用它当条件会让界面抖动（这是修过的另一个 bug）：

```kotlin
val accuracyUsable get() = accuracyM > 0f && accuracyM <= 25f &&
    (verticalAccuracyM.isNaN() || verticalAccuracyM <= 30f)
val has3dFix get() = hasAltitude && accuracyUsable
```

阈值故意放宽（25m / 30m），只用来挡掉"明显还没收敛"的状态。
垂直精度未知（NaN）时不否决，因为很多设备/网络解不给这个字段。
于是状态会经历：`正在搜索卫星…` → `已收到高程，精度收敛中` → `GPS 三维定位已锁定`。

### 多个定位源必须择优，不能让"最后回调者"覆盖
这是真机上观察到的现象：**界面在「已定位 / 无定位」之间以半秒级来回跳**。

根因：对 GPS、FUSED、NETWORK 三个 provider 注册了同一个监听器，且**每个回调都无条件
覆盖同一个变量**。FUSED provider 内部会聚合网络数据，于是「高质量 GPS 定位（带高程）」
被「低质量网络定位（无高程）」立刻顶掉，下一轮又反过来。

修法：按 provider 分别锁存，展示时用 `chooseBestFix` 择优：
```kotlin
val quality get() = when {
    hasAltitude && fromGnss -> 3   // GNSS + 高程，最理想
    fromGnss -> 2                  // GNSS 平面解
    else -> 1                      // 网络/融合
}
```
同质量时才比时间戳。这样无论回调顺序如何，结果都稳定。

**同时把状态判定与卫星数解耦**：原先 `fromGnss`/`has3dFix` 依赖 `satsUsedInFix > 0`，
而卫星数是秒级波动的瞬时值——它一变，界面文案就跟着切。三维定位的实质是"有高程"，
卫星数只能作为展示信息，不能当状态条件。

### 海拔必须区分「没有」和「是 0」
Android 的 `Location.hasAltitude()` 在 provider 尚未提供高程时**也返回 true**，
而 `getAltitude()` 给 0.0。若直接展示，界面会出现
「GPS 三维定位已锁定 + 海拔 0.00 m + 0 颗卫星」这种自相矛盾的结果。

因此判定条件是三重：
```kotlin
val hasAltitude get() = !altitude.isNaN() && altitude != 0.0
val has3dFix    get() = hasAltitude && satsUsedInFix > 0
```
界面永远优先说真话：没有高程就显示 `—` 并解释原因，绝不把 0 当海拔展示。

### 「上一次有效定位」缓存
室内拿不到 GNSS 锁时，系统只给网络定位的平面坐标（无高程）。但用户可能在室外
已经成功定位过，那个含高程的结果不该被丢弃。所以：

- 只缓存**有效**结果（有真实高程 **且** 有卫星参与解算），网络定位一律不缓存；
- 读出时**必须**带时间戳，界面标注为「历史值 + 取得时间 + 距今多久」，绝不伪装成实时数据；
- 用 `commit()` 同步落盘——这是"好不容易在室外拿到的一次定位"，不该因进程被杀而丢失。

### 不用任何定时器刷新界面
早先版本用 `LaunchedEffect + while(true){ delay(500) }` 刷新「数据新鲜度」，后果有两个：
1. 界面永远处于「不空闲」状态，Compose 的 `waitForIdle()` 永不返回，仪器测试直接挂死；
2. 生产环境每 500ms 白重组一次。

定位本身每秒就来一条新数据，`fix` 变了自然重组，定时器是多余的。

### 权限与开关的三种拦截态
- 未授权 → 引导页 + 申请按钮 + 「去系统设置」入口
- 系统定位总开关关闭 → 引导页 + 直接跳转定位设置
- 应用在前台时用户从快捷开关关掉定位 → 用 `PROVIDERS_CHANGED_ACTION` 广播感知并立即反映
  （`ON_RESUME` 在这条路径上不会触发）

### 深色主题是刻意的
户外打卡场景：深色背景阳光下对比度更好，纯白大字在反光屏上会糊成一团。

## 开发中修掉的真实崩溃（值得记下来）

### `org.json` 不接受 NaN —— 室外拿到 GPS 锁的瞬间会闪退
```
org.json.JSONException: Forbidden numeric value: NaN
    at org.json.JSONObject.put
    at FixCache.save
```
`altitudeMsl` 在 API 34 以下**恒为 NaN**，`verticalAccuracyM` 在多数设备上也是 NaN。
也就是说：**任何真实三维定位一写入缓存就崩**。这个 bug 的触发时机极其恶劣——
用户跑到室外、耐心等到锁星、正准备看海拔的那一刻闪退。

修法：序列化前把 NaN/Infinity 换成 JSON `null`，读回来再还原成 NaN（而不是 0）。
序列化逻辑抽成不依赖 Android 的纯函数 `FixJson`，才能被普通 JVM 单测覆盖。

### 本地单测里的 `org.json` 是"not mocked" stub
`testImplementation("org.json:json")` 必须显式引入真实实现，否则 Android 的 stub
会让 JSON 测试**假失败**；更阴险的是断言"返回 null"的用例会因为 stub 抛异常被
catch 住而**假通过**。

### 两个 Compose / 平台陷阱
- `LaunchedEffect(gps.fix)` 的 key 用整个对象是错的：`GpsFix` 含 `elapsedRealtimeNanos`，
  注入路径每次重组都新建对象，key 一变 effect 就被取消重启，`save()` 永远跑不完。
  必须用稳定值（时间戳+坐标）做 key。
- `LocationManagerCompat` 在 androidx.core 1.18 已被**移除**（整个 `android.location`
  子包都没了），必须直接用框架 API 并按版本分支。

## 构建与运行

需要 JDK 17+ 与 Android SDK（compileSdk 36）。模板用了 `jvmToolchain(17)`，
若本机没有 JDK 17，Gradle 会尝试自动下载工具链。

```bash
./gradlew assembleDebug          # 编译
./gradlew testDebugUnitTest      # 单元测试（39 个）
./gradlew assembleRelease        # 签名发布包（需 keystore.properties，见下）
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

若工具链下载在国内网络卡住（表现是构建毫无输出地挂住），在
`~/.gradle/gradle.properties` 里显式指定本机 JDK 17：

```properties
gpsshowJdk17Path=D:\\path\\to\\jdk17
```

### 安装时需要在手机上手动确认
部分设备（实测 Redmi / HyperOS）执行 `adb install` 后**必须在手机屏幕上手动确认**
才算完成，命令会挂起等待。按一次确认按钮即可，**不要反复重试**——
重试没有帮助，而且失败的安装会话会把已装版本一起回滚掉。

报错 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` 只说明"没确认成功"，
不代表设备有问题。重装还会撤销定位权限，需要重新授权。

## 图标

启动图标来自 Flaticon：[**planet** icon by Flaticon](https://www.flaticon.com/)，
源文件保留在 `icon-src/source-13991455.png`。按 Flaticon 免费授权要求在此署名；
如需商用请自行核实授权条款或替换图标。

预处理结果见 `icon-src/icon-preview.png`。生成要点（这几个不处理就会出问题）：

1. **把 alpha 当蒙版重新填色**，而不是把 128×128 直接拉大到 432×432。
   源图是纯黑剪影 + 透明底，取它的 alpha 通道、用主题色填充，
   放大后得到的是"边缘干净的纯色图形"，而不是糊掉的位图。
2. **自适应图标前景画布是 108dp，但只有中间 72dp 可见**，外围 18dp 会被
   各厂商蒙版裁掉。所以星球只占画布约一半，否则圆形蒙版一切就残。
   `icon-preview.png` 里同时给出了圆形/方形蒙版的裁切结果用于核对。
3. **`<monochrome>` 层（Android 13+ 主题图标）要单独留更大边距**，
   系统会把它再缩放塞进各种形状里。
4. 旧式 `mipmap-*/ic_launcher.webp` 仍要生成——API 25 及以下只认它们。

## 打包 APK

release 包已用自签名密钥签名，可直接安装：

```
app/build/outputs/apk/release/app-release.apk   7.67 MB
```

```powershell
.\gradlew.bat assembleRelease
```

签名配置读工程根目录的 `keystore.properties`（该文件不存在时 release 仍可构建，
只是产出未签名包）。密钥库在 `keystore/release.jks`，RSA 4096，有效期 10000 天。

> ⚠️ **以后要覆盖安装更新，必须用同一个密钥签名**，否则系统会拒绝安装。
> 密钥库或 `keystore.properties` 丢了就只能卸载重装（数据也会丢）。
> 这是自签名密钥，仅个人使用，未走 Google Play。

验证签名：
```powershell
$env:JAVA_HOME='D:\dev\jdk21'
& 'D:\dev\android-sdk\build-tools\36.0.0\apksigner.bat' verify --verbose --print-certs `
  app\build\outputs\apk\release\app-release.apk
```

### 安装时需要手动确认
这台设备上 `adb install` 需要**在手机屏幕上手动确认**才算完成。
所以安装命令会挂起等待，最长约 3 分钟——**按一次手机上的确认按钮即可**，
不要反复重试（重试没有帮助，之前的会话会被回滚）。
注意 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` 只说"没确认成功"，
不代表设备有问题。

## 测试覆盖

`app/src/test/`（39 个单元测试，全部通过）：
- `FormattingTest` — 度分秒转换与进位、半球标记、海拔/速度/方位格式化
- `AltitudeAvailabilityTest` — 高程可用性三态判定（0 / NaN / 真实值）
- `location/FixJsonTest` — JSON 序列化的 NaN 处理与往返（**对应闪退 bug**）
- `location/ChooseBestFixTest` — 多源择优、回调顺序无关性、状态判定不随卫星数抖动
  （**对应界面跳动 bug**）
- `location/AccuracyGateTest` — 精度门槛：未收敛时不得声称三维锁定，且判定不依赖卫星数
  （**对应"±151.9m 却报已锁定"bug**）

`app/src/androidTest/CheckInLayoutTest` 已编写但在本机跑不通：`createComposeRule()`
会让 instrumented 测试自身挂起，`am instrument` 不返回。这是测试基础设施与
HyperOS 的兼容问题，不是应用代码问题（两种布局都在真机上截图验证过）。

## 真机验证状态

**真实 GPS 海拔已拿到并验证**：release 版运行中出现过 `海拔 35.13 m` /
`平均海平面 33.76 m`，与实测地点（南京，约 20–40 m）相符 —— 此前网络定位给的是 0。

其余已确认项：
- 海拔渲染、横屏大字号 + `m` 单位
- 「上一次有效定位」缓存写入 → 重启 → 显示为历史值（带时间与距今时长）全链路通过
- 多源冲突场景下界面稳定：同时注入带高程的 `gps` 与无高程但时间戳更新的 `network`，
  连拍 5 张截图哈希完全一致，且选中 `gps` 那条
- 精度未收敛时正确降级为「已收到高程，精度收敛中」，不再误报已锁定
- 启动图标在桌面上正确显示（深底绿色星球，圆形/方形蒙版裁切后均完整）

**注意**：室内信号极弱时，`定位方式` 可能显示 `gps` 但 `参与解算 0 颗`、
`水平精度 ±150m` 量级——那是刚上电还没形成有效解的状态。此时界面已按上述门槛
降级显示，不会声称已锁定。要得到可靠海拔需要到室外等精度收敛到几十米以内。

## debug 后门说明

`app/src/debug/` 下有一个**只在 debug 包存在**的测试后门，release 包里是空实现
（已用 dex 字符串搜索确认后门不进正式包）。用途是验证海拔渲染、择优逻辑、缓存链路：

```powershell
# 应用需先运行过一次（创建 files 目录）

# 单源：验证海拔渲染
adb shell "run-as com.example.gpsshow sh -c 'cat > files/source_override.json'" <<< \
  '{"altitude":1268.4,"latitude":32.0607,"longitude":118.8039,"allowCacheWrite":true}'

# 多源：验证择优（GPS 带高程 vs 时间戳更新的网络解）
adb shell "run-as com.example.gpsshow sh -c 'cat > files/source_override.json'" <<< \
  '{"sources":[{"provider":"gps","altitude":448.6,"timeMillis":1000},
               {"provider":"network","altitude":null,"timeMillis":9999999999999}]}'

adb shell am force-stop com.example.gpsshow
adb shell am start -n com.example.gpsshow/.MainActivity

# 关闭
adb shell run-as com.example.gpsshow rm -f files/source_override.json
```

`allowCacheWrite` 默认 false：合成数据不会污染真实的历史值缓存。只有需要验证
「写入 → 重启 → 读回」链路时才显式打开。
