# 安全与隐私

## 密钥与签名

- 公开发布的 APK **可以**捆绑实验性 accessory 身份；持有 APK 即可提取。本地重新编译或清理 Git 历史**无法**收回已分发密钥。
- Git 树与源码归档**不得**包含：签名密钥库、密码、`offline-mfi/*`、`DIPLAY_AUTH_ASSETS_DIR` / `.private/` 内容。CI 会拒绝凭据容器。
- APK 签名密钥与 accessory 身份分离，只放在 GitHub Actions Secrets 或本机 `.private/`。构建步骤见 [docs/BUILD.md](docs/BUILD.md)。
- 某一版 iOS 接受实验身份 ≠ Apple 认证。

## 隐私与诊断

- **无账号、无远程认证、无自动上传。** 车机与 iPhone 经 Wi‑Fi Direct、车机热点（SoftAP）或同一局域网直连。
- 偏好与配对材料在应用私有存储；不进 Android 备份；卸载即清除。导出到下载目录的文件需自行删除。
- 诊断报告仅在你主动导出时生成；公开发布前请审阅，**勿**含热点密码或配对记录。
- 崩溃日志可能位于 `/sdcard/Download/diplay-crash.log`。
- 敏感漏洞请优先使用 GitHub 私密漏洞报告（若仓库已开启）。

## English (short)

Do not commit keystores, passwords, or offline-MFi assets. Public APKs may embed an extractable experimental accessory identity. There is no account or telemetry. Review diagnostics before posting. Build notes: `docs/BUILD.md`.
