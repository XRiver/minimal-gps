package com.example.gpsshow.debug

import com.example.gpsshow.location.GpsFix

/**
 * 测试后门读到的合成定位。
 *
 * 类型定义放在 main 源集，这样 debug / release 两个变体的 SourceOverride
 * 能共用同一个返回类型；否则 release 的空实现会因为类型不匹配而编译失败
 * （踩过的坑）。
 *
 * 支持**多条**来源，用来在真机上复现"GPS 与网络定位互相覆盖"这种冲突场景——
 * 这正是界面状态来回跳的根因，必须能验证择优逻辑真的生效。
 */
data class FixOverride(
    /** 模拟的定位来源，key 是 provider 名（gps / fused / network）。 */
    val fixes: Map<String, GpsFix>,
    /** 是否允许这些合成数据写入「上一次有效定位」缓存（默认否，避免污染真实记录）。 */
    val allowCacheWrite: Boolean,
) {
    /** 便捷取第一条，供只需要单源的调用方使用。 */
    val primary: GpsFix? get() = fixes.values.firstOrNull()
}
