# 0.10.0 系统长按截图兜底

2026-11-01（Asia/Shanghai），普通 APK 适配，flavor=null；范围见 [scope.md](scope.md)。

## 行为

「生效应用」新增「截图识别兜底」，默认关闭，开关、模式和两份名单在点击保存时原子提交。Android 13+ 主用户开启后，系统核验真实长按、名单、前台主窗和原生触发设置，在松开且原生未开始识别时截图，通过原宿主回调进入 CTA/总开关/键盘检查及原生 OCR 浮窗。当前仍只有传送门和 system 作用域，不 Hook 来源应用。

仅截同 UID 的原前台主窗，排除其他图层，拒绝锁屏、安全层和可见输入法；核验截图前后及领取时窗口身份不变。图片限尺寸和字节、只在内存传递，nonce 一次性且 10 秒过期。新配置身份或关闭开关会使旧投递失效。截图不会作为可点击卡片区域，不从该截图推导模拟点击。

## Evidence → Finding → Path

| Evidence | source_ref | repro_command | content_hash |
|---|---|---|---|
| E-log | `artifacts/lsp-20261101-132326-summary.json`；用户归档 | `python tools/analyze_lsp_archive.py F:/qq/MobileFile/LSPosed_20261101_132326.zip --date 2026-11-01` | `C3FD349447307D1BDC9CCD8DFB8FF41DE6CE574EEFE976FA74DBFAE7FCCBAFB6` |
| E-imports | 系统组件的 Interceptor、ViewContentFetcher；宿主及系统 SDK DEX 引用 | 读取 `analysis/ContentCatcherOS3_1/jadx/sources/`；`artifacts/system-sdk-references-0.10.0.json` | SDK 输入 hash 见引用 JSON；引用不是定义或实机证明 |
| E-contract | 新旧宿主契约 JSON | `tools/verify_host.py` 对新旧原 APK | 输入 hash 固定于 JSON，74 项定义检查 |
| E-build | `artifacts/release-build-0.10.0.txt`、`validation-0.10.0.json` | `gradlew.bat :app:assembleRelease --console=plain` | 最终 APK hash 见验证 JSON 和 SHA256SUMS |

| Finding | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F-source：名单放行不能补出虚拟 View/Chromium 的原生内容 | n/a_re | E-log,E-imports | high | Interceptor 空 ViewGroup 路径与网页包装器 | 新增独立截图链路；设备待验 |
| F-catalog：合法单段平台包会让旧白名单补集失败 | n/a_re | E-log,E-contract | high | SourceScopePublisher | 修正可信 PM 目录校验，目标/命令校验保持严格 |
| F-build：最终开关版本完成正式构建 | n/a_re | E-build | high | 0.10.0 / 19 | 签名与产物检查通过；最终全量测试未运行 |

P-1，`path_type=callflow`：用户保存开关和名单 → schema 3 框架快照 → 系统真实长按状态机 → 原生识别去重 → 安全前台主窗图层截图 → 限量 PNG / 可信宿主一次性领取 → 原 ClientCatcherResult 回调 → 宿主 OCR/浮窗。schema 1/2 兼容读取时新开关为关闭。

## 开发者模式与验证界限

所给 5.1.5 的 DevModeFragment / settings_developer.xml 仅含开发模式、预览/预发布服务器及 Server OCR，不提供增加取数范围的入口。仅增加 ContentCatcher 服务作用域也不能改变来源进程对空 ViewGroup 和 Chromium 的取数限制，故采用已有 system 作用域的截图链路。

最终产物为 `artifacts/TaplusBridge-0.10.0-release.apk`，版本 0.10.0 / 19。签名、对齐、哈希与大小记录于 `artifacts/validation-0.10.0.json`，校验和为 `artifacts/SHA256SUMS-0.10.0.txt`。沿用既有正式密钥。

按用户最后指示，最终开关版本不跑全量测试和完整 Lint；构建自动执行的 lintVital 不代表完整 lintRelease。此前探索性测试不冒充最终版本验收。未渲染最终开关页面，未验证手机实际截图 API、真实手势/窗口、SDK 回调、OCR、冻结模块或三个目标应用的效果，也未远程发布。

Timeline：11 月 1 日只读新日志及系统取数链；定位白名单目录错误，实施系统截图与可信宿主投递；按用户要求增加默认关闭开关；按最后要求只完成 Release 构建与产物检查。
