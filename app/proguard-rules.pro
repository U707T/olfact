# ---- 通用 ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- WebView 与注入桥 ----
# 供 WebView 反射调用的方法必须保留（document-start 脚本、GM_* 桥）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class * extends android.webkit.WebViewClient { *; }
-keepclassmembers class * extends android.webkit.WebChromeClient { *; }

# ---- 数据类（JSON 字段名要稳定，备份/规则导入导出依赖） ----
-keep class cn.olfact.core.api.** { *; }

# ---- ZXing ----
-keep class com.google.zxing.** { *; }

# ---- 反射/回调 ----
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}
