# 0.9.1 应用范围接管与未触发诊断

日期：2026-11-01（Asia/Shanghai）。普通 APK 适配诊断，flavor=null。授权见 [scope.md](scope.md)。

## 结论

0.9.0 存在真实的接管遗漏：模块名单允许来源后，仍调用宿主私有 `getConfig`，保留旧 Activity、触点范围和应用触发模式过滤，允许来源仍能得到 forbidden。本次已通过共享决策入口复现并修正，托管范围现在只由模块名单决定；未托管仍走原生路径。

用户点名的 LSPosed 管理器、BakaSU 与 Chrome 还有另一段尚未定位到具体原因的路径：日志证明它们的原生名单已经允许，但没有对应的文字/图片结果送到宿主。不能把修正宿主页面判断等同于解决这三个应用的全部实机触发。本次补充宿主原有采集配置请求和分阶段诊断，生成 0.9.1 / 18 候选，需设备复测。

## 设备日志观察

输入：`F:/qq/MobileFile/LSPosed_20261101_122701.zip`，10,577,790 字节。SHA-256：`5C45B18F3C495EB484288FF2EA3D84A0B61810C4AB5AC99D5EABEC61689CFE49`。

仅读取 ZIP 内相关模块日志和 full.log，没有解包整个归档或读取其他应用数据库。摘要为 `artifacts/lsp-20261101-122701-summary.json`，新增事件不保存任意来源包、正文、URL、坐标或 token。

| 观察 | 原始位置 | 含义 |
|---|---|---|
| 系统通道 ready | 模块日志第 169 行，12:23:33.365 | 启动早期三次 NullPointerException 后已恢复，不能当成持续通道失败 |
| 范围 Hook 安装成功 | 同日志第 474 行，12:24:34.147 | 入口已安装，不等于所有采集已成功 |
| 范围发布 3 次，失败 0 | 模块日志第 172/528/583 行 | 发布成功存在，非单纯未勾选/未安装 |
| 宿主启动读到 1 项名单 | full.log 第 18458 行 | 与用户只选一个黑名单一致 |
| 14 条原生名单检查，全部 size=1 且允许 | full.log 第 18005 至 79393 行；摘要 `source_scope_events` | 包括 LSPosed 管理器 2、Root 管理器 2、Chrome 1、其他 9 |
| 全归档相关正常正文 1 次、图片 0 次 | 摘要 `text_events/image_events` | 不支持“所有未触发都被宿主页面表拒绝” |
| 3 次 observe_control 返回 | full.log 第 34782/41916/52974 行 | Back/移动控制通知，不是正常正文，不能当成失败抓取后强行放行 |

系统 `ContentCatcher_TaplusBlackListHelper` 的允许记录对应已建立的输入监听，不能将 “Catcher list invalid” 单句误判为没有采集器。`isSecureWindow` 的反射警告在提供的系统实现中被捕获后继续，也不能单独当成这次拒绝原因。

## Evidence → Finding → Path

| Evidence | source_ref | repro_command | content_hash |
|---|---|---|---|
| E-log | 上述 ZIP、摘要及行号 | `python tools/analyze_lsp_archive.py 'F:/qq/MobileFile/LSPosed_20261101_122701.zip' --date 2026-11-01` | 上述 ZIP SHA-256 |
| E-imports | 已验证宿主 APK、DEX 主包、外部 miui.contentcatcher.sdk；本次位于 Java 层 | 对新旧 APK 运行 `tools/verify_host.py` | 宿主 APK 哈希保存在契约 JSON |
| E-old-page | 新版宿主 `data/common/ContentExtensionConfig` 与对应 smali；`apktool/assets/config.json:346/355`、`:643/652` | 对照旧模块 Hook 与上述页面配置 | 原始宿主哈希见 5.1.5 报告 |
| E-red | `artifacts/source-scope-gate-red-0.9.1.txt` | `gradlew.bat :app:testDebugUnitTest --tests '*SourceScopeGateTest'`，修复前执行 | n/a，保留实际失败记录：4 测试、2 失败 |
| E-green | `artifacts/source-scope-gate-green-0.9.1.txt`、最终 JUnit | 相同 gate 回归和 HostCaptureSettings/Provider/Snapshot 相关测试 | n/a，修复后通过 |
| E-native | `analysis/ContentCatcherOS3_1/jadx/sources/com/miui/contentcatcher/Interceptor.java`、`service/config/a.java`、`z/a.java` | 检索名单读取、observe_control_event、text_pick/recomment 分流 | n/a，本地系统组件导出 |
| E-build | `artifacts/validation-0.9.1.json`、签名与对齐输出 | 下文命令 | APK SHA-256 见下文 |

| Finding | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F-scope：允许包仍被旧页面表否决 | n/a_re | E-old-page,E-red,E-green | high | HostSourceScopeHooks → SourceScopeGate | 已本地修复；设备待验 |
| F-upstream：三个目标未见正文到宿主 | n/a_re | E-log,E-native | high | 原生抓取到宿主之前 | 观察确认；具体触发失败原因未确定 |
| F-config：名单同步不等于文字/图片 job 启用状态同步 | n/a_re | E-native,E-imports | high | ContentCatcherUtil.switchCatcherConfig | 补充窄请求，尚无设备 ACK |
| F-controls：控制包不含正文 | n/a_re | E-log,E-native | high | Interceptor Handler / DataHub | 保持原生忽略，不伪造采集结果 |

P-1，`path_type=callflow`：模块只黑名单 A → 合法来源 B 不在名单 → 0.9.0 仍调用旧页面过滤 → 原宿主 forbidden → 不建识别浮窗。修正后托管来源直接由模块返回 normal/forbidden。旧实现可在 B 站 MainActivity、便签 NotesListActivity 等全屏禁区配置中静态复现。此次三个目标没有相同本地页面条目，因此不将该例作为它们未送达正文的实机证明。

P-2，`path_type=callflow`：模块刷新 → 已接管且宿主真实总开关开启 → 通过原生 `switchCatcherConfig(Context,true)` 仅请求宿主 `text_pick/image_pick` job 启用 → 来源页面重新恢复后原生系统处理。方法返回只证明请求发出，不证明抓取恢复。系统若仅选到 recomment，普通正文还可能被热词过滤；本日志没有 jobTag 证据，故该路径只作经过源码验证的可能性，不当成本机已确定根因。

## 修改与诊断

- `SourceScopeGate` 是实际 Hook 使用的决策入口，托管范围不再调用原页面过滤。未托管保留原生返回与异常。
- `HostCaptureSettings` 校验真实静态方法契约，只在托管且宿主已开启时调用原有接口。宿主关闭、未托管时不改开关，不调用全局 updateConfig。原方法会同步其图片识别偏好，这项请求不等于启用模块总开关或改手势。
- `initPresentationType` 新 Hook 只观察，返回值原样保留。未到名单 gate 已失败记 property_blocked，到过 gate 后失败记 scope_blocked，通过记 passed。
- 新日志只保存阶段/结果/模式，以及 lsposed_manager/root_manager/chrome/other/unknown 固定来源类别。来源包只在内存中用于映射，不写入新增摘要。
- 不绕过宿主总开关、隐私、键盘、EditText、摄像/录音、FLAG_SECURE、空抓取结果或来源系统能力。不给控制通知补造正文或进行盲点。

## 验证与产物

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --console=plain
python -m unittest discover -s tools -p 'test_*.py' -v
python tools/verify_host.py '../传送门-5.1.5(50015).apk' --output artifacts/host-contract-0.9.1.json
python tools/verify_host.py '../传送门_4.1.3.apk' --output artifacts/host-contract-4.1.3-0.9.1.json
```

536 项 JVM/Robolectric 测试通过，0 失败/错误/跳过；28 项 Python 测试通过。新旧宿主各 73 项真实 DEX 定义及 static 标记通过。Release lint 为 0 错误、25 警告。额外验证最终 APK 包含 SourceScopeGate、HostCaptureSettings 及新诊断标记。

产物：`artifacts/TaplusBridge-0.9.1-release.apk`，22,270,178 字节，版本 **0.9.1 / 18**。

SHA-256：`E00ED62463DE80CD30E4EFC1782ABDE1D8B8AD4D060B5AE882AB4AA1FA41E171`。

长期证书与既有正式版相同：`2F3C681D676D8F763B191DEA0714C399E21BBAA0E948F25289EBFBCF41D1AABD`。v2 验签、ZIP 4 字节、native 16 KiB 与 ELF PT_LOAD 对齐通过。

## 设备复测与未完成项

本次 ADB 无设备，未安装或操作手机。覆盖安装后重启，打开模块，将当前单黑名单保存一次，等待系统配置同步，然后退出再进入三个目标应用，各尝试普通文字、图片。若仍无反应，按三个目标分别记录复测时间并导出新日志。

原生名单仍拒绝可定位名单发布/缓存；原生允许但没有 host_gate/host_presentation，则停在源端抓取/服务交付；property_blocked 表示宿主属性拒绝；gate allowed 且 presentation passed 后还无 UI，则另查识别/浮窗链。新的固定来源类别可避免混淆三个目标。

未确认三个应用已恢复；未验证真实 ROM 的 job 启用与跨进程刷新、源端输出、Hook 回调、冻结本体、Root/Shizuku 或目标启动。没有扩大来源应用作用域，未远程提交、推送或发布。

## Timeline

- 2026-11-01：只读归档，确认单名单实际发布和三个目标原生允许。
- 2026-11-01：拆分已确认的范围遗漏与尚未定位的源端无正文问题；用户补充目标应用与尝试类型。
- 2026-11-01：相同 gate 路径先测红，再修正并测绿；补充原生采集请求适配及阶段诊断。
- 2026-11-01：完整本地验证与长期签名构建通过，保留实机未完成边界。
