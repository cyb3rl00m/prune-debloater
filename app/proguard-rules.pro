# Shizuku starts the shell service by class name in a separate process.
-keep class io.github.cyb3rl00m.prune.core.shell.ShellService { *; }
-keep class io.github.cyb3rl00m.prune.IShellService { *; }
-keep class io.github.cyb3rl00m.prune.IShellService$* { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.github.cyb3rl00m.prune.**$$serializer { *; }
-keepclassmembers class io.github.cyb3rl00m.prune.** { *** Companion; }
-keepclasseswithmembers class io.github.cyb3rl00m.prune.** { kotlinx.serialization.KSerializer serializer(...); }

# Shizuku.newProcess is private since API 13 and called by reflection (fallback when the user service
# cannot start), so R8 must keep it and the process class it returns.
-keep class rikka.shizuku.Shizuku { *; }
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }
