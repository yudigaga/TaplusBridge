# 文档索引

当前正式版：**0.15.50 / 82**。返回[项目首页](../README.md)。

## 当前使用与发布

| 文档 | 内容 |
| --- | --- |
| [使用指南](usage.md) | 安装、作用域、配置保存、三段计时、来源名单、补点、浏览器、选字工具条、翻译、Lens 与恢复入口 |
| [0.15.50 更新说明](release-notes-0.15.50-release.md) | 相对上一远端正式版 0.15.43 的累计变化 |
| [0.15.50 构建与发布验证](2026-11-13-release-0.15.50.md) | 本版 APK、签名、对齐、R8、测试范围及发布证据 |
| [按需运行与空闲回收](2026-11-09-on-demand-runtime.md) | 当前本体按需启动、任务保护、空闲退出及图片分享授权的设计依据 |
| [传送门 5.1.5 适配报告](2026-11-01_apk-taplus-5.1.5-report.md) | 支持宿主的静态适配依据；不代表其他版本兼容或真实设备验收 |

## 本版技术记录（2026-11-13）

以下记录保留当时的本地候选身份、测试范围与诊断断点；后续正式发布状态以本版发布记录为准。设备日志中的某次成功、静态契约或本地回归均不能推导所有来源应用已兼容。

| 记录 | 对应变化及阅读提示 |
| --- | --- |
| [弹层长按与取链仲裁](2026-11-13-coolapk-links-dialog-fix.md) | 0.15.44：同源弹层准入、遮挡处理及原生／系统补点仲裁；保留点击已送达却未启动网页的限制 |
| [社交应用页面取链](2026-11-13-social-scenes-links-fix.md) | 0.15.45：命中路径上的有界父卡片 href、原生拖入 URLSpan；不猜标题或执行未知点击对象 |
| [默认浏览器、翻译图标与来源工具条](2026-11-13-edge-browser-toolbar.md) | 0.15.46：浏览器设置、图标、原生工具条及短行程触边修复；来源工具条需额外来源作用域 |
| [帖子取链与来源选字复测](2026-11-13-post-link-toolbar-diagnosis.md) | 已确认的来源作用域缺失与未闭合的取链断点；本条为诊断，未生成新版本 |
| [识别后关闭本轮新菜单](2026-11-13-recognition-popup-dismissal.md) | 0.15.47 的实现及复测记录；后续修正见下一条 |
| [菜单回调与识别任务保留](2026-11-13-popup-echo-retention.md) | 0.15.48：Overlay 优先级与一次控制回声处理；不改变三段时间，不宣称所有自绘菜单可关闭 |
| [AES／HyperPortal 对照优化](2026-11-13_reverse-AES-HyperPortal-optimization-report.md) | 0.15.49 沿用的叶节点描述、后台分享候选及图片缓存年龄修复；AES 版本不等于宿主版本 |
| [识别完成后的动作等待](2026-11-13-recognition-action-latency.md) | 0.15.50：已有有效链接时减少在线附加推荐等待；保留 OCR、分词与无链接卡片的必要等待 |

技术记录中出现的本地证据路径、构建归档和原始日志不等于可下载的公开附件。公开 APK 以仓库 Releases 为准。

## 历史版本与专题

历史文档用于追溯当时的实现、问题与验证，**当前操作以使用指南为准**。旧版双击识别、旧手势等待方式、冻结本体继续运行等实验已被后续行为取代；不要将早期计划或某次候选测试当作当前功能承诺。

| 专题 | 记录 |
| --- | --- |
| 上一远端正式版 | [0.15.43 更新说明](release-notes-0.15.43-release.md) · [0.15.43 构建](2026-11-09-release-0.15.43.md) · [长按／拖动响应优化](2026-11-09-gesture-latency.md) |
| 按需运行演进 | [0.15.41 构建](2026-11-09-release-0.15.41.md) · [0.15.42 构建](2026-11-09-release-0.15.42.md) · [早期可靠性与性能](2026-11-07-reliability-performance.md) |
| 规则与批量链接 | [剪贴板多链接 0.15.38](2026-11-06-release-0.15.38-clipboard-batch.md) · [115 批量直达 0.15.39](2026-11-06-release-0.15.39-direct-batch.md) · [云盘链接 0.15.37](2026-11-06-release-0.15.37-cloud-links.md) |
| 翻译 | [0.15.35 实现](2026-11-06-release-0.15.35-translation.md) · [翻译详细设置](2026-11-06-translation-usage.md)，其中冻结本体的历史说明已被按需运行取代 |
| 设置与通道 | [0.15.34 设置整理](2026-11-06-release-0.15.34-settings-organization.md) · [当时的设置归属表](2026-11-06-settings-map.md) · [0.15.32 识别通道](2026-11-06-release-0.15.32-recognition-channels.md) |
| 拖动与内容采集 | [0.15.0 全局拖拽](2026-11-03_global-drag-0.15.0-report.md) · [0.15.1 Android 16](2026-11-03_drag-android16-0.15.1-report.md) · [0.15.2 方向手势](2026-11-03_drag-native-gesture-0.15.2-report.md) · [0.15.29 系统来源采集](2026-11-05-release-0.15.29-scope-only-recognition.md) |
| 手势交接诊断 | [0.15.21 遮罩移交](2026-11-05-release-0.15.21-report.md) · [0.15.24 可触摸布局](2026-11-05-release-0.15.24-touchable-layout-handoff.md) · [0.15.27 覆盖范围](2026-11-05-release-0.15.27-drag-coverage.md) |
| Lens 会话与结果页 | [原生 Lens 分析](2026-11-02_lens-native-report.md) · [0.14.2 会话复用](2026-11-02_lens-session-reuse-0.14.2-report.md) · [0.14.3 同会话模式](2026-11-02_lens-live-modes-0.14.3-report.md) · [0.14.6 页面控件](2026-11-02_lens-exact-controls-0.14.6-report.md) |
| 来源范围、截图与补点 | [0.9.0 来源名单](2026-11-01_source-scope-report.md) · [0.9.1 诊断](2026-11-01_source-scope-0.9.1-report.md) · [0.10.0 截图兜底](2026-11-01_scope-capture-0.10.0-report.md) · [0.11.0 补点取链](2026-11-02_scope-link-probe-0.11.0-report.md) |
| 打开与目标解冻 | [通用链接打开](2026-10-30-generic-link-open-0.6.0.md) · [Root／Shizuku](2026-10-30-root-shizuku-probe-0.5.0.md) · [规则持久化](2026-10-30-rule-routing-persistence-0.7.0.md) |
| 初期适配 | [传送门适配分析](2026-10-28-taplus-adapter-report.md) · [ContentCatcher 分析](2026-10-28-contentcatcher-analysis.md) · [早期系统采集](2026-10-29-system-capture-observer.md) |

其他按日期命名的记录保留原路径，可在本目录按文件名查阅。`superpowers/plans/`、`superpowers/specs/` 为历史开发过程资料；它们及本地 `.local/` 记录不作为当前公开使用入口。
