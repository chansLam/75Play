# 安装与使用

面向 **长安 CS75 Plus 2020**（`OS_S311-WT` · Android 4.4.2 / API 19 · `armeabi-v7a`）。请在**安全停车**后操作。当前安装包为 **v2.0.5-beta**。

独立开源接收端，**非** Apple 认证。画面固定全屏，SurfaceView，H.264。帧率可选 15 / 30 / 45 / 60，默认 **30 fps**。

## 目标与范围

| 项目 | 说明 |
| --- | --- |
| 车型 / 固件 | CS75 Plus 2020 · `OS_S311-WT` |
| ABI | 仅 `armeabi-v7a` |
| 连接 | 无线默认：Wi‑Fi Direct、车机热点（SoftAP）、局域网（Existing Wi‑Fi）。**有线 USB 为 2.0.5-beta** |
| 车机配合 | 导航转向发到原厂仪表；顶部温度栏（显示设置里可关） |
| 不支持 | 内核 TUN、BYD HUD、其他车机 |

无线和有线都不创建车机 TUN。有线在应用内走 USB NCM 与 lwIP。

## 安装

1. 从 [Releases](https://github.com/chansLam/75Play/releases) 下载 APK（文件名见 [`site/config.json`](../site/config.json)，当前 `75Play-v2.0.5-beta-release.apk`）。这是预发布，应用内更新不会推送。
2. 若已安装**其他签名**的 75Play / 同类包，先彻底卸载。相同签名可覆盖安装。
3. 用车机文件管理器安装，或 `adb install -r <apk>`。**不要**装到 iPhone。
4. 打开 75Play。KitKat 上车机权限多为安装时授予。关掉其他投屏 / 手机互联应用。

项目主页（多语言下载页）：<https://chansLam.github.io/75Play/>

## 连接

打开 75Play 主页。iPhone **保持蓝牙开启**（播音乐时车机蓝牙可设为「仅通话」）。

| 方式 | 你需要做的 |
| --- | --- |
| **Wi‑Fi Direct**（默认） | 切换到此方式会**自动打开车机 Wi‑Fi**；点 **连接 CarPlay**，在 iPhone 上允许即可 |
| **车机热点**（SoftAP） | 切换到此方式会**自动配置并启动车机热点**；点 **连接 CarPlay**，在 iPhone 上允许即可 |
| **局域网** | 选对外部 Wi‑Fi 并输入密码（开放网络可留空）；**连上 Wi‑Fi 后会自动连接 CarPlay**，在 iPhone 上允许即可 |
| **有线 USB**（beta） | 用数据线把 iPhone 接到车机**数据** USB 口，再点 **连接 CarPlay**。若 USB 上已有 iPhone，会自动改走有线。弹出 USB 权限时请允许。原车 iPod 占用接口时，先退出再重插 |

要点：

- Wi‑Fi Direct / 车机热点：切换方式后一般**只需再点一次「连接 CarPlay」**。
- 局域网：选网填密后，Wi‑Fi 连上即会自动发起 CarPlay，无需再单独点连接（除非中途失败需重试）。
- 有线：主页没有单独的 USB 分页。插着线再点连接即可；无线三种方式仍是默认。

**Wi‑Fi Direct 与车机热点已在 2020 款 CS75 Plus（OS_S311-WT）实车验证。** 局域网与有线 USB 以本测试版上车结果为准。

## 设置速览

连接、设置和关于都在 75Play 壳层。投屏画面里不改偏好。三指下滑回到壳层。连接过程显示准备、网络、手机、握手、画面。

| 页面 | 常见项 |
| --- | --- |
| 主页 | Wi‑Fi Direct / 车机热点 / 局域网、选手机、连接 CarPlay；应用列表随图标收紧 |
| 显示与音频 | 三列：尺寸、缩放、帧率、安全区域、CarPlay 程序栏；音乐缓冲与三条声道；画面与其它（主题、平滑视频、顶部温度栏、语言、手势、悬浮球、开机自启） |
| 关于 | 三列：应用说明、软件更新与诊断报告、系统与硬件（含 Wi‑Fi MAC） |

方向盘按键在 CarPlay 前台时由按键测试接管：语音、上一曲 / 下一曲、接听 / 挂断。音量和 SRC 留给车机。改帧率需要重连；已连接时会先确认。

CarPlay 导航中的转向会发给原厂仪表。顶部温度栏默认开启，显示室外、车内和空调设定温度。

## 反馈

1. **关于 → 诊断报告 → 导出**
2. 把 `.txt` 附到 [Issue](https://github.com/chansLam/75Play/issues)，并写明固件、iPhone / iOS、75Play 版本与步骤

**不要**写热点密码或配对材料。见 [SECURITY.md](../SECURITY.md)。构建与签名见 [BUILD.md](BUILD.md)。
