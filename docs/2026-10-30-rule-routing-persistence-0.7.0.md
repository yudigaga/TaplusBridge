# 0.7.0：按网站规则流转与在线缓存固化

日期：2026-10-30（Asia/Shanghai）。用户明确的流程是：在规则列表选择目标应用，填写域名或网站，匹配后把对应原链接交给该应用。本版纠正0.6的全局目标误解，并处理在线规则重启后丢失的持久化缺陷。授权范围见 [scope.md](scope.md)。

## 行为

- 添加或编辑规则时，首先显示目标应用与域名/网址。完整网站仅提取域名保存；来源与备注在高级选项。长分享参数不再因整个URL超过256字符而被拒，输入仍有8192字符边界。
- HTTP命中规则统一使用原始URL的ACTION_VIEW并限定目标包，无单应用URI模板、无BROWSABLE类别限制。不匹配的网站不通过其他HTTP candidate绕过规则；独立的非HTTP原生/MAIN行为保留。
- 删除0.6全局目标、独立粘贴入口与对应执行类；升级只清理config.generic_target，不清在线/手动规则和开关。旧conversion字段仍可导入导出兼容，但不执行转换；新编辑保存none。
- 冻结目标继续Root/Shizuku，真实目标名称和图标、静默探测及来源窗口限制保持；不Hook微信/QQ。

## 持久化发现与修复

| 证据 | 来源 | 结论 |
|---|---|---|
| E-001 | `artifacts/rules-disk-red-0.7.0.txt` | 真实SharedPreferencesImpl重新读取磁盘XML：CRLF快照校验失败、旧初始化失败状态不重试、partial marker错误补基线均可复现；普通LF、多次设置提交、主备高水位与空撤回原本通过 |
| E-002 | `artifacts/rules-floor-red-0.7.0.txt` | 相同seed版本/SHA但首次失败留下无快照的状态无法恢复 |
| E-003 | `artifacts/rules-disk-recovery-red-0.7.0.txt` | 真实第一次commit失败的恢复map缺withdrawal标记；13项中仅此恢复例外仍失败，排除了仅用理想手造状态测试 |
| E-004 | `artifacts/junit-0.7.0/TEST-io.github.yudigaga.taplusbridge.rules.RuleRepositoryDiskAndroidTest.xml` | 当前13项真实磁盘冷载全部通过；测试绕过Context实例缓存，并等待QueuedWork落盘后创建新的Android SharedPreferencesImpl |
| E-005 | `artifacts/validation-0.7.0.json`、Miuix渲染图、最终APK | 新规则编辑、预览、迁移及完整本地验证记录 |

F-001：下载原文直接放入XML文本节点时，CR/CRLF可能被XML重读归一，导致原UTF-8 SHA失配。新版主备快照用带版本前缀Base64存原始UTF-8，与校验和、最高版本等元数据仍在同一个SharedPreferences提交；严格解码并重新校验SHA，兼容旧明文缓存。Base64用于保真，不是加密。

F-002：旧初始化失败会留下initialized但无快照；仅在已知从未接受过快照的失败状态重试基线。首次提交失败还可能留下seed高水位/校验和却丢失payload和withdrawal标记；新版仅在严格相同版本/SHA及固定旧失败状态下恢复同一份已知字节。更高版本、异SHA、撤回、未知partial marker或坏类型不会被重置，手动规则、目标开关与最高版本保留。

F-003：恢复基线前验证与已有手动规则的合并容量，避免补回22个基线域名后使原95个手动域名整表失效。在线页明确显示本地缓存修订和条数；筛选结果空不再被描述为本地没有规则。关闭在线更新仍使用已有缓存。

Path：更新后无需清数据，可重新同步一次在线规则，再重启检查本地缓存版本/数量。上述是已复现的实现缺陷；未拿到用户私有配置和真实重启录像，不能将它们认定为设备上唯一原因，也不承诺ROM主动清除应用数据时仍能保留私有缓存。

## 验证与交付

- APK `artifacts/TaplusBridge-0.7.0-dev.apk`，versionName `0.7.0-dev` / versionCode `12`，28,639,426字节。
- SHA-256：`58C19808FCA71C4CFE2A7B6988FA67BFCEA802A5CC5B62C72CFB14631A8F2253`。
- 47套件440项本地测试，0失败、0错误、0跳过；含13项真实磁盘冷载、18项规则流转，以及UI选择冻结应用→填写网站→保存→预览原URL。
- Python日志工具19项通过；lint 0错误、19警告，主要为同步偏好提交、系统反射、目标SDK和本地化等已审阅项目。
- v2开发签名证书延续 `4e06d06e242802a8b8e77f696079ddd78e3d615d0cf73b781f3a187abb51f980`；ZIP与四ABI原生库16KiB对齐通过。
- 原宿主58成员静态契约、API102及传送门/system作用域通过；最终APK不含旧GenericLinkLauncher、FluidCloudRuleCatalog或libxposed/旧Xposed API类。
- 9张实际Miuix本地渲染图在`artifacts/ui-0.7.0/`，已查看规则编辑、原URL匹配预览与本地缓存显示。不是手机截图。
- `adb devices -l` 无连接设备；未安装新版、未验收真实手机重启/目标内容/Root或Shizuku解冻/零闪页；未长期密钥正式签名、未远程发布、未修改IceBridge。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --console=plain --max-workers=1 '-Dorg.gradle.jvmargs=-Xms32m -Xmx1024m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
python -m unittest discover -s tools -p 'test_*.py'
python .\tools\verify_host.py '..\传送门_4.1.3.apk' --output .\artifacts\host-contract-0.7.0.json
```
