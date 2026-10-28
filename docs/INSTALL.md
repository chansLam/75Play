# 安装与使用

面向 **长安 CS75 Plus 2020**（`OS_S311-WT` · Android 4.4.2 / API 19 · `armeabi-v7a`）。请在**安全停车**后操作。

独立开源接收端，**非** Apple 认证。默认 **30 fps**；HEVC 等实验项默认关闭。

## 目标与范围

| 项目 | 说明 |
| --- | --- |
| 车型 / 固件 | CS75 Plus 2020 · `OS_S311-WT` |
| ABI | 仅 `armeabi-v7a` |
| 连接 | **无线-only**：Wi‑Fi Direct（默认）、车机热点（SoftAP）、局域网（Existing Wi‑Fi） |
| 不支持 | USB 有线 CarPlay（车机无 TUN）、其他车机、OEM 仪表 / HUD |

本车系统阉割了 TUN 虚拟网卡，无法创建有线所需的虚拟接口，因此本专版**只保留无线**。

## 安装

1. 从 [Releases](https://github.com/chansLam/75Play/releases) 下载 APK（文件名见 [`site/config.json`](../site/config.json)，当前 `75Play-v2.0.3-beta-release.apk`）。
2. 若已安装**其他签名**的 75Play / 同类包，先彻底卸载。
3. 用车机文件管理器安装，或 `adb install -r <apk>`。**不要**装到 iPhone。
4. 打开 75Play。KitKat 上车机权限多为安装时授予。关掉其他投屏 / 手机互联应用。

项目主页（多语言下载页）：<https://chansLam.github.io/75Play/>

## 连接

打开 75Play 主页，在连接中心切换方式。iPhone **保持蓝牙开启**（播音乐时车机蓝牙可设为「仅通话」）。

| 方式 | 你需要做的 |
| --- | --- |
| **Wi‑Fi Direct**（默认） | 切换到此方式会**自动打开车机 Wi‑Fi**；点 **连接 CarPlay**，在 iPhone 上允许即可 |
| **车机热点**（SoftAP） | 切换到此方式会**自动配置并启动车机热点**；点 **连接 CarPlay**，在 iPhone 上允许即可 |
| **局域网** | 选对外部 Wi‑Fi 并输入密码（开放网络可留空）；**连上 Wi‑Fi 后会自动连接 CarPlay**，在 iPhone 上允许即可 |

要点：

- Wi‑Fi Direct / 车机热点：切换方式后一般**只需再点一次「连接 CarPlay」**。
- 局域网：选网填密后，Wi‑Fi 连上即会自动发起 CarPlay，无需再单独点连接（除非中途失败需重试）。

**Wi‑Fi Direct 与车机热点已在 2020 款 CS75 Plus（OS_S311-WT）实车验证。** 局域网未作本车交付验证。

## 设置速览

在 CarPlay 内向下滑动打开设置（手指数见 **设置 → 车辆**），或回到 75Play 主页进入设置。

| 分类 | 常见项 |
| --- | --- |
| 连接 | Wi‑Fi Direct / 车机热点 / 局域网、选手机、车机蓝牙地址、开机启动 |
| 显示 | 尺寸、分辨率、帧率、昼夜、系统栏；Root 全局隐藏导航栏（需 su） |
| 音频 / 导航 | 音轨、缓冲、定位上报 |
| 车辆 | 驾驶侧、Siri 键、悬浮小白点、应用列表、手势 |
| 诊断 / 高级 | 诊断报告；安卓终端（实验性）；HEVC 等实验项 |

设置按**司机目标**分组；实验项在「高级」，标题带「实验性」后缀。改动若需重连，文案会说明；已连接时会先确认「保存并重新连接」。

## 反馈

1. **设置 → 诊断 → 保存诊断报告**
2. 把 `.txt` 附到 [Issue](https://github.com/chansLam/75Play/issues)，并写明固件、iPhone / iOS 与步骤

**不要**写热点密码或配对材料。见 [SECURITY.md](../SECURITY.md)。构建与签名见 [BUILD.md](BUILD.md)。
