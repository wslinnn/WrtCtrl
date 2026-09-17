# Changelog

## 1.0.0

### 进行中

- 工程骨架：Rust workspace（core + jni）、cargo-ndk 构建管线、Compose Material 3 主题（种子色动态色板）、五 Tab 导航壳、locale → strings.xml 转换工具（26 个活 namespace，插值语法转换与两语言对齐断言）
- 安全设计：JNI 边界 panic 防线（catch_unwind + logcat hook）与演练入口
