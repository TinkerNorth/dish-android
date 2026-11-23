# Dish Android: R8/ProGuard rules.
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

# Native function exports: every `external fun` in the core.jni *Native objects
# (SessionNative, SlotReportNative, PhysicalSlotNative, UsbDirectNative,
# ModelTableNative, InstrumentationNative) resolves to
# Java_com_tinkernorth_dish_core_jni_<Object>_<name>. Keep the classes and all
# native methods.
-keep class com.tinkernorth.dish.core.jni.*Native { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.tinkernorth.dish.core.jni.*Native {
    native <methods>;
}

# UsbDirectNative.attachUsbDevice reads the claim's fields by name through
# GetFieldID; the names are the contract, so neither may go nor be renamed.
-keepclassmembers class com.tinkernorth.dish.core.jni.UsbInterfaceClaim {
    <fields>;
}

# Java→native callback target for BT-bound physical-gamepad reports. Called
# from the native BT dispatch worker thread via CallStaticVoidMethod.
-keep class com.tinkernorth.dish.hotpath.input.BluetoothGamepadBridge { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.tinkernorth.dish.hotpath.input.BluetoothGamepadBridge {
    native <methods>;
}

# Java→native callback target for satellite → phone rumble. Called from the
# Kotlin Dispatchers.IO thread that drives SessionNative.receiveAck.
-keep class com.tinkernorth.dish.hotpath.input.RumbleBridge { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.tinkernorth.dish.hotpath.input.RumbleBridge {
    native <methods>;
}

# The other upcall targets: each method native code resolves by name in its bridge's
# nativeInstall (satellite_jni.cpp, resolveStaticMethod). Nothing in Kotlin calls them, so without
# these R8 removes them and the bridge goes silent. NativeLookupKeepRulesTest fails on a lookup
# with no rule here, and scripts/check_jni_keeps.py checks the minified APK's dex.
-keep class com.tinkernorth.dish.hotpath.input.FeedbackBridge {
    public static void dispatchLightbar(int, int, int, int, int);
    public static void dispatchTriggerEffects(int, int, byte[]);
    public static void dispatchPlayerLeds(int, int, int);
    public static void dispatchMicLed(int, int, int);
}
-keep class com.tinkernorth.dish.hotpath.input.MoonlightGamepadBridge {
    public static void dispatchReport(java.lang.String, int, int, int, int, int, int, int, int);
    public static void dispatchMotion(java.lang.String, int, int, int, int, int, int, int, int);
    public static void dispatchTouch(java.lang.String, int, boolean, int, int, int, boolean, int, int, int, boolean);
}
-keep class com.tinkernorth.dish.hotpath.input.SteamBleBridge {
    public static boolean write(int, int, byte[]);
}
-keep class com.tinkernorth.dish.hotpath.input.MicMuteBridge {
    public static void dispatchPadMicMute(int, boolean);
}
-keep class com.tinkernorth.dish.hotpath.audio.SpeakerAudioBridge {
    public static void dispatchAudioFrame(int, int, int, short[], boolean);
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
