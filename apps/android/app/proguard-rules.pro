# PushBox ProGuard/R8 混淆规则
# 保护加密逻辑、Room 实体和反射调用类

# ========================================
# 1. 保留 BouncyCastle 加密库（核心安全依赖）
# ========================================
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ========================================
# 2. 保留 Room 数据库实体和 DAO
# ========================================
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.* <fields>;
}
-keep @androidx.room.Dao interface *
-keepclassmembers interface * {
    @androidx.room.* <methods>;
}

# 保留 Room 生成的实现类
-keep class * implements androidx.room.RoomDatabase
-keep class * extends androidx.room.RoomDatabase

# ========================================
# 3. 保留 Kotlin 数据类和序列化相关
# ========================================
-keep class cn.iamplus.pushbox.data.** { *; }
-keepclassmembers class cn.iamplus.pushbox.data.** {
    <fields>;
}

# Kotlin 标准库反射支持
-keep class kotlin.Metadata { *; }
-keepclassmembers class * {
    @kotlin.Metadata <methods>;
}

# ========================================
# 4. 保留 Compose UI 组件（防止运行时崩溃）
# ========================================
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# 保留自定义 Composable 函数签名
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ========================================
# 5. 保留网络 API 模型（JSON 序列化）
# ========================================
-keep class cn.iamplus.pushbox.net.** { *; }
-keepclassmembers class cn.iamplus.pushbox.net.** {
    <fields>;
}

# 如果使用了 Gson/Moshi/Kotlinx Serialization，取消注释以下行：
# -keepattributes *Annotation*
# -keep class * implements java.io.Serializable

# ========================================
# 6. 保留日志和异常信息（便于调试）
# ========================================
-keepclassmembers class * {
    @android.util.Log <methods>;
}

# 保留异常堆栈中的类名和方法名
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*

# ========================================
# 7. 保留反射调用的类
# ========================================
-keep class cn.iamplus.pushbox.MainActivity
-keep class cn.iamplus.pushbox.App
-keep class cn.iamplus.pushbox.push.PushService

# BiometricManager 相关
-keep class androidx.biometric.** { *; }

# ========================================
# 8. 优化设置
# ========================================
# 启用 aggressive 优化（减小 APK 体积）
-optimizations !code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5

# 移除未使用的代码
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}

# 移除调试日志（release 构建）
-assumevalues class android.os.Build$VERSION {
    public static final int SDK_INT return 26..36;
}

# ========================================
# 9. 忽略无害警告
# ========================================
-dontnote okhttp3.**
-dontnote retrofit2.**
-dontnote kotlinx.coroutines.**
