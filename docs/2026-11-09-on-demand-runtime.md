# 按需运行与空闲回收

日期：2026-11-09（Asia/Shanghai）。基于本地 0.15.40 / 72 源码进行调整，本轮没有升级版本或生成新的 APK。[本次范围](scope.md)来自用户明确提出的“删除冻结本体通道、减少后台占用、用完快速退出”。

随后按用户要求完成本地正式版 **0.15.41／73**，APK、签名与本轮验证见 [正式构建记录](2026-11-09-release-0.15.41.md)。

## 使用行为

模块保持启用，正常使用所有功能；不再提供“冻结本体后继续运行”开关。设置页的系统同步状态只表示名单、截图、补点和手势配置确认。

本体主进程、`:lens` 工作进程按需启动。没有活动界面、正在执行的任务、服务绑定、未结束的编辑或待交付内容时，空闲约 **3 秒**主动退出自身进程。再次操作时由正常组件入口重新启动，不使用常驻前台服务、定时唤醒或自动拉活。

系统和宿主中的必要 Hook 继续监听手势和事件；本次只退出本体的自有进程，不终止传送门宿主、system_server、Shizuku 或目标应用。Hook 代码的这部分驻留不能等同于本体后台进程完全消失。

## 已移除和保留的能力

- 删除 Provider 不可用时回退系统规则流转、系统启动票据与 ATMS 标记拦截，以及系统代为恢复目标包的专用实现。
- 删除独立冻结开关、冻结就绪提示和只服务于该通道的类与测试。
- 保留正常 Provider/Activity 业务入口，目标应用的 Root/Shizuku 解冻方式不变。
- 保留系统名单、截图、补点、内容采集和拖拽手势通道；这些是正常功能基础，不能随冻结通道一并删除。
- 系统快照升级为 schema 8，可读取旧 schema 1–7。使用路由配置摘要维持普通票据的配置撤销语义，不再将规则正文交给系统做独立路由。

## 回收保护

| 场景 | 何时保护及释放 |
|---|---|
| 模块界面 | 创建/进入前台即阻止退出；停止或销毁后重新检查空闲；配置切换有退出宽限 |
| Provider | 同步调用期间持保护，返回即释放 |
| 配置发布 | 按代际持有有界保护；系统确认、最终失败、替换、断连后释放；同步线程闲置 1 秒释放 |
| 设置后台作业 | 实际开始执行时保护，完成或取消后释放，不为已取消的排队任务保活 |
| 拖拽、链接、翻译票据 | 仅待交付期间保护；消费、替换、取消、过期后释放，防止 Provider 返回后界面还没启动就退出 |
| 解冻及拖拽读取 | 任务执行期间保护；完成、超时或取消后释放 |
| Lens | 服务绑定和活动图片会话保护；关闭、解绑或宿主断开后取消上传/排队作业，销毁 WebView/Surface/PendingIntent，释放保护 |
| AI 翻译 | 请求执行期间保护；完成结束线程，关闭或取消时清理请求引用、网络取消信号和线程 |
| 外部文档选择 | 按 requestCode 管理有期限保护，保留于 ViewModel 跨界面重建；结果返回、最终销毁或超时后释放 |
| 未保存翻译设置 | 编辑对话框存续期间保护；保存、取消或销毁后释放；密钥草稿不写 Bundle |

Lease 的释放可跨线程且幂等；新任务和前台返回取消尚未执行的退出。自有进程身份同时校验包名、精确进程名和 UID；注入宿主/system 的代码不会安装退出控制。

## 分享图片及设置恢复

分享图片的随机授权元数据保存在本体私有存储，容量 8、有效期 10 分钟、只读，校验墙钟/单调时钟和 canonical 路径。模块退出后，接收应用仍可在已有 URI 授权有效期内重启 Provider 读取图片，已经打开的文件描述符也可继续读取。不为图片有效期维持 10 分钟后台线程；过期数据在访问时清理。

分享授权元数据不新增正文或链接落盘。无感取链的 EphemeralTicketStore 及动作/选择/翻译票据继续保存在短期内存；普通流转 TicketStore 沿用原有短期存储。规则输入与预览输入可恢复；导出在文件选择返回后的 IO 任务内读取当前手动规则并写入文件，避免把大 JSON 放入 Activity Bundle。

## 关键实现

| 文件 | 职责 |
|---|---|
| `runtime/ModuleProcessLifecycle.java` | 自有进程边界、界面计数、任务保护、3 秒退出及重入取消 |
| `runtime/BridgeApplication.java` | 仅模块主进程和 Lens 进程接入 |
| `runtime/BridgeTransport.java`、`RuntimeServer.java`、`RuntimeSnapshot.java` | 移除冻结业务，保留系统基础通道与配置身份 |
| `runtime/RuntimePublisher.java`、`ui/SettingsController.java` | 配置和后台设置任务生命周期 |
| `bridge/BridgeProvider.java`、各票据 Store、`ui/TransitActivity.java` | 业务调用与一次性流转生命周期 |
| `drag/DragShareProvider.java` | 进程重建后的受限图片授权 |
| `lens/LensService.java`、`LensSession.java`、`translation/TranslationActivity.kt` | 绑定/会话/请求及线程释放 |
| `ui/MainActivity.java`、`RuleSettingsScreen.kt`、`TranslationSettingsDialog.kt` | 外部交互和未完成编辑保护 |

上表路径均相对 `app/src/main/java/io/github/yudigaga/taplusbridge/`。

## 验证范围

新增回归先复现了旧实现中分享授权丢失、线程残留、短期票据缺少保护、配置变更不撤销旧票据，以及编辑草稿丢失等问题，再完成相应修改。独立交叉审查发现的文件选择重建和大导出 Bundle 问题已在本轮处理。

最终全量验证：135 个套件、1,391 项 Android 本地单元测试，0 失败、0 错误、0 跳过；Python 元数据测试 24 项和独立 Lens HTTP 上传测试 14 项全部通过。Release lint 为 0 错误、37 警告。首轮发现的旧测试重置方法反射引用及回收测试隔离问题已修正，最终完整命令返回 `BUILD SUCCESSFUL`。

单元测试 XML 位于 `E:\vs\claude\.taplusbridge-build\9943d4ab\app\test-results\testDebugUnitTest`，lint XML 位于同一 app 输出目录的 `reports/lint-results-release.xml`；本次统计和独立上传测试输出分别保存在 `.local/on-demand-unit-green.json` 与 `.local/on-demand-lens-upload-green.txt`。

```powershell
Set-Location -LiteralPath 'E:\vs\claude\传送门\TaplusBridge'
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease --offline --console=plain --max-workers=2
python -m unittest discover -s tools/tests -p 'test_*.py'
& '.\tools\test_lens_upload.ps1' -EvidencePath '.local\on-demand-lens-upload-green.txt'
```

ADB 当前无连接设备。尚未验证真实 ROM 下的进程退出时延、LSPosed/宿主回调、文件分享接收方、Google 图片会话、实际翻译接口和 Root/Shizuku 服务行为。本地回归证明调用和状态处理，不能代替手机后台进程与内存验收。

设备验收应至少覆盖：设置关闭后退出、Lens 同图切模式/原网页期间持续可用及关闭后退出、翻译成功/取消、分享后进程重建读取、文件选择中旋转、编辑中切换应用、重复操作取消旧退出，以及目标解冻完成后本体退出。可结合 `module process idle; exiting process=` 日志与 `pidof` 观察；普通网络请求、文件选择或未完成编辑仍在进行时不会被计为空闲。
