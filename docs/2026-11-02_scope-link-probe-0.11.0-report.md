# 0.11.0 通用长按取链与原生图文合并

2026-11-02（Asia/Shanghai），普通 APK 适配，`flavor=null`。授权与追加需求见 [scope.md](scope.md)；仅本地分析、修改、定向验证和签名构建，未操作手机或远程发布。

## 结果和使用

「生效应用」新增默认关闭的「长按取链接」开关，与名单一起保存。开启后真实长按松开会自动点击原位置一次，从系统公开 Activity 启动边界截获实际 HTTP(S) 地址，在原 ATMS 方法执行前返回，避免为该受控请求创建浏览器 Activity。既有原生文本、图片与取得的链接在同一轮交付，用户仍选择原生操作。原生图文在探测期间暂存，避免浮窗提前覆盖目标；取链失败则恢复本轮已有内容。

这条链路不依赖 BakaSU、微信、QQ 等应用名称、按钮文字或预置网址。仍仅使用 `com.miui.contentextension`、`system` 两个作用域。用户已明确接受自动执行原单击，并自行保证长按的是链接入口；非链接按钮的本地副作用无法撤销。截图仍取决于独立的截图兜底开关。

安装新包后重启，打开模块等待配置同步，在「生效应用」开启「长按取链接」并保存。对于原生无法采集图文的页面，可同时开启「截图识别兜底」。保留传送门总开关、隐私/CTA、主用户、前台窗口、锁屏、输入法与安全窗口限制。

## Evidence → Finding → Path

| Evidence | source_ref | repro_command | content_hash |
|---|---|---|---|
| E-log | `F:/qq/MobileFile/LSPosed_20261101_144258.zip`、`artifacts/lsp-20261101-144258-summary.json` | `python tools/analyze_lsp_archive.py F:/qq/MobileFile/LSPosed_20261101_144258.zip --date 2026-11-01` | `84DFFEBE75E40CBB7AE8980E2476BCFBE32075FE8E733C59AE03F725EA46C775` |
| E-host | 两版原宿主的 `TextContentExtensionService`、`ImageRecognition`、`TaplusRecognitionExpandedCard`、`TaplusSplashFloatView`，与实际 DEX 定义契约 | `python tools/verify_host.py '../传送门-5.1.5(50015).apk'`；旧版输入为 `../传送门_4.1.3.apk` | 宿主 SHA-256 及逐项契约见 `artifacts/host-contract-5.1.5-0.11.0.json`、`host-contract-4.1.3-0.11.0.json` |
| E-input | `ScopeLinkProbe`、`ScopeLinkLaunchHooks`、`SystemScopeCapture`、`SystemProbe` | 见下面的定向 Gradle 命令 | n/a，生产源码及 JUnit 证据 |
| E-transfer | `ScopeCaptureStore`、`ScopeCapturePayload`、`HostScopeCaptureHooks`、`RecognitionHooks` | 相同定向命令 | n/a，内存票据、宿主适配及合并源码 |
| E-test | `artifacts/scope-link-targeted-0.11.0.txt`、`artifacts/junit-0.11.0/`、`scope-link-diagnostics-0.11.0.txt` | 见下文 | 最终执行范围与结果见 `artifacts/validation-0.11.0.json` |
| E-build | `artifacts/release-build-0.11.0.txt`、`TaplusBridge-0.11.0-release.apk`、签名和对齐日志 | `gradlew.bat :app:assembleRelease --console=plain` | 最终 APK SHA-256 见 `artifacts/SHA256SUMS-0.11.0.txt` |

| Finding | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F-input：原生只识别标题/图片时，需执行原单击才能取得真实隐藏网址 | n/a_re | E-log,E-host,E-input | high | 系统输入与公开 ATMS 边界 | 本地实现和定向验证，真机待验 |
| F-mixed：直接增加 text_pick 会遮蔽同结果的 image_pick | n/a_re | E-host,E-transfer | high | 宿主 onStartPick 的文本分支优先并 return | 使用单轮主内容及补充 URL，保留原文/图片 |
| F-thread：原回调仍需后台线程 | n/a_re | E-host,E-transfer,E-test | high | ScopeCaptureDispatch → mCallback.onContentReceived | 保留 0.10.1 的线程修复与隐私检查 |
| F-boundary：公开 Activity 启动拦截不等于覆盖任意内部导航 | n/a_re | E-input,E-host | high | ScopeLinkLaunchHooks | 接受明确的实现边界，未宣称全协议通杀 |

P-1，`path_type=callflow`：真实 DOWN 记录可信来源与窗口 → 移动/取消/新手势撤销 → 长按 UP 确认 → 原生图文暂存 → 最多一次目标 UID 短点击 → 匹配公开启动在原 ATMS 执行前消费 → 实际 URL 与可选截图经配置绑定、10 秒一次性内存票据交付可信宿主 → 与本轮原生图文合并 → 原回调后台执行 → 原生图文入口、分词/OCR、推荐卡 → 用户点击所需操作。

P-2，`path_type=callflow`：未取得 URL → 有截图则保留图片识别；有原生结果则通过完成通知恢复原始内容；没有可用内容则不造结果。新真实 DOWN 或配置变化立即撤销旧手势，旧票据无法跨配置交付。

## 验证与边界

本次运行与取链、混合内容、开关和交付有关的 85 项 JVM 定向用例，零失败、错误或跳过；另有 2 项诊断工具定向检查通过。两版宿主各 77 项实际 DEX 定义契约通过。最终命令、逐套件数量、版本、证书、对齐和实际哈希以 `artifacts/validation-0.11.0.json` 为准，不把前一轮中间构建或缓存的全量结果计入本轮。正式版本为 0.11.0 / 21。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*ScopeLinkProbeAndroidTest' --tests '*ScopeCapturePayloadAndroidTest' --tests '*ScopeCaptureStoreAndroidTest' --tests '*ScopeNativeMergeAndroidTest' --tests '*ConfigStoreSourceScopeAndroidTest.linkProbe*' --tests '*ConfigStoreSourceScopeAndroidTest.aMalformedFallbackPreference*' --tests '*RuntimeSnapshotAndroidTest.schemaThreeRetains*' --tests '*RuntimeSnapshotAndroidTest.schemaFour*' --tests '*RuntimeServerAndroidTest.link*' --tests '*ScopeCaptureDispatchAndroidTest' --console=plain
.\gradlew.bat :app:assembleRelease --console=plain
```

只接受主用户默认显示的可信前台目标；点击前及交付前重查配置、窗口和物理松手。URL 等待最多 3 秒；没有观察到启动的已发出点击在超时后保留最长 10 秒同来源抑制，新触摸或配置变化立即解除。该窗口内晚到启动只拦截，不交付过期 URL；不会永久拦截来源应用。诊断只保留固定阶段/原因，不记录网址、截图、正文、nonce 或任意包名。

内部 JS/Compose/WebView 导航、PendingIntent、其他协议、跨用户、多显示、未覆盖的启动接口、超出归因窗口的异步动作，以及原生长按菜单遮挡均不能保证覆盖。双指模式的探测只在完整手势确认后建立，提前原生界面的控制有限。捕获成功只证明拿到实际启动 URL，不证明服务器重定向后的最终网址。

2026-11-01 日志只证明旧版 0.10.1 的三次截图已接收并交付，不能作为本版 URL、混合操作或不可见效果的实机验证。本轮未跑全量测试或完整 lint，未连接设备验证 LSPosed 回调、识别 UI、OCR/网络和无闪屏效果，未安装或远程发布。

## 时间线

- 2026-11-01：读取 14:42 日志和示例界面，确认旧截图链已有交付，隐藏 URL 仍需真实点击取数。
- 2026-11-01：用户确认开关内自动执行原单击，并要求网页启动被拦截、过程不可见。
- 2026-11-02：继续完成通用系统拦截、延迟启动抑制及原生结果协调。
- 2026-11-02：用户补充图文链接同时可选，调整为同轮保留原生内容和补充 URL；本地定向验证与签名构建记录在 artifacts。
