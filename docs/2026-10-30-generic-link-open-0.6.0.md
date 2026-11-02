# 0.6.0：指定应用的通用链接流转

日期：2026-10-30（Asia/Shanghai）。本次按用户最新要求改为“选择目标应用，把链接交给它打开”，不再用逐应用域名适配作为必经路径。范围与授权见 [scope.md](scope.md)。

## 新交互

1. 首页“指定目标应用”选择已安装或冻结应用。以后识别到的 HTTP(S) 链接原样交给该包，优先于所有自动规则，不做专用深链转换。
2. 首页“通用打开”可选择本次应用、粘贴链接、明确点击打开。本次目标独立于全局设置，不必先创建域名规则。
3. 目标已启用且支持该链接时直接打开；冻结目标沿用 Root / Shizuku、透明前台入口、截止与取消保护。
4. 清除指定目标才恢复原来的 IceBridge 自动规则模式。现有规则、在线缓存及授权设置保留。

只有模块本地界面可创建本次明确选择的内存票据。公开 TransitActivity 仍只读取随机 token，不读取外部 target/url；宿主 IPC 不接受 explicit_target 字段。链接不写配置、日志或页面保存状态；请求只有单次、短期内存票据。

通用模式是原始网址的 ACTION_VIEW + 指定包，不带 browser 专用类别，不强制要求目标声明为浏览器。目标必须有系统允许调用的链接处理入口；无法处理时明确返回错误，不把打开首页或浏览器当作链接成功。因而“统一流转”不等于能让任意应用理解任意网站内容。

## 新日志结论

归档：`LSPosed_20261030_110942.zip`，SHA-256 `02ebba921aa87c43c5028e79600a152ddad3cc3d9404a5cd6cf4f98069561597`。只读相关日志，未执行归档内容，未保存正文/网址/nonce/坐标。

| 证据 | 定位 | 观察 |
|---|---|---|
| E-001 | `artifacts/lsp-20261030-110942-{metadata,summary}.json` | 四轮均为 waiting_release → ready → input_sent → URL_FOUND → resolve/no_match → publish_local/0 |
| E-002 | 2026-10-30 11:08:48.535、52.192、54.675、56.703；模块日志556、568、580、592行 | 捕获成功，但没有目标卡片生成，也没有进入解冻或目标应用启动的证据 |
| E-003 | `artifacts/generic-red-0.6.0.txt` | 旧 Planner 下，新手动打开测试10项中6项失败，包含未知域、冻结票据及用户明确选择优先级 |
| E-004 | `artifacts/generic-category-red-0.6.0.txt` | 旧通用 Intent 的 BROWSABLE 类别误拒只有普通 VIEW 入口的应用；真实PM回归失败 |
| E-005 | `artifacts/junit-0.6.0/`、`gradle-0.6.0.txt`、`validation-0.6.0.json` | 当前完整验证与最终APK记录；不代表真机所有链接内容已成功打开 |

F-001：这四轮不是“目标应用已经启动后闪退”的已证实样本，而是捕获后被规则门槛拦下；“闪”与探测浮窗恢复一致，缺少屏幕帧不能作视觉确认。归档没有HTTP或已知域名字面量，也没有模块私有规则配置，不能猜测四个实际网址或故障应用。

F-002：新版明确指定目标会直接绕过域名匹配、冲突、规则组停用与损坏/空规则表，因此无须再为每个应用补一份域名表。URL的大小写、查询、转义、端口及片段原样保留；链接与目标包不进入Root命令。

Path：安装0.6.0后重启，选择目标，再长按识别或用通用打开粘贴测试。真实应用是否接受、是否进入对应内容，仍需在设备上核验；未接受时显示错误，不静默回退。

## 实现与验证

- `ConfigStore.genericTarget` 保存用户选择；`LaunchPlanner` 优先本次明确选择，再取全局目标，未指定才走自动规则。
- `GenericLinkLauncher.open` 只创建原始 HTTP(S) VIEW，限定目标包；直接或透明内存票据交付。选错包、未安装、无处理器、失去页面、启动异常均明确失败。
- 18项通用规划测试覆盖陌生域/短链、规则冲突/停用/空表/损坏、最新目标重规划、原生无HTTP路径、输入和本地来源限制；11项打开测试覆盖真实PM处理器、普通VIEW入口、冻结票据、无首页回退、异常清理和线程/页面限制。
- UI测试覆盖保存/清除指定目标、本次选择、明确点击、错误反馈及链接不持久化；Miuix本地渲染在 `artifacts/ui-0.6.0/`。
- 最终测试数量、lint、签名、16KiB对齐、APK大小与哈希见 `artifacts/validation-0.6.0.json`；校验和见 `artifacts/SHA256SUMS-0.6.0.txt`。
- 版本 `0.6.0-dev / 11`，仍沿用Android Debug证书，不是长期密钥正式版；API102、传送门/system作用域，不Hook微信/QQ，不修改IceBridge、不远程发布。
- 当前未连接设备，未安装新版、未实测目标内容落点/零闪页/Root或Shizuku解冻；本地测试不替代这些验收。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --console=plain --max-workers=1 '-Dorg.gradle.jvmargs=-Xms32m -Xmx1024m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
python -m unittest discover -s tools -p 'test_*.py'
```

## 最终候选

- `artifacts/TaplusBridge-0.6.0-dev.apk`，versionCode 11，28,718,322 字节。
- SHA-256：`6F7C6692CEE8B0E7943836E367C4537DEC6E29AFD3A0068608DC55A259D12B18`。
- 47套件、438项本地测试，0失败/错误/跳过；另有19项Python日志工具测试通过。
- lint 0错误、18警告；v2开发证书签名与ZIP/原生库16KiB对齐通过；58个原宿主静态成员契约通过。
- Miuix的10张本地渲染图已生成，新增指定目标与手动打开界面已查看；没有连接真实设备。
