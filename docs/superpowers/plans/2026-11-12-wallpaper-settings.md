# 壁纸设置 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在壳层设置里增加壁纸：默认背景、固定的自定义图片、按本地日期更新的每日壁纸，以及 Wallhaven 搜索（结果写入自定义图片）。

**Architecture:** 纯逻辑放在 `WallpaperCatalog` / `WallpaperHosts`，偏好和 jpeg 放在 `WallpaperStore`，联网放在可替换的 `WallpaperTransport`。`ShellChrome` 根布局铺同一张图，主页、设置、关于因此共用。设置行只画在「画面与主题」列末尾。

**Tech Stack:** Kotlin、Android Views、API 19、OkHttp 3.12.13、Conscrypt TLS 1.2、JUnit 4、Robolectric 4.17。

## Global Constraints

- `minSdk` / `targetSdk` 均为 19。禁止 API 20+ 的类、方法、常量和 `SDK_INT >= 20` 分支。
- 壁纸偏好只进 `DiPlayPreferences` 的 `diplay` 文件。`AirPlayPersistence` 与 `CarPlayHostActivity` 不读不写壁纸。
- 不新增 `SettingsSection`，不增加第五列，不加二级导航，不删除 `bg_home` / `bg_home_light`。
- 不从壁纸取色，不用 `RenderEffect` 或实时模糊。颜色、字号、间距只用现有令牌；新尺寸先写入令牌文件。
- 每日来源只有必应（`zh-CN`、1920 宽）和 Wallhaven（`categories=100`、`purity=100`）。搜索只走 Wallhaven。不内置 API 密钥。
- 壁纸 HTTP 使用自己的 OkHttp 客户端：Conscrypt、`TLSv1.2`、`HTTP/1.1`、优先 IPv4、连接 12 秒、读取 20 秒。信任库是系统根证书加上 `R.raw.update_ca_bundle`。主机名校验保持开启。不改 `DiPlayUpdateClient`。
- 只允许 HTTPS，主机仅限 `www.bing.com`、`bing.com`、以 `.bing.net` 结尾的主机、`wallhaven.cc`、`w.wallhaven.cc`、`th.wallhaven.cc`。带用户信息的 URL 拒绝。跳转后的地址也要在这份名单里。
- 文案键名使用 `settings_wallpaper_` 前缀，写入 `values` 和全部 `values-xx`。
- 单元测试不访问必应或 Wallhaven，也不要在 JVM 上构造 Conscrypt。
- 每个提交只包含该任务列出的文件。不要把未要求的设计文档一并 `git add`。
- 新的 Kotlin 文件顶部写 `// SPDX-License-Identifier: AGPL-3.0-only`。

## File Structure

| 文件 | 职责 |
|---|---|
| `common/src/main/java/com/shihab/diplay/wallpaper/WallpaperCatalog.kt` | 模式、来源、地址、JSON、采样、行可见性、每日状态 |
| `common/src/main/java/com/shihab/diplay/wallpaper/WallpaperHosts.kt` | URL 白名单 |
| `common/src/main/java/com/shihab/diplay/wallpaper/WallpaperStore.kt` | `diplay` 偏好与 `filesDir/wallpaper/*.jpg` |
| `common/src/main/java/com/shihab/diplay/wallpaper/WallpaperBitmaps.kt` | 采样解码、JPEG 写出、图库复制上限 |
| `common/src/main/java/com/shihab/diplay/wallpaper/WallpaperClient.kt` | 通过 transport 取文本和字节，校验主机与体积 |
| `common/src/main/java/com/shihab/diplay/wallpaper/OkHttpWallpaperTransport.kt` | API 19 TLS 客户端与取消 |
| `common/src/main/java/com/shihab/diplay/wallpaper/CenterCropBitmapDrawable.kt` | 居中裁剪绘制 |
| `common/src/main/java/com/shihab/diplay/wallpaper/ShellWallpaper.kt` | 把当前模式画到壳层根布局 |
| `common/src/main/java/com/shihab/diplay/wallpaper/WallpaperSearch.kt` | 搜索状态机 |
| `common/src/main/java/com/shihab/diplay/wallpaper/WallpaperDailyRefresh.kt` | 壳层打开时按日期下载 |
| `common/src/main/java/com/shihab/diplay/ui/settings/WallpaperSettingsColumn.kt` | 四行设置 |
| `common/src/main/java/com/shihab/diplay/ui/settings/WallpaperSearchDialog.kt` | 搜索对话框 |
| `common/src/test/java/com/shihab/diplay/wallpaper/*Test.kt` | 上面对应的单元测试 |

修改：`DiPlayPreferences.kt`、`DiPlayHost.kt`、`DiPlayActivity.kt`、`ShellChrome.kt`、`FragmentSetting.kt`、`LayoutMetrics.kt`、`theme/Spacing.kt`（`ShellComponentSize`）、七个 `strings.xml`。

---

### Task 1: 目录与白名单

**Files:**
- Create: `common/src/test/java/com/shihab/diplay/wallpaper/WallpaperCatalogTest.kt`
- Create: `common/src/main/java/com/shihab/diplay/wallpaper/WallpaperCatalog.kt`
- Create: `common/src/main/java/com/shihab/diplay/wallpaper/WallpaperHosts.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `enum class WallpaperMode { DEFAULT, CUSTOM, DAILY }`
  - `enum class WallpaperDailySource { BING, WALLHAVEN }`
  - `enum class DailyStatus { UPDATED_TODAY, KEEPING_PREVIOUS, USING_DEFAULT }`
  - `data class WallpaperImage(val fullUrl: String, val thumbUrl: String)`
  - `sealed class WallpaperFetch { data class Ready(val image: WallpaperImage) : WallpaperFetch(); data object Empty : WallpaperFetch(); data object Invalid : WallpaperFetch() }`
  - `sealed class WallpaperSearchParse { data class Hits(val images: List<WallpaperImage>) : WallpaperSearchParse(); data object Empty : WallpaperSearchParse(); data object Invalid : WallpaperSearchParse() }`
  - `data class WallpaperRows(val pick: Boolean, val source: Boolean)`
  - `WallpaperCatalog.MAX_LONG_EDGE = 1920`，`SEARCH_LIMIT = 24`，`JPEG_QUALITY = 85`
  - `WallpaperCatalog.mode(stored: String?): WallpaperMode`
  - `WallpaperCatalog.source(stored: String?): WallpaperDailySource`
  - `WallpaperCatalog.modeKey(mode: WallpaperMode): String` 返回 `default` / `custom` / `daily`
  - `WallpaperCatalog.sourceKey(source: WallpaperDailySource): String` 返回 `bing` / `wallhaven`
  - `WallpaperCatalog.bingArchiveUrl(): String`
  - `WallpaperCatalog.wallhavenDailyUrl(): String`
  - `WallpaperCatalog.wallhavenSearchUrl(query: String): String?`
  - `WallpaperCatalog.parseBing(json: String): WallpaperFetch`
  - `WallpaperCatalog.parseWallhavenDaily(json: String): WallpaperFetch`
  - `WallpaperCatalog.parseWallhavenSearch(json: String): WallpaperSearchParse`
  - `WallpaperCatalog.needsDailyDownload(mode: WallpaperMode, savedDay: String?, today: String): Boolean`
  - `WallpaperCatalog.sampleSize(width: Int, height: Int): Int`
  - `WallpaperCatalog.modeAfterSearch(): WallpaperMode`
  - `WallpaperCatalog.modeAfterPickerCancel(previous: WallpaperMode, hasCustomFile: Boolean): WallpaperMode`
  - `WallpaperCatalog.rows(mode: WallpaperMode): WallpaperRows`
  - `WallpaperCatalog.dailyStatus(savedDay: String?, today: String, hasDailyFile: Boolean): DailyStatus`
  - `WallpaperHosts.allowed(url: String): Boolean`

- [ ] **Step 1: Write the failing test**

Create `common/src/test/java/com/shihab/diplay/wallpaper/WallpaperCatalogTest.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperCatalogTest {
    @Test fun unknownModeAndSourceFallBack() {
        assertEquals(WallpaperMode.DEFAULT, WallpaperCatalog.mode(null))
        assertEquals(WallpaperMode.DEFAULT, WallpaperCatalog.mode("future"))
        assertEquals(WallpaperMode.CUSTOM, WallpaperCatalog.mode("custom"))
        assertEquals(WallpaperMode.DAILY, WallpaperCatalog.mode("daily"))
        assertEquals(WallpaperDailySource.BING, WallpaperCatalog.source(null))
        assertEquals(WallpaperDailySource.BING, WallpaperCatalog.source("nope"))
        assertEquals(WallpaperDailySource.WALLHAVEN, WallpaperCatalog.source("wallhaven"))
        assertEquals("default", WallpaperCatalog.modeKey(WallpaperMode.DEFAULT))
        assertEquals("custom", WallpaperCatalog.modeKey(WallpaperMode.CUSTOM))
        assertEquals("daily", WallpaperCatalog.modeKey(WallpaperMode.DAILY))
        assertEquals("bing", WallpaperCatalog.sourceKey(WallpaperDailySource.BING))
        assertEquals("wallhaven", WallpaperCatalog.sourceKey(WallpaperDailySource.WALLHAVEN))
    }

    @Test fun dailyDownloadFollowsModeAndLocalDay() {
        assertFalse(WallpaperCatalog.needsDailyDownload(WallpaperMode.DEFAULT, null, "2026-11-12"))
        assertFalse(WallpaperCatalog.needsDailyDownload(WallpaperMode.CUSTOM, null, "2026-11-12"))
        assertTrue(WallpaperCatalog.needsDailyDownload(WallpaperMode.DAILY, null, "2026-11-12"))
        assertTrue(WallpaperCatalog.needsDailyDownload(WallpaperMode.DAILY, "2026-11-11", "2026-11-12"))
        assertFalse(WallpaperCatalog.needsDailyDownload(WallpaperMode.DAILY, "2026-11-12", "2026-11-12"))
    }

    @Test fun urlsCarryTheRequiredFilters() {
        assertTrue(WallpaperCatalog.bingArchiveUrl().startsWith("https://www.bing.com/HPImageArchive.aspx?"))
        assertTrue(WallpaperCatalog.bingArchiveUrl().contains("idx=0"))
        assertTrue(WallpaperCatalog.bingArchiveUrl().contains("n=1"))
        assertTrue(WallpaperCatalog.bingArchiveUrl().contains("mkt=zh-CN"))
        val daily = WallpaperCatalog.wallhavenDailyUrl()
        assertTrue(daily.contains("purity=100"))
        assertTrue(daily.contains("categories=100"))
        assertTrue(daily.contains("sorting=toplist"))
        assertTrue(daily.contains("topRange=1d"))
        assertNull(WallpaperCatalog.wallhavenSearchUrl("  "))
        val search = WallpaperCatalog.wallhavenSearchUrl(" mountain ")!!
        assertTrue(search.contains("q=mountain"))
        assertTrue(search.contains("purity=100"))
        assertTrue(search.contains("categories=100"))
        assertTrue(search.contains("sorting=relevance"))
        assertTrue(search.contains("atleast=1280x720"))
    }

    @Test fun bingParserBuildsThe1920Image() {
        val json = """{"images":[{"urlbase":"/th?id=OHR.Example_ZH-CN1"}]}"""
        val ready = WallpaperCatalog.parseBing(json) as WallpaperFetch.Ready
        assertEquals("https://www.bing.com/th?id=OHR.Example_ZH-CN1_1920x1080.jpg", ready.image.fullUrl)
        assertEquals(WallpaperFetch.Empty, WallpaperCatalog.parseBing("""{"images":[]}"""))
        assertEquals(WallpaperFetch.Invalid, WallpaperCatalog.parseBing("""{"images":[{}]}"""))
        assertEquals(WallpaperFetch.Invalid, WallpaperCatalog.parseBing("not-json"))
    }

    @Test fun wallhavenParserKeepsOnlyAllowedImages() {
        val json = """
            {"data":[
              {"path":"https://w.wallhaven.cc/full/ab/wallhaven-abc.jpg","thumbs":{"small":"https://th.wallhaven.cc/small/ab/abc.jpg"}},
              {"path":"http://insecure.example/a.jpg","thumbs":{"small":"https://th.wallhaven.cc/small/ab/nope.jpg"}},
              {"path":"https://w.wallhaven.cc/full/ab/second.jpg"}
            ]}
        """.trimIndent()
        val hits = WallpaperCatalog.parseWallhavenSearch(json) as WallpaperSearchParse.Hits
        assertEquals(2, hits.images.size)
        assertEquals("https://th.wallhaven.cc/small/ab/abc.jpg", hits.images[0].thumbUrl)
        assertEquals("https://w.wallhaven.cc/full/ab/second.jpg", hits.images[1].thumbUrl)
        val daily = WallpaperCatalog.parseWallhavenDaily(json) as WallpaperFetch.Ready
        assertEquals(hits.images[0].fullUrl, daily.image.fullUrl)
        assertEquals(WallpaperSearchParse.Empty, WallpaperCatalog.parseWallhavenSearch("""{"data":[]}"""))
        assertEquals(WallpaperFetch.Empty, WallpaperCatalog.parseWallhavenDaily("""{"data":[]}"""))
        assertEquals(WallpaperSearchParse.Invalid, WallpaperCatalog.parseWallhavenSearch("[]"))
        assertEquals(WallpaperFetch.Invalid, WallpaperCatalog.parseWallhavenDaily("""{"nope":1}"""))
    }

    @Test fun searchStopsAt24() {
        val items = (1..30).joinToString(",") {
            """{"path":"https://w.wallhaven.cc/full/ab/$it.jpg","thumbs":{"small":"https://th.wallhaven.cc/small/ab/$it.jpg"}}"""
        }
        val hits = WallpaperCatalog.parseWallhavenSearch("""{"data":[$items]}""") as WallpaperSearchParse.Hits
        assertEquals(24, hits.images.size)
    }

    @Test fun sampleSizeUsesPowersOfTwo() {
        assertEquals(1, WallpaperCatalog.sampleSize(1920, 720))
        assertEquals(1, WallpaperCatalog.sampleSize(100, 100))
        assertEquals(1, WallpaperCatalog.sampleSize(0, 0))
        assertEquals(2, WallpaperCatalog.sampleSize(3840, 2160))
        assertEquals(4, WallpaperCatalog.sampleSize(4000, 1000))
    }

    @Test fun searchAndPickerDecisions() {
        assertEquals(WallpaperMode.CUSTOM, WallpaperCatalog.modeAfterSearch())
        assertEquals(WallpaperMode.DAILY, WallpaperCatalog.modeAfterPickerCancel(WallpaperMode.DAILY, false))
        assertEquals(WallpaperMode.DEFAULT, WallpaperCatalog.modeAfterPickerCancel(WallpaperMode.DEFAULT, false))
        assertEquals(WallpaperMode.CUSTOM, WallpaperCatalog.modeAfterPickerCancel(WallpaperMode.DAILY, true))
    }

    @Test fun rowsAndDailyStatus() {
        assertEquals(WallpaperRows(pick = false, source = false), WallpaperCatalog.rows(WallpaperMode.DEFAULT))
        assertEquals(WallpaperRows(pick = true, source = false), WallpaperCatalog.rows(WallpaperMode.CUSTOM))
        assertEquals(WallpaperRows(pick = false, source = true), WallpaperCatalog.rows(WallpaperMode.DAILY))
        assertEquals(DailyStatus.UPDATED_TODAY, WallpaperCatalog.dailyStatus("2026-11-12", "2026-11-12", true))
        assertEquals(DailyStatus.KEEPING_PREVIOUS, WallpaperCatalog.dailyStatus("2026-11-11", "2026-11-12", true))
        assertEquals(DailyStatus.USING_DEFAULT, WallpaperCatalog.dailyStatus(null, "2026-11-12", false))
        assertEquals(DailyStatus.USING_DEFAULT, WallpaperCatalog.dailyStatus("2026-11-12", "2026-11-12", false))
    }

    @Test fun hostsAllowOnlyTheWallpaperSites() {
        assertTrue(WallpaperHosts.allowed("https://www.bing.com/HPImageArchive.aspx?format=js"))
        assertTrue(WallpaperHosts.allowed("https://bing.com/th?id=1"))
        assertTrue(WallpaperHosts.allowed("https://ts1.explicit.bing.net/th?id=1"))
        assertTrue(WallpaperHosts.allowed("https://wallhaven.cc/api/v1/search"))
        assertTrue(WallpaperHosts.allowed("https://w.wallhaven.cc/full/ab/a.jpg"))
        assertTrue(WallpaperHosts.allowed("https://th.wallhaven.cc/small/ab/a.jpg"))
        assertFalse(WallpaperHosts.allowed("http://www.bing.com/a"))
        assertFalse(WallpaperHosts.allowed("https://evil.example/a"))
        assertFalse(WallpaperHosts.allowed("https://www.bing.com.evil.com/a"))
        assertFalse(WallpaperHosts.allowed("https://notbing.net/a"))
        assertFalse(WallpaperHosts.allowed("https://user:pass@www.bing.com/a"))
        assertFalse(WallpaperHosts.allowed("not a url"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :common:testDebugUnitTest --tests com.shihab.diplay.wallpaper.WallpaperCatalogTest`

Expected: FAIL，编译错误 `Unresolved reference: WallpaperCatalog`。

- [ ] **Step 3: Write minimal implementation**

Create `common/src/main/java/com/shihab/diplay/wallpaper/WallpaperHosts.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import java.net.URI
import java.util.Locale

internal object WallpaperHosts {
    fun allowed(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (error: Exception) {
            return false
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (!uri.userInfo.isNullOrEmpty()) return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        return host == "www.bing.com" ||
            host == "bing.com" ||
            host == "wallhaven.cc" ||
            host == "w.wallhaven.cc" ||
            host == "th.wallhaven.cc" ||
            host.endsWith(".bing.net")
    }
}
```

Create `common/src/main/java/com/shihab/diplay/wallpaper/WallpaperCatalog.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import org.json.JSONObject
import java.net.URLEncoder

internal enum class WallpaperMode { DEFAULT, CUSTOM, DAILY }

internal enum class WallpaperDailySource { BING, WALLHAVEN }

internal enum class DailyStatus { UPDATED_TODAY, KEEPING_PREVIOUS, USING_DEFAULT }

internal data class WallpaperImage(val fullUrl: String, val thumbUrl: String)

internal sealed class WallpaperFetch {
    data class Ready(val image: WallpaperImage) : WallpaperFetch()
    data object Empty : WallpaperFetch()
    data object Invalid : WallpaperFetch()
}

internal sealed class WallpaperSearchParse {
    data class Hits(val images: List<WallpaperImage>) : WallpaperSearchParse()
    data object Empty : WallpaperSearchParse()
    data object Invalid : WallpaperSearchParse()
}

internal data class WallpaperRows(val pick: Boolean, val source: Boolean)

internal object WallpaperCatalog {
    const val MAX_LONG_EDGE = 1920
    const val SEARCH_LIMIT = 24
    const val JPEG_QUALITY = 85

    fun mode(stored: String?): WallpaperMode = when (stored) {
        "custom" -> WallpaperMode.CUSTOM
        "daily" -> WallpaperMode.DAILY
        else -> WallpaperMode.DEFAULT
    }

    fun source(stored: String?): WallpaperDailySource = when (stored) {
        "wallhaven" -> WallpaperDailySource.WALLHAVEN
        else -> WallpaperDailySource.BING
    }

    fun modeKey(mode: WallpaperMode): String = when (mode) {
        WallpaperMode.DEFAULT -> "default"
        WallpaperMode.CUSTOM -> "custom"
        WallpaperMode.DAILY -> "daily"
    }

    fun sourceKey(source: WallpaperDailySource): String = when (source) {
        WallpaperDailySource.BING -> "bing"
        WallpaperDailySource.WALLHAVEN -> "wallhaven"
    }

    fun bingArchiveUrl(): String =
        "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN"

    fun wallhavenDailyUrl(): String =
        "https://wallhaven.cc/api/v1/search?categories=100&purity=100&sorting=toplist&topRange=1d&atleast=1920x720&page=1"

    fun wallhavenSearchUrl(query: String): String? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        return "https://wallhaven.cc/api/v1/search?q=$encoded&categories=100&purity=100" +
            "&atleast=1280x720&sorting=relevance&page=1"
    }

    fun parseBing(json: String): WallpaperFetch {
        val root = jsonObject(json) ?: return WallpaperFetch.Invalid
        val images = root.optJSONArray("images") ?: return WallpaperFetch.Invalid
        if (images.length() == 0) return WallpaperFetch.Empty
        val urlbase = images.optJSONObject(0)?.optString("urlbase").orEmpty()
        if (urlbase.isBlank()) return WallpaperFetch.Invalid
        val full = "https://www.bing.com$urlbase" + "_1920x1080.jpg"
        if (!WallpaperHosts.allowed(full)) return WallpaperFetch.Invalid
        return WallpaperFetch.Ready(WallpaperImage(full, full))
    }

    fun parseWallhavenDaily(json: String): WallpaperFetch = when (val parsed = parseWallhavenSearch(json)) {
        is WallpaperSearchParse.Hits -> WallpaperFetch.Ready(parsed.images.first())
        WallpaperSearchParse.Empty -> WallpaperFetch.Empty
        WallpaperSearchParse.Invalid -> WallpaperFetch.Invalid
    }

    fun parseWallhavenSearch(json: String): WallpaperSearchParse {
        val root = jsonObject(json) ?: return WallpaperSearchParse.Invalid
        if (!root.has("data")) return WallpaperSearchParse.Invalid
        val data = root.optJSONArray("data") ?: return WallpaperSearchParse.Invalid
        if (data.length() == 0) return WallpaperSearchParse.Empty
        val images = ArrayList<WallpaperImage>(WallpaperCatalog.SEARCH_LIMIT)
        for (index in 0 until data.length()) {
            if (images.size >= SEARCH_LIMIT) break
            val item = data.optJSONObject(index) ?: continue
            val path = item.optString("path")
            if (!WallpaperHosts.allowed(path)) continue
            val thumb = item.optJSONObject("thumbs")?.optString("small").orEmpty()
            val thumbUrl = if (WallpaperHosts.allowed(thumb)) thumb else path
            images.add(WallpaperImage(path, thumbUrl))
        }
        if (images.isEmpty()) return WallpaperSearchParse.Invalid
        return WallpaperSearchParse.Hits(images)
    }

    fun needsDailyDownload(mode: WallpaperMode, savedDay: String?, today: String): Boolean =
        mode == WallpaperMode.DAILY && savedDay != today

    fun sampleSize(width: Int, height: Int): Int {
        val longEdge = maxOf(width, height)
        if (longEdge <= MAX_LONG_EDGE) return 1
        var sample = 1
        while (sample <= longEdge / 2 && longEdge / sample > MAX_LONG_EDGE) {
            val next = sample * 2
            if (next <= sample) break
            sample = next
        }
        return sample
    }

    fun modeAfterSearch(): WallpaperMode = WallpaperMode.CUSTOM

    fun modeAfterPickerCancel(previous: WallpaperMode, hasCustomFile: Boolean): WallpaperMode =
        if (hasCustomFile) WallpaperMode.CUSTOM else previous

    fun rows(mode: WallpaperMode): WallpaperRows = WallpaperRows(
        pick = mode == WallpaperMode.CUSTOM,
        source = mode == WallpaperMode.DAILY,
    )

    fun dailyStatus(savedDay: String?, today: String, hasDailyFile: Boolean): DailyStatus = when {
        !hasDailyFile -> DailyStatus.USING_DEFAULT
        savedDay == today -> DailyStatus.UPDATED_TODAY
        else -> DailyStatus.KEEPING_PREVIOUS
    }

    private fun jsonObject(json: String): JSONObject? = try {
        JSONObject(json)
    } catch (error: Exception) {
        null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :common:testDebugUnitTest --tests com.shihab.diplay.wallpaper.WallpaperCatalogTest`

Expected: BUILD SUCCESSFUL，`WallpaperCatalogTest` 全部通过。

- [ ] **Step 5: Commit**

```powershell
git add common/src/main/java/com/shihab/diplay/wallpaper/WallpaperCatalog.kt common/src/main/java/com/shihab/diplay/wallpaper/WallpaperHosts.kt common/src/test/java/com/shihab/diplay/wallpaper/WallpaperCatalogTest.kt
git commit -m "feat: add wallpaper catalog and host allowlist"
```

---

### Task 2: 偏好、文件与解码

**Files:**
- Modify: `common/src/main/java/com/shihab/diplay/DiPlayBootstrap.kt`（`DiPlayPreferences` 对象末尾、`HOME_APP_LIST` 常量之前）
- Create: `common/src/main/java/com/shihab/diplay/wallpaper/WallpaperStore.kt`
- Create: `common/src/main/java/com/shihab/diplay/wallpaper/WallpaperBitmaps.kt`
- Create: `common/src/test/java/com/shihab/diplay/wallpaper/WallpaperStoreTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `WallpaperMode`、`WallpaperDailySource`、`WallpaperCatalog.mode`、`source`、`modeKey`、`sourceKey`、`sampleSize`、`JPEG_QUALITY`
- Produces:
  - `DiPlayPreferences.wallpaperMode(context: Context): String?`
  - `DiPlayPreferences.saveWallpaperMode(context: Context, key: String)`
  - `DiPlayPreferences.wallpaperDailySource(context: Context): String?`
  - `DiPlayPreferences.saveWallpaperDailySource(context: Context, key: String)`
  - `DiPlayPreferences.wallpaperDailyDay(context: Context): String?`
  - `DiPlayPreferences.saveWallpaperDailyDay(context: Context, day: String?)`
  - `WallpaperStore(context: Context)`：`mode()`、`saveMode(mode)`、`source()`、`saveSource(source)`（同时清掉日期）、`dailyDay()`、`saveDailyDay(day)`、`hasCustom()`、`hasDaily()`、`activeFile(): File?`、`writeCustom(bitmap: Bitmap): Boolean`、`writeDaily(bitmap: Bitmap): Boolean`、`incomingFile(): File`
  - `WallpaperBitmaps.PICK_MAX_BYTES = 25 * 1024 * 1024`
  - `WallpaperBitmaps.copyStream(stream: InputStream, dest: File, maxBytes: Int = PICK_MAX_BYTES): Boolean`
  - `WallpaperBitmaps.decode(bytes: ByteArray): Bitmap?`
  - `WallpaperBitmaps.decodeFile(file: File): Bitmap?`
  - `WallpaperBitmaps.writeJpeg(file: File, bitmap: Bitmap): Boolean`

- [ ] **Step 1: Write the failing test**

Create `common/src/test/java/com/shihab/diplay/wallpaper/WallpaperStoreTest.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class WallpaperStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.filesDir.resolve("wallpaper").deleteRecursively()
    }

    @Test fun modeSourceAndDayRoundTrip() {
        val store = WallpaperStore(context)
        assertEquals(WallpaperMode.DEFAULT, store.mode())
        assertEquals(WallpaperDailySource.BING, store.source())
        assertNull(store.dailyDay())
        store.saveMode(WallpaperMode.DAILY)
        store.saveSource(WallpaperDailySource.WALLHAVEN)
        store.saveDailyDay("2026-11-12")
        assertEquals(WallpaperMode.DAILY, store.mode())
        assertEquals(WallpaperDailySource.WALLHAVEN, store.source())
        assertEquals("2026-11-12", store.dailyDay())
        store.saveSource(WallpaperDailySource.BING)
        assertEquals(WallpaperDailySource.BING, store.source())
        assertNull(store.dailyDay())
    }

    @Test fun activeFileFollowsMode() {
        val store = WallpaperStore(context)
        val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        assertTrue(store.writeCustom(bitmap))
        assertTrue(store.writeDaily(bitmap))
        bitmap.recycle()
        store.saveMode(WallpaperMode.DEFAULT)
        assertNull(store.activeFile())
        store.saveMode(WallpaperMode.CUSTOM)
        assertEquals("custom.jpg", store.activeFile()!!.name)
        store.saveMode(WallpaperMode.DAILY)
        assertEquals("daily.jpg", store.activeFile()!!.name)
    }

    @Test fun copyStreamHonorsTheCap() {
        val dest = context.filesDir.resolve("wallpaper/incoming.jpg")
        val ok = WallpaperBitmaps.copyStream(ByteArrayInputStream(ByteArray(4)), dest, maxBytes = 8)
        assertTrue(ok)
        assertEquals(4, dest.length())
        val tooBig = context.filesDir.resolve("wallpaper/too-big.jpg")
        assertFalse(WallpaperBitmaps.copyStream(ByteArrayInputStream(ByteArray(9)), tooBig, maxBytes = 8))
        assertFalse(tooBig.exists())
    }

    @Test fun jpegRoundTripKeepsADecodableFile() {
        val store = WallpaperStore(context)
        val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
        assertTrue(store.writeCustom(bitmap))
        bitmap.recycle()
        val decoded = WallpaperBitmaps.decodeFile(store.activeFile()!!.also { store.saveMode(WallpaperMode.CUSTOM) })
        assertTrue(decoded != null && decoded.width > 0 && decoded.height > 0)
        decoded!!.recycle()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :common:testDebugUnitTest --tests com.shihab.diplay.wallpaper.WallpaperStoreTest`

Expected: FAIL，`Unresolved reference: WallpaperStore`。

- [ ] **Step 3: Write minimal implementation**

在 `DiPlayPreferences` 里、`HOME_APP_LIST` 常量之前加入：

```kotlin
    fun wallpaperMode(context: Context): String? = prefs(context).getString("wallpaper_mode", null)
    fun saveWallpaperMode(context: Context, key: String) {
        prefs(context).edit().putString("wallpaper_mode", key).apply()
    }
    fun wallpaperDailySource(context: Context): String? = prefs(context).getString("wallpaper_daily_source", null)
    fun saveWallpaperDailySource(context: Context, key: String) {
        prefs(context).edit().putString("wallpaper_daily_source", key).apply()
    }
    fun wallpaperDailyDay(context: Context): String? = prefs(context).getString("wallpaper_daily_day", null)
    fun saveWallpaperDailyDay(context: Context, day: String?) {
        val editor = prefs(context).edit()
        if (day == null) editor.remove("wallpaper_daily_day") else editor.putString("wallpaper_daily_day", day)
        editor.apply()
    }
```

Create `common/src/main/java/com/shihab/diplay/wallpaper/WallpaperBitmaps.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

internal object WallpaperBitmaps {
    const val PICK_MAX_BYTES = 25 * 1024 * 1024

    fun copyStream(stream: InputStream, dest: File, maxBytes: Int = PICK_MAX_BYTES): Boolean {
        dest.parentFile?.mkdirs()
        return try {
            FileOutputStream(dest).use { out ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        dest.delete()
                        return false
                    }
                    out.write(buffer, 0, read)
                }
            }
            true
        } catch (error: Exception) {
            dest.delete()
            false
        }
    }

    fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = WallpaperCatalog.sampleSize(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    fun decodeFile(file: File): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = WallpaperCatalog.sampleSize(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    fun writeJpeg(file: File, bitmap: Bitmap): Boolean {
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        return try {
            FileOutputStream(part).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, WallpaperCatalog.JPEG_QUALITY, out)) {
                    part.delete()
                    return false
                }
            }
            if (file.exists() && !file.delete()) {
                part.delete()
                return false
            }
            if (!part.renameTo(file)) {
                part.delete()
                return false
            }
            true
        } catch (error: Exception) {
            part.delete()
            false
        }
    }

    fun readCapped(stream: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
```

Create `common/src/main/java/com/shihab/diplay/wallpaper/WallpaperStore.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import android.content.Context
import android.graphics.Bitmap
import com.shihab.diplay.DiPlayPreferences
import java.io.File

internal class WallpaperStore(context: Context) {
    private val app = context.applicationContext
    private val directory: File get() = File(app.filesDir, "wallpaper")
    private val customFile = File(directory, "custom.jpg")
    private val dailyFile = File(directory, "daily.jpg")

    fun mode(): WallpaperMode = WallpaperCatalog.mode(DiPlayPreferences.wallpaperMode(app))
    fun saveMode(mode: WallpaperMode) {
        DiPlayPreferences.saveWallpaperMode(app, WallpaperCatalog.modeKey(mode))
    }
    fun source(): WallpaperDailySource = WallpaperCatalog.source(DiPlayPreferences.wallpaperDailySource(app))
    fun saveSource(source: WallpaperDailySource) {
        DiPlayPreferences.saveWallpaperDailySource(app, WallpaperCatalog.sourceKey(source))
        DiPlayPreferences.saveWallpaperDailyDay(app, null)
    }
    fun dailyDay(): String? = DiPlayPreferences.wallpaperDailyDay(app)
    fun saveDailyDay(day: String) {
        DiPlayPreferences.saveWallpaperDailyDay(app, day)
    }
    fun hasCustom(): Boolean = customFile.isFile
    fun hasDaily(): Boolean = dailyFile.isFile
    fun incomingFile(): File = File(directory, "incoming.jpg")
    fun activeFile(): File? = when (mode()) {
        WallpaperMode.CUSTOM -> customFile.takeIf { it.isFile }
        WallpaperMode.DAILY -> dailyFile.takeIf { it.isFile }
        WallpaperMode.DEFAULT -> null
    }
    fun writeCustom(bitmap: Bitmap): Boolean = WallpaperBitmaps.writeJpeg(customFile, bitmap)
    fun writeDaily(bitmap: Bitmap): Boolean = WallpaperBitmaps.writeJpeg(dailyFile, bitmap)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :common:testDebugUnitTest --tests com.shihab.diplay.wallpaper.WallpaperStoreTest`

Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

```powershell
git add common/src/main/java/com/shihab/diplay/DiPlayBootstrap.kt common/src/main/java/com/shihab/diplay/wallpaper/WallpaperStore.kt common/src/main/java/com/shihab/diplay/wallpaper/WallpaperBitmaps.kt common/src/test/java/com/shihab/diplay/wallpaper/WallpaperStoreTest.kt
git commit -m "feat: store shell wallpaper mode and jpeg files"
```

---

### Task 3: 壁纸 HTTP 客户端

**Files:**
- Create: `common/src/main/java/com/shihab/diplay/wallpaper/WallpaperClient.kt`
- Create: `common/src/main/java/com/shihab/diplay/wallpaper/OkHttpWallpaperTransport.kt`
- Create: `common/src/test/java/com/shihab/diplay/wallpaper/WallpaperClientTest.kt`

**Interfaces:**
- Consumes: `WallpaperHosts.allowed`
- Produces:
  - `data class WallpaperHttpResult(val status: Int, val body: ByteArray, val finalUrl: String, val ok: Boolean)`
  - `fun interface WallpaperTransport { fun get(url: String): WallpaperHttpResult }`
  - `WallpaperClient.MAX_BODY = 12 * 1024 * 1024`
  - `WallpaperClient(transport: WallpaperTransport)`：`text(url: String): String?`、`bytes(url: String): ByteArray?`
  - `OkHttpWallpaperTransport(context: Context)`：实现 `WallpaperTransport`，另有 `cancel()` 与 `fun userAgent(context: Context): String`

- [ ] **Step 1: Write the failing test**

Create `common/src/test/java/com/shihab/diplay/wallpaper/WallpaperClientTest.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WallpaperClientTest {
    @Test fun skipsTransportWhenTheUrlIsRefused() {
        var calls = 0
        val client = WallpaperClient(WallpaperTransport {
            calls++
            WallpaperHttpResult(200, byteArrayOf(1), it, true)
        })
        assertNull(client.bytes("http://evil.example/a"))
        assertEquals(0, calls)
    }

    @Test fun skipsBodyWhenTheFinalHostIsRefused() {
        val client = WallpaperClient(WallpaperTransport {
            WallpaperHttpResult(200, "ok".toByteArray(), "https://evil.example/landed", true)
        })
        assertNull(client.text("https://www.bing.com/start"))
    }

    @Test fun returnsBodyForAnAllowedFinalHost() {
        val client = WallpaperClient(WallpaperTransport {
            WallpaperHttpResult(200, "png".toByteArray(), "https://w.wallhaven.cc/full/ab/a.jpg", true)
        })
        assertEquals("png", client.text("https://wallhaven.cc/api/v1/search"))
    }

    @Test fun rejectsOversizedBodies() {
        val client = WallpaperClient(WallpaperTransport {
            WallpaperHttpResult(200, ByteArray(WallpaperClient.MAX_BODY + 1), "https://www.bing.com/a.jpg", true)
        })
        assertNull(client.bytes("https://www.bing.com/a.jpg"))
    }

    @Test fun rejectsFailedStatus() {
        val client = WallpaperClient(WallpaperTransport {
            WallpaperHttpResult(404, byteArrayOf(1), "https://www.bing.com/missing", false)
        })
        assertNull(client.bytes("https://www.bing.com/missing"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :common:testDebugUnitTest --tests com.shihab.diplay.wallpaper.WallpaperClientTest`

Expected: FAIL，`Unresolved reference: WallpaperClient`。

- [ ] **Step 3: Write minimal implementation**

Create `common/src/main/java/com/shihab/diplay/wallpaper/WallpaperClient.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

internal data class WallpaperHttpResult(
    val status: Int,
    val body: ByteArray,
    val finalUrl: String,
    val ok: Boolean,
)

internal fun interface WallpaperTransport {
    fun get(url: String): WallpaperHttpResult
}

internal class WallpaperClient(private val transport: WallpaperTransport) {
    fun text(url: String): String? = bytes(url)?.toString(Charsets.UTF_8)

    fun bytes(url: String): ByteArray? {
        if (!WallpaperHosts.allowed(url)) return null
        val result = try {
            transport.get(url)
        } catch (error: Exception) {
            return null
        }
        if (!result.ok || result.status !in 200..299) return null
        if (!WallpaperHosts.allowed(result.finalUrl)) return null
        if (result.body.size > MAX_BODY) return null
        return result.body
    }

    companion object {
        const val MAX_BODY = 12 * 1024 * 1024
    }
}
```

Create `common/src/main/java/com/shihab/diplay/wallpaper/OkHttpWallpaperTransport.kt`。不要在单元测试里实例化这个类。信任库加载按 `DiPlayUpdateClient` 里逐段解析 PEM 的方式读取 `R.raw.update_ca_bundle`，再和系统 `TrustManager` 合成；任一信任库通过即可，主机名校验不关闭。

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import android.content.Context
import com.shihab.diplay.host.R
import com.shihab.diplay.util.AppLog
import okhttp3.Call
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.TlsVersion
import java.io.IOException
import java.net.Inet4Address
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal class OkHttpWallpaperTransport(context: Context) : WallpaperTransport {
    private val app = context.applicationContext
    private val agent = userAgent(app)
    private val calls = CopyOnWriteArrayList<Call>()
    private val client: OkHttpClient by lazy { buildClient(app) }

    override fun get(url: String): WallpaperHttpResult {
        if (!WallpaperHosts.allowed(url)) return WallpaperHttpResult(0, ByteArray(0), url, false)
        val request = Request.Builder().url(url).header("User-Agent", agent).build()
        val call = client.newCall(request)
        calls.add(call)
        return try {
            call.execute().use { response ->
                val finalUrl = response.request().url().toString()
                val payload = response.body()
                if (payload == null) {
                    WallpaperHttpResult(response.code(), ByteArray(0), finalUrl, false)
                } else {
                    val length = payload.contentLength()
                    val bytes = if (length > WallpaperClient.MAX_BODY) {
                        null
                    } else {
                        WallpaperBitmaps.readCapped(payload.byteStream(), WallpaperClient.MAX_BODY)
                    }
                    if (bytes == null) WallpaperHttpResult(response.code(), ByteArray(0), finalUrl, false)
                    else WallpaperHttpResult(response.code(), bytes, finalUrl, response.isSuccessful)
                }
            }
        } catch (error: Exception) {
            AppLog.w("Wallpaper", "request failed", error)
            WallpaperHttpResult(0, ByteArray(0), url, false)
        } finally {
            calls.remove(call)
        }
    }

    fun cancel() {
        calls.forEach { it.cancel() }
    }

    companion object {
        fun userAgent(context: Context): String {
            val version = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (error: Exception) {
                null
            } ?: "0"
            return "75Play/$version (wallpaper)"
        }

        private fun buildClient(context: Context): OkHttpClient {
            val trust = compositeTrust(context)
            val factory = SSLContext.getInstance("TLSv1.2", org.conscrypt.Conscrypt.newProvider())
                .apply { init(null, arrayOf(trust), null) }
                .socketFactory
            val spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2)
                .build()
            return OkHttpClient.Builder()
                .sslSocketFactory(factory, trust)
                .connectionSpecs(listOf(spec))
                .protocols(listOf(Protocol.HTTP_1_1))
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .dns { hostname ->
                    Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
                }
                .addNetworkInterceptor { chain ->
                    val next = chain.request().url().toString()
                    if (!WallpaperHosts.allowed(next)) throw IOException("wallpaper host refused")
                    chain.proceed(chain.request())
                }
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        private fun compositeTrust(context: Context): X509TrustManager {
            val systemFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            systemFactory.init(null as KeyStore?)
            val system = systemFactory.trustManagers.filterIsInstance<X509TrustManager>().single()
            val bundled = bundledTrust(context)
            return object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    accept(chain, authType, X509TrustManager::checkClientTrusted)
                }
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    accept(chain, authType, X509TrustManager::checkServerTrusted)
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    (system.acceptedIssuers + bundled.acceptedIssuers)
                private fun accept(
                    chain: Array<X509Certificate>,
                    authType: String,
                    check: X509TrustManager.(Array<X509Certificate>, String) -> Unit,
                ) {
                    try {
                        system.check(chain, authType)
                    } catch (systemError: Exception) {
                        try {
                            bundled.check(chain, authType)
                        } catch (bundledError: Exception) {
                            throw CertificateException(systemError)
                        }
                    }
                }
            }
        }

        private fun bundledTrust(context: Context): X509TrustManager {
            val factory = java.security.cert.CertificateFactory.getInstance("X.509")
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
            context.resources.openRawResource(R.raw.update_ca_bundle).use { stream ->
                val pem = stream.readBytes().toString(Charsets.UTF_8)
                pem.split("-----BEGIN CERTIFICATE-----").drop(1).forEachIndexed { index, block ->
                    val end = block.indexOf("-----END CERTIFICATE-----")
                    if (end < 0) return@forEachIndexed
                    val certPem = "-----BEGIN CERTIFICATE-----" + block.substring(0, end) +
                        "-----END CERTIFICATE-----\n"
                    try {
                        store.setCertificateEntry("wallpaper-root-$index", factory.generateCertificate(certPem.byteInputStream()))
                    } catch (error: Exception) {
                        AppLog.w("Wallpaper", "wallpaper CA certificate parse failed", error)
                    }
                }
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trust.init(store)
            return trust.trustManagers.filterIsInstance<X509TrustManager>().single()
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :common:testDebugUnitTest --tests com.shihab.diplay.wallpaper.WallpaperClientTest`

Expected: BUILD SUCCESSFUL。不要新增会构造 `OkHttpWallpaperTransport` 的测试。

- [ ] **Step 5: Commit**

```powershell
git add common/src/main/java/com/shihab/diplay/wallpaper/WallpaperClient.kt common/src/main/java/com/shihab/diplay/wallpaper/OkHttpWallpaperTransport.kt common/src/test/java/com/shihab/diplay/wallpaper/WallpaperClientTest.kt
git commit -m "feat: add the wallpaper HTTP client"
```

---

### Task 4: 壳层根背景

**Files:**
- Modify: `common/src/main/java/com/shihab/diplay/ui/theme/Spacing.kt`（`ShellComponentSize` 末尾）
- Modify: `common/src/main/java/com/shihab/diplay/ui/LayoutMetrics.kt`
- Create: `common/src/main/java/com/shihab/diplay/wallpaper/CenterCropBitmapDrawable.kt`
- Create: `common/src/main/java/com/shihab/diplay/wallpaper/ShellWallpaper.kt`
- Modify: `common/src/main/java/com/shihab/diplay/ui/shell/ShellChrome.kt`
- Create: `common/src/test/java/com/shihab/diplay/wallpaper/ShellWallpaperTest.kt`

**Interfaces:**
- Consumes: `WallpaperStore.activeFile()`、`WallpaperBitmaps.decodeFile`
- Produces:
  - `ShellComponentSize.WALLPAPER_THUMB = 96`
  - `LayoutMetrics.wallpaperThumbPx(context: Context): Int`
  - `CenterCropBitmapDrawable(bitmap: Bitmap)`
  - `ShellWallpaper.apply(root: View)`
  - `ShellChrome.applyWallpaper()`

- [ ] **Step 1: Write the failing test**

Create `common/src/test/java/com/shihab/diplay/wallpaper/ShellWallpaperTest.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import android.graphics.Bitmap
import android.widget.LinearLayout
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ShellWallpaperTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.filesDir.resolve("wallpaper").deleteRecursively()
    }

    @Test fun defaultModeUsesTheBuiltInBackground() {
        val root = LinearLayout(context)
        WallpaperStore(context).saveMode(WallpaperMode.DEFAULT)
        ShellWallpaper.apply(root)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(shadowOf(root.background).createdFromResId != 0)
    }

    @Test fun customModeInstallsTheCenterCropDrawable() {
        val store = WallpaperStore(context)
        val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        assertTrue(store.writeCustom(bitmap))
        bitmap.recycle()
        store.saveMode(WallpaperMode.CUSTOM)
        val root = LinearLayout(context)
        ShellWallpaper.apply(root)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(root.background is CenterCropBitmapDrawable)
    }

    @Test fun missingDailyFileLeavesNoCenterCrop() {
        WallpaperStore(context).saveMode(WallpaperMode.DAILY)
        val root = LinearLayout(context)
        ShellWallpaper.apply(root)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(root.background !is CenterCropBitmapDrawable)
        assertNull(WallpaperStore(context).activeFile())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :common:testDebugUnitTest --tests com.shihab.diplay.wallpaper.ShellWallpaperTest`

Expected: FAIL，`Unresolved reference: ShellWallpaper`。

- [ ] **Step 3: Write minimal implementation**

在 `ShellComponentSize` 末尾、类结束大括号之前添加：

```kotlin
    /** 搜索结果缩略图边长。驾驶舱按物理像素使用，不另乘 density。 */
    const val WALLPAPER_THUMB = 96
```

在 `LayoutMetrics` 里 `capMinH` 之后添加：

```kotlin
    fun wallpaperThumbPx(context: Context): Int = px(context, ShellComponentSize.WALLPAPER_THUMB)
```

Create `common/src/main/java/com/shihab/diplay/wallpaper/CenterCropBitmapDrawable.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

internal class CenterCropBitmapDrawable(private val bitmap: Bitmap) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun draw(canvas: Canvas) {
        val area = bounds
        if (area.width() <= 0 || area.height() <= 0 || bitmap.isRecycled) return
        val scale = maxOf(area.width().toFloat() / bitmap.width, area.height().toFloat() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = area.left + (area.width() - width) / 2f
        val top = area.top + (area.height() - height) / 2f
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
```

Create `common/src/main/java/com/shihab/diplay/wallpaper/ShellWallpaper.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import android.graphics.Bitmap
import android.view.View
import com.shihab.diplay.ui.GlassColors
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal object ShellWallpaper {
    private val generation = AtomicInteger()
    private val executor = Executors.newSingleThreadExecutor()
    private var shown: Bitmap? = null

    fun apply(root: View) {
        val ticket = generation.incrementAndGet()
        val file = WallpaperStore(root.context).activeFile()
        if (file == null) {
            root.setBackgroundResource(GlassColors.bgHome())
            recycleShown()
            return
        }
        executor.execute {
            val bitmap = WallpaperBitmaps.decodeFile(file)
            root.post {
                if (ticket != generation.get()) {
                    bitmap?.recycle()
                    return@post
                }
                if (bitmap == null) {
                    root.setBackgroundResource(GlassColors.bgHome())
                    return@post
                }
                val previous = shown
                shown = bitmap
                root.background = CenterCropBitmapDrawable(bitmap)
                if (previous != null && previous !== bitmap && !previous.isRecycled) previous.recycle()
            }
        }
    }

    private fun recycleShown() {
        val previous = shown
        shown = null
        if (previous != null && !previous.isRecycled) previous.recycle()
    }
}
```

在 `ShellChrome` 增加 `private var rootView: LinearLayout? = null`。`install()` 创建根 `LinearLayout` 后赋值 `rootView = root`。`install()` 末尾调用 `applyWallpaper()`。新增：

```kotlin
    fun applyWallpaper() {
        val root = rootView ?: return
        ShellWallpaper.apply(root)
    }
```

`import com.shihab.diplay.wallpaper.ShellWallpaper`。

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :common:testDebugUnitTest --tests com.shihab.diplay.wallpaper.ShellWallpaperTest`

Expected: BUILD SUCCESSFUL。默认模式那条断言的是背景来自资源 id，自定义模式那条断言类型是 `CenterCropBitmapDrawable`。若 Robolectric 上 `setBackgroundResource` 的 shadow id 为 0，改为断言 `root.background !is CenterCropBitmapDrawable`，不要改生产代码去迎合错误的资源 id。

- [ ] **Step 5: Commit**

```powershell
git add common/src/main/java/com/shihab/diplay/ui/theme/Spacing.kt common/src/main/java/com/shihab/diplay/ui/LayoutMetrics.kt common/src/main/java/com/shihab/diplay/wallpaper/CenterCropBitmapDrawable.kt common/src/main/java/com/shihab/diplay/wallpaper/ShellWallpaper.kt common/src/main/java/com/shihab/diplay/ui/shell/ShellChrome.kt common/src/test/java/com/shihab/diplay/wallpaper/ShellWallpaperTest.kt
git commit -m "feat: paint one wallpaper behind every shell page"
```

---

### Task 5: 设置行、文案与图库

**Files:**
- Modify: `common/src/main/res/values/strings.xml`
- Modify: `common/src/main/res/values-zh-rCN/strings.xml`
- Modify: `common/src/main/res/values-zh-rTW/strings.xml`
- Modify: `common/src/main/res/values-es/strings.xml`
- Modify: `common/src/main/res/values-ru/strings.xml`
- Modify: `common/src/main/res/values-uk/strings.xml`
- Modify: `common/src/main/res/values-ar/strings.xml`
- Modify: `common/src/main/java/com/shihab/diplay/ui/DiPlayHost.kt`
- Modify: `common/src/main/java/com/shihab/diplay/DiPlayActivity.kt`
- Create: `common/src/main/java/com/shihab/diplay/ui/settings/WallpaperSettingsColumn.kt`
- Modify: `common/src/main/java/com/shihab/diplay/ui/FragmentSetting.kt`

**Interfaces:**
- Consumes: `WallpaperCatalog.rows`、`dailyStatus`、`modeAfterPickerCancel`、`WallpaperStore`、`WallpaperBitmaps.copyStream`、`decodeFile`、`DiPlayHost`
- Produces:
  - `DiPlayHost.applyShellWallpaper()`
  - `DiPlayHost.refreshDailyWallpaper()` 先留空实现，Task 7 填上
  - `FragmentSetting.refreshWallpaperStatus()`
  - `WallpaperSettingsColumn(page: Fragment, pickImage: () -> Unit)`：`fill(card: LinearLayout)`、`onPickerResult(uri: Uri?)`、`refreshStatus()`

- [ ] **Step 1: Add strings, then run the translation test so it fails before the other locales exist**

先只改 `values/strings.xml`，把下面这一组插在 `shell_nav_dashboard` 之前：

```xml
    <string name="settings_wallpaper">Wallpaper</string>
    <string name="settings_wallpaper_custom">Custom image</string>
    <string name="settings_wallpaper_daily">Daily wallpaper</string>
    <string name="settings_wallpaper_decode_failed">This image can\'t be used</string>
    <string name="settings_wallpaper_default">Default background</string>
    <string name="settings_wallpaper_pick">Choose image</string>
    <string name="settings_wallpaper_picked">Set</string>
    <string name="settings_wallpaper_search">Search wallpapers</string>
    <string name="settings_wallpaper_search_action">Search</string>
    <string name="settings_wallpaper_search_empty">No wallpapers found</string>
    <string name="settings_wallpaper_search_failed">Search is unavailable. Try again later.</string>
    <string name="settings_wallpaper_search_hint">Enter a keyword</string>
    <string name="settings_wallpaper_source">Daily source</string>
    <string name="settings_wallpaper_source_bing">Bing</string>
    <string name="settings_wallpaper_source_wallhaven">Wallhaven</string>
    <string name="settings_wallpaper_status_default">Using the default background</string>
    <string name="settings_wallpaper_status_previous">Keeping the previous image</string>
    <string name="settings_wallpaper_status_today">Updated today</string>
    <string name="settings_wallpaper_thumb_cd">Wallpaper result</string>
    <string name="settings_wallpaper_thumb_failed">Couldn\'t download</string>
    <string name="settings_wallpaper_unset">Not chosen</string>
```

Run: `.\gradlew.bat :common:testDebugUnitTest --tests com.shihab.diplay.SettingsTranslationsTest`

Expected: FAIL，某个 `values-xx` 缺少 `settings_wallpaper` 键。

- [ ] **Step 2: Add the same keys to every locale**

在每个 `values-xx/strings.xml` 的 `shell_nav_dashboard` 之前插入同名键。译文：

`values-zh-rCN`：壁纸、自定义图片、每日壁纸、这张图片无法使用、默认背景、选择图片、已设置、搜索壁纸、搜索、没有找到壁纸、暂时无法搜索，请稍后再试、输入关键词、每日来源、必应、Wallhaven、暂时使用默认背景、沿用上一张、今日已更新、壁纸结果、下载失败、未选择。

`values-zh-rTW`：桌布、自訂圖片、每日桌布、這張圖片無法使用、預設背景、選擇圖片、已設定、搜尋桌布、搜尋、沒有找到桌布、暫時無法搜尋，請稍後再試、輸入關鍵字、每日來源、必應、Wallhaven、暫時使用預設背景、沿用上一張、今日已更新、桌布結果、下載失敗、未選擇。

`values-es`：Fondo、Imagen personalizada、Fondo diario、Esta imagen no se puede usar、Fondo predeterminado、Elegir imagen、Definida、Buscar fondos、Buscar、No se encontraron fondos、La búsqueda no está disponible. Inténtalo más tarde.、Escribe una palabra、Fuente diaria、Bing、Wallhaven、Usando el fondo predeterminado、Se conserva la anterior、Actualizado hoy、Resultado de fondo、No se pudo descargar、Sin elegir。

`values-ru`：Обои、Своё изображение、Обои дня、Это изображение нельзя использовать、Фон по умолчанию、Выбрать изображение、Выбрано、Поиск обоев、Найти、Обоев не найдено、Поиск сейчас недоступен. Повторите позже.、Введите слово、Источник дня、Bing、Wallhaven、Пока фон по умолчанию、Оставлено предыдущее、Обновлено сегодня、Результат обоев、Не удалось скачать、Не выбрано。

`values-uk`：Шпалери、Власне зображення、Шпалери дня、Це зображення не можна використати、Тло за замовчуванням、Вибрати зображення、Вибрано、Пошук шпалер、Знайти、Шпалер не знайдено、Пошук зараз недоступний. Спробуйте пізніше.、Введіть слово、Джерело дня、Bing、Wallhaven、Поки тло за замовчуванням、Залишено попереднє、Оновлено сьогодні、Результат шпалер、Не вдалося завантажити、Не вибрано。

`values-ar`：الخلفية、صورة مخصصة、خلفية اليوم、لا يمكن استخدام هذه الصورة、الخلفية الافتراضية、اختيار صورة、تم التعيين、البحث عن خلفيات、بحث、لم يتم العثور على خلفيات、البحث غير متاح الآن. حاول لاحقاً.、أدخل كلمة、مصدر اليوم、Bing、Wallhaven、استخدام الخلفية الافتراضية مؤقتاً、الإبقاء على السابقة、تم التحديث اليوم、نتيجة الخلفية、تعذر التنزيل、لم يتم الاختيار。

键的顺序与英文文件一致。这些字符串没有 `%` 占位符。

- [ ] **Step 3: Run the translation test**

Run: `.\gradlew.bat :common:testDebugUnitTest --tests com.shihab.diplay.SettingsTranslationsTest`

Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Wire the rows and the picker**

`DiPlayHost` 在 `rebuildTheme()` 之后增加：

```kotlin
    fun applyShellWallpaper()
    /** 每日模式且日期不是今天时在后台下载。Task 7 之前空实现。 */
    fun refreshDailyWallpaper()
```

`DiPlayActivity` 实现：

```kotlin
    override fun applyShellWallpaper() {
        shellChrome.applyWallpaper()
    }

    override fun refreshDailyWallpaper() = Unit
```

`FragmentSetting` 注册图库并在画面与主题列里调用 `wallpaperColumn.fill(card)`，放在 `videoColumn.fill(card)` 之后：

```kotlin
class FragmentSetting : Fragment() {
    private val displayColumn = DisplaySettingsColumn(this)
    private val videoColumn = VideoSettingsColumn(this)
    private val audioColumn = AudioSettingsColumn(this)
    private val vehicleColumn = VehicleColumn(this)
    private val pickWallpaper = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (this::wallpaperColumn.isInitialized) wallpaperColumn.onPickerResult(uri)
    }
    private lateinit var wallpaperColumn: WallpaperSettingsColumn

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wallpaperColumn = WallpaperSettingsColumn(this) { pickWallpaper.launch("image/*") }
    }

    fun refreshWallpaperStatus() {
        if (this::wallpaperColumn.isInitialized && isAdded) wallpaperColumn.refreshStatus()
    }
}
```

`import androidx.activity.result.contract.ActivityResultContracts`。

在现有 `onCreateView` 的画面与主题 `Section` 里，`videoColumn.fill(card)` 之后加 `wallpaperColumn.fill(card)`。

Create `common/src/main/java/com/shihab/diplay/ui/settings/WallpaperSettingsColumn.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.settings

import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.DiPlayDialog
import com.shihab.diplay.ui.GlassColors
import com.shihab.diplay.ui.LayoutMetrics
import com.shihab.diplay.wallpaper.WallpaperBitmaps
import com.shihab.diplay.wallpaper.WallpaperCatalog
import com.shihab.diplay.wallpaper.WallpaperDailySource
import com.shihab.diplay.wallpaper.WallpaperMode
import com.shihab.diplay.wallpaper.WallpaperStore
import java.time.LocalDate
import java.util.concurrent.Executors

internal class WallpaperSettingsColumn(
    page: Fragment,
    private val pickImage: () -> Unit,
) : SettingsColumnKit(page) {
    private val io = Executors.newSingleThreadExecutor()
    private val store get() = WallpaperStore(requireContext())
    private var previousMode = WallpaperMode.DEFAULT
    private lateinit var modeCap: TextView
    private lateinit var pickRow: View
    private lateinit var pickCap: TextView
    private lateinit var sourceRow: View
    private lateinit var sourceCap: TextView
    private lateinit var statusView: TextView

    fun fill(card: LinearLayout) {
        val ctx = card.context
        modeCap = capPick(ctx, modeLabel(store.mode())) { showModePicker(it) }
        card.addView(settingRow(ctx, ctx.getString(R.string.settings_wallpaper), modeCap))
        pickCap = capPick(ctx, pickLabel()) {
            previousMode = store.mode()
            pickImage()
        }
        pickRow = settingRow(ctx, ctx.getString(R.string.settings_wallpaper_pick), pickCap)
        card.addView(pickRow)
        sourceCap = capPick(ctx, sourceLabel(store.source())) { showSourcePicker(it) }
        statusView = TextView(ctx).apply {
            textSize = LayoutMetrics.settingsCapTextSize(ctx)
            setTextColor(GlassColors.TEXT_MUTED)
            gravity = Gravity.END
        }
        val sourceBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            isClickable = true
            setOnClickListener { sourceCap.performClick() }
            addView(sourceCap)
            addView(statusView)
        }
        sourceRow = settingRow(ctx, ctx.getString(R.string.settings_wallpaper_source), sourceBox)
        card.addView(sourceRow)
        val searchCap = capPick(ctx, ctx.getString(R.string.settings_wallpaper_search_action)) { openSearch() }
        card.addView(settingRow(ctx, ctx.getString(R.string.settings_wallpaper_search), searchCap))
        render()
    }

    fun refreshStatus() {
        if (!::pickCap.isInitialized) return
        render()
    }

    fun onPickerResult(uri: Uri?) {
        if (uri == null) {
            store.saveMode(WallpaperCatalog.modeAfterPickerCancel(previousMode, store.hasCustom()))
            render()
            return
        }
        val ctx = requireContext()
        io.execute {
            val incoming = store.incomingFile()
            val copied = ctx.contentResolver.openInputStream(uri)?.use {
                WallpaperBitmaps.copyStream(it, incoming)
            } == true
            val bitmap = if (copied) WallpaperBitmaps.decodeFile(incoming) else null
            val saved = bitmap != null && store.writeCustom(bitmap)
            bitmap?.recycle()
            incoming.delete()
            val hostActivity = activity ?: return@execute
            hostActivity.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (saved) {
                    store.saveMode(WallpaperMode.CUSTOM)
                    host.applyShellWallpaper()
                } else {
                    DiPlayDialog.showInfo(
                        ctx,
                        ctx.getString(R.string.settings_wallpaper),
                        ctx.getString(R.string.settings_wallpaper_decode_failed),
                    )
                }
                render()
            }
        }
    }

    private fun openSearch() = Unit

    private fun showModePicker(anchor: TextView) {
        val modes = listOf(WallpaperMode.DEFAULT, WallpaperMode.CUSTOM, WallpaperMode.DAILY)
        showDropdown(anchor, modes.map { modeLabel(it) }, modes.indexOf(store.mode()).coerceAtLeast(0)) { index ->
            when (val next = modes[index]) {
                WallpaperMode.CUSTOM -> if (store.hasCustom()) {
                    store.saveMode(WallpaperMode.CUSTOM)
                    host.applyShellWallpaper()
                } else {
                    previousMode = store.mode()
                    pickImage()
                }
                WallpaperMode.DAILY -> {
                    store.saveMode(next)
                    host.applyShellWallpaper()
                    host.refreshDailyWallpaper()
                }
                WallpaperMode.DEFAULT -> {
                    store.saveMode(next)
                    host.applyShellWallpaper()
                }
            }
            render()
        }
    }

    private fun showSourcePicker(anchor: TextView) {
        val sources = listOf(WallpaperDailySource.BING, WallpaperDailySource.WALLHAVEN)
        showDropdown(anchor, sources.map { sourceLabel(it) }, sources.indexOf(store.source()).coerceAtLeast(0)) { index ->
            store.saveSource(sources[index])
            host.applyShellWallpaper()
            host.refreshDailyWallpaper()
            render()
        }
    }

    private fun render() {
        val ctx = modeCap.context
        val mode = store.mode()
        val rows = WallpaperCatalog.rows(mode)
        modeCap.text = modeLabel(mode) + " ▾"
        pickCap.text = pickLabel() + " ▾"
        sourceCap.text = sourceLabel(store.source()) + " ▾"
        statusView.text = when (
            WallpaperCatalog.dailyStatus(store.dailyDay(), LocalDate.now().toString(), store.hasDaily())
        ) {
            com.shihab.diplay.wallpaper.DailyStatus.UPDATED_TODAY -> ctx.getString(R.string.settings_wallpaper_status_today)
            com.shihab.diplay.wallpaper.DailyStatus.KEEPING_PREVIOUS -> ctx.getString(R.string.settings_wallpaper_status_previous)
            com.shihab.diplay.wallpaper.DailyStatus.USING_DEFAULT -> ctx.getString(R.string.settings_wallpaper_status_default)
        }
        pickRow.visibility = if (rows.pick) View.VISIBLE else View.GONE
        sourceRow.visibility = if (rows.source) View.VISIBLE else View.GONE
    }

    private fun modeLabel(mode: WallpaperMode): String {
        val ctx = requireContext()
        return when (mode) {
            WallpaperMode.DEFAULT -> ctx.getString(R.string.settings_wallpaper_default)
            WallpaperMode.CUSTOM -> ctx.getString(R.string.settings_wallpaper_custom)
            WallpaperMode.DAILY -> ctx.getString(R.string.settings_wallpaper_daily)
        }
    }

    private fun sourceLabel(source: WallpaperDailySource): String {
        val ctx = requireContext()
        return when (source) {
            WallpaperDailySource.BING -> ctx.getString(R.string.settings_wallpaper_source_bing)
            WallpaperDailySource.WALLHAVEN -> ctx.getString(R.string.settings_wallpaper_source_wallhaven)
        }
    }

    private fun pickLabel(): String {
        val ctx = requireContext()
        return ctx.getString(if (store.hasCustom()) R.string.settings_wallpaper_picked else R.string.settings_wallpaper_unset)
    }
}
```

`capPick` 已经带上 ` ▾`，`render()` 再写一遍 ` ▾`，避免下拉把文案改掉后和状态不一致。不要在本任务引用 `WallpaperSearchDialog`。

- [ ] **Step 5: Compile**

Run: `.\gradlew.bat :common:compileDebugKotlin :common:testDebugUnitTest --tests com.shihab.diplay.SettingsTranslationsTest --tests com.shihab.diplay.wallpaper.WallpaperCatalogTest`

Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: Commit**

```powershell
git add common/src/main/res/values/strings.xml common/src/main/res/values-zh-rCN/strings.xml common/src/main/res/values-zh-rTW/strings.xml common/src/main/res/values-es/strings.xml common/src/main/res/values-ru/strings.xml common/src/main/res/values-uk/strings.xml common/src/main/res/values-ar/strings.xml common/src/main/java/com/shihab/diplay/ui/DiPlayHost.kt common/src/main/java/com/shihab/diplay/DiPlayActivity.kt common/src/main/java/com/shihab/diplay/ui/settings/WallpaperSettingsColumn.kt common/src/main/java/com/shihab/diplay/ui/FragmentSetting.kt
git commit -m "feat: add wallpaper choices to the shell settings"
```

---

### Task 6: 搜索对话框

**Files:**
- Create: `common/src/main/java/com/shihab/diplay/wallpaper/WallpaperSearch.kt`
- Create: `common/src/test/java/com/shihab/diplay/wallpaper/WallpaperSearchTest.kt`
- Create: `common/src/main/java/com/shihab/diplay/ui/settings/WallpaperSearchDialog.kt`
- Modify: `common/src/main/java/com/shihab/diplay/ui/settings/WallpaperSettingsColumn.kt`（只填 `openSearch()`）

**Interfaces:**
- Consumes: `WallpaperCatalog.wallhavenSearchUrl`、`parseWallhavenSearch`、`modeAfterSearch`、`WallpaperClient`、`WallpaperBitmaps.decode`、`WallpaperStore.writeCustom`
- Produces:
  - `sealed class WallpaperSearchResult { data object NeedQuery : WallpaperSearchResult(); data object Failed : WallpaperSearchResult(); data object None : WallpaperSearchResult(); data class Hits(val images: List<WallpaperImage>) : WallpaperSearchResult() }`
  - `WallpaperSearchController(client: WallpaperClient).search(query: String): WallpaperSearchResult`
  - `WallpaperSearchDialog.show(context: Context, onApplied: () -> Unit)`

- [ ] **Step 1: Write the failing test**

Create `common/src/test/java/com/shihab/diplay/wallpaper/WallpaperSearchTest.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperSearchTest {
    @Test fun blankQueryDoesNotCallTheClient() {
        var calls = 0
        val controller = WallpaperSearchController(WallpaperClient(WallpaperTransport {
            calls++
            WallpaperHttpResult(200, ByteArray(0), it, true)
        }))
        assertEquals(WallpaperSearchResult.NeedQuery, controller.search("  "))
        assertEquals(0, calls)
    }

    @Test fun transportFailureBecomesFailed() {
        val controller = WallpaperSearchController(WallpaperClient(WallpaperTransport {
            WallpaperHttpResult(500, ByteArray(0), it, false)
        }))
        assertEquals(WallpaperSearchResult.Failed, controller.search("road"))
    }

    @Test fun emptyDataBecomesNoneAndHitsStayHits() {
        val empty = WallpaperSearchController(WallpaperClient(WallpaperTransport {
            WallpaperHttpResult(200, """{"data":[]}""".toByteArray(), "https://wallhaven.cc/api/v1/search", true)
        }))
        assertEquals(WallpaperSearchResult.None, empty.search("road"))
        val hit = WallpaperSearchController(WallpaperClient(WallpaperTransport {
            val json = """{"data":[{"path":"https://w.wallhaven.cc/full/ab/a.jpg","thumbs":{"small":"https://th.wallhaven.cc/small/ab/a.jpg"}}]}"""
            WallpaperHttpResult(200, json.toByteArray(), "https://wallhaven.cc/api/v1/search", true)
        }))
        val hits = hit.search("road") as WallpaperSearchResult.Hits
        assertEquals(1, hits.images.size)
        assertTrue(hits.images[0].thumbUrl.contains("th.wallhaven.cc"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :common:testDebugUnitTest --tests com.shihab.diplay.wallpaper.WallpaperSearchTest`

Expected: FAIL，`Unresolved reference: WallpaperSearchController`。

- [ ] **Step 3: Write the controller and dialog**

Create `common/src/main/java/com/shihab/diplay/wallpaper/WallpaperSearch.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

internal sealed class WallpaperSearchResult {
    data object NeedQuery : WallpaperSearchResult()
    data object Failed : WallpaperSearchResult()
    data object None : WallpaperSearchResult()
    data class Hits(val images: List<WallpaperImage>) : WallpaperSearchResult()
}

internal class WallpaperSearchController(private val client: WallpaperClient) {
    fun search(query: String): WallpaperSearchResult {
        val url = WallpaperCatalog.wallhavenSearchUrl(query) ?: return WallpaperSearchResult.NeedQuery
        val json = client.text(url) ?: return WallpaperSearchResult.Failed
        return when (val parsed = WallpaperCatalog.parseWallhavenSearch(json)) {
            is WallpaperSearchParse.Hits -> WallpaperSearchResult.Hits(parsed.images)
            WallpaperSearchParse.Empty -> WallpaperSearchResult.None
            WallpaperSearchParse.Invalid -> WallpaperSearchResult.Failed
        }
    }
}
```

Create `common/src/main/java/com/shihab/diplay/ui/settings/WallpaperSearchDialog.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.settings

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.DiPlayDialog
import com.shihab.diplay.ui.GlassColors
import com.shihab.diplay.ui.LayoutMetrics
import com.shihab.diplay.wallpaper.OkHttpWallpaperTransport
import com.shihab.diplay.wallpaper.WallpaperBitmaps
import com.shihab.diplay.wallpaper.WallpaperCatalog
import com.shihab.diplay.wallpaper.WallpaperClient
import com.shihab.diplay.wallpaper.WallpaperImage
import com.shihab.diplay.wallpaper.WallpaperSearchController
import com.shihab.diplay.wallpaper.WallpaperSearchResult
import com.shihab.diplay.wallpaper.WallpaperStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object WallpaperSearchDialog {
    fun show(context: Context, onApplied: () -> Unit) {
        val transport = OkHttpWallpaperTransport(context)
        val client = WallpaperClient(transport)
        val controller = WallpaperSearchController(client)
        val closed = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(3)
        val main = Handler(Looper.getMainLooper())
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val input = EditText(context).apply {
            setSingleLine(true)
            hint = context.getString(R.string.settings_wallpaper_search_hint)
            setTextColor(GlassColors.TEXT_PRIMARY)
            setHintTextColor(GlassColors.TEXT_MUTED)
            setBackgroundResource(GlassColors.inputBg())
            val padH = LayoutMetrics.capPadH(context)
            val padV = LayoutMetrics.capPadV(context)
            setPadding(padH, padV, padH, padV)
        }
        val search = Button(context).apply {
            text = context.getString(R.string.settings_wallpaper_search_action)
            setBackgroundResource(GlassColors.btnPrimary())
            setTextColor(GlassColors.NAV_SELECTED_TEXT)
            minHeight = LayoutMetrics.rowMinH(context)
            minimumHeight = LayoutMetrics.rowMinH(context)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(search)
        }
        val progress = ProgressBar(context).apply { visibility = View.GONE }
        val status = TextView(context).apply {
            text = context.getString(R.string.settings_wallpaper_search_hint)
            setTextColor(GlassColors.TEXT_MUTED)
            textSize = LayoutMetrics.settingsCapTextSize(context)
        }
        val results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(header)
        content.addView(progress)
        content.addView(status)
        content.addView(results)
        val dialog = DiPlayDialog.showCustom(
            context,
            context.getString(R.string.settings_wallpaper_search),
            content,
            fillMaxHeight = true,
        )
        fun alive() = !closed.get() && dialog.isShowing
        dialog.setOnDismissListener {
            closed.set(true)
            transport.cancel()
            pool.shutdownNow()
        }
        search.setOnClickListener {
            results.removeAllViews()
            progress.visibility = View.VISIBLE
            status.visibility = View.VISIBLE
            status.text = ""
            val query = input.text?.toString().orEmpty()
            pool.execute {
                val outcome = controller.search(query)
                main.post {
                    if (!alive()) return@post
                    progress.visibility = View.GONE
                    when (outcome) {
                        WallpaperSearchResult.NeedQuery -> {
                            status.visibility = View.VISIBLE
                            status.text = context.getString(R.string.settings_wallpaper_search_hint)
                        }
                        WallpaperSearchResult.Failed -> {
                            status.visibility = View.VISIBLE
                            status.text = context.getString(R.string.settings_wallpaper_search_failed)
                        }
                        WallpaperSearchResult.None -> {
                            status.visibility = View.VISIBLE
                            status.text = context.getString(R.string.settings_wallpaper_search_empty)
                        }
                        is WallpaperSearchResult.Hits -> {
                            status.visibility = View.GONE
                            bindHits(context, results, outcome.images, client, pool, main, ::alive, dialog, onApplied)
                        }
                    }
                }
            }
        }
    }

    private fun bindHits(
        context: Context,
        results: LinearLayout,
        images: List<WallpaperImage>,
        client: WallpaperClient,
        pool: java.util.concurrent.ExecutorService,
        main: Handler,
        alive: () -> Boolean,
        dialog: android.app.Dialog,
        onApplied: () -> Unit,
    ) {
        val edge = LayoutMetrics.wallpaperThumbPx(context)
        var row: LinearLayout? = null
        images.forEachIndexed { index, image ->
            if (index % 4 == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                results.addView(row)
            }
            val note = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextColor(GlassColors.TEXT_MUTED)
                textSize = LayoutMetrics.ghostTextSize(context)
                visibility = View.GONE
            }
            val spinner = ProgressBar(context).apply { visibility = View.GONE }
            val thumb = ImageView(context).apply {
                contentDescription = context.getString(R.string.settings_wallpaper_thumb_cd)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setOnClickListener {
                    note.visibility = View.GONE
                    spinner.visibility = View.VISIBLE
                    pool.execute {
                        val bytes = client.bytes(image.fullUrl)
                        val bitmap = bytes?.let { WallpaperBitmaps.decode(it) }
                        val saved = bitmap != null && WallpaperStore(context).writeCustom(bitmap)
                        bitmap?.recycle()
                        main.post {
                            if (!alive()) return@post
                            spinner.visibility = View.GONE
                            if (!saved) {
                                note.text = context.getString(R.string.settings_wallpaper_thumb_failed)
                                note.visibility = View.VISIBLE
                                return@post
                            }
                            WallpaperStore(context).saveMode(WallpaperCatalog.modeAfterSearch())
                            onApplied()
                            dialog.dismiss()
                        }
                    }
                }
            }
            val cell = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(edge, edge)
                addView(thumb, FrameLayout.LayoutParams(edge, edge))
                addView(spinner, FrameLayout.LayoutParams(edge, edge, Gravity.CENTER))
                addView(note, FrameLayout.LayoutParams(edge, edge, Gravity.CENTER))
            }
            row!!.addView(cell)
            pool.execute {
                val bytes = client.bytes(image.thumbUrl)
                val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                main.post {
                    if (!alive()) {
                        bitmap?.recycle()
                        return@post
                    }
                    if (bitmap == null) {
                        note.text = context.getString(R.string.settings_wallpaper_thumb_failed)
                        note.visibility = View.VISIBLE
                    } else {
                        thumb.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }
}
```

`WallpaperSettingsColumn.openSearch()` 改为：

```kotlin
private fun openSearch() {
    WallpaperSearchDialog.show(requireContext()) {
        host.applyShellWallpaper()
        render()
    }
}
```

- [ ] **Step 4: Run tests**

Run: `.\gradlew.bat :common:compileDebugKotlin :common:testDebugUnitTest --tests com.shihab.diplay.wallpaper.WallpaperSearchTest`

Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

```powershell
git add common/src/main/java/com/shihab/diplay/wallpaper/WallpaperSearch.kt common/src/test/java/com/shihab/diplay/wallpaper/WallpaperSearchTest.kt common/src/main/java/com/shihab/diplay/ui/settings/WallpaperSearchDialog.kt common/src/main/java/com/shihab/diplay/ui/settings/WallpaperSettingsColumn.kt
git commit -m "feat: search wallpapers into the custom image"
```

---

### Task 7: 每日更新

**Files:**
- Create: `common/src/main/java/com/shihab/diplay/wallpaper/WallpaperDailyRefresh.kt`
- Create: `common/src/test/java/com/shihab/diplay/wallpaper/WallpaperDailyRefreshTest.kt`
- Modify: `common/src/main/java/com/shihab/diplay/DiPlayActivity.kt`

**Interfaces:**
- Consumes: `WallpaperCatalog.needsDailyDownload`、`bingArchiveUrl`、`wallhavenDailyUrl`、`parseBing`、`parseWallhavenDaily`、`WallpaperClient`、`WallpaperStore`、`WallpaperBitmaps.decode`
- Produces: `WallpaperDailyRefresh.refreshIfDue(context: Context, today: () -> String = { LocalDate.now().toString() }, client: WallpaperClient? = null, runner: (Runnable) -> Unit = { executor.execute(it) }, poster: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) }, onApplied: () -> Unit)`

- [ ] **Step 1: Write the failing test**

Create `common/src/test/java/com/shihab/diplay/wallpaper/WallpaperDailyRefreshTest.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class WallpaperDailyRefreshTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.filesDir.resolve("wallpaper").deleteRecursively()
    }

    @Test fun defaultModeDoesNotDownload() {
        val store = WallpaperStore(context)
        store.saveMode(WallpaperMode.DEFAULT)
        var calls = 0
        WallpaperDailyRefresh.refreshIfDue(
            context,
            today = { "2026-11-12" },
            client = countingClient { calls++ },
            runner = { it.run() },
            poster = { it.run() },
        ) {}
        assertEquals(0, calls)
        assertNull(store.dailyDay())
    }

    @Test fun sameDayDoesNotDownload() {
        val store = WallpaperStore(context)
        store.saveMode(WallpaperMode.DAILY)
        store.saveDailyDay("2026-11-12")
        var calls = 0
        WallpaperDailyRefresh.refreshIfDue(
            context,
            today = { "2026-11-12" },
            client = countingClient { calls++ },
            runner = { it.run() },
            poster = { it.run() },
        ) {}
        assertEquals(0, calls)
    }

    @Test fun failedDownloadKeepsThePreviousFile() {
        val store = WallpaperStore(context)
        store.saveMode(WallpaperMode.DAILY)
        val existing = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        assertTrue(store.writeDaily(existing))
        existing.recycle()
        val before = store.activeFile()!!.readBytes()
        WallpaperDailyRefresh.refreshIfDue(
            context,
            today = { "2026-11-12" },
            client = WallpaperClient(WallpaperTransport { WallpaperHttpResult(500, ByteArray(0), it, false) }),
            runner = { it.run() },
            poster = { it.run() },
        ) {}
        assertNull(store.dailyDay())
        assertTrue(store.activeFile()!!.readBytes().contentEquals(before))
    }

    @Test fun bingSuccessStoresToday() {
        val store = WallpaperStore(context)
        store.saveMode(WallpaperMode.DAILY)
        val jpeg = ByteArrayOutputStream().also { out ->
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out))
            bitmap.recycle()
        }.toByteArray()
        val imageUrl = "https://www.bing.com/th?id=OHR.Example_ZH-CN1_1920x1080.jpg"
        val client = WallpaperClient(WallpaperTransport { url ->
            val body = if (url == imageUrl) {
                jpeg
            } else {
                """{"images":[{"urlbase":"/th?id=OHR.Example_ZH-CN1"}]}""".toByteArray()
            }
            WallpaperHttpResult(200, body, url, true)
        })
        var applied = 0
        WallpaperDailyRefresh.refreshIfDue(
            context,
            today = { "2026-11-12" },
            client = client,
            runner = { it.run() },
            poster = { it.run() },
        ) { applied++ }
        assertEquals("2026-11-12", store.dailyDay())
        assertTrue(store.hasDaily())
        assertEquals(1, applied)
    }

    private fun countingClient(onCall: () -> Unit) = WallpaperClient(WallpaperTransport {
        onCall()
        WallpaperHttpResult(200, ByteArray(0), it, true)
    })
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :common:testDebugUnitTest --tests com.shihab.diplay.wallpaper.WallpaperDailyRefreshTest`

Expected: FAIL，`Unresolved reference: WallpaperDailyRefresh`。

- [ ] **Step 3: Write minimal implementation**

Create `WallpaperDailyRefresh.kt`：

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object WallpaperDailyRefresh {
    private val busy = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()

    fun refreshIfDue(
        context: Context,
        today: () -> String = { LocalDate.now().toString() },
        client: WallpaperClient? = null,
        runner: (Runnable) -> Unit = { executor.execute(it) },
        poster: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
        onApplied: () -> Unit,
    ) {
        val app = context.applicationContext
        val store = WallpaperStore(app)
        val day = today()
        if (!WallpaperCatalog.needsDailyDownload(store.mode(), store.dailyDay(), day)) return
        if (!busy.compareAndSet(false, true)) return
        val http = client ?: WallpaperClient(OkHttpWallpaperTransport(app))
        runner(Runnable {
            try {
                val bytes = download(store.source(), http)
                val bitmap = bytes?.let { WallpaperBitmaps.decode(it) }
                if (bitmap != null && store.writeDaily(bitmap)) {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    store.saveDailyDay(day)
                } else {
                    bitmap?.recycle()
                }
            } finally {
                busy.set(false)
            }
            poster(Runnable(onApplied))
        })
    }

    private fun download(source: WallpaperDailySource, client: WallpaperClient): ByteArray? {
        val page = when (source) {
            WallpaperDailySource.BING -> WallpaperCatalog.bingArchiveUrl()
            WallpaperDailySource.WALLHAVEN -> WallpaperCatalog.wallhavenDailyUrl()
        }
        val json = client.text(page) ?: return null
        val fetch = when (source) {
            WallpaperDailySource.BING -> WallpaperCatalog.parseBing(json)
            WallpaperDailySource.WALLHAVEN -> WallpaperCatalog.parseWallhavenDaily(json)
        }
        val image = (fetch as? WallpaperFetch.Ready)?.image ?: return null
        return client.bytes(image.fullUrl)
    }
}
```

失败时不要改 `daily.jpg`，也不要写 `wallpaper_daily_day`。`onApplied` 在成功和失败时都回调，让界面刷新状态。

把 `DiPlayActivity.refreshDailyWallpaper()` 从空实现改成：

```kotlin
    override fun refreshDailyWallpaper() {
        WallpaperDailyRefresh.refreshIfDue(this) {
            if (isFinishing || isDestroyed) return@refreshIfDue
            shellChrome.applyWallpaper()
            (supportFragmentManager.findFragmentByTag(ShellNavigator.SETTINGS) as? FragmentSetting)
                ?.refreshWallpaperStatus()
        }
    }
```

在 `onResume()` 里 `super.onResume()` 的下一行调用 `refreshDailyWallpaper()`。这样主题重建提前 return 时，当日下载仍然会开始。

- [ ] **Step 4: Run tests**

Run: `.\gradlew.bat :common:testDebugUnitTest --tests com.shihab.diplay.wallpaper.WallpaperDailyRefreshTest --tests com.shihab.diplay.wallpaper.WallpaperCatalogTest --tests com.shihab.diplay.wallpaper.WallpaperStoreTest --tests com.shihab.diplay.wallpaper.WallpaperClientTest --tests com.shihab.diplay.wallpaper.WallpaperSearchTest --tests com.shihab.diplay.SettingsTranslationsTest`

Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

```powershell
git add common/src/main/java/com/shihab/diplay/wallpaper/WallpaperDailyRefresh.kt common/src/test/java/com/shihab/diplay/wallpaper/WallpaperDailyRefreshTest.kt common/src/main/java/com/shihab/diplay/DiPlayActivity.kt
git commit -m "feat: refresh the daily wallpaper when the shell opens"
```

---

### Task 8: 总检查

**Files:** 无新文件。

- [ ] **Step 1: Run the module tests and lint**

Run:

```powershell
.\gradlew.bat :common:testDebugUnitTest :mobile:lintDebug
```

Expected: BUILD SUCCESSFUL。若 lint 只报本次新增的 API 20+ 调用或新的硬编码颜色，修掉再跑。既有的、与壁纸无关的 lint 失败不要顺手改。

- [ ] **Step 2: Manual check on a 1920×720 day and night session**

日间和夜间各看一次：主页、设置、关于是同一张图；三种模式单选；自定义图片取消图库后回到原模式；搜索点一张后变成自定义并不再变；每日模式同一天不重复请求，断网时沿用上一张或暂时显示车内图；切回默认背景后车内图还在；进入 CarPlay 后画面没有壁纸。

这一步没有自动化命令。做不到车机时，在计划完成说明里写明没做这项。
