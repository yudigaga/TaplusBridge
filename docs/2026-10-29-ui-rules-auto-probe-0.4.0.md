# 0.4.0：Miuix 页面、IceBridge 规则与自动静默识别

日期：2026-10-29（Asia/Shanghai）。本次是本地开发候选，flavor=null。范围与授权见 [scope.md](scope.md)。

## 结果与升级

用户要求去除旧传送门规则，完整采用 IceBridge 规则，整理 Miuix 页面，并取消长按后的“无感识别此卡片”人工步骤。本版将这些改动合并为 0.4.0-dev / versionCode 9。

删除旧 `config.rules` 与 `seed_version`，保留功能开关和解冻方式；以后规则由 `icebridge_rules_v1` 管理。旧 RouteDefaults、RouteMatcher、DeepLinks 与旧 View 页面助手不再保留。首次离线基线是 IceBridge 自有 revision 1 原始快照，32 条候选，SHA-256 为 `e840f50919b022b085621c34394c7d09fde1373e22467e490bbb8c8633a05203`。

`-dev` 没有识别数量限制，也没有 DEBUG 限制分支。此前无法继续识别的已确认本地原因包括全来源约 36 秒保护、旧任务 busy 状态及事件代次隔离。新版移除全来源冷却，新识别可以替换旧请求；终态、失败和取消不再要求用户进应用清理。

传送门原有取消流程没有取消全部分词、推荐任务或 OCR observer。新版把实际 AsyncTask 与 OCR observer 对象绑定到启动 epoch，真实 `onPostExecute`、`onNext`、`onError` 回调先核验归属。边界 reset 先使旧 epoch 失效，再 cancel/dispose 并清理分词忙标志；已经排入主线程的旧结果仍被拒绝。当前长按中不接受未知对象或背景回调完成本地屏障，避免 text/image 快速切换时把旧结果当成新卡片结果。

真机连续识别、来源网页是否零闪现、实际内容落点与 Root/冰箱解冻尚未验收。来源未给出的 URL、其他启动 API 或原 Activity 内导航，不能靠版本名或更多规则保证解决。

## 页面与规则

使用真实 Compose Miuix 0.9.4，首页、规则、更多三页；跟随系统明暗主题。首页集中识别开关和解冻方式；规则页以目标应用分组，提供搜索、已安装过滤、目标开关、手动编辑、来源选择、匹配预览、JSON 导入导出和在线管理。Root 检查仅在用户点击时发起。

规则与本地 IceBridge `3ac3baa6273cb084c2563bd34d10d1f54e5da8ee` 对齐：manual 优先、精确来源优先、更长域名优先。先筛选启用且已安装的目标，再判断冲突与选择规则；禁用或隐藏但仍已安装的应用可参与解冻。最高优先级存在不同目标时不自动猜测。B站官方版与 PiliPlus 可用目标冲突需要通过组开关或手动规则解决。

预览和真实启动共用 `bridge/IceRuleRouting`。固定转换来自 IceBridge 的审核目录，在线 `none` 使用 HTTP 原地址限定目标包；没有恢复旧传送门专属 B站/抖音/知乎转换。手动文件兼容 IceBridge v1；拒绝重复键、未知动作字段、无效结构和超限输入，不悄悄截断。

在线源固定为 IceBridge 自有 GitHub 与镜像，默认不主动联网。用户允许更新并点击同步后下载完整快照，验证 SHA-256、严格 UTF-8、结构和单调版本；同版本不同内容拒绝，保留已验证备份，接受空撤回后不复活离线基线。关闭更新保留已有有效缓存。提交失败明确返回失败并恢复普通内存配置，同时保留达到的版本/撤回保护；磁盘写入和恢复未确认时不声称已经安全落盘。

## 自动静默探测

只有来源与原卡片几何可靠、当前识别未得到可用 URL、相关开关开启时才安排探测。本地 segment 结果或空 OCR 同步终点确认本地完成；非空推荐任务读取宿主 `sLastRecommendationTask`，仅在 `AsyncTask.Status.FINISHED` 后解除网络屏障，失败而没有 detail 回调也能完成。最多观察 15 秒，超时拒绝探测，不把超时当作结果完成或手势抬起。已有全文、OCR 或服务端链接优先走普通识别，不需要合成点击；READY 前和 TAP 前晚到链接会撤销探测资格。每轮用宿主 epoch 与模块 nonce 标识；前一轮迟到回调不进入新轮结果。

系统侧 `PointerEventDispatcher.onInputEvent` 只跟踪默认屏输入生命周期，不保存坐标或正文。协议 2 的 READY 必须带真实 UP 证明；CANCEL 与未知状态不能授权。注入的 deviceId/downTime 在输入前登记，涵盖 Android 分发器重写的虚拟设备 ID，避免自己的异步 DOWN/CANCEL 被当作新物理手势而卡住下一轮。

系统在 READY、TAP 检查和 DOWN 注入前重复核验无活动物理触摸、来源 UID/用户/Activity/屏幕/窗口与新鲜坐标。保留受限菜单外 DOWN/CANCEL 和最多一次卡片 DOWN/UP；不对未知窗口盲点。已核对的公共 `ATMS.startActivityAsUser` 在原方法体之前消费本次启动，不显示原网页后再隐藏。

捕获到唯一有效网址后，内存回传传送门并产生目标应用名称和图标卡片。**识别本身不自动打开目标；用户点击新卡片后才解冻/打开。** 探测票据只有 HTTP 规则路径，没有原浏览器或旧原生桌面回退。

新 generation 立即替换旧 generation，结束后普通来源启动立即放行；去掉全来源 36 秒 tombstone。近期网址只保存最多 16 个短期 SHA-256 指纹，不落盘，不把相同网址在新的点击轮次误判为永久重复。任意未知异步启动若恰好进入新点击窗口仍无法绝对归因，其他启动 API 和原 Activity 内导航未证明覆盖。

## 证据与验证边界

| 证据 | 来源与复现 | 结论 |
|---|---|---|
| E-001 | `app/src/main/resources/META-INF/xposed/{scope.list,module.prop}` 与最终 APK 内同名条目 | 作用域仍是传送门与 system，API 102，不 Hook 微信/QQ |
| E-002 | `artifacts/junit-0.4.0/TEST-*.xml`；运行下方 Gradle 命令 | 覆盖规则迁移/优先级/安装投影/快照撤回/保存失败、触摸释放、连续探测、旧回调和实际 UI 交互；精确总数见验证 JSON |
| E-003 | `artifacts/ui-0.4.0/*.png`；MiuixSettingsScreenTest | 实际 Activity 与 Miuix 绘制，390dp 浅色和 320dp 深色 1.5 倍字体，包含规则编辑、预览和帮助；不是手机截图 |
| E-004 | `artifacts/host-contract-0.4.0.json`；verify_host.py | 传送门 4.1.3 的 58 个静态 DEX 成员契约；不证明运行时 Hook 回调 |
| E-005 | `artifacts/validation-0.4.0.json`、签名/对齐记录、SHA256SUMS | 最终 APK 版本、哈希、证书、对齐和完整本地检查结果；未安装到设备 |
| E-006 | `artifacts/python-tests-0.4.0.txt` | 14 项归档诊断工具测试通过，不读取聊天正文或完整 URL |

Finding → Path：E-002 支持旧会话阻塞和重复处理回归在本地测试中已修复，交付候选进行连续卡片实测；E-003 支持 Miuix 页面与交互已落地；E-001/E-004 仅支持静态适配，不能推出“真机所有卡片可识别”。实际设备结果到达前，保留源窗口、未知启动 API、无 URL 与多目标冲突这些明确边界。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --console=plain --max-workers=1 '-Dorg.gradle.jvmargs=-Xms32m -Xmx1024m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
python .\tools\verify_host.py '..\传送门_4.1.3.apk' --output .\artifacts\host-contract-0.4.0.json
python -m unittest discover -s tools -p 'test_*.py'
```

APK 仍由原 Android Debug 证书签名，非长期密钥正式版；未远程发布、未改 IceBridge、未运行真机 Hook、实际注入、服务响应或 Root/冰箱验收。

## 最终候选记录

- 安装包：`artifacts/TaplusBridge-0.4.0-dev.apk`，versionName `0.4.0-dev`，versionCode `9`，28,995,086 字节。
- SHA-256：`45270F349AFC737BEEE5587688F9E18E0B663A129A6DC07F8B212BD3C43AF26D`。
- 42 个本地测试套件共 376 项，0 失败、0 错误、0 跳过；另有 14 项 Python 诊断工具测试通过。
- lint：0 错误、17 警告，涉及同步配置写入、目标 SDK、系统内部反射、导出 Provider、依赖版本及本地化建议；Provider 仍按 UID/用户/受信宿主鉴权，未为消除警告扩大权限或改目标 SDK。
- v2 签名通过，沿用证书 SHA-256 `4e06d06e242802a8b8e77f696079ddd78e3d615d0cf73b781f3a187abb51f980`；ZIP 4 字节与原生库 16 KiB 对齐通过，四种 ABI 的 `libandroidx.graphics.path.so` ELF LOAD 段均为 16 KiB 对齐。
- 已从最终 APK 复核 Miuix 类、API 102 元数据、传送门/system 作用域、离线基线校验和、不打包 libxposed/旧 Xposed API 类。7 张实际 Miuix 本地渲染图已检查。
- `adb devices -l` 无连接设备。没有设备安装、系统 Hook 实测、真实服务器响应、Root/冰箱解冻或零闪页验证。

完整机器可读记录：`artifacts/validation-0.4.0.json`。
