# 传送门 Google Lens 原生识图实施与验证

2026-11-02，Asia/Shanghai。普通 APK 适配，`flavor=null`。范围和用户授权见 [scope.md](scope.md)。

## 当前交付边界

现有 TaplusBridge 接入独立的「Google Lens 识图」开关，将支持版本图片面板的「识物」改为「识图」。已实现当前图片交接、模块自身 WebView 会话、原生结果列表及取消处理。本轮从用户在 Codex 打开的正常 Google Lens 结果页读取了真实卡片结构；最终解析逻辑现场返回 `results_ready`，取得设定上限 40 条真实结果，并核对前四条标题、来源、缩略图和点击链接。验证码不是正常使用的必经步骤。

**没有连接 Android 设备，不能声称手机上的本地图上传、LSPosed Hook 和同面板结果回填已经端到端通过。** 版本为 0.12.0 / 22，本地长期密钥签名 Release。最终 APK 哈希、签名、对齐、Lint 和定向测试结果见 `artifacts/lens-validation-0.12.0.json`，这是本地候选交付，不是远程发布。

## 安装和操作

1. 安装 `artifacts/TaplusBridge-0.12.0-release.apk`，LSPosed 保持传送门及系统框架作用域，更新后重启。
2. 打开模块首页，确认「Google Lens 识图」开启；该开关独立于链接识别与应用流转开关。
3. 长按选中图片，打开图片识别面板，点击「识图」。当前选中图片将发送给 Google，需要手机能访问 Google。
4. 正常结果在原面板的原生控件中显示标题、来源和缩略图；点击卡片打开实际取得的网页链接。
5. 只有实际需要上传交互、Google 确认或验证时，才显式打开同一网页会话。返回或点击取消结束该任务。

关闭「Google Lens 识图」后，新打开的图片面板恢复原识物入口。模块本体必须处于启用状态；已有的冻结本体链接流转不提供 WebView 取数能力。扫码、保存图片、文字识别保留原分支。

## 宿主依据和实现

| 项目 | 已确认的本地事实 |
| --- | --- |
| 目标 | `com.miui.contentextension`，4.1.3 / 40013、5.1.5 / 50015 |
| 模块 | Java/Kotlin，libxposed API 102，minSdk 28，targetSdk 35 |
| 点击入口 | `text.cardview.TaplusRecognitionExpandedImageCard.onClick(View)`，与 `mRecognizeObject` 实例比对 |
| 原行为 | `doSkipToXiaoMiScanner(17,"identify_items")`，设置任务完成、交图给扫描器、发取消事件 |
| 当前图片 | `TextContentExtensionService.getBitmap()`；不重新截取聊天界面，不回收宿主持有的 Bitmap |
| 面板容器 | 卡片的 `mImageGroup:RelativeLayout`；只注入一次原生 ScrollView，保留当前图预览 |
| 关闭和换图 | `setBitmap`、`onStartPick`、`cancelTask`、`onDestroy`、View detach 与 Bitmap generation |
| 工作进程 | APK 自身的 `:lens`，普通应用 UID，独立 WebView 数据目录；不在宿主或系统进程创建 WebView |
| 图片 IPC | 后台缩放至最长边 2048、PNG 编码、SHA-256，使用只读 FD 交接；工作进程再校验大小、摘要、格式及尺寸 |
| 结果 IPC | 请求、面板、图片、页面代次关联；有界结果包，内嵌缩略图限流，拒绝过期回调 |

原面板没有本次可复用的识物结果列表，原分支只向外跳转。原生结果区不使用宿主 AndroidX，不伪造宿主网络响应。进程调用只接受同 Android 用户的受支持宿主或模块自身；临时 URI 只读并随任务撤销。

宿主 `AppStateWatcher.onForegroundActivitiesChanged` 原本会在前台变化时关闭面板。仅显式打开本任务网页时，按模块 UID、来源 UID 和两秒转换窗口保留本轮面板；临时隐藏浮窗以免遮挡网页。终态同步回调在 Activity 结束前撤销交互状态，主线程恢复原生面板。主页、其他应用、切图与取消路径保留宿主取消行为。导航栏独立遮罩、ROM 回调顺序和实际浮窗恢复仍须真机验证。

## Google 网页实测

开发用隔离 Chrome 对公开样例及用户提供的同一 `uploadbyurl` 链接都遇到 Google `/sorry/index`，存在真实验证码表单。这仅代表该开发环境当次的结果，不代表用户必须验证。

用户随后在 Codex 中打开了正常 Google Lens 页面。浏览器 DOM 读取接口超时后，通过原生可见页面和 DevTools 只读表达式取得实际结构：

| 字段 | 当前页面依据 |
| --- | --- |
| 结果范围 | `#center_col [data-sv-cpt="4"][role="button"]` |
| 同一卡片 | `div.N54PNb.BToiNc` 祖先，图片与 `a.LBcIee` 同属该卡片 |
| 标题 | 链接内 `[role="heading"][aria-level="3"]` |
| 来源 | 同链接 `.R8BTeb` 的实际文本 |
| 缩略图 | 图片预览内 `.q07dbf.uhHOwf.ez24Df img`，观察到 `data:image/png` 和 `data:image/jpeg` |
| 点击链接 | 实际 `a.LBcIee.href`，本页为 Google `/goto?url=...` 跳转链接 |

现场最终解析逻辑共返回 40 条结果（当前任务上限，不表示页面只有 40 条），前四条来源为 Trip.com、Bilibili、Trip.com、Trip.com。第一条 Google 跳转经独立 HTTP 检查返回 302，目标为 `us.trip.com/restaurant/china/zhoushan/detail/restaurant-30929959/`。协议明确保留 `link_type=google_redirect`，不把 Google 不透明参数冒充原站 URL，也不伪造原图地址或置信度。

解析版本为 `google-visual-cards-20261102`。卡片结构取自真实页面；脱敏样本去除了会话/追踪参数并注明测试缩略图替换。正常链接不调用收费 API、图床或自建服务器。未知布局明确失败，空数组不被直接解释成无结果。正常 Google 布局可能随入口、地区、WebView 或实验变化，当前桌面页面证据不能等同于所有手机版布局兼容。

上传使用已核对的 Chromium multipart 表单机制，字段为 `encoded_image`；WebView 文件回调交付当前图片。日志严格区分 `file_chooser_delivered` 与真正 `results_ready`；文件回调或页面加载完成都不算上传/搜索成功。Android 上自动触发上传、是否需要真实点击，以及实际上传产生同类结果仍待验证。

## Evidence → Finding → Path

| Evidence | source_ref | repro_command | content_hash |
| --- | --- | --- | --- |
| E-host | 两个原 APK、`artifacts/lens-host-*-0.12.0.json` | `python tools/verify_lens_host.py '../传送门-5.1.5(50015).apk'`，旧版同样执行 | 各契约 JSON 记录原 APK SHA-256 |
| E-imports | 宿主 Java、Android View、Bitmap、扫描器 Intent、进程观察器依赖；原生库未改 | 检索两版反编译的卡片和 Service | APK 同 E-host；新增逻辑在 Java/WebView |
| E-web | `analysis/lens-2026-11-02/evidence.md`、`artifacts/lens-user-link-check-0.12.0.txt` | 一次正常浏览器导航，未求解或绕过验证 | n/a，原始搜索会话仅本地研究 |
| E-live | `artifacts/lens-live-dom-evidence-0.12.0.json`、真实脱敏卡片 fixture | 正常 Lens 页面 DevTools 只读选择器提取；不上传聊天截图 | n/a，结果字段与用户可见页面核对 |
| E-parser | `assets/lens/extract.js`、`LensPageParser`、`tools/test_lens_page_parser.cjs` | `node tools/test_lens_page_parser.cjs <本机Playwright包路径>` | 解析版本和测试输出记录于 artifacts |
| E-test | `artifacts/lens-targeted-0.12.0.txt`、`lens-test-summary-0.12.0.json` | 下列定向 Gradle 命令 | n/a，实际用例结果 |
| E-build | APK、`apksigner-0.12.0.txt`、`zipalign-0.12.0.txt` | `gradlew.bat :app:lintRelease :app:assembleRelease --console=plain` | 最终哈希见 `lens-validation-0.12.0.json` |

| Finding | severity | evidence_ids | confidence | location | status |
| --- | --- | --- | --- | --- | --- |
| F-action：识物真实入口可以窄范围接管 | n/a_re | E-host | high | 卡片 onClick、当前 Bitmap | 两版静态契约通过，Hook 实机待验 |
| F-results：正常 Lens 卡片能取得真实字段 | n/a_re | E-live,E-parser | high | 当前用户打开的桌面 Google 页面 | 有界返回 40 条，前四条已核对；手机版与自动上传仍待验 |
| F-challenge：验证不是必需步骤 | n/a_re | E-web,E-live | high | 不同浏览器会话 | 本开发会话受限，用户 IAB 正常 |
| F-lifecycle：外部确认界面会触发原宿主关闭 | n/a_re | E-host,E-test | high | 前台观察器、浮窗、终态回调 | 有界处理已实现，真机窗口行为待验 |

P-1，`path_type=callflow`：选中图片 → 原识物点击 → 当前 Bitmap 后台编码 → 只读 FD 与摘要 → 已认证工作进程 → WebView multipart 上传 → 真实 Google 结果卡片 → 校验/去重/有界结果 → 请求关联检查 → 同一浮窗的原生列表。

P-2，`path_type=callflow`：真实确认/验证或需要点击上传 → 原生明确提示 → 用户显式打开同一 WebView → 暂时隐藏宿主浮窗 → 完成或取消 → 终态同步通知结束交互 → 恢复原生结果区；关闭、换图、超时和断连使旧请求失效。

## 本地验证与待验项目

| 最终检查 | 结果 |
| --- | --- |
| JVM / Robolectric 定向测试 | 72 项，0 失败、错误或跳过 |
| 离线浏览器解析测试 | 13 组通过，含真实脱敏 DOM、验证页、错误链接、去重和总载荷边界 |
| 当前真实桌面页面 | 最终逻辑 `results_ready`，返回 40 条并核对前四条字段 |
| 两版宿主 DEX 定义 | 每版 14 项通过 |
| Release lint / build | 构建成功；Lint 0 错误、30 警告 |
| 签名 / 对齐 | 长期证书 SHA-256 `2f3c681d676d8f763b191dea0714c399e21bbaa0e948f25289ebfbcf41d1aabd` 一致；APK v2、ZIP 16 KiB 与四个原生库 ELF 对齐通过 |
| 产物 | `TaplusBridge-0.12.0-release.apk`，22,436,354 字节 |
| APK SHA-256 | `7edf455407bab5eefde131dcb4def437d13cf73aeec3b27929ecdf6bd1615ae8` |

APK 内解析器与当前源码 SHA-256 相同；Xposed API 102、入口及 `com.miui.contentextension/system` 作用域已检查。Lint 警告完整保留在 `artifacts/lint-0.12.0.txt`。本次另为已有系统框架专用反射位置增加准确的 Lint 注解，没有改变其取链行为。

定向范围：Lens 解析、IPC 调用身份与 FD 生命周期、图片摘要和格式、页面代次、原生面板及交互状态、BridgeProvider 开关调用、SettingsController 和 Miuix 开关。完整统计、Lint 警告和最终签名产物以最终验证 JSON 与日志为准。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*lens.*' --tests '*BridgeProviderAndroidTest' --tests '*SettingsControllerAndroidTest' --tests '*MiuixSettingsScreenTest.lensSwitch*' --console=plain
.\gradlew.bat :app:lintRelease :app:assembleRelease --console=plain
```

未跑全量测试；未安装手机。手机 Android/WebView 版本未知，没有测过真实 LSPosed 回调、本地图的 WebView 上传、快速换图、扫码/OCR 实机回归、断网、系统字体/深色面板、后台限制和导航栏遮罩。局部测试和桌面真实结果不替代这些验收。

下一步是在目标 HyperOS 3 手机更新后，用两张不同图片分别完成一次真实上传与原生回填，并核对 `TaplusBridge-Lens` 分阶段日志；随后测试重复点击、换图、关闭和断网。日志不输出原始图片、Cookie、令牌或完整会话地址。

## 时间线

- 2026-11-02：读取用户截图、方案和现有工程，保留已有未提交修改。
- 2026-11-02：两版宿主识物、图片和面板调用链定位，增加实际 DEX 契约。
- 2026-11-02：完成工作进程、IPC、原生面板与任务生命周期，开发会话遇到验证。
- 2026-11-02：用户说明自身链接正常并打开 Codex 网页；从正常页面读取真实结果和 DOM，更新实际卡片解析。
- 2026-11-02：定向测试、Release lint、长期签名构建和产物检查，真机端到端保留待验。
