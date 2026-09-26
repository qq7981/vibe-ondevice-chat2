# 默认不混淆。接入 MNN 后如果开启 minify，需要保留 JNI 桥接类：
# -keep class com.alibaba.mnn.** { *; }
# -keepclasseswithmembernames class * { native <methods>; }
