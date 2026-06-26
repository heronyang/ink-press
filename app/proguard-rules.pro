# Keep the Jsoup library intact during R8/ProGuard minification
-keep class org.jsoup.** { *; }
-dontwarn org.jspecify.**
-dontwarn javax.annotation.**

# Allow optimization and obfuscation for android and kotlin runtime libraries
-keepattributes Signature, InnerClasses, EnclosingMethod, AnnotationDefault, *Annotation*, SourceFile, LineNumberTable

# Jetpack Compose specific Keep Rules
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *** *(...);
}
