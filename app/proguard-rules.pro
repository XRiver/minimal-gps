# 混淆规则。
#
# 当前 release 构建 isMinifyEnabled = false，所以这个文件不参与实际处理；
# 它存在的意义是：将来若开启 R8，规则已经就位，不会出现"开启了却没有规则文件"的隐性坑。
#
# 保留 org.json：FixCache / FixJson 通过 JSONObject 做序列化，
# 反射路径在混淆下容易被破坏。
-keep class org.json.** { *; }

# 保留 Kotlin 元数据，Compose 与协程依赖它
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-dontwarn kotlinx.coroutines.**
