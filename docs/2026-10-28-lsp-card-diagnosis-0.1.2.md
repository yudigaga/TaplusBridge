# 传送门卡片与目标图标：用户日志诊断

日期：2026-10-28，时区 Asia/Shanghai。范围见 [scope.md](scope.md)。状态：完成日志调查和诊断版构建，手机卡片/图标问题尚未确认解决。

## 结论

用户截图为 17:52:00，实际记录在 17:51:30 使用 Intent builder 的是旧宿主进程，该进程仅安装了来源跟踪和两个打开链接 Hook。新识别与图标增强在 17:54:27 的新进程才安装，17:56:15 再次重启时也安装成功。截图早于新进程的增强接入；不能用它判断新图标 Hook 的最终效果。

17:54 后的归档证明 Hook 初始化成功、作用域为传送门/用户0，没有发现 TaplusBridge 自身异常。它不能证明所有绑定回调都执行过，也不记录每次解析/图标写入结果。宿主的 `unknown client` 异常来自查询输入法窗口高度，随后仍有识别流程，不应误判为模块加载失败或卡片问题的已确定原因。

后续文字识别记录有几次没有 HTTP URL，图片路径记录了 `viewBitmap`；未记录的其他原始字段仍未知，不能据此宣称完整载荷没有链接。17:56:27.287 那次文字有 942 字符及一条 877 字符的完整抖音链接，实际生产解析器回放成功：提取1条，规则唯一命中抖音，固定深链转换成功。故此样本不能用“链接被截断”或“本地 URL 解析失败”解释。

## Evidence → Finding → Path

输入 ZIP SHA-256：`DB665871E6FC2E3A9525446768DE1447F689ECEDF81E71026D78F9DAA52F8BB2`。

| E-id | source_ref | repro_command | content_hash |
|---|---|---|---|
| E-scope | ZIP `db_scope.txt:443-445`、`scopes.txt:605-607` | 仅检索 taplusbridge 相关配置行 | 输入 ZIP 哈希 |
| E-old | ZIP `log/modules_2026-10-28T17:11:34.091705.log:1721-1730` | `python tools/analyze_lsp_archive.py <用户ZIP>` | 同上 |
| E-new | 同日志 `:1733-1737`、`:1745-1749` | 同上 | 同上 |
| E-text | ZIP `full.log:109009`，17:56:27.287 | `tools/ReplayTaplusRecognition.java` 读取原日志后，仅输出元数据 | 同上 |
| E-img | ZIP `full.log:77987`、`:104448`、`:113579`、`:115274` | 摘要工具只输出 bitmap 事件 | 同上 |
| E-caption | 宿主 `TaplusSplashFloatView.generateNerCard` 与 `TaplusRecommendBaseCard.bindNerInfo` | 搜索 `open_browser`、`bindNerInfo`；中文资源 open_browser 实为“打开链接” | 宿主原 APK 既有哈希 |
| E-replay | `artifacts/lsp-20261028-175706-replay.txt` | 生产类的真实输入回放 | n/a，无私有正文或 URL |

| F-id | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F-loaded | n/a_re | E-scope,E-new | high | 当前手机宿主进程 | 识别 Hook 初始化已见日志 |
| F-timing | n/a_re | E-old,E-new | high | 截图与进程加载时间差 | 17:52 图早于新识别 Hook 安装 |
| F-parser | n/a_re | E-text,E-replay | high | LinkExtractor/RouteMatcher/DeepLinks | 该样本文字解析、匹配、转换均正常 |
| F-icon | n/a_re | E-caption | high | URL 的 NER 卡片绑定路径 | 路径已覆盖；缺真机实际回调结果 |
| F-gap | n/a_re | E-new,E-img | high | 接入后到实际 UI 的诊断缺口 | 0.1.2 补阶段日志，待新归档验证 |

P-1，`path_type=callflow`：ContentCatcher → 捕获/提取计数 → 本地/服务器卡片发布计数 → bind_ner/bind_recommend → resolve 成功或固定原因 → icon_requested → ImageView 原操作执行后 icon_displayed。

## 0.1.2 只补诊断

- 模块启动显示 `build=0.1.2-dev/3`，区分重启前后的实际加载版本。
- 卡片绑定、预览解析、字段存在情况、图标请求与实际写入分别记录。例：`recognition stage=resolve outcome=success target=com.coolapk.market`，`recognition stage=icon_guard outcome=icon_displayed`。
- 失败原因只允许固定值：disabled、recognition_disabled、no_match、ambiguous、target_missing、invalid_request、unsupported_host、exception 等，不输出任意异常消息。
- 诊断每会话去重且最多64条；回调异常不影响原流程。
- Planner 新增结构化拒绝原因，原有 plan()、规则优先级、Intent 构造与失败保护保持相同。Provider 不给未授权调用者额外信息。
- 不新增微信/来源应用作用域、不补造缺失 URL、不改变识别手势或宿主服务器行为，不新增可见中转页面。

这不是已确认的功能修复：从当前日志尚不能确定用户卡片在数据、发布、解析还是 UI 写入处失败。不能以“安装日志成功”代替“卡片识别成功”，也不能把模拟控件测试当作手机图标已显示正确。

## 验证与复现

本地 111 项 JVM/Robolectric 测试通过，0 失败/错误/跳过；lint 0 错误、12 警告；38 项宿主 DEX 契约通过。候选 `0.1.2-dev / 3` 为 236440 字节，Debug v2 签名与 ZIP 对齐通过，无原生库。SHA-256：`6FE4A3A57ABA2A5FFA7B65D9C34F44F8187E6EC079C321F5F2C7EC253B97713B`。详情见 `artifacts/validation-0.1.2.json`。实际日志摘要见 `artifacts/lsp-20261028-175706-summary.json`。新增测试检查拒绝原因、诊断无正文/URL、去重/容量和日志失败保护。未安装或运行此诊断候选到手机。

```powershell
python tools/analyze_lsp_archive.py 'F:/qq/MobileFile/LSPosed_20261028_175706.zip' --output artifacts/lsp-20261028-175706-summary.json
```

回放 Java 源码在 `tools/ReplayTaplusRecognition.java`；编译时 classpath 指向本工程 Gradle 输出的 `intermediates/javac/debug/compileDebugJavaWithJavac/classes`，再用同一 classpath 运行并传入 ZIP 路径。程序直接读取日志，只输出长度、主机、数字ID形态与目标包，不保存原文和完整URL。

手机操作：覆盖安装诊断 APK，强行停止传送门后重新触发；确认日志启动行含 `0.1.2-dev/3`；分别长按一条酷安完整链接和失败的原卡片各一次，再导出 LSPosed 日志。新阶段结果用于继续修复实际失败点。

Timeline：17:52 用户截图 → 17:54:27 新识别 Hook 首次入进程 → 17:56:15 新进程再次安装 → 17:56:27 收到可回放的抖音 URL → 17:57:37 归档文件落盘 → 本地脱敏分析/生产解析器回放/诊断构建。未改 IceBridge，未远程发布。
