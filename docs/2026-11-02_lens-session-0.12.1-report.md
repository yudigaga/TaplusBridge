# 0.12.1 识图初始页面与网页会话启动修复

2026-11-02，Asia/Shanghai。授权范围见 [scope.md](scope.md)，普通故障分析，`flavor=null`。

## 结论

用户的 0.12.0 / 22 日志确认，图片已经从传送门交给模块工作进程，但未记录网页文件交付或 Lens 结果解析。两次网页会话窗口启动被 Android 16 明确以 `BAL_BLOCK` 拒绝。这不是“已经访问 Lens，随后识图结果布局变了”的证据，也没有验证码页面证据。

0.12.1 / 23 修复初始上传文档的虚拟地址与来源识别，补全 PendingIntent 创建方和发送方授权，保留可见浮窗直到网页 Activity 确认挂接成功。未确认时显示错误及同会话重试，不自动重新上传图片。

本次没有连接手机；以下本地回归、构建、签名与对齐以 `artifacts/lens-validation-0.12.1.json` 为准。新版本在用户手机上成功上传、打开网页及回填原生结果仍需复测，不能用旧版实机日志宣称新版已通过。

## 实机证据

输入 `LSPosed_20261102_101830.zip`，SHA-256：`93ac33c95fc152ec05a65d9e1c161cea6753f5762a0daee9be695b19a2a1e489`。仅在内存读取相关日志和版本信息，脱敏摘要见 `artifacts/lens-log-20261102-101830-summary.json`。

| 项目 | 日志证据 |
| --- | --- |
| 设备 | Android 16 / API 36，HyperOS OS3.0 |
| WebView | `com.google.android.webview`，156.0.8078.47 / 807804703 |
| 模块 | 0.12.0 / 22，API 102；宿主 versionCode 50015，日志未给出有效 versionName |
| 图片 | `full.log:55254`，374 × 832，61,551 字节，读取准备完成 |
| 页面 | `55633` 开始第 1 代；`55819` 返回 `needs_interaction`，`parser=none` |
| 文件及结果 | 没有 `file_chooser_delivered` 或 `results_ready` 记录，不能声称图片已经上传 |
| 第一次启动 | `36356`，10:17:32.040，`LensInteractionActivity` 被后台启动检查拒绝；`36381` 返回 102 |
| 第二次启动 | `56244`，10:18:09.893，同样拒绝；`56269` 返回 102 |
| 拒绝细节 | `balRequireOptInByPendingIntentCreator=true`、创建方 `BSP.NONE`，发送方默认模式；宿主仍有可见非 Activity 窗口，满足授权后的可见浮窗条件 |
| 结束 | `58510–58511`，10:18:13.733，取消并清理 |

实际页面地址没有写入旧日志，**不能说日志直接观察到了 `about:blank`**。该原因来自代码和 Chromium 已公开的实现：旧版 `loadDataWithBaseURL` 传 `historyUrl=null`，平台将虚拟/历史地址设为 `about:blank`，而旧逻辑在任何解析之前只允许 HTTPS Google 地址。`baseURL` 的同源和相对地址语义不等于 `getUrl()` 的虚拟地址语义。

## 改动

- 上传文档的 base URL 和 history URL 显式相同；模块只认可本会话创建的初始上传文档，导航后撤销这份本地图交付权限。空白虚拟地址、Google 上传页和真实结果页分开处理；不会全局放行 `about:`、`data:`、文件或第三方页面。
- 图片只向模块拥有的上传控件交付一次。初始页等待或需要真实点击时提供准确提示；增加不含查询参数的页面阶段分类日志。
- PendingIntent 创建方在 API 35+ 显式授权，Activity 使用 `NEW_TASK`。发送方在 API 34+ 显式授权，API 36 采用 `ALLOW_IF_VISIBLE`；该模式的 AOSP 判定链包含可见非 Activity 窗口。没有更改系统权限或降低 target SDK。
- 点击后立即显示“正在打开当前网页会话…”，保留可见浮窗；只有当前请求收到 `interaction_opened` 后才隐藏浮窗。启动转换租约和确认超时统一为 8 秒，返回窗口仍为 2 秒。
- 未收到确认时恢复原生提示，保留同一图片、任务和 PendingIntent 重试。关闭、换图、失败和超时撤销等待。回调改为同步 Binder，使终态返回转换检查确实在工作进程结束 Activity 前发生；不在 Binder 线程更新 View。

验证码检测、真实结果卡片解析、收费依赖范围、链接流转和截图开关没有改变。本次依据是初始页状态和窗口启动，不扩大网页解析器的受信域。

## Evidence → Finding → Path

| Evidence | source_ref | repro_command | content_hash |
| --- | --- | --- | --- |
| E-log | `artifacts/lens-log-20261102-101830-summary.json` 与原归档行号 | 以 Python `zipfile` 只读 `full.log`、属性与当前模块日志，检索固定 Lens 标签和本 Activity 的 BAL 判定 | 原归档 SHA-256 见上文 |
| E-navigation | `LensSession`、`LensNavigationState`；Chromium 虚拟地址构造和测试 | 下列定向命令的导航用例 | n/a，当前源码和 JUnit 证据 |
| E-launch | `PortalLensPanel`、`LensInteractionLease`、`ILensCallback.aidl` | 下列定向命令的会话启动、可见性、无确认重试用例 | n/a，当前源码和 JUnit 证据 |
| E-build | `artifacts/lens-validation-0.12.1.json`、测试与构建日志、APK | `gradlew.bat :app:lintRelease :app:assembleRelease --console=plain` | 最终 APK 哈希见验证 JSON |

| Finding | severity | evidence_ids | confidence | location | status |
| --- | --- | --- | --- | --- | --- |
| F-initial：初始文档在解析前可能被虚拟地址检查拒绝 | n/a_re | E-log,E-navigation | high（实现），中等（旧日志未含实际地址） | `loadDataWithBaseURL`、`inspect`、文件回调 | 本地回归覆盖，手机新版本待验 |
| F-launch：网页按钮的 Activity 被系统拒绝 | n/a_re | E-log,E-launch | high | 创建方/发送方 PendingIntent 与宿主窗口 | 日志直接确认旧版失败；新授权与可见性逻辑待实机复测 |
| F-feedback：发送调用没有异常不能证明窗口已打开 | n/a_re | E-log,E-launch | high | 启动确认与看门狗 | 改为窗口挂接确认后隐藏，未确认保留重试 |

P-1，`path_type=callflow`：当前 Bitmap → 已验证 FD → 模块上传文档（显式 history URL）→ 一次文件交付 → Google 导航 → 真实解析器 → 请求关联后的原生结果。

P-2，`path_type=callflow`：需要网页交互 → 原生按钮 → 有界启动租约且浮窗可见 → 创建方与发送方 opt-in → 同一 WebView 成功挂接 → `interaction_opened` → 隐藏浮窗；无确认则原会话重试，取消/终态则清理和恢复。

## 验证和使用

| 本轮最终检查 | 结果 |
| --- | --- |
| 定向 JVM / Robolectric | 86 项，0 失败、错误或跳过；含 11 项上传初始页和会话确认回归 |
| Release lint / build | 成功；Lint 0 错误、30 警告 |
| APK 版本和大小 | 0.12.1 / 23，无开发后缀，22,436,350 字节 |
| 长期签名 | APK v2 验证通过，证书 SHA-256 `2f3c681d676d8f763b191dea0714c399e21bbaa0e948f25289ebfbcf41d1aabd` 与前版一致 |
| 对齐 | ZIP `-c -P 16 -v 4` 通过，四个原生库 ELF PT_LOAD 均至少 16 KiB |
| 包内内容 | 解析器 asset 与当前源码一致，API 102 和原作用域正确；生成回调代码使用同步 transact flag 0 并读取 reply |
| APK SHA-256 | `40d75c5f82b540e05231c71dfe2cb09d15f51311f8bc752d89a30ff261794caa` |

安装包：`artifacts/TaplusBridge-0.12.1-release.apk`。逐套件和产物详情见 `artifacts/lens-test-summary-0.12.1.json`、`lens-validation-0.12.1.json`。页面结果解析脚本没有修改，本轮未重复进行桌面实网识图。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*lens.*' --tests '*BridgeProviderAndroidTest' --tests '*SettingsControllerAndroidTest' --tests '*MiuixSettingsScreenTest.lensSwitch*' --console=plain
.\gradlew.bat :app:lintRelease :app:assembleRelease --console=plain
```

导航用例明确模拟 Chromium 虚拟地址，不把 Robolectric 当成真实 Chrome/WebView 上传验证。API 34/35 的选项、同一 WebView 挂接确认、初始页来源与文件交付、同会话重试、换图和回调关联有本地覆盖；API 36 的可见窗口规则以 AOSP 源码核对，没有在连接的 Android 16 手机上执行。

覆盖安装新 APK 后重启，确认模块首页显示 0.12.1，LSPosed 保持传送门和系统框架作用域。再次长按图片点击识图；若需要上传操作，打开当前会话点击“上传当前图片”。新增日志应依次反映 `page=module_upload`、文件交付和 Google 页面阶段；`interaction_opened` 仅表示窗口已挂接，不表示识图成功。

未跑全量测试，未连接手机复测实际打开、Google 上传/确认、手机布局与原生回填。没有远程发布。

## 平台依据与时间线

- Android 官方后台 Activity 启动说明及 ActivityOptions/PendingIntent API：创建方与发送方 opt-in 的版本要求。
- AOSP `android16-release/services/core/java/com/android/server/wm/BackgroundActivityStartController.java`：visible-only 分支包含 `mCheckRealCallerNonAppVisible`。
- Chromium `LoadUrlParams.createLoadDataParamsWithBaseUrl` 与 `LoadDataWithBaseUrlTest`：显式 virtual/history URL，null 时使用 `about:blank`。
- 2026-11-02：读取用户 10:18:30 归档并定位两次拒绝 → 修复初始页和启动确认 → 定向验证、Lint、签名构建，记录设备复测边界。
