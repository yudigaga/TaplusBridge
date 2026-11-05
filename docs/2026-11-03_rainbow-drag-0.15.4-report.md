# 0.15.4：长按内容、四边彩虹与碰触反馈

日期：2026-11-03（Asia/Shanghai）。版本：0.15.4 / 36。范围及授权见 [scope.md](scope.md)。这是本地长期签名 R8 Release 交付，未远程发布。

## 最终交互

用户提供富内容卡片截图、`Screenrecorder-2026-11-03-16-00-59-602.mp4` 和 Android 官方拖放文档，随后明确要求四边彩虹、对应操作图标、向拖动内容靠拢、接触震动、松手执行。

长按仍从原生 ContentCatcher 回调接收文字或图片，关联当前真实触摸流后才建立全屏遮罩。遮罩内显示 144 × 72 dp 的内容预览及四边操作图标。朝某一边移动时，该边彩虹根据移动距离向预览弯曲；接触后彩虹贴着预览边缘，图标高亮，首次接触震动一次。保持接触不重复震动，离开后再接触可再次反馈。进入接触状态后松手才提交该边已配置动作，仍按此前约定弹出本轮内容选择；接触前松手、拖回中心、多指或取消不提交。

图标以同一套线条绘制：模块能力为四格，分享为连接节点，浏览器为地球，按规则打开为应用窗口与箭头，识图为相机，翻译为双语字符，复制为叠页，未设置为禁用符号。系统原生全局拖放接收窗口也复用相同四边样式；该路径保留 Android 自己的拖动阴影，避免重复预览。

源应用不支持 Android `startDragAndDrop` 时，原长按回调仍可作为模块方向手势的内容源。它不是把其他应用的消息 View 强行改成可拖放 View，也不代表可将消息或文件直接投放给任意第三方应用。来源未提供的隐藏链接和文件字节不能由预览标题推断。

## 识别内容保留

原长按面板自然结束后，如需要回放，本次直接保留完整的原 native result，避免重新构造仅含标题文字的结果而丢失属性和额外 job。已有面板只在同结果身份且已附着时复用，其他新面板仍不覆盖。

拖拽完成读取现有 RecognitionHooks 的链接快照。快照同时绑定 service 实例、result 实例和识别 epoch，不能从其他长按会话取得链接。原生推荐任务明确归属本 epoch 且未结束时继续等待，最长使用现有 20 秒截止时间；到期仍保留已经完成的文字。完成 IPC 将原文、识别文字和真实 HTTP(S) 链接分别保留；最多接收 8 个链接，检查长度、协议并去重，不把标题搜索结果或缩略图地址冒充原始落地链接。

`viewContent` 同时接受 String 与 CharSequence，避免富文本标题因类型不同被丢弃。候选内容缺少几何或没有文字/图像时新增固定原因日志。候选认领允许经过身份核验的原生宿主遮罩位于同一来源窗口前，不放宽到其他应用或安全窗口。

本次没有放宽原点点击取链的物理松手、窗口或来源约束。长按返回的元数据与原推荐可用时可保留链接；如果卡片必须依赖另外的原点点击取链才能暴露 URL，这条拖动手势仍不能保证恢复它。截图卡片的真实成功率仍需手机验证。

## 验证和产物

运行 17 个定向 JUnit / Robolectric 套件，涉及彩虹碰触位置、密度缩放、回中取消、边缘吸附、震动重入、两类拖放 UI、长按交接、单次 token、来源校验、卡片富文本、识别会话隔离、链接边界及选择操作。准确统计与最终 lint、签名、对齐、APK 和 mapping 哈希见 [validation-0.15.4.json](../artifacts/validation-0.15.4.json)。

最终结果：175 项测试通过，失败/错误/跳过均为 0；Release lint 为 0 错误、37 警告。54 项宿主静态定义检查通过。APK v2 长期签名、4 字节 ZIP、16 KiB 原生库 ZIP 与 ELF PT_LOAD 对齐通过；设备框架 ContentCatcher SDK 仍未作动态验证。

本地 Canvas 渲染检查包括中心、靠近、四边接触状态，见 [渲染拼图](../artifacts/ui-0.15.4/rainbow-contact-sheet.png)。它检查实际 View 绘制，不代表手机帧率、手指触感或触摸交接已经验收。

APK：[TaplusBridge-0.15.4-release.apk](../artifacts/TaplusBridge-0.15.4-release.apk)。配套 [SHA-256](../artifacts/SHA256SUMS-0.15.4.txt) 和 [R8 mapping](../artifacts/TaplusBridge-0.15.4-r8-mapping.zip) 同目录归档。签名使用与 0.15.3 正式包相同的长期密钥。安装后重启手机使系统侧 Hook 更新。

APK 为 3,267,337 字节，SHA-256：`F9DBDD49CF2203A38FD1CB1555D5C41E3943640FEEFED65349D74DE0FF2AEEB5`。长期签名证书 SHA-256：`2f3c681d676d8f763b191dea0714c399e21bbaa0e948f25289ebfbcf41d1aabd`。

未运行全量测试，未连接手机、安装 APK、进行真实 LSPosed 回调、系统手势隔离或震动体验验收。15:34 日志仅能证明旧版的部分长按手势走到了选择请求被接受，不能代替 0.15.4 的设备验证。

## Evidence → Finding → Path

| Evidence | source_ref | repro_command | content_hash |
|---|---|---|---|
| E-input | 用户截图、16:00:59 录屏、15:34:49 ZIP | 只读本地媒体与 ZIP，抽帧核对跟手预览；日志仅取阶段和计数 | 见验证清单 inputs |
| E-geometry | EdgeDragTarget、SystemLongPressDrag、RainbowDragView | EdgeDragTargetTest、RainbowDragViewAndroidTest、DragGlobalEdgeAndroidTest | 见验证清单 source_sha256 |
| E-recognition | HostDragHooks、RecognitionHooks、BridgeProvider、DragActionStore、DragPayload | RecognitionEpochIsolationAndroidTest、DragHandoffAndroidTest、BridgeProviderAndroidTest 等 | 见验证清单 source_sha256 |
| E-release | APK、mapping、签名/对齐和测试 XML | testDebugUnitTest 定向过滤；lintRelease；assembleRelease；apksigner verify；zipalign -c -P 16 -v 4 | 见验证清单 |

| Finding | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F-contact | n/a_re | E-geometry | local verified | 彩虹与预览接触 | 视觉和提交共用位置计算；手机待验证 |
| F-content | n/a_re | E-recognition | local verified | 原长按识别与拖拽内容选择交界 | 保留完整结果和同会话链接；额外原点点击取链不扩展 |
| F-package | n/a_re | E-release | local verified | 0.15.4 / 36 Release | 见最终验证清单；没有远程发布 |

P-drag（path_type=callflow）：真实长按 → 原生允许的文本/图像回调 → 来源和手势认领 → 全屏触摸遮罩及预览 → 活跃边彩虹靠近 → 接触震动 → 松手冻结接触边 → 导入 → 复用或完整回放本轮原长按结果 → 等待本轮识别与推荐 → 选择内容 → 该边预设动作。

Timeline：读取 15:34 日志及来源卡片截图；按用户要求查看录屏和官方文档；将方向箭头改为四边彩虹、图标和跟手预览；加入共享接触判定和单次触觉反馈；补齐原识别链接保留；定向回归、实际 View 渲染、Release lint 和长期签名 R8 构建。仅记录本地证据。
