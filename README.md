# WrtCtrl

OpenWrt 路由器的 Android 原生管理客户端。Rust 核心 + Jetpack Compose（Material 3 动态主题）。

> 项目处于重构开发期（1.0.0），功能按里程碑逐步落地。

## 特性（规划）

- 仪表盘：CPU / 内存 / 负载 / 实时带宽 / 连接数 / 存储
- 网络与无线、终端列表、流量统计
- 10 个 luci 插件的 schema 驱动原生编辑（防火墙 / WiFi / PassWall2 等）
- 运维工具：诊断 / 系统日志 / 连接跟踪 / 路由表 / 进程 / 启动项 / 重启
- 远程安全写路径：`uci.apply{rollback}` + `uci.confirm` 兜底，失联自愈
- 中英双语，Material You 风格动态主题色

## 架构

```
Kotlin/Compose (UI)  ←JNI→  Rust core (通信/会话/UCI 规则)
```

- `crates/wrtctrl-core`：rpcd ubus JSON-RPC 客户端、会话管理、UCI 安全写路径（纯 Rust，可宿主机单测）
- `crates/wrtctrl-jni`：JNI 导出层（cdylib）
- `app/`：Android 应用（Compose UI、设备仓储、schema 驱动编辑器）

## 构建

环境要求：JDK 17、Android SDK（API 35）、NDK 27.2.12479018、Rust stable（含 `aarch64-linux-android` / `x86_64-linux-android` 目标）、cargo-ndk。

```bash
# Rust 单测（宿主机直跑）
cargo test -p wrtctrl-core

# 生成多语言资源（从 locale JSON 转换）
node tools/gen-strings.mjs <locale目录> app/src/main/res

# Android 构建（自动先经 cargo-ndk 构建 Rust）
./gradlew assembleDebug
```

## License

GPL-3.0
