# 贡献指南

感谢关注 75Play。本仓库专为 **长安 CS75 Plus 2020 SoftAP**（Android 4.4.2 / API 19 · `armeabi-v7a`）维护。

更完整的助手约定见 [`AGENTS.md`](AGENTS.md)。

## 开 Issue

1. 先搜是否已有相同问题。
2. 写明：车机固件（如 `OS_S311-WT`）、iPhone / iOS、75Play 版本、复现步骤。
3. 附上「设置 → 诊断」导出的 `.txt`。
4. **不要**上传热点密码、配对材料或密钥。

## 提 Pull Request

- 保持产品边界：不要加入 BYD HUD / 仪表 cluster、有线 USB CarPlay、多 OEM 车辆数据设置。
- `minSdk` / `targetSdk` 均为 **19**；不要堆 Android 6+ 手机权限路径。
- 新增或移动设置项：按司机目标分类（见 `AGENTS.md`），同步全部 `values-xx` 字符串。
- 提交前尽量跑通：

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest \
  :mobile:lintDebug :mobile:assembleDebug
```

- 改文档或项目主页时：更新对应 `docs/*` / `README*.md`；改站点文案后执行 `python scripts/build_site.py`。
- **禁止**提交密钥库、密码、`DIPLAY_AUTH_ASSETS_DIR` / `.private/` 内容。

## 上游移植

上游 tip 只维护在 [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md)。合入建议：`merge(changan|legacy): port <hash> …`。不引入有线 USB、TUN、BYD HUD / cluster。

## 协议

贡献代码默认按仓库 **GPL-3.0** 许可；涉及 DiAuto 衍生 UI 时请注意 **AGPL-3.0** 义务（见第三方致谢）。
