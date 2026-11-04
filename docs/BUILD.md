# 构建与发布

在仓库根目录执行。密钥与 offline-MFi **不要**提交进 Git（见 [SECURITY.md](../SECURITY.md)）。

## 模块

| 模块 | 用途 |
| --- | --- |
| `:mobile` | APK 壳（`com.shihab.diplay`） |
| `:common` | 设置 UI、CarPlay Host |
| `:shared` | 协议、SoftAP、媒体、JNI |

## 环境

- JDK 25
- Android SDK Platform 37
- NDK 25.2.9519653
- `ANDROID_HOME` / `ANDROID_SDK_ROOT`，或 gitignored 的 `local.properties`（`sdk.dir=`）

ABI 仅 `armeabi-v7a`（见各模块 `abiFilters` / `Application.mk`）。

libopus 与 lwIP 源码不入库。本地编原生库、CI 和发布都会先执行：

```sh
python3 scripts/prepare-libopus.py
python3 scripts/prepare-lwip.py
```

## 调试包

```sh
./gradlew :mobile:assembleDebug
```

产物：`mobile/build/outputs/apk/debug/mobile-debug.apk`（无 MFi 时不能独立完成认证）。Debug 包含多 dex，Dalvik 会在 `MultiDex.install` 崩溃，不能装到目标车机；车上安装用 `assembleStandaloneRelease` 的单 dex 包。

与 CI 一致的检查（见 `.github/workflows/android.yml`）：

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest \
  :mobile:lintDebug :mobile:assembleDebug
```

## 含 offline-MFi 的独立调试包

将认证材料放到：

```text
<DIPLAY_AUTH_ASSETS_DIR>/offline-mfi/{identity.pk8,certificate.p7b}
```

也可用 `.private/runtime-assets/`（已 gitignore）。

```sh
export DIPLAY_AUTH_ASSETS_DIR=...
./gradlew :mobile:assembleStandaloneDebug
```

PowerShell：

```powershell
$env:DIPLAY_AUTH_ASSETS_DIR="..."
.\gradlew.bat :mobile:assembleStandaloneDebug "-PDIPLAY_AUTH_ASSETS_DIR=$env:DIPLAY_AUTH_ASSETS_DIR"
```

## 本机正式签名

环境变量：`ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`、`DIPLAY_AUTH_ASSETS_DIR`。

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-cs75plus-release.ps1
```

产物默认：`dist/75Play-v<version>-release.apk`。

## 应用内更新

- 打开主界面时检查本仓库 **稳定版** Release（成功后约 24h 内不重复）；也可在 **关于 → 检查更新** 手动触发。
- 只接受标签 `vX.Y.Z` 或 `vX.Y.Z-cs75plus`。带 `-beta`、`-alpha`、`-rc` 的预发布不会被选中。
- 已安装 `2.0.5-beta` 时，同数字的稳定版 `v2.0.5` 也不会被当成更新（比较会去掉 `-` 后面的后缀）。下一稳定版请提高 `versionName` 的数字部分，并提高 `versionCode`。
- Release 须同时提供 `75Play-vX.Y.Z-release.apk` 与同名 `.sha256`，且通过包名、`versionCode`、签名证书校验后才调安装器。预发布文件名带后缀，例如 `75Play-v2.0.5-beta-release.apk`。

## 打 tag 发布（Actions）

需要 6 个 Secrets：

| Secret | 用途 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 签名库 |
| `ANDROID_KEYSTORE_PASSWORD` | 库密码 |
| `ANDROID_KEY_ALIAS` | 别名 |
| `ANDROID_KEY_PASSWORD` | 密钥密码 |
| `DIPLAY_AUTH_IDENTITY_PK8_BASE64` | offline-MFi 身份 |
| `DIPLAY_AUTH_CERTIFICATE_P7B_BASE64` | offline-MFi 证书 |

本机生成（勿提交）：`scripts\generate-cs75plus-ci-secrets.ps1` → `.private/cs75plus-ci-secrets/`。

发布前清单：

1. 提高 `mobile/build.gradle.kts` 的 `versionName` / `versionCode`（本测试版为 `2.0.5-beta` / `205`）
2. 在 `CHANGELOG.md` 增加同名小节（标题必须是 `## 2.0.5-beta`，与标签去掉 `v` 后一致）
3. 更新 `site/config.json` 的 `version`、`tag`、`apk_asset`，以及 `site/content.json`
4. `python scripts/build_site.py`
5. `git tag v2.0.5-beta && git push origin v2.0.5-beta`

带 `-beta` / `-alpha` / `-rc` 的标签会自动标成 GitHub 预发布。Actions 会先准备 libopus 与 lwIP，再打签名包。产物名来自 `site/config.json` 的 `apk_asset`。

改仓库名时同步 `site/config.json` 的 `github_repository`，以及应用内更新检查用的仓库常量。

## 项目主页

静态页由 `scripts/build_site.py` 根据 `site/content.json` + `CHANGELOG.md` 生成，部署见 `.github/workflows/pages.yml`。
