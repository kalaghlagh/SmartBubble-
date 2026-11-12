# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------------------------
# Release builds currently have minification OFF (isMinifyEnabled = false in build.gradle.kts).
# The rules below are what R8 needs if it is turned on; enable it only after a release build
# has been verified on a device.
# ---------------------------------------------------------------------------------------------

# Tesseract4Android: classes and members are called from native code (JNI).
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }

# Room entities / DAOs are referenced by generated code.
-keep class com.example.data.model.** { *; }

# Keep line numbers for readable crash stack traces.
-keepattributes SourceFile,LineNumberTable
