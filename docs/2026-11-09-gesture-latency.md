# 长按识别与拖动响应优化

日期：2026-11-09（Asia/Shanghai）。本轮用户提供 `LSPosed_20261109_184005.zip`，要求优化长按识别、拖动后续动作的慢响应与停顿。附件只作诊断证据。

## 证据与范围

当前代码与日志模块均为 0.15.42 / 74，日志宿主为 5.1.5 / 50015。ZIP SHA-256：`97F8BE6BFE1DA2BC07D7F10F5652B846EA414F90A781CCD97B3AAF4508AC153F`。

`log/modules_2026-11-09T17_42_10.610382.log` 两次分享：

| 过程 | 时间 | 耗时 |
| --- | --- | --- |
| 2427 released → 2429 imported | 18:27:53.390 → 18:28:00.206 | 6.816 秒 |
| 2574 released → 2582 imported | 18:36:46.398 → 18:36:53.115 | 6.717 秒 |

上述停顿与 `HostLongPressDrag` 缺 URL 时的 6500ms 等待相符。`nativeProbeAvailable` 仅代表原生结果有几何坐标，原先却也能在补点开关关闭时触发等待；获取链接结束但没有 URL 时也没有完成通知，仍等到上限。日志不能确认这两次的开关状态，不能把开关关闭断言为这两次的唯一原因。

识别还有同步 Provider 查询：每次 `recognition_channels` 都构造完整 RuntimeSnapshot，重算规则身份；规则仓库每次构造和查询反复校验相同在线快照。旧实现回归中，1 次初始读取加 25 次轮询重建了 26 次快照。一次 `resolve` 同步耗时 170.826ms，足以造成可见卡帧；`pick_to_url` 的 3.315780 秒属于含异步宿主推荐在内的端到端耗时，不能视作主线程阻塞。

旧开机日志另有一次 8.15 秒 `snapshot_read`，附近系统有 CPU pressure；没有足够证据把它归为后续分享停顿。日志没有对应模块或宿主 ANR。

## 处理方式

1. 规则仓库在进程内复用不可变的解析/合并结果。每次比较实际的九个路由存储值，直接写入、类型损坏、禁用目标、撤回和版本下限变更即时失效；读取失败不返回旧规则。
2. RuntimeSnapshot 仅复用路由身份哈希，键为当前实际有效规则及 native/mode/cloud 三个路由设置；识别开关和其他配置仍逐请求读取。认证和宿主版本检查不缓存，不用 TTL 或延迟失效。未采用完整配置缓存，避免临时读取失败的降级值留在缓存中。
3. 相同或无效补充正文先返回，避免 40ms 轮询重复查询识别来源；新正文继续检查当前来源开关。
4. 复制、翻译使用已有正文，不等待卡片链接；补点关闭时有原生坐标也不等待。分享、规则、浏览器、传送门在补点进行时保留原等待窗口。
5. 原生与系统链接获取增加完成通知。成功先交付 URL；明确结束且无 URL 时停止剩余等待。通知绑定原服务/结果或系统手势实例，旧回调不能结束新手势。

保留用户设置的原生长按、识别和拖动阶段时长；不新增后台常驻、权限或依赖。未推送或远程发布。

## 验证

回归先在旧实现观察失败，然后实施修改。完整 Android 本地测试已通过：136 个套件、1410 项，失败/错误/跳过均为 0。Python 工具根目录 39 项、tools/tests 28 项均通过。针对日志宿主 5.1.5 / 50015 的 77 项通用静态成员检查通过。

全量首轮失败为 `DragChoiceActivityAndroidTest.batchGroupingReadsOneRuleSnapshotAndOneSwitchSetForAll64Links` 与 `LinkChoiceActivityAndroidTest.groupingReadsOneRuleSnapshotAndOneSwitchSetForAll64Links`：旧计数器观察 `getString(manual)`，新的仓库从 `getAll()` 读取。计数器改为完整快照次数，一次仓库构造状态/校验与有效规则读取共 3 次，两个仓库共 6 次；保留 64 条链接的界面分组、配置更新和行为断言。两项定向重跑通过，最终全量亦通过。

独立审阅修正了完成通知的所有者判断：系统补点结束不能提前结束已接手的原生补点；先在新回归中观察失败，再改为按当前所有者判断完成。未发现剩余阻塞项。

最终 `:app:testDebugUnitTest :app:lintRelease :app:assembleRelease` 返回 `BUILD SUCCESSFUL`，耗时 5 分 47 秒；临时 single-use Gradle 堆 1536 MiB、单 worker，没有修改项目 JVM 配置。Release lint 为 0 错误、37 警告。

## 本地优化候选

本次未递增版本，候选仍为 **0.15.42 / 74**，与现有正式版本分目录保存；未提交推送、打标签或远程发布。

| 项目 | 结果 |
| --- | --- |
| APK | `artifacts/optimization-20261109-gesture/TaplusBridge-0.15.42-release.apk` |
| 大小 | 3,578,881 字节 |
| SHA-256 | `48B4E7F63D67086BE723F7FD58F59B575484B7699E612D3AB5BF4D941A6DF6FC` |
| R8 映射 | 同目录 `r8-mapping.zip` |
| 映射归档 SHA-256 | `E3CE3EC64DCF9DCAB9995DC56B203847F64EABD88C5216463D6C328B82B30B61` |
| 长期证书 SHA-256 | `2f3c681d676d8f763b191dea0714c399e21bbaa0e948f25289ebfbcf41d1aabd` |
| 签名与对齐 | APK v2、ZIP 4 字节、原生库 ZIP 16 KiB 与四 ABI ELF PT_LOAD 16 KiB 均通过 |

最终 APK badging 确认版本 0.15.42 / 74；现有发布验证收集器通过本次候选 APK、Manifest、映射、作用域、规则种子、测试、lint、签名及对齐检查。同目录保存 `validation.json`、`source.patch`、完整构建/测试日志和验证证据。

设备侧响应尚需实测：本地测试不代表真实 Hook 回调、ROM 窗口交接、网络识别或实际帧率验收。
