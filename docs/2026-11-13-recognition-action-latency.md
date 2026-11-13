# 长按拖动识别完成后的动作延迟

日期：2026-11-13。用户反馈部分场景很快，部分场景在识别完成、长按拖动后执行打开或分享还需约 4–5 秒。

## 定位与修改

当前代码存在一条可复现的额外等待：`HostDragHooks.perform` 已取得分词结果，但仍通过 `RecognitionHooks.dragSnapshot` 等待本轮在线推荐 AsyncTask 完成。即便当前快照已经有有效网址，原等待标志也不会解除。在线推荐的完成时间不同，可以使相同操作走出不同耗时；这不是统一的固定延迟。

修改限定在 `DragSnapshot` 的最终边界：先按原有 RouteLink 校验、数量上限和不可变复制处理链接，再把等待条件收紧为“尚无有效链接，且当前在线任务仍待完成”。两处拖动交付分支共用该快照，因此正常分词和关闭分词两种路径同时生效。

以下边界保持：

- 无有效链接或只有非法链接时，仍等待本轮推荐，保留卡片只有标题、稍后取得真实网址的流程。
- OCR、分词完成条件和用户设置的三个手势阶段时间不变。
- 服务实例、原生结果对象、epoch、任务归属和配置开关校验不变；旧结果不能解除新手势等待。
- 提前交付保留当前已捕获的全部有效链接；交付之后在线服务器新增的附加推荐不追写已经开始的动作。这与现有“取得有效链接即停止补点等待”的策略一致，不保证收集任意未来链接。

`HostDragHooks` 增加被动分段记录：推荐等待仅记录一次 `recognition_wait`，区分 `snapshot_pending` / `recommendation_pending`；已有 `recognition_ready` / `recognition_complete` 增加本次交付的 `elapsed_ms` 和链接数量。计时从本次 Pending 创建开始，不是从最初手指按下开始。不记录原文、网址或令牌，不改变 100ms 轮询间隔。

## 复现与验证范围

新增 `RecognitionDragReadinessAndroidTest`，调用真实 `dragSnapshot`，使用受控策略 Provider 与真实 AsyncTask 归属。旧实现 7 项中 1 项明确失败：同一有效手势已有两个真实网址，在线任务仍为 PENDING 时，快照却继续 waiting。其余用例覆盖非法网址、空链接、旧服务/结果/epoch、旧任务、关闭服务器通道及 FINISHED 状态。

红灯日志位于工作区 `analysis/latency-20261113/recognition-red.log`，修改前源码副本在同目录 `before/`。修复后定向测试通过；最终全量 152 个套件、1,634 项 Android 本地单元测试通过，失败、错误、跳过均为 0。

独立审阅核对了当前两版宿主的图文路径：`TaplusRecognitionExpandedTextCard.refresh()` 先把 `mIsLoading` 置 true，再启动 OCR，更新分词结果后才解除；正常路径仍检查 `!isLoading`，关闭分词路径仍检查 `ocrComplete`。本轮改动不会移除这些完成条件。审阅未发现阻塞问题，README 同步说明交付快照及晚到推荐的边界。

最终命令 `:app:testDebugUnitTest :app:lintRelease :app:assembleRelease --max-workers=1 --console=plain` 退出 0，耗时 5 分 37 秒；R8 已执行。Release lint 为 0 错误、33 警告。APK v2 签名、ZIP 4 字节 / native 16 KiB 对齐均通过；长期签名证书与前版一致。

## 本地交付

| 项目 | 结果 |
| --- | --- |
| 版本 | 0.15.50 / 82 |
| 工作区主目录 APK | `E:/vs/claude/传送门/TaplusBridge-0.15.50-release.apk` |
| 大小 | 3,644,413 字节 |
| SHA-256 | 4B15D15F22067ED4A231904738BD3A8BCF02DC3138BC7049E7BBAB51EC665399 |
| 归档 | `artifacts/release-0.15.50/` 保存 APK、JUnit、构建日志、lint、签名、对齐、R8 映射、修改记录和验证 JSON |

未安装、发布或提交；安装本地候选后重启，使 LSPosed 加载新代码。

本轮无 ADB 设备连接，没有当前 0.15.49 的新运行日志。已有归档最高为 0.15.47；其中旧版的一些等待发生在 imported 之前或 OCR/分词阶段，不能用于断言用户当前全部 4–5 秒都属于这次修正。浏览器全量候选扫描、分享目标名称加载和冻结应用解冻均检查过，但用户明确了拖动阶段，因此没有把这些其它路径混入本轮修改。
