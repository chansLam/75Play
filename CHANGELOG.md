# 更新日志

本仓库基于 [DiPlay-Legacy-Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android) 修改优化，面向长安 CS75 Plus 2020 车机（`OS_S311-WT` · Android 4.4.2 / API 19 · `armeabi-v7a`）。

格式参考 [Keep a Changelog](https://keepachangelog.com/)。版本号遵循语义化版本。

---

## 2.0.0

面向长安 CS75 Plus 2020 车机（`OS_S311-WT` · Android 4.4.2 / API 19 · `armeabi-v7a`）发布。

### 新增

- **玻璃双主题 UI**：主页与设置重新设计、按司机目标分组排版。
- **无线 CarPlay**：内置 MFi 认证密钥；Wi‑Fi Direct / 车机热点（SoftAP）/ 局域网（Existing Wi‑Fi）；无需越狱、转接盒或账号。因车机无 TUN，已移除 USB 有线。
- **一键连接**：简化连接流程。
- **全屏沉浸**：CarPlay 铺满宽屏，默认 30 fps；CarPlay 内打开 / 管理车机应用；悬浮小白点返回。
- **多语言**：简 / 繁中文、英文、阿拉伯语、西班牙语、俄语、乌克兰语。
- **安卓终端**（实验性）、系统设置入口（`*#518200#*`）。

### 修复

- **启动提速**：减少黑屏 / 闪启动页；品牌页首帧与背景图异步解码。
- **电话与导航无声**。
- **更新下载**：上游 TLS 移植；IPv4 优先、重试与 DNS 排序、镜像域名白名单。
- **音频**：上游修复移植并本地增强（回声消除双保险，适配 CS75）。
- **稳定性**：启动失败原因显示；精简 ROM 缺 `PATH` / `HOME` 的崩溃修复。

