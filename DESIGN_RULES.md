# 75Play 壳层设计规则

> 使用 `ui-android` 技能建立。任何 UI/UX 修改前必须阅读本文件。

## 技术边界

- Android Views + AppCompat，不引入 Jetpack Compose 或 Material 3。
- `minSdk` / `targetSdk` 均为 19，所有实现必须能在 Android 4.4.2 运行。
- 目标为 12.3 英寸、1920×720 固定横屏车机；其他尺寸只提供兼容排布。
- 只重构 `DiPlayActivity` 壳层。`CarPlayHostActivity` 继续只承载 CarPlay 会话。
- 技能中的 Compose、动态取色、预测返回和 API 34 要求因产品边界豁免。

## 视觉锚点

- 主锚点：Bergside `glassmorphism`。具体规则来源：`https://github.com/bergside/awesome-design-skills/tree/main/skills/glassmorphism`。
- 品牌种子色：`#1856FF`；成功 `#07CA6B`；警告 `#E89558`；危险 `#EA2143`。
- 信息密度：dense。以旧版车机截图的 600px 左右有效高度为下限，优先保证首屏可扫描。
- 日间与夜间主题均为必需。
- 动态壁纸取色：关闭。
- 表面策略：只使用两级半透明玻璃（壳层导航/顶栏、页面内容面板），设置行不得再套卡片。
- 壳层始终保留 `bg_home_photo.jpg` / `bg_home_light_photo.jpg` 两张车内背景图。
- 玻璃效果使用 API 19 可用的透明填充、细描边与遮罩，不使用 RenderEffect 或实时模糊。
- 按压反馈：色调切换；不使用装饰性缩放和循环动画。
- 图标：沿用项目矢量图标的圆角线性语言，选中态允许实心版本。
- 设置与车辆页不使用二级分类导航；宽屏并列展示全部分组，窄屏纵向连续展示。

## 生效中的禁止项

1. 禁止泛用渐变和多色装饰渐变。
2. 禁止占位文案、泛化宣传语和无含义按钮文案。
3. 禁止在页面代码中新增任意颜色、字号、间距和圆角值。
4. 禁止在页面内临时指定字体族。
5. 用户已明确确认半透明玻璃风格；仍禁止正文实时模糊、RenderEffect 和多层毛玻璃嵌套。
6. 禁止卡片嵌套以及同一卡片同时使用描边和阴影。
7. 禁止模板化三卡片、营销 Hero 和无层级按钮列表。
8. 禁止循环、自动播放、视差和纯装饰动画。

## 令牌唯一来源

| 角色 | 文件 |
|---|---|
| 日间/夜间角色色 | `common/src/main/java/com/shihab/diplay/ui/theme/Color.kt` |
| 字号与字体族 | `common/src/main/java/com/shihab/diplay/ui/theme/Type.kt` |
| 五级圆角 | `common/src/main/java/com/shihab/diplay/ui/theme/Shape.kt` |
| 间距与最小触控尺寸 | `common/src/main/java/com/shihab/diplay/ui/theme/Spacing.kt` |
| 功能动效时长 | `common/src/main/java/com/shihab/diplay/ui/theme/Motion.kt` |
| 主题入口 | `common/src/main/java/com/shihab/diplay/ui/theme/Theme.kt` |

页面不得新增颜色字面量。新增尺寸必须先加入令牌文件，并说明它对应的组件角色。

## 壳层结构

- 驾驶台：连接状态与主操作优先，应用网格作为次级区域。
- 控制中心：显示、音频、画面与主题、车辆控制四组同时展示；语言与方控归车辆控制。
- 车辆与设备：维护与诊断、应用信息、系统与硬件三组同时展示；开发者终端保留现有门禁。
- 顶栏只放页名、关键连接状态和时间；导航最多三个主目的地。
- 所有可点击控件至少 48 物理像素；目标车机固件会报告手机式 density，驾驶舱档位不得再次按 density 放大。图标按钮必须有无障碍说明。
- 所有异步区域必须提供加载、空数据和错误状态。

## 验证

```powershell
.\gradlew.bat detektDesign :common:testDebugUnitTest :mobile:lintDebug
```

页面改造完成后还必须在 1920×720 日间与夜间各检查一次，不允许文字截断、控件重叠或触控区域不足。
