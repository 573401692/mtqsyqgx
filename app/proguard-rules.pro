# ========== Xposed 模块必须保留的类和方法 ==========
# Hook 入口类 - 不能被混淆或移除
-keep class com.mtqsyqgx.app.HookInit { *; }
-keep class com.mtqsyqgx.app.HookInit$* { *; }

# 保留 Xposed 相关接口实现
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keep class * implements de.robv.android.xposed.IXposedHookZygoteInit { *; }
-keep class * implements de.robv.android.xposed.IXposedHookInitPackageResources { *; }

# 保留 Xposed Bridge API（防止混淆库引用）
-keep class de.robv.android.xposed.** { *; }
-keep class io.github.libxposed.** { *; }

# 所有使用了 @JavascriptInterface 的方法不能被移除
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 保留所有 Activity 子类（SecureActivity 基类体系）
-keep class * extends android.app.Activity { *; }
-keep class * extends android.app.Fragment { *; }

# 保留 Serializable 类
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# 保留枚举
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留 Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# 保留 R 类和 BuildConfig
-keep class **.R$* { *; }
-keep class **.BuildConfig { *; }

# 保留行号信息（崩溃日志还原用）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# libxposed service 引用的注解类（compileOnly api 未打包，R8 无需解析）
-dontwarn io.github.libxposed.annotation.**
-dontwarn io.github.libxposed.api.**
