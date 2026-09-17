# JNI：native 方法按名称绑定到 wrtctrl-jni 导出符号，整个 bridge 包不可混淆
-keep class dev.wrtctrl.bridge.** { *; }
