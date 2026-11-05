# 0.15.0 全局拖拽适配记录

日期：2026-11-03（Asia/Shanghai）。版本：0.15.0／32。范围见 [scope.md](scope.md)。本地候选需要真机验收，未发布到远程，也未安装设备。

## 使用与实际边界

在模块首页进入「拖拽操作」，启用并保存，提前设置四个方向的动作。功能默认关闭，默认操作为上方模块能力菜单、下方分享、左方识图、右方翻译。**所有方向先完整识别，再弹窗只选择内容，最后执行该方向的固定操作**。浏览器和规则动作选择一个真实网址，分享和传送门可选择原文、识别文字、图片、链接或全部内容；不重新选择操作。

完整识别保留来源给出的文字、HTML／Intent 中的实际 HTTP(S) 链接和当前图片，并调用宿主原生图片文字识别。原文与 OCR 结果分开，空 OCR 结果仍可选择已有图片或文字。识图按钮最终确认后才向 Google Lens 上传图片，识别汇总阶段不调用 Google Lens。

来源应用必须自己支持 Android 全局拖拽；只有普通长按菜单的控件不会因此自动支持拖出。系统接入限 Android 13+、主用户、主显示器和可验证的系统拖拽接口；模块应用需要保持可用。未知 ROM 合同不强行适配。未传出的卡片隐藏网址不能由标题推断，本次不在松手后自动点击原卡片，原「长按取链接」另行保留。

分享目标可以选择具体组件并调整顺序，也可以显示全部可用目标。当前内容类型不受目标支持时隐藏该目标；仅选目标为空时提示配置，不回退到全部。图片最多 8 MiB，规范为长边不超过 2000、总像素不超过 300 万的 PNG；分享的也是该规范图。临时图片分享 URI 只读，最多保留十分钟，不复用来源原始 URI。

## 实现

1. `SystemDragHooks` 只观察成功的 `DragDropController.performDrag`，验证原始 token、UID、主显示器、锁屏状态、来源名单和配置后启动非导出的 `DragActivity`；系统启动 Intent 只带来源和时序，不带正文、图片或网址。
2. `DragActivity` 接收真实 `DragEvent`，在 drop 时申请标准 `requestDragAndDropPermissions`，有界复制后释放权限。拖到中央、无动作方向、返回、退出或旋转均取消。
3. `DragActionStore` 保留最新一轮，首次宿主读取只能成功一次，识别完成换成新的单次选择 token，最长六十秒。正文和图片仅在内存／临时缓存保存，不进入规则配置或日志。
4. 模块通过已认证的 Runtime 通道请系统启动有签名权限保护的宿主服务；宿主通过 `BridgeProvider` 认证 UID、用户和版本后取件。`DragHostPayload` 使用独立载荷适配器，不放宽原截图／取链的 URL 文本校验。
5. 宿主仍通过 `mCallback.onContentReceived` 创建本轮任务。图片走原生 OCR，全选分词后从适配器取得正文，避免 `||` 分隔符以及默认未选导致空文字。原生窗口关闭后才弹选择窗；后续识图／翻译按宿主真实小卡展开链路显示完整面板。
6. 选择内容后执行原方向动作。规则打开复用 `LaunchPlanner`、来源规则和既有解冻渠道；浏览器打开限定网页浏览器；宿主最终操作使用执行模式，避免再次进入识别／选择循环。
7. `DragGestureGate` 在确认系统拖拽后停止同轮截图和取链，并阻止排队的模拟点击。结束保留最多一秒尾期，新的真实 DOWN 立即恢复普通长按，最长六十秒自动失效。旧 completion 在新识别会话开始后失效，不关闭新长按面板。

同时修复既有 `LensEmbeddedBrowser` 的移除回调重入和 API 28/29 分支调用 API 30 `release()` 的问题；两条旧导航测试改为断言当前实际执行的控件隐藏脚本。未修改 Lens 网络、模式和会话逻辑。

## Evidence → Finding → Path

| Evidence | source_ref | repro_command | content_hash |
|---|---|---|---|
| E-doc | 小米《全局拖拽功能技术适配说明》，pId=1594 | 访问用户提供的官方文档，检查标准 ClipData、DragEvent 与授权接口 | n/a，在线文档 |
| E-host-4 | `../artifacts/drag-host-4.1.3.json` | `python tools/verify_drag_host.py ../传送门_4.1.3.apk` | 产物中记录 APK SHA-256 |
| E-host-5 | `../artifacts/drag-host-5.1.5.json` | `python tools/verify_drag_host.py "../传送门-5.1.5(50015).apk"` | 产物中记录 APK SHA-256 |
| E-tests | `../artifacts/junit-0.15.0/` | `./gradlew.bat :app:testDebugUnitTest` | 本次 JUnit XML；汇总见验证清单 |
| E-release | `../artifacts/validation-0.15.0.json` | `./gradlew.bat :app:lintRelease :app:assembleRelease`，随后 apksigner／zipalign 校验 | 清单记录 APK 与签名证书 SHA-256 |

| Finding | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F-drag | n/a_re | E-doc | high | `drag/SystemDragHooks.java` | 按标准全局拖拽接入；ROM 行为待实机 |
| F-host | n/a_re | E-host-4, E-host-5 | high | `hook/HostDragHooks.java` | 两版各 53 项宿主成员合同及继承关系通过 |
| F-sdk | n/a_re | E-host-4, E-host-5 | explicit_limit | ContentCatcher 系统 SDK | 不在宿主 APK 定义，不能据此证明设备 SDK 构造／回调可用 |
| F-choice | n/a_re | E-tests | local_only | `drag/DragActivity.java`、`DragActionStore.java` | 内容选择、方向固定、取消、范围变化及单次票据本地验证 |
| F-runtime | n/a_re | E-release | explicit_limit | HyperOS／LSPosed 真机 | 未验证实际拖出、授权交付、输入时序、弹窗层级、目标应用和服务响应 |

P-drag（`path_type=callflow`）：来源原生全局拖拽 → 系统元数据校验／本轮互斥 → 四方向 drop → 标准授权和载荷复制 → 单次宿主识别 → 原文／OCR／图片／网址保留 → 关闭原生浮窗 → 内容选择 → 固定方向动作 → 既有规则／分享／识图／翻译能力。

时间线：2026-11-03 先核对官方文档和两版宿主；随后实现设置、系统拖拽与长按互斥；按用户澄清改成所有方向先识别、弹窗仅选内容；最后完成回归、宿主静态合同和本地签名构建。准确测试数量、lint 警告、签名、对齐、哈希和 UI 渲染产物均以 `validation-0.15.0.json` 为准。

## 尚未验证

未连接或安装真机，未验证 LSPosed 回调、HyperOS 私有 WMS 字段、ContentCatcher 系统 SDK、来源真实拖出行为、跨窗口 URI 交付、长按手势时序、Root／Shizuku、实际分享目标和 Google 返回结果。单元测试及 DEX 合同不等于上述运行时验收。没有远程推送、标签或发布。测试源码沿用仓库既有被忽略的本地 `app/src/test/` 布局，本次归档其 JUnit 结果。

## 本次最终验证

- JUnit：859 项，失败、错误、跳过均为 0。
- lintRelease：0 错误、32 警告；未隐藏警告，完整文本已归档。
- 长期 Release 证书与 0.14.6 一致，APK v2 签名、4 字节及 16 KiB 页对齐检查通过。
- APK SHA-256：`B23E776D10780DFBF7D33E73F243296A00350F63885DCBA40F37D774115CCF30`。
- `TaplusBridge-0.15.0-release.apk`、对应 R8 mapping ZIP、JUnit XML、解码清单、签名和对齐日志均在 `artifacts/`。
- 验证工具按本次 R8 mapping 解析改名和内联，核对存活 DEX 方法；不再把被正确内联删除的辅助类误报为能力缺失。
