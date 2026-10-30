# B1 只放保底规则；各模块的 keep 规则在 release 收尾（B5）时补。
# 保留反射用到的契约类
-keep class cn.olfact.core.api.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
