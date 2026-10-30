# 安装与使用

面向 **长安 CS75 Plus 2020**（`OS_S311-WT` · Android 4.4.2 / API 19 · `armeabi-v7a`）。请在**安全停车**后操作。

独立开源接收端，**非** Apple 认证。画面固定全屏，SurfaceView，H.264。帧率可选 15 / 30 / 45 / 60，默认 **30 fps**。

## 目标与范围

| 项目 | 说明 |
| --- | --- |
| 车型 / 固件 | CS75 Plus 2020 · `OS_S311-WT` |
| ABI | 仅 `armeabi-v7a` |
| 连接 | **无线-only**：Wi‑Fi Direct（默认）、车机热点（SoftAP）、局域网（Existing Wi‑Fi） |
| 不支持 | USB 有线 CarPlay（车机无 TUN）、其他车机、OEM 仪表 / HUD |

本车系统阉割了 TUN 虚拟网卡，无法创建有线所需的虚拟接口，因此本专版**只保留无线**。

## 安装

1. 从 [Releases](https://github.com/chansLam/75Play/releases) 下载 APK（文件名见 [`site/config.json`](../site/config.json)，当前 `75Play-v2.0.3-release.apk`）。
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

连接、设置和关于都在 75Play 壳层。投屏画面里不改偏好。三指下滑回到壳层。

| 页面 | 常见项 |
| --- | --- |
| 主页 | Wi‑Fi Direct / 车机热点 / 局域网、选手机、连接 CarPlay |
| 显示与音频 | 三列：尺寸、缩放、帧率、安全区域、CarPlay 程序栏；音乐缓冲与三条声道；画面与其它（主题、平滑视频、语言、手势、悬浮球、开机自启） |
| 关于 | 三列：应用说明、软件更新与诊断报告、系统与硬件（含 Wi‑Fi MAC） |

方向盘按键在 CarPlay 前台时由按键测试接管：语音、上一曲 / 下一曲、接听 / 挂断。音量和 SRC 留给车机。改帧率需要重连；已连接时会先确认。

## 反馈

1. **关于 → 诊断报告 → 导出**
2. 把 `.txt` 附到 [Issue](https://github.com/chansLam/75Play/issues)，并写明固件、iPhone / iOS 与步骤

**不要**写热点密码或配对材料。见 [SECURITY.md](../SECURITY.md)。构建与签名见 [BUILD.md](BUILD.md)。
