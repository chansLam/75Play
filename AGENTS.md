# AGENTS.md

给在本仓库工作的编码助手的约定。项目：**75Play CS75 Plus 2020**（v2.0.2）。

## 产品边界

- 目标车机：Android **4.4.2 / API 19**，`armeabi-v7a`；无线 **Wi‑Fi Direct 优先（默认）**，车机热点（SoftAP）与局域网（Existing Wi‑Fi）为可选方式。
- **不要**加入：BYD HUD、仪表 cluster、有线 USB CarPlay、多 OEM 车辆数据设置。
- `minSdk` / `targetSdk` 均为 **19**。不以手机高版本为目标；`SDK_INT` 分支仅保留 KitKat 侧行为或车机假 SDK 校正（见 `realSdkInt()`），不要再堆 Android 6+ 运行时权限 / FGS type / Overlay type 等手机路径。
- **Activity / 模块**
  - 三模块：`mobile → common → shared`；不要为壳/Host 再拆 Gradle 业务模块。
  - `DiPlayActivity`（壳层，默认 task）：连接、**全部可编辑设置**、诊断、关于。连接中 / 失败是壳上的弹窗。
  - `CarPlayHostActivity`（独立 `taskAffinity`）：**只承载 CarPlay 影像与会话**（画面、触控、Siri/方控、沉浸式、回壳）。**不包括**连接中 / 失败层。**禁止**在 Host 内再加设置菜单或编辑偏好的 Overlay；Host 仅从 `AirPlayPersistence` **读取**会话所需配置。
  - 不要合并壳与 Host 为一个 Activity（KitKat 任务栈 / Launcher / 黑窗风险）；多指下滑回壳走 `FloatingBall.backToDiPlay`。
- SoftAP 启用统一走 `CarHotspotEnable`（壳 / Boot / Controller 薄包装）；不要再复制一套 `setWifiApEnabled`。
- 开发者终端（About 入口 / `TerminalOverlay`）为产品能力，Release 可开（须门禁）；实现可用 jackpal 或等价轻量 shell。
- 上游致谢与移植对照 tip 只维护在 `docs/THIRD_PARTY_NOTICES.md`（各上游一条最新提交，不记明细表）。
- 文档默认中文：`README.md`、`docs/INSTALL.md`、`docs/BUILD.md`、`SECURITY.md`、`CHANGELOG.md`、`CONTRIBUTING.md`。
- 项目主页：`site/content.json` + `scripts/build_site.py`；改文案或 CHANGELOG 后执行 `python scripts/build_site.py`。

## 提交前检查

与 `.github/workflows/android.yml` 一致：

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest \
  :mobile:lintDebug \
  :mobile:assembleDebug
```

配置 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 或 gitignored 的 `local.properties`。  
**禁止**提交密钥库、密码、`DIPLAY_AUTH_ASSETS_DIR` / `.private/` 内容。  
Release 用的六个 Actions Secrets 见 `docs/BUILD.md`。

## 增加或移动设置项

可编辑项只放在壳层（`DiPlayActivity`），不放进 `CarPlayHostActivity`。壳内页面怎么分、设置放哪一页，按当前界面重组，不按固定 Fragment 分组锁死。

持久化走 `AirPlayPersistence`（会话偏好）与 `DiPlayPreferences`（壳层 BT/自动连接，以及主页应用网格的 `hidden_packages` / `app_order`，文件仍为 `home_app_list`）。  
**不要**在 `CarPlayHostActivity` 加设置菜单或编辑偏好的 Overlay。

`SettingsArchitecture.kt` 若保留，只作对照与测试，**不是** UI 渲染入口；壳层改版后让它跟上，不要反过来规定界面。  
`ConnectionSettingsSection.createHotspotModeChoice` 等 `settings/*` 工厂供测试或 Host 只读复用。

实验项标题后缀用各语言既有「（实验性）」等形式。  
若需重连才生效：文案写清；已连接时用 `confirmReconnectChange(...)`，**不得**未经同意断开会话。  
新字符串写入 `common/src/main/res/values/` 及全部 `values-xx`（设置文案宜 `settings_` 前缀）。
