# 仅 debug 构建附加（见 build.gradle.kts debug 块注释）：裁剪但不混淆——
# 崩溃堆栈（last_crash.txt）保持可读类名与行号
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
