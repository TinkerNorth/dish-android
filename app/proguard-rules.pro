# Dish Android — R8/ProGuard rules.
#
# R8 is enabled on release (isMinifyEnabled = true). Without these keeps the
# JNI bridges silently get renamed and the first rumble or BT report on a
# release build crashes with NoSuchMethodError / NoSuchFieldError.

# Preserve line numbers in stack traces shipped via crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── JNI bridges ─────────────────────────────────────────────────────────────
# satellite_jni.cpp resolves these classes + static methods by name via
# FindClass / GetStaticMethodID. Renaming the class, its package, the method
# name, or the parameter signature breaks the native bind at runtime.

# Native function exports: every `external fun` in SatelliteNative resolves to
# Java_com_tinkernorth_dish_core_jni_SatelliteNative_<name>. Keep the class and
# all native methods.
-keep class com.tinkernorth.dish.core.jni.SatelliteNative { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.tinkernorth.dish.core.jni.SatelliteNative {
    native <methods>;
}

# Java→native callback target for BT-bound physical-gamepad reports. Called
# from the native BT dispatch worker thread via CallStaticVoidMethod.
-keep class com.tinkernorth.dish.hotpath.input.BluetoothGamepadBridge { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.tinkernorth.dish.hotpath.input.BluetoothGamepadBridge {
    native <methods>;
}

# Java→native callback target for satellite → phone rumble. Called from the
# Kotlin Dispatchers.IO thread that drives SatelliteNative.receiveAck.
-keep class com.tinkernorth.dish.hotpath.input.RumbleBridge { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.tinkernorth.dish.hotpath.input.RumbleBridge {
    native <methods>;
}

# Catch-all: any class anywhere that declares a native method must keep its
# class name + that method's signature. Belt-and-braces against future
# additions outside the explicit list above.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ── kotlinx.serialization ───────────────────────────────────────────────────
# The Gradle plugin generates serializer classes that R8 doesn't see referenced
# directly. The plugin ships consumer rules but they only cover the runtime;
# our @Serializable model classes still need keep clauses so the generated
# `$serializer` companions resolve at runtime.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    *** Companion;
}

-keepclassmembers @kotlinx.serialization.Serializable class * {
    static **$* *;
}

-keepclasseswithmembers class **$$serializer {
    static **$$serializer INSTANCE;
}
