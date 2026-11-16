# Consumer proguard rules for :core module
-keep class kotlin.coroutines.jvm.internal.SpillingKt { *; }
-keep class com.dokar.quickjs.** { *; }
-keep class com.euthopiar.core.sync.supabase.** { *; }
-keepclassmembers class com.euthopiar.core.sync.supabase.** { *; }
