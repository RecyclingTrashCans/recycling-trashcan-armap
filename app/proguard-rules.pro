# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /opt/android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:
-keep class com.google.android.gms.location.** { *; }
-keepclassmembernames class com.google.android.gms.location.* { *; }
-keep class com.google.android.gms.common.** { *; }
-keepclassmembernames class com.google.android.gms.common.* { *; }
-keep class com.google.android.gms.tasks.** { *; }
-keepclassmembernames class com.google.android.gms.tasks.* { *; }
-keep class com.google.ar.core.** { *; }
-keepclassmembernames class com.google.ar.core.* { *; }
