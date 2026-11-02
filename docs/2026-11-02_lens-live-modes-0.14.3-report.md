# 0.14.3 / 28：AI、完全匹配与原网页退出修复

日期：2026-11-02（Asia/Shanghai）。依据用户提供的 `LSPosed_20261102_191252.zip` 与截图修复，按用户最后指示直接构建 R8 长期签名 Release，不运行本版回归测试。未安装手机、提交代码或远程发布。

## 日志证据

输入 ZIP SHA-256：`4169fcc6e62739b231c1e735b5dc133620de1507c5281680842c5266c9bb67b6`。设备运行 0.14.2／27，Android 16，HyperOS OS3.0.315.0.WPCCNXM。脱敏摘要见 `artifacts/lens-log-20261102-191252-summary.json`。

- 全部／外观匹配和四字符查询可得到 40 条结果。完全匹配明确记录 `mode=exact` 后出现 `layout_unrecognized`，不能将解析器未适配说成 Google 确认没有匹配。日志未捕获 AI 模式的 refine 行，AI 归属来自用户描述。
- 19:11:57.275 原网页打开，19:11:58.070 新结果返回，随后来源应用失去前台，AppWatcher 关闭传送门。另一次 19:12:13.658 打开原网页，19:12:41.100 输入法高度更新后 KeyboardWatcher 关闭传送门。两次均有取消任务、app-request Activity finish；归档未捕获 FATAL EXCEPTION／Fatal signal 等崩溃标记。
- 根因是宿主收到结果时提前结束临时前台与键盘保护，即使原网页查看窗口仍然可见。

## 本版变更

原网页可见期间，页面结果、空结果及非终止查询错误不再结束交互保护；明确返回、取消、网络失败和超时仍会清理。Binder 回调先同步发布查看状态，避免 UI 主线程排队时后续结果抢先撤销保护；换图与失效请求不能延长旧保护。

AI／完全匹配使用 Android 11+ 的 SurfaceControlViewHost／SurfaceView 将 `:lens` 工作进程现有 WebView 嵌入结果区域，原生搜索栏和筛选保留。显示 Google 自身的答案、来源或无匹配反馈，不虚构这两种模式的 DOM 选择器或搜索结果。Android 9／10 保留同一会话的原网页入口作为明确兜底。

模式切换与原网页打开、返回均复用首次上传的 WebView、图片和 Cookie；不要求手动重新选择图片。跨进程挂载接口沿用支持的宿主与同用户校验，限制尺寸与身份，迟到的 SurfacePackage 被释放，旧 viewId 不能拆除新挂载。Surface 内容的触摸交给网页；聚焦输入时保留宿主键盘保护。空结果同时移除工作进程的 120 秒超时，避免已经完成的空结果稍后销毁会话。

## 构建范围

已执行 Debug Java 编译；Release 使用 `:app:assembleRelease`，保留其必需的构建任务及 R8。按用户要求不运行本版 JVM／Robolectric、完整 lintRelease 或手机验收；此前离线解析器的 16 场景基线不作为新增跨进程网页功能的验证。真实 Google AI／完全匹配返回、跨进程合成、触摸、输入法与 HyperOS 行为仍待手机验证。

安装包、签名、对齐与哈希记录位于 `artifacts/` 下的 0.14.3 文件；R8 mapping 随本版单独归档。构建成功与安装包校验不等于手机运行通过。
