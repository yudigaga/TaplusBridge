# 0.10.1 截图回调线程修复

2026-11-01（Asia/Shanghai），普通 APK 故障适配，flavor=null；授权见 [scope.md](scope.md)。

## 结论

0.10.0 的系统长按识别、截图和内存传输已经在设备上发生；真正阻断点在宿主交付线程。模块把原 `mCallback.onContentReceived` 投到主线程，而传送门接着调用 `PrivacyManager.requestPrivacyUpdate`，后者明确禁止主线程并直接抛 IllegalStateException。反射将其包装为 InvocationTargetException，故图片已接收但没有进入 onPickImage/OCR。

0.10.1 使用 `ScopeCaptureDispatch` 保持后台调用原回调；主线程入口转到专用 worker，已经在 worker 时直接执行。宿主仍自行 postToMain(startPickImageTask)，没有跳过隐私或替换原生 UI 流程。开关和名单保持原值。

## Evidence → Finding → Path

| Evidence | source_ref | repro_command | content_hash |
|---|---|---|---|
| E-log | `F:/qq/MobileFile/LSPosed_20261101_142030.zip`、`artifacts/lsp-20261101-142030-summary.json` | `python tools/analyze_lsp_archive.py F:/qq/MobileFile/LSPosed_20261101_142030.zip --date 2026-11-01` | `ACB611DBF3DEA89CE932682C8A0EAF3B345CEEED0F1A70B36F52F5D4A6F88C52` |
| E-thread | 新版宿主 `com/miui/privacypolicy/PrivacyManager.java:28–29/:74–80` 及对应 smali；服务 `onStartPick/getPrivacyUpdateInfo` | 检索 `checkThreadPermission`、`requestPrivacyUpdate` | 原宿主输入 hash 见既有契约 JSON |
| E-imports | 宿主 Java 回调、Privacy SDK、外部 miui.contentcatcher SDK 引用 | 同原宿主 DEX/反编译结果，本次未改 native 或 SDK | n/a，等价依赖锚点 |
| E-red | `artifacts/scope-delivery-thread-red-0.10.1.txt` | `gradlew.bat :app:testDebugUnitTest --tests '*ScopeCaptureDispatchAndroidTest'` | n/a，修复前2项均失败 |
| E-green | `artifacts/scope-delivery-thread-green-0.10.1.txt` | 相同定向命令 | n/a，修复后2项通过 |
| E-build | `artifacts/release-build-0.10.1.txt`、`validation-0.10.1.json` | `gradlew.bat :app:assembleRelease --console=plain` | 最终 APK SHA-256 见验证 JSON 与校验文件 |

| Finding | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F-thread：宿主原回调被错误切到主线程 | n/a_re | E-log,E-thread,E-red,E-green | high | HostScopeCaptureHooks.take → dispatch → callback | 本地修复；手机最终UI待验 |
| F-transfer：前段截图和传输已工作 | n/a_re | E-log | high | system submitted → host_received | 所给设备日志确认 |

日志计数：11 次 host_received、11 次宿主主线程禁止警告、11 次 host_deliver InvocationTargetException；警告与失败全部按时间配对，间隔 1–4 毫秒。首对为 full.log 第40753行与模块日志第577行。没有 host_submitted 或正常 image 事件。日志的3条菜单 ClassCastException属于其他选择菜单链，未混作本问题。

P-1，`path_type=callflow`：旧 main.post → 原回调 → onStartPick → getPrivacyUpdateInfo → PrivacyManager主线程检查 → IllegalStateException → InvocationTargetException → 无识别。新路径为 worker原回调 → 原隐私检查正常线程契约 → 宿主自身主线程UI调度。

## 验证和限制

定向回归复用真实交付调度入口，测试回调执行已由 Java、smali 和设备日志确认的线程检查契约，并验证宿主仍能把 UI 投至主线程；这是本地契约测试，不冒充完整 SDK 网络、OCR 或设备验收。测试前先失败，修复后两项通过。两个新增 Python 诊断定向回归也通过，未跑全量。

正式包 `artifacts/TaplusBridge-0.10.1-release.apk`，版本0.10.1 / 20，长期证书与现有正式版一致。签名、对齐和实际哈希记录在 `artifacts/validation-0.10.1.json`、`apksigner-0.10.1.txt`、`zipalign-0.10.1.txt`、`SHA256SUMS-0.10.1.txt`。

未运行完整测试或完整 lintRelease，未在真实设备确认修复后的宿主识别及界面，未安装或远程发布。升级后重启并打开模块，让当前构建配置同步；确认「截图识别兜底」仍开启，再复测三个应用。
