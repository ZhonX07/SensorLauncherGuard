# libxposed 注解是 compileOnly 依赖，R8 无法解析，直接忽略。
-dontwarn io.github.libxposed.annotation.**

# 入口类名必须保留原名：
# `META-INF/xposed/java_init.list` 里写的是 `io.github.sensorlaunchguard.xposed.GuardModule`，
# R8 允许改名（allowobfuscation）会让该清单指向不存在的类，模块在 release 包里根本无法被加载。
# 同理，构造函数也必须是 public 且无参。
-keepnames public class * extends io.github.libxposed.api.XposedModule
-keep,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Manifest 通过 android:name 引用，类名同样不能被改写。
-keep class io.github.sensorlaunchguard.GuardApplication { *; }

# 钩子内部大量使用反射（SystemSensorManager / Instrumentation 重载枚举）与 lambda，
# 保留反射入口所在类的成员名，便于崩溃栈定位；不做激进优化以免改变 Hooker 行为。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
