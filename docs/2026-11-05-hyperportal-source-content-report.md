# HyperPortal 通用取数与补点校验

日期：2026-11-05（Asia/Shanghai），普通代码/逆向分析；未连接或操作设备。范围按 [scope.md](scope.md)，用户本轮授权分析日志、参考 HyperPortal 并修正通用识别与拖动补点；不增加逐应用适配，不替换系统 APK，不增加来源 Xposed 作用域。

## 结论

HyperPortal 的真实源内容采集在另一份 AES 来源端代码内，依赖源进程 View/Compose 对象，不能把其 SystemUI 菜单或 Compose UI 库当作跨进程取数能力。TaplusBridge 增加可选 Android AssistStructure 请求，在既有系统作用域内由框架向源 Activity 请求结构。仅采用原主窗口与触点附近的实际正文及绝对 URL；不提供有效结构时继续走现有截图/补点。

补点偏位的静态可复现路径是：遮罩接管前源应用收到超出 touch slop 的 MOVE，页面可滚动；普通识别候选因此失效，而原拖动兜底可重新申请相同屏幕坐标的点击。这只能证明代码风险，日志未保存坐标，不能据此确认每次实际错点的元素。

## Evidence

| E-id | source_ref | repro_command | content_hash |
|---|---|---|---|
| E-log | 用户 `LSPosed_20261105_155431.zip`；脱敏摘要 `artifacts/drag-recognition-log-20261105-155431.json` | `python tools/analyze_lsp_archive.py F:/qq/MobileFile/LSPosed_20261105_155431.zip --output artifacts/drag-recognition-log-20261105-155431.json` | `2F9D1977384551C90B4BA3A559E99094841F8FDAF1AE4BB764315E9D55B8FE5B` |
| E-bridge | 上游提交 `32d82800bd159627afd85a1ccd012c45d6628e93`；本地 `../analysis/HyperPortal-source/Bridge.java`，配套 Interceptor/ViewContentFetcher smali | `rg -n 'preExtract|enhanceFetchViewContent|SemanticsOwner|TextView|prefetchWebViewAt' ../analysis/HyperPortal-source/Bridge.java` | `AA030C648A22B2157EBDC9796BE1CABF5AECBE417DC0F41CEE3BF18F5A325534` |
| E-platform | 本地缓存 `../analysis/aosp-android16-ActivityTaskManagerService.java`，3193–3198 请求接口、3472–3535 调用源进程 | `rg -n 'requestAssistContextExtras|enqueueAssistContext' ../analysis/aosp-android16-ActivityTaskManagerService.java` | n/a：平台接口参考，设备实现未验证 |
| E-tests | 本轮定向 JUnit XML 与日志工具结果 | 项目 Gradle `testDebugUnitTest` 过滤 Assist/Scope/Drag/Recognition/Runtime/Input 套件 | `artifacts/validation-0.15.28.json` |

## Findings

| F-id | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F-source | n/a_re | E-bridge | high | AES Interceptor → Bridge；ViewContentFetcher → source View | 已确认源进程依赖；未照搬应用专属分支 |
| F-web | n/a_re | E-bridge | high | Bridge.prefetchWebViewAt | 当前源码内没有调用点；不得宣称其 JS/WebView 分支已连通 |
| F-point | correctness | E-log,E-tests | high for code path | SystemLongPressDrag/Scope point ownership | 接管前移动永久撤销补点，回到原位不恢复；接管后拖动不受影响 |
| F-structure | n/a_re | E-platform,E-tests | high for local contract | SystemAssistContent/AssistContentExtractor | 新增可选平台结构采集；设备覆盖待验证 |

## Path

P-real-content（callflow）：真实 DOWN → 同 device/down/配置/源窗口的累计识别截止 → 框架请求源 Activity 结构 → 后台受限触点选择 → 实际文字/链接 → 认证的一次性正文载荷或同轮拖动 source_text。正文有真实 URL 时跳过补点。

P-fallback（callflow）：无结构/无链接 → 已有截图或原点补点 → 校验真实 UP、窗口及手势代次 → 遮罩接管前已移动则拒绝补点；遮罩接管后的移动仍可完成拖动。普通弹窗关闭可能使用单独 DOWN/CANCEL，不等于改变最终原点；日志不保存原始坐标。

## 边界与记录

- 只接受匹配源 Activity 与原主窗口的结构；保留 secure、输入法、用户、配置和窗口验证。
- 最多 8 窗口、512 节点、32 层、128 KiB 正文、8 个实际链接；密码/编辑输入、未知变换、屏幕外内容不作为正文。
- 回包截止 2 秒；最多两个后台读取线程；手势/配置替换立即失效，30 秒释放结构正文；日志只保留固定阶段。
- 不推断缺失链接，不用类名、占位文案伪造内容。不保证所有 Compose、WebView、自绘或安全窗口提供结构。
- 2026-11-05：读取新日志并确认 0.15.27/59；23 次图片准备、19 次接管成功、1 次原生拖动 claim。
- 同日：源码比较确认 AES 源端依赖；新增平台结构路径、原点稳定性和异步交付回归，关键缺口先红后绿。
- 产物与最终检查见 [0.15.28 发布说明](2026-11-05-release-0.15.28-source-content.md)。未进行手机验收或远程发布。
