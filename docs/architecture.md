# 架构

## 总览

```
┌─────────────────────────────┐
│  app（Kotlin + Compose）      │  单 Activity，页面为组合级分支
│  ui/screen · ui/component    │  ViewModel 持有状态与轮询
├─────────────────────────────┤
│  bridge/WrtCore（JNI 门面）    │  每个原生导出一个 suspend 包装
├─────────────────────────────┤
│  wrtctrl-jni（Rust）          │  导出层：JSON 信封 + panic 防线
├─────────────────────────────┤
│  wrtctrl-core（Rust）         │  会话 / ubus / UCI 写路径 / 解析
└──────────────┬──────────────┘
               │ HTTP JSON-RPC（reqwest + rustls）
        rpcd ubus（OpenWrt 路由器）
```

## 关键设计

### 单 Activity 组合级分支

不用 Navigation 库：底部五 Tab 与覆盖页（编辑器、工具页）都是组合级互斥分支，经 `SaveableStateHolder` 按 key 保存恢复。页面状态三分层：

- 页面级瞬态（Tab 索引、搜索词、展开态）→ `rememberSaveable`
- 组合级分支 → `SaveableStateProvider` 包裹
- 数据/轮询/写操作状态 → ViewModel（跨 Activity 重建存活）

### 轮询门控（PollingGate）

数据页 3s、工具页 5s 可选轮询。门控 = 组合可见（底栏选中才组合）× 生命周期（ON_RESUME/ON_PAUSE），双条件都满足才活跃；循环 `delay` 醒来复查门控。页面不可见或息屏后循环挂起在 `first{it}` 上，零轮询；进程进入缓存态后被系统冻结，网络活动归零。

### UCI 安全写路径

所有 uci 写操作收敛到 core 的 `uci_commit` 单一出口：

```
ensure_session（探针失效→静默重登）
  → uci apply {rollback:true, timeout:120}
  → uci confirm
```

- `commit_lock` 串行化：rpcd 的 rollback/confirm 是全局单槽，并发提交会互相吞噬 confirm
- 写前强一致回读：本地集合为空可能是拉取失败而非真无数据，盲写会产生重复 section
- 失败自动回滚：confirm 未在 120s 内到达则设备侧自动还原

### 传输安全

- 会话流量与探活流量分客户端管理；重定向全局禁用（防 307 重发登录体）；禁用系统代理
- HTTPS 模式 TOFU 证书指纹校验：首连一次性 TLS 握手捕获叶证书 SHA-256 随设备持久化，此后每次登录比对，不一致即中止（`crates/wrtctrl-core/src/tls_pin.rs`）
- 凭据仅内存明文，落盘经 Android Keystore（AES-256-GCM）加密；`allowBackup=false` 不参与云备份

### 桥接约定

- 每个原生导出对应一个 Kotlin suspend 包装；JSON 信封 `{ok, data | error{code, message, ubus}}`
- 阻塞式导出 + Kotlin `Dispatchers.IO`（无推送场景，免去线程附加/全局引用的崩溃源）
- JNI 边界 `catch_unwind` 防 panic 穿越，panic 转错误信封并写 logcat

### 插件 schema 编辑器

`viewmodel/plugin/` 提供声明式字段描述（类型/候选/依赖/校验/只读/自定义值），一个通用编辑器渲染所有插件页；候选值走 core 的 candidates 通道（hosthints/接口/zone/USB/iwinfo 实时枚举）。

## 模块索引

| 路径 | 职责 |
|---|---|
| `app/.../ui/screen` | 每页一个文件（Home/Network/Client/Statistics/Apps/工具族） |
| `app/.../ui/component` | 共享组件（PollingGate/GroupHeader/StatusBadge/图表等） |
| `app/.../viewmodel` | 页面状态与轮询；`*Parsers` 为纯解析（JVM 单测覆盖） |
| `app/.../bridge` | JNI suspend 门面 |
| `app/.../data` | 设备仓储（DataStore+Keystore）、偏好 |
| `app/.../net` | 私网目标网络绑定（NetBinder）与本地网络权限门 |
| `crates/wrtctrl-core` | 会话/ubus/UCI 写路径/无线/诊断/日志/探活/证书指纹 |
| `crates/wrtctrl-jni` | JNI 导出层（cdylib） |
| `tools/` | 多语言资源生成与校验脚本 |
