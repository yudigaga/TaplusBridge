# 传送门应用范围接管报告

日期：2026-11-01（Asia/Shanghai），普通 APK 适配分析，flavor=null。范围与授权见 [scope.md](scope.md)。

此文记录 0.9.0 历史实现。其允许包仍调用旧页面配置的行为已在 0.9.1 纠正；本次设备日志及尚未解决的采集断点见 [0.9.1 诊断报告](2026-11-01_source-scope-0.9.1-report.md)。

## 结果与使用

已实现 taplusbridge **0.9.0 / 17** 的「生效应用」页面。黑名单和白名单二选一，应用可按名称或包名搜索并多选，两份名单分别保留。黑名单排除所选应用，白名单只允许所选应用；空黑名单允许所有应用，空白名单全部禁止。首次保存前保持原生设置，保存后接管。未安装/不可见的已选包仍保留，可手动取消，避免安装状态变化丢失配置。

该范围针对来源应用触发传送门，不改变链接规则的目标应用选择。传送门原有页面、手势与系统 ContentCatcher 能力边界保持。系统原生黑名单在来源进程内存在缓存，保存后可能需要退出来源 Activity 再进入。名单是设备级包名设置，仅主用户可以修改。真实设备尚未验收。

## Evidence → Finding → Path

宿主源码基准为 `../传送门_5.1.5_反编译结果/jadx/sources/com/miui/contentextension/`，系统源码基准为 `analysis/ContentCatcherOS3_1/jadx/sources/`。宿主原 APK 与哈希见 [5.1.5 报告](2026-11-01_apk-taplus-5.1.5-report.md)；原始 APK 没有修改。

| Evidence | source_ref | repro_command | content_hash |
|---|---|---|---|
| E-imports | 宿主 Java 配置类、系统 SDK、RxJava/miuix 依赖；原始 APK | `python tools/verify_host.py '../传送门-5.1.5(50015).apk'` | `E7810EF31917CD64C80BDD338D4434E9CB6C49F4C823CA9B8F7BE6688F0B9C5B` |
| E-publish | 宿主 `setting/whitelist/utils/BlacklistUtils.java:366–380` | 在该文件检索 `saveBlacklistToSettingProvider` 和 `taplus_blacklist` | 同 E-imports |
| E-host-gate | 宿主 `data/common/ContentExtensionConfig.java:510`；`services/TextContentExtensionService.java:425/:553` | 检索 `getConfigWithUser`、`isForbiddenByUser` | 同 E-imports |
| E-native-gate | 系统 `com/miui/contentcatcher/Interceptor.java:1120–1155`、`:1178–1193`；`v/a.java:112/:215/:288` | `rg -n 'taplus_blacklist|f529r' analysis/ContentCatcherOS3_1/jadx/sources` | n/a，本地系统组件导出；原始输入记录见既有 ContentCatcher 报告 |
| E-catalog | 本地 SDK35 `android/content/pm/PackageManager.java:1173–1179` | 检索 `MATCH_ANY_USER` | n/a，本地 SDK 源码 |
| E-contract | `artifacts/host-contract-0.9.0.json`、`host-contract-4.1.3-0.9.0.json` | 对新旧原 APK 分别执行 `tools/verify_host.py` | 输入 APK 固定于各 JSON |
| E-validation | `artifacts/validation-0.9.0.json`、`junit-0.9.0/`、`lint-0.9.0.txt`、`ui-0.9.0/` | 下文验证命令 | 交付 APK SHA-256 见下文 |

| Finding | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F-native：只改识别结果无法放行原生黑名单中的应用 | n/a_re | E-publish,E-native-gate | high | Global 名单与来源进程缓存 | 已实现专用名单发布；设备待验 |
| F-host：还须在创建浮窗和识别前执行模块范围 | n/a_re | E-host-gate,E-contract | high | ContentExtensionConfig.getConfigWithUser | 已接管应用层，保留页面/手势检查 |
| F-white：系统没有对应白名单键，需转换为黑名单补集 | n/a_re | E-native-gate,E-catalog | high | SourceScopePublisher | 本地补集/异常/多资料包测试通过；ROM 待验 |
| F-frozen：本体被冻结后配置不能依赖其 Provider 启动 | n/a_re | E-validation | high | RuntimeSnapshot / RuntimeServer / HostSourceScopeHooks | 框架保存配置并发布，模拟测试通过 |

P-1，`path_type=callflow`：主用户选择模式/应用 → `ConfigStore` 原子保存单个 JSON → 无数据广播通知宿主，并由既有 `RuntimePublisher` 同步完整框架快照 → 宿主/系统从可信配置计算有效黑名单 → 只写 `taplus_blacklist` → 来源 Activity 恢复时原生拦截器重读 → 长按触发前按名单决定是否捕获。对应 E-publish/E-native-gate、F-native/F-white。

P-2，`path_type=callflow`：已捕获内容进入宿主 → `getConfigWithUser` 检查模块范围 → 拒绝时返回 forbidden；允许时调用原私有 `getConfig` 保留页面与手势限制 → 原识别与卡片链。对应 E-host-gate、F-host。

## 实现

- `SourceScopePolicy` 只接受 BLACKLIST/WHITELIST，包名严格校验，最多 4,096 个名单条目，排序不可变。非法保存数据不作为“全部允许”处理。
- `ConfigStore` 用单个 JSON 保存模式和两份名单；提交失败不发送通知。设置页读取坏数据时提供空白名单修复草稿，明确提示，直到用户显式保存才修复原值。
- `RuntimeSnapshot` 升级 schema 2，包含范围；旧 schema 1 仍可解析为未接管。配置身份包含范围变化，原在途冻结流转票据会因身份变化失效。
- `BridgeProvider.source_scope` 仅向同用户、已验证宿主 UID/版本返回已存策略，忽略调用方提供的名单。冻结时读取当前构建的框架快照；只允许回退至已知的受管策略，不用过期未接管缓存覆盖有效名单。
- 宿主 Hook 覆盖启动、原生设置保存和最终 Global 发布入口。接管期间跳过会将派生名单写入 `blacklistConfig`/宿主 SP 的更新链；原始黑名单文件保留。
- `getConfigWithUser` 拒绝不在范围的来源，允许时使用原 `getConfig`；不绕过总开关、隐私许可、页面限制或手势支持检查。
- 白名单使用带 MATCH_ANY_USER、MATCH_DISABLED_COMPONENTS、MATCH_UNINSTALLED_PACKAGES 的包目录，包含无桌面图标、停用及其他用户的包，不按当前用户 FLAG_INSTALLED 再过滤。查询失败或目录无效时整次拒绝发布，保留原 Global 值。
- 系统框架监听所有用户的包安装/卸载，重新计算补集；宿主也监听自身可见的安装变化。跨用户观察器注册异常只记录固定错误，不接受广播中的名单数据。
- 框架 `status` 对已认证配置发布有效名单并确认结果，即使宿主未运行；不依赖冻结本体被启用。范围与“应用流转”和“独立流转”开关分别管理。

## 验证与产物

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --console=plain
python -m unittest discover -s tools -p 'test_*.py' -v
python tools/verify_host.py '../传送门-5.1.5(50015).apk' --output artifacts/host-contract-0.9.0.json
python tools/verify_host.py '../传送门_4.1.3.apk' --output artifacts/host-contract-4.1.3-0.9.0.json
```

结果：526 项 JVM/Robolectric 测试，0 失败/错误/跳过；22 项 Python 测试通过；新旧宿主各 70 项真实 DEX 定义及 static 标记通过；Release lint 0 错误、25 警告。范围相关测试涵盖双名单、空名单、持久化冷读、旧快照兼容、坏数据、主用户写入、Provider/Binder 权限、冻结状态发布、包目录异常和 UI 搜索/保存/取消。已人工查看浅色与深色大字截图，文字、勾选和保存按钮可见，列表可滚动。

APK：`artifacts/TaplusBridge-0.9.0-release.apk`，22,270,182 字节，版本 0.9.0 / 17。

SHA-256：`443B02AA3077F7E634240B5EF238FC32C1326DD695575EA2EE07376A4D1859CD`。

沿用现有长期证书 `2F3C681D676D8F763B191DEA0714C399E21BBAA0E948F25289EBFBCF41D1AABD`，v2 验签通过；ZIP 4 字节、native 16 KiB 与 ELF PT_LOAD 对齐通过。原始输出见同目录 `apksigner-0.9.0.txt`、`zipalign-0.9.0.txt`、`module-manifest-0.9.0.xml`、`gradle-0.9.0.txt`。

## 真机待验与边界

更新模块后需重启，并保持传送门/system 作用域启用。进入首页「生效应用」，选择并保存，再退出来源应用的 Activity 后重新进入。系统源端没有名单 ContentObserver；持续存活的 Activity 缓存可能仍保留旧值，不能承诺当前页面立即刷新。

未新增微信、QQ 或其他来源应用 Hook；未修改系统 ContentCatcher feature/page 规则。系统没有对应抓取能力的应用仍不能凭名单获得支持；`com.miui.personalassistant` 在所提供的系统组件中另有硬编码拒绝。现有目标路由、Root/Shizuku、冻结本体行为不扩大权限。

未安装或操作真实设备，未完成 LSPosed 回调、ROM Global 权限、全设备包枚举与包变化观察器、源端缓存刷新、真实长按/双指/剪贴板、冻结本体冷启动和目标解冻的设备验收。Robolectric 只能验证请求 flags、模拟目录及逻辑，不能证明 ROM 实际跨用户行为。未远程提交、推送、打标签或发布。

## Timeline

- 2026-11-01：核对宿主名单生成、文件与 Global 发布，以及系统来源进程长按前读取链。
- 2026-11-01：实现互斥模式、应用选择、持久化、可信框架快照与窄宿主 Hook。
- 2026-11-01：补齐主用户限制、全用户包变化观察、失效缓存保护与坏数据修复界面。
- 2026-11-01：完整本地测试、Lint、正式构建及签名/对齐检查通过，产出 0.9.0。
