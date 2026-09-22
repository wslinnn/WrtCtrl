# WrtCtrl

OpenWrt 路由器的 Android 原生管理客户端。Kotlin + Jetpack Compose UI，业务逻辑在 Rust core（`crates/wrtctrl-core`），经 JNI 桥接，通过 rpcd 的 ubus 通道与路由器通信。

> 当前版本 1.0.0，变更见 [CHANGELOG.md](CHANGELOG.md)。

## 特性

- **仪表盘**：CPU / 内存 / 温度环、实时带宽图（点击查值）、NAT 会话、存储挂载点，卡片可排序/显隐/折叠
- **网络**：接口 / 设备 / 无线三视角；radio 与 SSID 在线编辑（含 MTK 固件扩展字段，枚举值来自 iwinfo 实时查询）；Wi-Fi 二维码分享
- **客户端**：无线终端 + DHCPv4/v6 双栈租约，OUI 厂商识别与品牌图标，防火墙拉黑与静态租约绑定
- **统计**：带宽 / 负载曲线与窗口统计
- **工具**：网络诊断（ping / traceroute / nslookup）、系统日志、NAT 会话、进程、路由表、启动项、重启
- **插件**：firewall / samba4 / upnp / wolultra / autoreboot / arpbind / cifs-mount / usb-printer 的 schema 驱动原生编辑，功能对齐 LuCI 对应页
- **安全写路径**：uci 提交统一走 `apply{rollback}` + `confirm`，commit 串行化，会话过期自动预检重登，失败自动回滚
- **传输安全**：HTTPS 模式 TOFU 证书指纹校验（首连记录、换证告警）；密码经 Android Keystore（AES-256-GCM）加密存储，不参与云备份
- 中英双语，Material 3 动态色主题，深浅色三态切换

## 架构

```
app (Kotlin/Compose)  ←JNI→  wrtctrl-jni  →  wrtctrl-core (Rust)  →  rpcd ubus (HTTP/JSON-RPC)
```

分层说明与关键设计见 [docs/architecture.md](docs/architecture.md)。

## 构建

环境要求：JDK 17、Android SDK（API 35）、NDK 27.2.12479018、Rust stable（含 `aarch64-linux-android` / `x86_64-linux-android` 目标）、cargo-ndk。

```bash
# Rust 单测（宿主机直跑）
cargo test -p wrtctrl-core

# Android 构建（preBuild 自动经 cargo-ndk 构建 Rust）
./gradlew assembleDebug

# 静态检查 + JVM 单测
./gradlew detekt testDebugUnitTest
```

多语言资源由 `tools/gen-strings.mjs` 从 locale JSON 生成，机制见 [docs/i18n.md](docs/i18n.md)。

## 贡献

提交规范与验证纪律见 [docs/contributing.md](docs/contributing.md)。

## License

[GPL-3.0](LICENSE)，第三方组件与移植算法的许可说明见 [NOTICE.md](NOTICE.md)。
