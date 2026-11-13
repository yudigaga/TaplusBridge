# 帖子取链与来源选字工具条复测

2026-11-13，Asia/Shanghai。本轮为日志及代码诊断，尚未修复帖子内取链；没有修改生产代码或生成新 APK。

## 先恢复来源工具条的运行条件

在 LSPosed 的 TaplusBridge 作用域中额外勾选酷安，然后结束并重启酷安；模块的“生效应用”名单也须允许酷安。保留系统框架与内容扩展作用域。现有 0.15.46 已包含此来源 Hook，暂不需要因作用域缺失重新安装模块。

归档 `F:/qq/MobileFile/LSPosed_20261113_140925.zip` 的 SHA-256 为 `e9192a7d8ee70f829ed1f8e7e2f2255a0b0710b1d15409919f169cb303bcb591`。`db_scope.txt:417–425` 记录本模块仅有 system、com.miui.contentextension 两项，均为 user 0，没有 com.coolapk.market。

模块日志 `log/modules_2026-11-13T13:59:53.881684.log:15、699、1161` 分别证明 system_server 和两次内容扩展进程加载的是 0.15.46 / 78。第 126、187 行虽然有来源选字 Hook 安装消息，但两条都来自 system PID 3649，不能视为酷安来源 Hook 已运行。`full.log:47655` 的 14:07:34.624 记录酷安仍启动原系统 AiTranslateService，与缺少来源注入一致。

## 帖子取链的实际断点

| 证据 | 观察 | 结论与处理路径 |
|---|---|---|
| 模块日志 1311–1316 | 14:08:23.842 释放；24.017 记录 tapped；27.001 timeout | 已执行补点，等待真实启动请求超时 |
| full.log 56429–56443 | 注入 DOWN、UP 都送达酷安 FeedDetailActivityV8 主窗 | 不能归因为补点未送达 |
| full.log 56539、56578、56650 | 补点后依次显示三个酷安进程的 PopupWindow，没有对应 Activity 启动 | 与进入原生选字界面一致；控件为何没有发出链接跳转仍需核对 APK |
| 模块日志 1317 | text_present=true、image_present=false、link_present=false | 导入前只有文字，没有提取出 URL |
| 模块日志 1320–1323 | consume_rejected reason=capture_focus；随后外层 gesture_expired、import_unavailable | 导入还被窗口校验拒绝；gesture_expired 是通用映射，不能当成超时证据 |
| 模块日志 948–954 | 另一轮 blocked=http → captured → link_present=true → imported | 同一份日志中取链拦截器存在成功路径，14:08 这轮没有观察到链接启动 |

14:08 失败轮没有 `blocked` 或 URL 校验 `rejected`，因此不能认定真实 URL 被校验器拒绝。释放到消费拒绝约 3.36 秒，未达到 7.5 秒释放有效期。

`ScopeCaptureWindow.validateWindows` 将原窗口身份、主窗、边界或 surface 变化标记为 `capture_window_changed`；`capture_focus` 还可能来自空焦点、空命中窗口、未认可的前景窗口或子窗。当前日志没有这些 PopupWindow 的类型、归属字段及父窗关系，无法判定具体分支。没有据此放宽窗口准入。

第一轮 `full.log:56704–56716` 在 14:08:27.088–27.098 恢复宿主主浮窗与启动浮窗，早于 27.202 的消费校验；因此也不能将消费拒绝唯一归因于酷安 PopupWindow。现有日志不能区分来源选区窗口与宿主恢复窗口的影响。

14:08:36 是另一轮短长按：宿主取消后走系统采集回退，14:08:39.401 报 `capture_focus`。它不是前一轮的消费拒绝，不能合并为同一事件。

## 继续定位所需信息

需要手机当前安装的酷安 APK，以核对帖子内链接控件、真实 URL 的存放位置和补点时的选区处理。已有源码仅能说明原生 TextView 路径，不能证明酷安实际使用了哪一种链接实现。来源工具条加作用域后，也需要确认酷安进程出现来源 Hook 安装记录，才能继续判断翻译是否还有第二处断点。

已生成脱敏日志摘要：`artifacts/diagnostics-20261113-140925/summary.json`。提取文件位于 `../analysis/lsp-20261113-140925/`，保留原始归档行号。未联网访问帖子链接，未输出帖子正文、完整 URL 或精确触点；未运行 Gradle 或真机验证。
