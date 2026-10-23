# 致谢与许可

## 上游

本专版的 KitKat / SoftAP 适配感谢下列上游。表中只记**各仓库一条最新 tip**（移植对照），不展开明细。

| 上游 | 说明 | 跟踪分支 | 最新提交（移植对照） |
| --- | --- | --- | --- |
| [DiPlay](https://github.com/shihabal3amri/DiPlay) | 主上游 / 接收端主体 | `main` | [`4487c689`](https://github.com/shihabal3amri/DiPlay/commit/4487c689) · 2026-10-22（**最新 tip**；本批仅更新跟踪哈希，新增全部为网站下载量统计、发布说明、BYD/吉利仪表集群地图，均不适用 SoftAP 专版，无可移植内容。此前已移植至 `b309eae9`，下次从该提交之后查增量） |
| [diplay-changan](https://github.com/c80361619/diplay-changan) | 长安 CS75（2018）Android 4.4.2 / i.MX6 | `kitkat` | [`959279ea21`](https://github.com/c80361619/diplay-changan/commit/959279ea21) · 2026-10-16（**上次移植点**；本批已移植 `51f03374` 无线 IPv4 优先/协议族切换，解决反复重连） |
| [DiPlay-Legacy-Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android) | Legacy / KitKat 兼容 | `main` | [`46d0047f`](https://github.com/programmerguohuajing/DiPlay-Legacy-Android/commit/46d0047f) · 2026-10-23（**最新 tip = v0.2.90**；本批仅更新跟踪哈希，新增为自定义图标大小限制、USB 权限诊断日志，大部分不适用 SoftAP 专版，无可移植内容。此前已移植至 `054c6b58` 平台 AAC 解码器退化自动切换软解（Issue #170）；下次从该提交之后查增量） |

目标机型为 CS75 Plus **2020** SoftAP，并非上述仓库的直接 fork；合入为改写移植（port）。建议 commit：`merge(changan|legacy): port <hash> …`。

移植前：`git fetch` 上游，与表中 tip 对照；有更新则审完后把「最新提交」改成新 tip。不引入有线 USB、TUN、BYD HUD / cluster。

本仓库自研能力（悬浮小白点、车机应用列表、全屏沉浸、SoftAP 专版裁剪、Root 全局隐藏导航栏、安卓终端、玻璃 UI 等）不记入上表。

## 谱系与依赖

- 接收端谱系：[xcertplay](https://github.com/shilapi/xcertplay) 等 — 根目录 `LICENSE` 为 **GPL-3.0**。
- 界面：`DiPlayActivity` 等衍生自 [DiAuto](https://github.com/shihabal3amri/DiAuto)（**AGPL-3.0**，见 [`docs/licenses/DiAuto-AGPL-3.0.txt`](licenses/DiAuto-AGPL-3.0.txt)）。
- 更早上游曾致谢 LIVI、Showcase 等；源码署名保留。
- 依赖摘要：AndroidX / Kotlin（Apache 2.0）、Bouncy Castle、JmDNS（Apache 2.0）、SLF4J（MIT）；可选 SpeexDSP、软件 libopus（BSD）。详见 [`docs/licenses/dependencies/`](licenses/dependencies/)。
- CarPlay 图标归 Apple Inc.；使用不代表认证。
- offline-MFi 不随公开 Git 分发，见 [SECURITY.md](../SECURITY.md)。
