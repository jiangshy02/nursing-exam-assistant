# Add project specific ProGuard rules here.
-keepattributes Signature
-keepattributes *Annotation*

# Room
-keep class com.xiaoniu.nursing.model.** { *; }

# Gson
-keepattributes SerializedName
-keep class com.xiaoniu.nursing.MainActivity$ImportQuestion { *; }
-keep class com.xiaoniu.nursing.MainActivity$ImportOption { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
