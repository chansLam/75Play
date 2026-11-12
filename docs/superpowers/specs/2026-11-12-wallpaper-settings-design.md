# 壁纸设置

壳层主页、设置、关于共用一张壁纸。设置页「画面与主题」列增加壁纸项。CarPlay 投屏画面不使用这张壁纸。

## 行为

三种模式单选，同一时间只有一个生效：

| 模式 | 行为 |
|---|---|
| 默认背景 | 继续使用现有日间 / 夜间车内图 `bg_home` / `bg_home_light` |
| 自定义图片 | 使用用户选定的那一张，之后不再自动更换 |
| 每日壁纸 | 壳层打开且联网时，按本地日期更换。同一天重复打开不再请求 |

搜索结果和自定义图片相同：点中一张后写入自定义文件，模式改为自定义，之后固定不动。

每日来源也是单选：

- **必应**：当天归档中的一张，市场 `zh-CN`，宽度 1920。
- **Wallhaven**：近一天热门中的第一张普通内容图。类别只要 General（`categories=100`），分级只要 SFW（`purity=100`）。同一天再打开仍是这一张。

换每日来源会清掉「今天已更新」的日期，从而重新下载。新图成功之前继续显示上一张每日图。

默认背景的两张内置车内图保留，不删除、不替换资源文件。自定义和每日全天用同一张，不随日间 / 夜间换图。玻璃面板仍跟随主题。不从壁纸取色。

## 架构

偏好放在壳层 `DiPlayPreferences`（`diplay` SharedPreferences）。会话配置 `AirPlayPersistence` 不保存壁纸。`CarPlayHostActivity` 不读取、不写入壁纸。

画面只改 `ShellChrome` 根布局的背景。三个 Fragment 都铺在这个根上，所以三页是同一张图。

`DiPlayHost` 增加 `applyShellWallpaper()`。`ShellChrome` 装好根布局后立刻调用一次，主题重建（`applyShell()`）也会再走这条路径，避免自定义或每日图被退回车内图。设置变更和每日下载完成后同样调用。

设置行由新的 `WallpaperSettingsColumn` 画在「画面与主题」列末尾，跟在现有视频 / 主题行后面。不新增 `SettingsSection`，不增加第五列，不加二级导航。选图和搜索使用现有 `DiPlayDialog` 与 `GlassDropdown`。

逻辑分成四块，界面只调用它们：

| 单元 | 职责 |
|---|---|
| `WallpaperMode` | `default` / `custom` / `daily`，以及每日来源 `bing` / `wallhaven`。无法识别的值分别回到默认背景和必应 |
| `WallpaperCatalog` | 拼请求地址、解析 JSON、判断今天要不要下载、计算解码采样。不访问网络，不碰 Android |
| `WallpaperStore` | 读写模式、来源、本地日期，以及 `custom.jpg` / `daily.jpg` |
| `WallpaperClient` | 在后台用 TLS 1.2 下载 JSON 和图片。只访问允许的主机 |

图库选择用 AndroidX `ActivityResultContracts.GetContent()`，类型 `image/*`，注册在 `FragmentSetting` 上。这与壳层已有的 `registerForActivityResult` 相同，不调用 API 20 以上的平台接口。

## 界面

「画面与主题」列末尾四行，行样式与现有设置行相同：

1. **壁纸**：右侧 ▾ 单选「默认背景 / 自定义图片 / 每日壁纸」。
2. **选择图片**：仅自定义模式显示。右侧为「已设置」或「未选择」。点击打开系统图库。从其他模式切入自定义且还没有自定义文件时，直接打开图库；用户取消则退回切入前的模式。已经是自定义时取消图库，模式保持不变。
3. **每日来源**：仅每日模式显示。右侧 ▾ 单选「必应 / Wallhaven」，并显示状态：今日已更新、沿用上一张、暂时使用默认背景。
4. **搜索壁纸**：三种模式下都显示。打开对话框。

搜索对话框标题为「搜索壁纸」。标题下是输入框和「搜索」按钮，正文是可滚动缩略图网格，最多 24 张。点一张开始下载原图；成功后写入自定义文件、模式改为自定义、刷新壳层背景并关闭对话框。

搜索正文的状态：

- 尚未搜索：提示输入关键词。
- 请求中：加载。
- 无结果：没有找到壁纸。
- 请求失败：暂时无法搜索，请稍后再试。
- 某一张原图失败：网格保留，只在该张上标出失败，对话框不关闭，模式不变。

图库返回的文件无法解码时，用现有说明对话框显示「这张图片无法使用」。已铺上的壁纸和已保存的文件都保持原样。

文案键名使用 `settings_wallpaper_` 前缀，写入 `common/src/main/res/values/` 以及全部 `values-xx`。颜色、字号、间距和圆角只用现有壳层令牌。新增尺寸时先加入令牌文件。

## 数据与文件

偏好键：

| 键 | 值 |
|---|---|
| `wallpaper_mode` | `default`、`custom`、`daily`。缺省为 `default` |
| `wallpaper_daily_source` | `bing`、`wallhaven`。缺省为 `bing` |
| `wallpaper_daily_day` | 成功换上每日图的本地日期，`yyyy-MM-dd`。缺省为空 |

文件目录为 `filesDir/wallpaper/`：

- `custom.jpg`：自定义图片，包含从搜索设上的图。
- `daily.jpg`：最近一张成功下载的每日图。

保存前按 2 的幂次采样，使长边不超过 1920 像素；原图长边已经不超过 1920 时不放大。然后压成 JPEG（质量 85）。显示时用 API 19 的 `Canvas` 做居中裁剪，铺满壳层根布局。换图时回收上一张位图。不使用 `RenderEffect` 或实时模糊。

切回默认背景时立即恢复内置车内图，两个 jpg 都留在目录里。再切回自定义时，有 `custom.jpg` 就直接显示。再切回每日时，若日期就是今天则直接显示 `daily.jpg`，否则发起下载。

壳层 `onResume` 时：模式为每日，且 `wallpaper_daily_day` 不是今天，才开始下载。进行中的请求不重复发起。下载过程中继续显示已有的 `daily.jpg`；没有每日图时继续显示默认车内图。

## 网络

壁纸使用自己的 OkHttp 客户端，不使用更新检查那个客户端。更新客户端关闭了跳转，信任库也只为更新包准备。壁纸客户端复制它在 API 19 上的做法：Conscrypt、`TLSv1.2`、`HTTP/1.1`、优先 IPv4。信任库是系统默认根证书加上现有的 `update_ca_bundle`。主机名校验保持开启。连接超时 12 秒，读取超时 20 秒。

只使用 HTTPS。允许的主机只有：

- `www.bing.com`
- `bing.com`
- 以 `.bing.net` 结尾的主机（必应图片跳转）
- `wallhaven.cc`
- `w.wallhaven.cc`
- `th.wallhaven.cc`

跳转后的地址也必须落在这份名单里，否则视为失败。请求带应用自己的 `User-Agent`（应用名与版本名）。不内置、不要求 API 密钥。

地址由 `WallpaperCatalog` 生成：

- 必应当天：`https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN`。图片地址取返回条目的 `urlbase`，拼成 `https://www.bing.com` + `urlbase` + `_1920x1080.jpg`。
- Wallhaven 每日：`https://wallhaven.cc/api/v1/search?categories=100&purity=100&sorting=toplist&topRange=1d&atleast=1920x720&page=1`。取 `data` 第一张的 `path`。
- Wallhaven 搜索：同一过滤，另加 UTF-8 编码的 `q`，`sorting=relevance`，`atleast=1280x720`，`page=1`。缩略图用 `thumbs.small`，原图用 `path`。最多取 24 条。关键词去掉首尾空白后为空，则不发请求。

缩略图最多 3 个并发。对话框关闭时取消尚未完成的缩略图和原图请求。

## 失败

| 情况 | 结果 |
|---|---|
| 每日下载失败，已有 `daily.jpg` | 继续显示上一张，状态为「沿用上一张」 |
| 每日下载失败，没有 `daily.jpg` | 显示默认车内图，状态为「暂时使用默认背景」 |
| 每日来源返回空列表或坏 JSON | 与下载失败相同 |
| 搜索关键词为空 | 不发请求，正文保持「输入关键词」 |
| 搜索失败或空列表 | 对话框内显示对应文案，模式不变 |
| 搜索原图失败 | 只标记该张，模式不变 |
| 图库取消且没有 `custom.jpg` | 模式退回切换前的值 |
| 图库文件无法解码 | 说明对话框，文件和模式都不变 |
| 跳转到名单以外的主机 | 视为这次请求失败 |

## 测试

单元测试不访问必应或 Wallhaven。`WallpaperCatalog` 用固定 JSON 字符串覆盖：

- 模式与来源的合法值和无法识别的值。
- 每日模式且日期不是今天时需要下载；日期是今天时不下载；切换来源后需要下载。
- 空关键词不产生搜索地址。必应地址含 `idx=0` 与 `mkt=zh-CN`。Wallhaven 每日和搜索地址都含 `purity=100` 与 `categories=100`。
- 必应样例能拼出 1920 宽图片地址。Wallhaven 样例能取出 `thumbs.small` 与 `path`。空 `data`、缺字段、坏 JSON 得到失败而不是抛到界面。
- 采用搜索结果的决策把模式写成 `custom`。
- 图库取消且没有自定义文件时恢复先前模式。
- 采样计算使用 2 的幂次，结果长边不超过 1920；原图更小则采样为 1。

`SettingsTranslationsTest` 已要求每个新字符串出现在全部 `values-xx`，占位符一致。新增文案靠这条现有测试守住。

车机上手动看一次，不写进单元测试：1920×720 日间和夜间各看一遍；主页、设置、关于是同一张图；切回默认背景后车内图仍在；CarPlay 投屏没有壁纸；每日与搜索的加载、空结果、失败文案各走一遍。

## 不做

- 不给 `CarPlayHostActivity` 加壁纸或设置项。
- 不为三个壳层页面分别保存壁纸。
- 不增加第五个设置列，也不做壁纸二级页。
- 不接入必应、Wallhaven 以外的源，不提供 API 密钥配置。
- 不做动态壁纸、视差、自动播放，也不从壁纸取色。
- 不新增 Android 6 以上的运行时权限。图库读取使用系统返回的地址；清单里已有的存储权限保持不动。
