# 0.15.2：补齐原生长按后的方向手势

日期：2026-11-03（Asia/Shanghai）。版本：0.15.2 / 34。范围见 [scope.md](scope.md)。本次保留原输入和原生长按行为，通过旁路观察添加单指长按后的方向操作；没有新增 SystemUI 或来源应用作用域。

## 日志与原因

输入为 LSPosed_20261103_110419.zip，SHA-256 为 7223C9031680146B59015793473354266E401F30E577EACDBA381B9CD9CD8F85。流式筛选元数据见 [诊断摘要](../artifacts/drag-diagnosis-20261103-110419.json)。

当前模块日志第 15 行确认加载 0.15.1/33，第 57 行确认 drag stage=installed signatures=1 display_layout=split。当前 modules 与 verbose 日志中的 drag stage 都仅有一次 installed，没有 observed、shown 或 rejected。用户也确认长按仅出现原识别或来源应用菜单，内容没有跟手移动。因此上一轮 Android 16 字段修复已经越过安装问题，但原手势没有进入被观察的系统 performDrag。

旧实现只接收已经开始的 Android 全局拖拽。HostDragHooks 的安装并不提供来源拖拽入口；所检查的 ContentCatcher 版本也没有 startDragAndDrop，移动会取消其原采集状态。只增加 SystemUI 接收窗口无法补上这个断点。

## 本次实现

- 在现有 PointerEventDispatcher 观察点复制单指主屏触摸元数据，使用独立状态机识别长按后相对 DOWN 点的四方向移动。长按前超过系统 slop 的移动视为滚动，不触发新操作；方向阈值为 48dp 或三倍 slop 中较大者。松手时冻结方向，回到中心、多指、取消、异流、过期或新 DOWN 均阻止旧方向操作。
- 方向监听独立使用拖拽开关，不要求另开截图兜底或链接探测。先核验原始可见主窗口、来源与配置，再把宿主已获准的原生 pick 与同一物理流按来源、坐标和时间配对。只有可信宿主可认领，模块 Provider 必须消费系统单次 lease 才能导入内容。
- onPickText/onPickImage 的原回调继续执行。新分支不吞触摸、不注入输入、不延期或重放原 pick、不主动关闭来源菜单。普通长按、短按和无方向松手不显示新增预览；仅出现方向后显示不可触摸的跟手方向提示。
- 预览窗口自身 alpha 限制在系统允许触摸遮挡上限之下，避免只设置 NOT_TOUCHABLE 却让新增悬浮层阻断下方应用输入。
- 独立复制本轮图像，宿主可继续自行持有或释放原 Bitmap。图片归一化上限 2000 边 / 300 万像素 / 8MB；PFD、缓存及图片快照都有清理。
- 确认方向后复用原有完整识别、原文/OCR/链接/图片选择和预设动作。仍在显示同一原生面板时直接复用；原流程自然结束后才可为新增操作创建识别结果；若已有不同结果则放弃新增操作，不覆盖或取消它。新路径完成识别时也不主动调用 cancelTask。
- 5.1.5 的 URL 图片仅旁路读取本轮原 onPickImage 内 setBitmap 的解析结果，不额外下载，也不读取可能陈旧的 sBitmap。未命中此路径时仍保留原宿主行为。

## 验证与限制

执行受影响链路的定向 JUnit/Robolectric 测试、Release lint、签名构建、两版宿主静态合同，以及 APK 签名、对齐、版本、作用域和 mapping 检查。准确数量、哈希与证书见 [验证清单](../artifacts/validation-0.15.2.json)。没有运行全量测试，没有安装手机、执行真实 LSPosed 回调或远程发布。

最终本地结果：18 个测试套件共 249 项通过，失败/错误/跳过均为 0；lintRelease 为 0 错误、32 警告。两版拖拽宿主各 54 项静态定义检查通过，设备提供的外部 SDK 仍仅有引用证据。正式签名证书与 0.15.1 一致，APK v2 签名、4 字节及 16 KiB 对齐通过。APK 为 3250949 字节，SHA-256：702D4B5F2697E650617EA3E71DF541288B932BEDFA58077F6121EF4D0DC9653B；对应 R8 mapping 已归档。

这条新增入口依赖宿主本轮确实回传文本或可读取的图像，不从来源数据库补取内容。原采集没有传出的隐藏卡片网址仍不能凭标题恢复。为了保留原输入，来源应用依然可自行响应长按、选字或滚动；手机上的菜单共存、触摸时序与原生面板变化必须实测，不能仅凭本地测试声称绝无冲突。单指方向手势与系统设置的双指长按模式没有合并。

## 安装后复核

1. 覆盖安装 TaplusBridge-0.15.2-release.apk，打开模块保存拖拽开关及四方向动作，然后重启手机。
2. 原地长按并松手：应保留原识别或应用菜单，不执行新方向操作。
3. 单指长按至原采集触发后向一个方向移动，出现方向提示再松手：应进入固定操作的内容选择。回中心或取消不执行。
4. 对比日志：新增 drag gesture stage=installed、armed、direction、released、imported，以及 host stage=claimed/imported。旧 drag stage=observed 仅表示真正的 Android 全局拖拽，不是新方向入口的成功条件。

## Evidence → Finding → Path

| Evidence | source_ref | repro_command | content_hash |
|---|---|---|---|
| E-log | 诊断摘要内当前 modules 日志第 15、57 行 | 只读 ZIP 并筛选本模块 build / drag stage 固定元数据 | 输入 ZIP 的 SHA-256 如上 |
| E-input | SystemProbe、SystemLongPressDrag、LongPressDirection | 定向测试 LongPressDirectionTest / PhysicalTouchStateTest | 构建对应源码 |
| E-pick | HostScopeCaptureHooks、HostLongPressDrag、HostDragHooks | HostLongPressDragAndroidTest / HostDragNativeReuseTest | 构建对应源码 |
| E-bridge | RuntimeServer、DragNativeImport、BridgeProvider | RuntimeServerAndroidTest / DragProviderAuthAndroidTest / DragNativeImportAndroidTest | 构建对应源码 |
| E-apk | validation-0.15.2.json、mapping 与签名/对齐记录 | Gradle lintRelease / assembleRelease，apksigner verify，zipalign -c | 见验证清单 |

| Finding | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F-installed | n/a_re | E-log | high | 0.15.1 system_server | 已越过安装问题，未观察到 performDrag |
| F-entry | n/a_re | E-input、E-pick、E-bridge | local verified | 新单指方向入口 | 本地实现与回归通过，手机待验证 |
| F-preserve | n/a_re | E-pick | local verified | 原长按与菜单 | 原输入与pick继续，新增路径不取消其他面板；手机共存待验证 |

P-native（path_type=callflow）：真实 DOWN → 来源窗口与配置验证 → 原 ContentCatcher pick 正常通过隐私与范围检查 → 旁路关联本轮内容 → 原输入/原pick继续 → 长按后 MOVE / UP 冻结方向 → 单次 lease 导入 → 复用本轮识别 → 选择内容 → 既定方向操作。

Timeline：读取 11:04 归档确认安装成功但无拖拽回调；用户确认没有内容跟手并要求保留原行为；补齐旁路方向入口和内容导入；定向回归、Release lint、本地长期签名构建与产物核验。手机行为仍待安装重启后的实测。
