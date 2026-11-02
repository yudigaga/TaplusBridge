# 传送门链接识别与跳转显示增强 0.1.1

日期：2026-10-28。范围：[scope.md](scope.md)。用户新增要求：增强分享卡片、折叠文字中的链接识别，显示真实目标应用，取消正常流转中的可见中转页面；并明确禁止 Hook 微信。模块作用域始终只有 `com.miui.contentextension`。

## 已确认与实现

传送门 `onPickText()` 会把 CharSequence 转成 String，也会读取 Bundle 中的 `viewContent` 或 `apkParamList` 指向的 `miuiCE_*` 字段。这个边界允许模块先检查完整文本和 URLSpan，从系统实际回传的数据中保留可能丢失的 URL。模块还检查 `image_pick` 的非图片元数据及 propertyMap；不会把 `imgUrl`、`thumburl`、图标等图片下载地址当作文章落地页。

本地解析支持普通 HTTP(S)、`www`、HTML href、卡片 XML 的直接 appmsg/url、CDATA 和一次实体解码。解析有长度、节点数、深度和数量限制，不访问网络、数据库或来源应用对象。既不自动展开「更多」，也不从标题猜造原始 URL。

本地链接只在能按现行规则确认目标应用时生成推荐卡片。通过宿主 `RecommendationMonitor.onDetailReady()` 合并，保留原有服务器卡片；文本和 OCR 的分词回调可在服务器没有返回结果时触发本地补充。包括离线 OCR 的 `ImageRecognition.processOcrResult(Context,String)`。会话结束与新触发时清理临时链接，排队发布带会话代数，过期任务不覆盖新会话。

显示在共同的 `TaplusRecommendBaseCard` 绑定处修改，紧凑/展开卡片与剪贴板 URL 一致显示「在〈应用名〉打开」。应用名称与 64×64 图标来自模块进程对实际规划目标的 PackageManager 查询。复用卡片先清理图标覆盖；宿主晚到的浏览器图标任务不能覆盖已确认的目标图标。

点击时重新匹配规则。启用且可处理链接的目标通过 Provider 返回 direct Intent，由传送门直接启动。冻结目标继续由前台入口完成 Root/冰箱解冻，但入口为透明主题、无内容布局、无启动预览和动画；等待/成功不创建页面、进度条或弹窗。只有错误、超时才显示取消/原始打开对话框。

## 没有发现的“隐藏开关”

`assets/config.json` 的微信条目只设置 Activity、触发方式与屏幕区域。`ContentExtensionConfig` 处理的是 normal/forbidden 展示策略。`ContentCatcherUtil.updateClientConfig()` 只见 `text_pick`/`image_pick` 开关。清单的 `not_fetch_textview` 已经是 false，实际系统 ContentCatcher 实现在此 APK 外，未找到已验证的配置可令它读取微信折叠消息或原始卡片模型。

因此，若系统仅向传送门传入标题、截断后的文字或截图，模块无法从这些缺失的信息中可靠恢复网址。用户两张截图没有显示 URL，不能作为原始链接样本。没有手机、ContentCatcher 回传样本或真实 Hook 日志，本报告不宣称这两条具体消息已修复。

## Evidence → Finding → Path

宿主源码位置相对于父目录 `传送门_4.1.3_反编译结果/sources/com/miui/contentextension/`。

| E-id | source_ref | repro_command | content_hash |
|---|---|---|---|
| E-capture | `services/TextContentExtensionService.java:377` | 搜索 `getParams`、`viewContent`、`apkParamList`、`toString` | 宿主 `20FCE3E54932711177EB449F60586113D07F287371FD47D629CA5B59A984A93E` |
| E-config | `utils/ContentCatcherUtil.java:133`；`data/common/ContentExtensionConfig.java:385`；`resources/assets/config.json` | 搜索 `updateClientConfig`、`validArea` | 同上 |
| E-caption | `text/cardview/TaplusRecommendBaseCard.java` | 搜索 `getNerContent`、`icon_browser`、`bindRecommendInfo` | 同上 |
| E-defs | `artifacts/host-contract-0.1.1.json` | `python tools/verify_host.py ../传送门_4.1.3.apk` | 同上，38 项定义/标记 |
| E-tests | `artifacts/junit-0.1.1/` | `gradlew.bat :app:testDebugUnitTest` | n/a，本地生成报告 |
| E-build | `artifacts/validation-0.1.1.json` | `gradlew.bat :app:lintDebug :app:assembleDebug`，apksigner，zipalign | 验证记录中的候选 APK SHA-256 |

| F-id | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F-rich | n/a_re | E-capture,E-defs,E-tests | high | `HostRecognitionEnhancer` | 本地全文/跨度/卡片字段提取已测试，真实回传待验证 |
| F-label | n/a_re | E-caption,E-tests | high | 卡片共同基类绑定与应用图标 | Android 控件文案及图标像素测试通过 |
| F-direct | n/a_re | E-tests,E-build | high | Provider direct + 透明 TransitTheme | 生命周期与无内容窗口测试通过，真实 ROM 待验证 |
| F-limit | n/a_re | E-config,E-capture | high | 系统 ContentCatcher 边界 | 没有足够证据保证恢复系统未传入的隐藏 URL |

P-1，`path_type=callflow`：系统 ContentCatcher 回传 → 模块读取全文/跨度/结构化字符串 → 本地解析 URL → 规则解析实际应用 → 本地卡片合并 → 真实名称和图标 → 点击后重新规划 → 直接打开或透明解冻后打开。

## 验证范围

完整版本化结果见 `artifacts/validation-0.1.1.json`：105 项 JVM/Robolectric 测试通过，0 失败/错误/跳过；lint 0 错误、12 警告；38 项真实宿主 DEX 定义通过。测试使用 Android SDK 34/35 模拟环境；API 102 依赖和宿主契约为静态检查。APK 为 `0.1.1-dev / 2`、217680 字节，Debug v2 签名及 ZIP 对齐通过，模块无原生库。SHA-256 为 `3B58E082A729188778F2A088597905A82B1691CFFFB3A8FE6CEE6993A5F988BA`。没有连接手机，未安装、未执行 LSPosed Hook、Root/冰箱解冻、真实目标深链，也没有复现截图中的具体消息。

新增测试覆盖 XML 卡片直接 URL 优先于缩略图、折叠全文、标题不能造 URL、URLSpan 显示文本之外的目的地、实际目标标签/图标、复用卡片清理、无内容透明入口、取消/旋转/一次性启动。模块不新增网络请求，不新增来源应用作用域，只持久化链接数量诊断，不保存聊天正文和 URL。

建议手机验证：覆盖升级 0.1.1，LSPosed 只勾选传送门并重启宿主；重新长按两条消息；查看模块的“最近一次本地提取”数量。若为 0，只能说明当前提取路径未找到链接，不能据此归因于微信、OCR 或服务端。还需结合实际 ContentCatcher 字段或无内容诊断进一步定位。

Timeline：2026-10-28 接收截图与无页面要求 → 用户明确限定仅传送门 → 排查宿主配置/原始数据边界 → 实现与测试 → 本地候选构建。未修改 IceBridge 或宿主 APK，未远程发布。
