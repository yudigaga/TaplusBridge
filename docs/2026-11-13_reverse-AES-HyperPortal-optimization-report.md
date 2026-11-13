# AES / HyperPortal 更新对照与 TaplusBridge 优化

分析日期：2026-11-13（Asia/Shanghai）。类型：普通 APK 静态逆向与本地实现对照，flavor = null。

本轮借鉴 AES 的“正文为空时读取内容描述”，补齐 Assist 对部分语义叶节点的取数；借鉴 HyperPortal 的后台目标构建，减少自定义分享菜单的主线程工作。对照图片分享生命周期时，还发现并修正了文件缓存年龄早于分享授权起点的问题。两包采用的来源进程取数和 SystemUI 文件托管与本项目架构不同，不能整体替换后就获得相同能力。实机识别成功率、ROM 窗口行为与耗时变化没有在本轮验证。

## 样本、范围与输出

授权来自本次用户要求：反编译工作区的两个更新 APK，与当前项目比较并优化。本轮读取并修改当前 TaplusBridge 工作区，保留原有未提交工作；分析样本不安装、不执行。仅为补齐反编译工具从官方 release 获取独立 JADX，无样本业务网络请求。详细范围保存在本地证据路径 `analysis/comparison-20261113/AES/scope.md` 与 `analysis/comparison-20261113/HyperPortal/scope.md`；本节的 analysis 路径相对工作区父目录，不属于公开仓库。

| 样本 | 包名 / 版本 | SHA-256 |
| --- | --- | --- |
| AES-patched.apk，751,277 字节 | com.miui.contentcatcher，5.1.7 / 1000000048 | B77E814384F51053C6FB237993EA3FF4005A51BC23968736A61705C011AD62EE |
| HyperPortal.apk，7,401,620 字节 | com.github.leaf.hyperportal，0.1.4 / 10 | 237A575BC224FF66CB4269CA9BD0D41B3CD64028BC76E8E7CFA99C681D88644A |

新输出位于工作区 `analysis/comparison-20261113/`，没有覆盖旧目录。每包都保留 `jadx/sources`、`apktool`、版本信息和单包报告。全局 JADX 启动脚本所指 jar 缺失，本轮单独使用该目录下的 JADX 1.5.5；Apktool 为 3.0.2。

AES 是来源端系统采集组件，**5.1.7 不是传送门宿主版本**。本项目支持的传送门宿主仍为 `com.miui.contentextension` 4.1.3 / 40013 和 5.1.5 / 50015。新版 AES 的真实 DEX 通过现有采集组件 9 项历史 ABI 检查，验证 JSON 保存在本地 `analysis/comparison-20261113/AES/verified-contentcatcher.json`；这不代表宿主完整适配或设备成功。

## 两包的处理方式与取舍

| 项目 | 对照样本 | TaplusBridge 的处理 |
| --- | --- | --- |
| 只有语义描述的控件 | AES `Bridge.collectAccessibility` 先取 text，空时读 contentDescription | 采纳字段回退，在现有命中、可见、隐私和遍历限制后读取无正文叶节点描述，不读取 hint，父容器描述不用于聚合 |
| 分享候选构建 | HyperPortal 的 d91/c91 用 executor 构建后回主线程 | 采纳同类调度：自定义分享的 PackageManager 查询及名称排序交给已有 IO，每项名称只读一次 |
| 图片交付 | HyperPortal 使用 SystemUI 内 provider 和分享代理 | 保留模块自己的随机 URI、只读授权、8 项容量和 10 分钟 TTL；修复图片 mtime 与授权起点不一致 |
| 深入来源内容 | AES 在真实来源进程反射 View/Compose、读取 WebView DOM 和特定应用字段 | 系统 AssistStructure 与宿主回调不持有这些真实对象；本轮不引入来源进程注入和私有字段适配 |
| OCR | HyperPortal 接入 AICR V2 私有 Binder，并区分服务/模型/取消/无文字等状态 | 保留现有宿主 OCR、Lens 与识别通道；AICR 所需固件服务、权限和混淆入口需独立设备证据 |
| 系统兼容 | HyperPortal 包含 AppsFilter、URI grant、Zygote/SELinuxMMAC 等私有 Hook | 这些安装逻辑不等于所有固件运行成功；当前项目没有必要为本轮优化新增这些依赖 |
| 异步有效性 | AES 对配置刷新合并、失败退避和手势 generation 有保护 | 现有 SystemAssistContent 已有 2 秒时限、受限后台读取和物理 DOWN 绑定，继续保留 |

源码与 smali 位置详见本地单包报告 `analysis/comparison-20261113/AES/2026-11-13_reverse-AES-patched-report.md` 和 `analysis/comparison-20261113/HyperPortal/2026-11-13_reverse-HyperPortal-report.md`。本轮实现依据行为重新编写，没有搬入第三方源码。

## 本轮修改与行为边界

Assist 的 `Node` 增加可选描述读取，实际适配器从 `AssistStructure.ViewNode.getContentDescription()` 取得数据。已有正文时保持正文优先；描述回退只用于非根、可见命中面积不超过窗口一半的叶节点，避免无子节点的全屏语义摘要出现在空白触点。描述中的真实 URL 继续交给既有链接解析。窗口归属、触点、遮挡、密码/编辑/assistBlocked、变换矩阵和 8 窗口 / 512 节点 / 32 层限制继续生效。若来源不提供描述和正文，这一补充仍无法还原其内容。

图片分享原先按“授予时刻 + 10 分钟”发 URI，但图片导入清理按更早的文件修改时间回收。经过内容选择或识别等待后分享的图片，可能被下一轮导入提前删除。现在在成功授予前刷新文件 mtime，并使用同一个 wall 时间写入授权元数据；更新时间失败则返回读取错误。未扩展重复授予同文件的语义，生产分享入口仍只移交一次图片所有权。

自定义分享目标查询、去重、名称读取和排序由后台完成，回到主线程后核对页面代次与销毁状态。默认系统分享 chooser 保持原有直接入口。名称在后台缓存到本次候选结果，不做跨页面长期缓存。图片 URI 的暴露仍在原有位置，本轮没有宣称消除所有主线程文件操作。异步等待期间，已销毁、正在关闭或已移交图片所有权的页面拒绝旧内容选择回调，避免再次消费空 payload。

此外检查过图片流自身的 10 秒截止：它在 `read()` 返回后检查时间，不能独立中断阻塞流；但 DragActivity 已有 12 秒取消与描述符关闭保护。本轮没有为此重复增加超时基础设施，也不把前置 Provider Binder 阻塞写成已解决。

## Evidence → Finding → Path

| Evidence | source_ref / 复现 | content_hash |
| --- | --- | --- |
| E-01：AES 身份及采集 ABI | `analysis/comparison-20261113/AES/verified-contentcatcher.json`；同目录 `verify-current.py` | 上表 AES 样本 SHA-256 |
| E-02：描述回退 | AES `jadx/sources/dev/dragshare/catcher/Bridge.java:6984`；`apktool/smali_classes2/dev/dragshare/catcher/Bridge.smali:5401、5413` | 同上 |
| E-03：后台候选构建 | HyperPortal `apktool/smali/d91.smali:8660`，`c91.smali:49、127`；单包 metadata.json 保留关键文件散列 | 上表 HyperPortal 样本 SHA-256 |
| E-04：项目缺口与修复 | `AssistContentExtractorAndroidTest`、`DragHandoffAndroidTest`、`DragShareActivityAndroidTest`；红绿日志位于本次 analysis 目录 | 工作区为可修改源码，完成后归档散列 |

| Finding | severity | evidence_ids | confidence | location | status |
| --- | --- | --- | --- | --- | --- |
| F-01：仅有描述的叶节点被遗漏 | n/a_re | E-02、E-04 | high | AssistContentExtractor | 已实现本地回退 |
| F-02：自定义分享枚举阻塞 UI 且反复取名称 | n/a_re | E-03、E-04 | high | DragActivity | 后台构建并保留晚回调校验 |
| F-03：缓存年龄早于分享授权，可能提前删图 | n/a_re | E-04 | high | DragShareProvider、DragPayload.deleteExpired | 授予时同步 mtime 起点 |

P-01（callflow）：已验证来源触点 → 后台 AssistStructure → 窗口/遮挡/隐私检查 → 正文或合格叶节点描述 → 既有 Result / URL 解析（E-02、E-04，F-01）。

P-02（callflow）：分享动作 → 图片授予时同步文件时间 → 默认 chooser，或后台枚举自定义候选 → 主线程检查代次 → 展示并启动目标（E-03、E-04，F-02、F-03）。

## 验证记录与限制

最初定向回归 50 项出现 3 项预期失败：描述字段没有适配、描述节点结果为空、新授权图片被清理。实现前两项后，同组 50 项通过，日志为 `regression-red.log` / `regression-green.log`。审阅后新增全屏描述回归，先观察到预期错误，再收紧边界。自定义分享的后台查询/一次名称读取、默认 chooser 与销毁回调通过定向检查；另在真实待完成分享任务中模拟图片已移交的状态，旧内容回调明确复现 `payload.select` 空指针（`share-callback-red.log`），随后加入入口校验。重复提交的完整图片 UI 夹具未成功稳定建立，本报告以静态调用链和回调边界回归为依据，不宣称已完成该整条设备/界面复现。

最终全量通过：151 个套件、1,627 项 Android 本地单元测试，失败、错误、跳过均为 0。Python 工具检查通过：根 tools 39 项，tools/tests 34 项。命令 `:app:testDebugUnitTest :app:lintRelease :app:assembleRelease --max-workers=1 --console=plain` 退出 0，耗时 4 分 54 秒；R8 已执行。Release lint 为 0 错误、33 警告，保留完整报告，没有将其描述为零警告。独立审阅提出的两项边界均已修正，最终无剩余阻塞项。

## 本地候选与归档

| 项目 | 结果 |
| --- | --- |
| APK | `artifacts/optimization-20261113-comparison/TaplusBridge-0.15.48-release.apk` |
| 版本 | 0.15.48 / 80，与原本地候选同版本，单独目录标识本轮优化 |
| 大小 | 3,628,033 字节 |
| SHA-256 | B7440DC7B86F675A5851E444D1CA141563F61970E458BAF303FDFDC0BF7BFFDA |
| 签名 | APK v2 通过，长期证书 SHA-256 `2f3c681d676d8f763b191dea0714c399e21bbaa0e948f25289ebfbcf41d1aabd`，与此前正式包记录一致 |
| 对齐 | ZIP 4 字节、native ZIP 16 KiB、四 ABI ELF PT_LOAD 16 KiB 均通过 |
| 模块作用域 | com.miui.contentextension、system |
| R8 | 同目录 `r8-mapping.zip`；映射包含新增 DragActivity$ShareTarget 及后台分享方法 |

候选目录保存 `validation.json`、JUnit XML、完整构建与 lint 输出、签名、badging、对齐及红灯回归日志。工作区初始哈希快照与本轮修改记录用于区分已有工作；本轮只改动三个产品源码文件和必要测试，没有提交、推送、发布或安装。安装本地候选后需重启，使 LSPosed 使用新代码。

## 静态与设备验证边界

JADX 对 AES 的 Bridge 仍有 8 处未完成方法；HyperPortal 导出 3,042 个 Java 文件，19 处反编译错误。关键结论用 smali 交叉验证，伪代码中的占位异常不作为实际行为证据。native 分词内部算法未深入分析。没有在设备上安装、替换系统 APK、测试真实触摸、SystemUI、AICR、Root/Shizuku、网络服务或分享接收；本地测试不能代替这些验证。

Timeline：独立目录解包并记录哈希 → 补齐本次 JADX → Java/smali 核对与新版采集 ABI 检查 → 当前工作区缺口回归红灯 → 小范围实现 → 定向绿灯与工具检查 → 完整验证及审阅归档。

## 后续版本递增交付

按用户后续要求，版本由 0.15.48 / 80 递增为 **0.15.49 / 81**，已重新执行 `:app:lintRelease :app:assembleRelease --max-workers=1 --console=plain`，退出 0，耗时 2 分 24 秒。Release lint 为 0 错误、33 警告，R8、APK v2 长期签名和 ZIP 对齐验证通过。本次只同步版本配置与 README，不重复运行上一轮已通过的功能测试；上述 1,627 项及 73 项测试记录属于前一轮优化验证。

新版交付到工作区主目录：`E:/vs/claude/传送门/TaplusBridge-0.15.49-release.apk`。SHA-256 为 `8B1CBD006E4AD5E6F2644136A8E4AE68B90DCF95EE3DBF8686B3902A51E75784`。新版本的构建、签名、badging、对齐与映射归档单独保存在 `artifacts/release-0.15.49/`，原 0.15.48 证据保留。
