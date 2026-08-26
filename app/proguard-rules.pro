# ===== SwipeSimulatorApp ProGuard 规则 =====

# --- AndroidX / AppCompat ---
-keep class androidx.appcompat.** { *; }
-keep class androidx.core.** { *; }
-dontwarn androidx.localbroadcastmanager.**

# --- 保留无障碍服务（系统通过反射实例化） ---
-keep class com.swipesim.SwipeAccessibilityService { *; }

# --- 保留 Application 子类 ---
-keep class com.swipesim.App { *; }

# --- 保留 SwipeConfig 及内部类（JSON 序列化用反射） ---
-keep class com.swipesim.SwipeConfig { *; }
-keep class com.swipesim.SwipeConfig$* { *; }
-keep class com.swipesim.SwipeConfig$ClickPoint { *; }

# --- org.json（系统自带，但保险起见） ---
-dontwarn org.json.**

# --- 枚举 ---
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
