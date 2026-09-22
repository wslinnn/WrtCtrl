# Changelog

## 1.0.0

### 已有功能

- 仪表盘：CPU / 内存 / 温度环、实时带宽图（点击查值、断档截断）、NAT 会话计数、存储挂载点；卡片拖拽排序 / 显隐 / 折叠，配置持久化
- 设备门控：多设备管理、并行探活（ICMP 优先 + HTTP 兜底）、表单校验与分类错误指引、冷启动自动重连
- 网络页：接口 / 设备 / 无线三视角；radio 启停与重启；无线设置编辑器（radio/SSID，MTK 固件扩展字段组，iwinfo 实时枚举）；Wi-Fi 二维码分享
- 客户端页：无线终端 + DHCPv4/v6 双栈租约、OUI 厂商识别与品牌图标、防火墙拉黑、静态租约绑定
- 统计页：带宽 / 负载双视图，窗口统计与点击查值
- 工具页：网络诊断（ping / traceroute / nslookup）、系统日志、NAT 会话、进程、路由表、启动项、重启
- 插件页：firewall（区域/转发/重定向/规则/NAT/ipset）、samba4、upnp、wolultra、autoreboot、arpbind、cifs-mount、usb-printer
- 安全：uci 写路径统一 rollback + confirm、TOFU 证书指纹校验、Keystore 加密存储、禁用云备份
- 体验：中英双语、深浅色三态、Material 3 动态色、页面状态保留（回退/重建回到原位）
- 性能：数据页 3s 轮询可见性门控（后台/息屏零轮询）、恒定数据缓存、并行拉取、大 payload 解析在 IO 线程

### 工程

- Rust workspace（core + jni）经 cargo-ndk 交叉编译，release profile 体积优化（opt-level=z + lto + strip）
- debug 构建同开 R8（-dontobfuscate 保崩溃堆栈可读），APK ~17.5MB
- locale JSON → strings.xml 生成管线（gen-strings）+ 四断言校验脚本（check-strings）
- detekt 规则基线 + JVM 单测（125）+ Rust 单测（60+）+ CI 构建
