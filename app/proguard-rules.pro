# R90 闹钟 ProGuard 规则
#
# 工程本身没有反射、没有序列化、没有 JNI，理论上不需要额外规则。
# 这里只做一份显式声明，避免将来加东西时忘了。

# 保留 Compose 运行时需要的元数据（AGP 会自动处理，这里是双保险）
-keep class androidx.compose.runtime.** { *; }

# 数据类不混淆（便于崩溃日志阅读）
-keep class com.yanfei.r90alarm.** { *; }

# 移除日志
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
