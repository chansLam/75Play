# 致谢与许可

## 上游

本专版的 KitKat / SoftAP 适配感谢下列上游。表中只记**各仓库一条最新 tip**（移植对照），不展开明细。

| 上游 | 说明 | 跟踪分支 | 最新提交（移植对照） |
| --- | --- | --- | --- |
| [DiPlay](https://github.com/shihabal3amri/DiPlay) | 主上游 / 接收端主体 | `main` | [`4487c689`](https://github.com/shihabal3amri/DiPlay/commit/4487c689) · 2026-10-22（其后 compare 约 159 个提交已审。已移植 `411f0b6063` bplist 上限、`a83284c500` 控制包上限、`aeb1d73356` 数据口只接受控制对端、`0d73aed31a`/`7f40cfabaa` 缓冲音乐时钟对准稳定水位。集群、HUD、有线 USB、车道引导、轮速未移植。GitHub 限流未取到新 tip 哈希，下次从 `main` 最新提交查增量） |
| [diplay-changan](https://github.com/c80361619/diplay-changan) | 长安 CS75（2018）Android 4.4.2 / i.MX6 | `kitkat` | [`959279ea21`](https://github.com/c80361619/diplay-changan/commit/959279ea21) · 2026-10-16（**上次移植点**；本批已移植 `51f03374` 无线 IPv4 优先/协议族切换，解决反复重连） |
| [DiPlay-Legacy-Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android) | Legacy / KitKat 兼容 | `main` | [`46d0047f`](https://github.com/programmerguohuajing/DiPlay-Legacy-Android/commit/46d0047f) · 2026-10-23（v0.2.90。v0.2.93–v0.3.5 [`0dc0b91f`](https://github.com/programmerguohuajing/DiPlay-Legacy-Android/commit/0dc0b91fbd193a1de03f274a3f80316381bb9ed3) 已审：移植了解码器连续输出停滞后重建、过期帧在仍出画时只丢该帧、画面流断开后多等一轮再拆会话。有线 USB、root hostapd、海思硬解、亿咖通热点、连上后断蓝牙未移植。本仓库热点只上报一个地址且默认 IPv4，没有上游那条干等 IPv6 的循环。v0.2.91–v0.2.92 那一页因限流未打开，跟踪哈希先留在这里） |

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
