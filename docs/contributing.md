# 贡献指南

## 提交规范

```
type(scope): 中文标题

- 要点正文（中文，说明做了什么/为什么/影响面）
```

- type 词表：`feat` / `fix` / `refactor` / `docs` / `chore` / `build` / `perf` / `test`
- scope 用模块名（`home` / `network` / `client` / `wifi` / `core` / `bridge` …）
- 一个 commit 只做一件事；行为无关的清理与功能改动分开提交

## 验证纪律

提交前必须全绿：

```bash
./gradlew detekt testDebugUnitTest   # 静态检查 + JVM 单测
cargo test -p wrtctrl-core           # 改动 Rust core 后必跑
./gradlew assembleDebug              # 出包
```

- detekt 规则基线在 `config/detekt/detekt.yml`，放宽项必须带理由注释
- 双精度断言必须带第三个 delta 参数（JUnit4 无 delta 的 `assertEquals(double, double)` 恒失败）
- 协程兜底 `catch (Exception)` 必须先 rethrow `CancellationException`

## 多语言文案

- 新键同时加 `app/src/main/res/values/strings.xml`（中文）与 `values-en/strings.xml`（英文）
- 生成文件 `strings_generated.xml` 不要手改：改值走 `gen-strings.mjs` 的 OVERRIDES，废弃走 DROP_KEYS（见 [i18n.md](i18n.md)）
- 提交前跑 `node tools/check-strings.mjs`（双语键集/重复键/撞键/字面量 CJK 四断言）

## 代码约定

- 数值格式化统一走 `util/Format`（内部固定 Locale.US），不要散落 `String.format`
- UI 只显示分类后的本地化指引文案；原始错误链只进 logcat（tag=`wrtctrl`）
- 轮询类拉取失败静默保留旧值，空态有专门占位
- 列表 key 必须对「条目恒存在」全局唯一；图表配置 remember、数据走 modelProducer
- 横向多元素布局按可用宽度比例分配（weight + fillMaxWidth/aspectRatio）

## 签名与发布

Release APK 的签名配置来自环境变量（CI 注入）或仓库根 `keystore.properties`（本地，已 gitignore）：

```properties
SIGNING_STORE_PASSWORD=...
SIGNING_KEY_ALIAS=...
SIGNING_KEY_PASSWORD=...
```

- keystore 文件默认取仓库根 `release.keystore`，可用环境变量 `SIGNING_KEYSTORE_FILE` 指向其他路径；环境变量优先于 keystore.properties
- 两路都未配置时跳过签名配置，release 构建产出未签名包；debug 构建在签名可用时也使用 release 签名（保证 debug → release 可原地升级）
- 本地出包：`./gradlew assembleRelease`（产物 `app/build/outputs/apk/release/`）
- CI 发布：GitHub 仓库 secrets 配 `SIGNING_KEYSTORE_BASE64`（keystore 文件的 base64）与上述三个密码/别名变量；推送 `v*` tag 后 release workflow 自动跑测试、构建签名 APK 并创建 GitHub Release
- 版本号在 `app/build.gradle.kts` 的 `versionName` / `versionCode`，发版前更新

## 图表（Vico）

- 图表配置必须 `remember`，数据更新只走 `CartesianChartModelProducer.runTransaction`
- 轴 formatter 查不到值返回 `"--"`，禁止空串（Vico 3 会抛异常）
- 时间戳列表必须与 `runTransaction` 同帧对齐
- 多行标签的文本组件需显式 `lineCount`
