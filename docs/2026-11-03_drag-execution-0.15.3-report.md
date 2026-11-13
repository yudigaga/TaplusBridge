# 0.15.3：修复方向拖拽结果被取链流程再次拦截

> 本文中的本地归档路径不随源码仓库分发；公开发布产物请以 Releases 为准。

日期：2026-11-03（Asia/Shanghai）。版本：0.15.3 / 35。范围见 [scope.md](scope.md)。用户要求修复执行问题并直接提供正式版；本次输出为长期密钥签名、启用 R8 的本地 Release APK。

## 日志证据与判断

输入：`F:/qq/MobileFile/LSPosed_20261103_150229.zip`。SHA-256：`D938D621E04E449C9A81BA3893D0EBDA6A8DD3C001BE2D1FEC43278E6F6E872C`。只读 ZIP 条目并筛选阶段元数据，不执行日志中的任何内容，也不在报告保存识别正文或完整链接。

当前 verbose 条目为 `log/verbose_2026-11-03T14:54:37.554599.log`，SHA-256：`eeeac273cb601abf2a0afb023589881404a416373557dad0ab558bc396ce68a3`。

- 共 20 次接管尝试：18 次 `takeover_succeeded`、2 次 `takeover_failed`。失败发生于 15:01:49.350 和 15:02:01.597，后续尝试恢复成功。
- 15 次宿主内容导入，其中左方向 6 次、右方向 9 次；另有 3 次松手方向为 null，不进入方向动作。
- 全程宿主 PID 为 14658，本轮没有观察到宿主进程重启。
- `scope link stage=blocked reason=native_pick` 共 28 次，`drag delivery stage=` 和内容选择阶段记录均未出现。
- 示例：14:59:47.590 左方向导入后，14:59:47.628 又出现原生文本捕获，14:59:47.634 进入 native_pick 拦截。其他导入后也多次出现相近时序。

代码检查确认一处执行阻断：拖拽在需要重新识别时，经宿主 `mCallback.onContentReceived` 重投本轮结果；宿主同步调用 `onStartPick → onPickText/onPickImage`。原取链 Hook 将这次模块回放当作新的物理长按捕获，再次交给 ownedLinkPick 并提前返回，导致识别和内容选择不能继续。日志时序与此路径一致，但旧日志缺少执行分段记录，不能仅凭它逐次证明所有失败均由这一处引起。

日志中的已装版本仍为 0.15.2-dev / 34。此前多个候选沿用该版本号；本地检查的旧开发包含执行日志字符串，因此不把当前现象直接归因为“安装了旧包”。本次递增版本号以便区分后续日志。

## 修复范围

`HostDragHooks` 为主动回放添加 ThreadLocal 上下文，只在当前线程、同一 service 实例和同一 result 实例均匹配时生效。调用结束或抛出异常都会恢复先前上下文；嵌套回放也能恢复外层状态。

`HostScopeCaptureHooks` 将精确匹配的拖拽回放归入已有 synthetic pick 路径，使它继续宿主识别，不再重复作为物理捕获进入取链等待。原来源范围检查仍执行。普通长按继续原来的观察和取链流程；不同实例、其他线程及其他原生面板不能使用该标记。

新增 received、native_submitted、native_replay_admitted 阶段日志；已有“识别完成后原生面板已变化”的拒绝分支补充明确原因。保留原面板身份保护，不放宽为覆盖新面板。未新增 SystemUI 或来源应用作用域，未改变四方向配置或规则选择语义。

## 本地验证与交付

执行 `:app:testDebugUnitTest` 的 8 个定向套件，以及 `:app:lintRelease :app:assembleRelease`。109 项测试全部通过，失败、错误、跳过均为 0；Release lint 为 0 错误、38 警告。新增 4 项回放测试覆盖精确实例、跨线程拒绝、异常清理及嵌套恢复。其余套件覆盖原生识别复用、长按拖拽、内容导入、交接、内容选择和运行时通道。

传送门 4.1.3 的 54 项宿主静态定义检查通过。外部 ContentCatcher SDK 仍仅有引用证据；静态合同不证明手机回调已执行。Release DEX 已核对包含本次回放阶段标记，API 102 元数据及作用域为 `com.miui.contentextension`、`system`，未打包 API 实现类。

正式 APK：TaplusBridge-0.15.3-release.apk（本地归档：`../artifacts/TaplusBridge-0.15.3-release.apk`），3,267,333 字节。SHA-256：`1FA3833C40594CB4CAAE3A665A7A288781CC2C80B8BFE0AEE2E2EBBA203A093E`。

APK v2 签名通过，证书 SHA-256 为 `2f3c681d676d8f763b191dea0714c399e21bbaa0e948f25289ebfbcf41d1aabd`，与 0.15.2 正式包一致。4 字节 ZIP 对齐、16 KiB 原生库 ZIP 对齐及各架构 ELF PT_LOAD 对齐通过。R8 mapping、测试 XML、lint、签名与对齐输出均已归档，见 验证清单（本地归档：`../artifacts/validation-0.15.3.json`） 和 校验和（本地归档：`../artifacts/SHA256SUMS-0.15.3.txt`）。

本次没有运行全量测试，没有连接设备、安装、真机 Hook 复测或远程发布。不能将本地通过表述为手机执行已验收。

安装后重启手机以更新系统侧 Hook。若当前为同证书正式包，可覆盖安装；若为此前 Android Debug 签名的开发包，需先导出配置，切换签名前不能直接覆盖，卸载旧包会清除其应用数据。

复核动作：长按后向已配置方向移动并松手，应弹出内容选择，再按预设动作处理所选内容；普通无方向长按仍保留原流程。后续日志应能区分 received、native_replay_admitted、native_submitted、recognition_complete、choice_launch 各阶段。隐藏卡片中未由宿主提供的链接仍不保证可恢复。

## Evidence → Finding → Path

| Evidence | source_ref | repro_command | content_hash |
|---|---|---|---|
| E-log | 本报告列出的 ZIP、当前 verbose 条目及时间点 | 只读 ZIP 筛选 drag gesture / scope link / drag delivery 固定阶段 | 上述 ZIP 与条目 SHA-256 |
| E-call | HostDragHooks、HostScopeCaptureHooks；本地 4.1.3 TextContentExtensionService | 追踪 onContentReceived → onStartPick → onPickText/onPickImage | 验证清单 source_sha256、宿主合同 SHA-256 |
| E-test | junit-0.15.3 与 HostDragReplayAndroidTest | 8 个定向套件，含回放标记异常与隔离测试 | 验证清单 source_sha256 |
| E-release | validation-0.15.3.json、签名、对齐和 R8 mapping 归档 | assembleRelease；apksigner verify；zipalign -c -P 16 -v 4 | 上述 APK SHA-256、验证清单 mapping_sha256 |

| Finding | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F-replay | n/a_re | E-log、E-call | 代码确认，日志时序相符 | 原生拖拽结果重投与取链 Hook 交界 | 已修复并通过本地定向回归；真机待验证 |
| F-isolation | n/a_re | E-call、E-test | local verified | ThreadLocal 与实例身份判断 | 仅精确回放放行，原始长按路径保留 |
| F-package | n/a_re | E-release | local verified | 0.15.3 / 35 Release | 长期签名与对齐通过，本地交付 |

P-replay（path_type=callflow）：方向松手 → 单次授权内容导入 → 设置 pending → 必要时精确标记原生回放 → scope Hook 继续宿主识别 → 识别内容选择 → 已配置方向动作。复用已有识别结果时不需要进入回放分支。

Timeline：读取 15:02 归档确认导入后缺少执行阶段；对照宿主同步调用链定位重复拦截；加入精确回放标记及隔离测试；完成定向回归、Release lint、R8 长期签名构建和安装包核验。手机行为仍待安装重启后的复核。
