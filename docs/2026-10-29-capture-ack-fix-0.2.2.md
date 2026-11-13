# 0.2.2-dev 捕获确认回调修复

> 本文中的本地归档路径不随源码仓库分发；公开发布产物请以 Releases 为准。

日期：2026-10-29，Asia/Shanghai。

用户已经正确勾选系统框架并重启。10:33归档证明0.2.1进入system_server、安装观察器并接收两次布防请求。仍提示勾选和重启，原因是模块匿名ResultReceiver跨进程解包失败，确认消息没有回到传送门；不能把这份日志解释成用户配置未生效。

## 日志证据

输入：`F:/qq/MobileFile/LSPosed_20261029_103303.zip`，SHA256：`e67e7d4747dd709b5d4f75f73f386e7e15be7d0ceb8dd5b5f856a022919227df`。

| 本地时间 | 归档内位置 | 已证实事实 |
| --- | --- | --- |
| 10:25:21.816 | 当前`log/modules_...log`第15行 | `build=0.2.1-dev/6; system_server=true; process=system` |
| 10:25:22.386–22.390 | 同文件52–53行 | 系统启动回调到达，观察Hook已安装 |
| 10:25:29.399 | 同文件166行 | 系统观察命令通道就绪 |
| 10:32:08.042–08.043 | `full.log`52748、52780行 | ACK匿名类解包报ClassNotFoundException/BadParcelableException |
| 10:32:08.065 | 当前模块日志566行 | 系统已布防微信、用户0、15秒窗口 |
| 10:32:16.589 | `full.log`56363、56395行 | 再次出现同一ACK解包失败 |
| 10:32:16.599 | 当前模块日志567行 | 第二次系统布防 |

模块日志文件全名为`log/modules_2026-10-29T10:25:16.467645.log`。异常类固定为`io.github.yudigaga.taplusbridge.hook.HostCaptureAction$Handshake$1`，栈指向`SystemCaptureObserver.arm()`读取`ack`的位置。系统对无法读取的Bundle值进行容错后得到空回调，旧代码仍继续布防；宿主2.5秒后超时，显示了错误归因的重启提示。

本次没有`capture stage=observed`。它不能证明原卡片不存在URL，也不能用`armed`代替宿主已收到确认。此时应先修复握手，而非根据缺失的观察结果扩大启动Hook或来源应用作用域。

无正文、原URL、域名或nonce的结构化证据见日志摘要（本地归档：`../artifacts/diagnostics/capture-20261029-103303.json`）。归档没有整体提取，报告未保存聊天内容。

## 修复

发送端保留匿名回调在宿主进程中的Binder实现，先通过`ResultReceiver.writeToParcel()`和`ResultReceiver.CREATOR`生成框架基类实例，再放入广播。系统只需加载`android.os.ResultReceiver`，不再尝试加载模块匿名类。

系统侧在签发租约前要求ACK对象非空；缺少回调时记录固定`command_rejected / missing_ack`并拒绝布防。宿主新增`host_ack / accepted|failed`固定日志，超时提示改为“未收到系统捕获确认，请查看模块日志”，不再把所有握手失败都归因于勾选或重启。

日志分析工具修复旧的日期硬编码，支持`--date YYYY-MM-DD`及自动推断，识别系统加载、确认回调和固定异常事件；只保存白名单元数据。读取旧归档时仍能保留来源文件和行号。

作用域继续是`com.miui.contentextension`和`system`，广播目标包继续为运行时系统包`android`。不Hook微信或QQ，不扩大卡片组件白名单，不修改原Activity启动，不新增URL持久化、解冻或捕获后的路由行为。

## 验证

先添加实际入口回归：`HostCaptureAction.arm()`生成广播 → Intent经Parcel往返 → 接收ClassLoader拒绝模块类 → 读取ACK。修复前，定向测试报BadParcelableException，原因为ClassNotFoundException，与真机相同；原失败证据保存在回归失败记录（本地归档：`../artifacts/ack-parcel-regression-red-0.2.2.xml`）。

修复后，测试要求ACK实际类型是`ResultReceiver.class`，成功ACK驱动主线程关闭一次，重复ACK不重复关闭，且保留迟到、超时、拒绝和关闭失败的取消约束。Robolectric内置ShadowResultReceiver会绕过真实Binder路径，直接调用当前对象的空回调，因此本测试类替换该快捷Shadow，以SDK实现执行Binder/Handler路径，没有删除ACK行为断言。

- 最终完整构建、183项Android测试全部通过：失败0、错误0、跳过0。Android相关单元测试使用Robolectric SDK34。
- 日志工具9项Python测试全部通过，包含日期过滤、原URL/nonce不泄漏、准确标签匹配和异常白名单。
- Lint为0错误、16警告；宿主39项DEX定义契约通过。
- 最终APK核对API102元数据、传送门与系统框架作用域、版本、入口和取消权限；未打包libxposed API类或旧Xposed桥接类。
- APK v2签名及4字节对齐通过，无原生库。Android Debug证书SHA256：`4e06d06e242802a8b8e77f696079ddd78e3d615d0cf73b781f3a187abb51f980`。
- 版本0.2.2-dev / versionCode 7，大小234459字节，APK SHA256：`666C00480E0FEBA5A9FE5019C464CFBA720CA237F5D798A525622B53462BCC04`。

安装包：TaplusBridge-0.2.2-dev.apk（本地归档：`../artifacts/TaplusBridge-0.2.2-dev.apk`）。完整本地验证见validation-0.2.2.json（本地归档：`../artifacts/validation-0.2.2.json`），对应JUnit、Lint、签名、对齐和清单在`artifacts/`。

## 新版复测

覆盖安装0.2.2，保留现有“传送门 + 系统框架”勾选并重启，使系统进程加载本次修复。长按原卡片进入传送门，点捕获操作；预期收到已准备提示、浮窗关闭，然后15秒内点原卡片，再查看捕获结果。确认返回后的本地日志应出现`capture stage=host_ack outcome=accepted`。

本版本仍只验证启动请求是否含URL，原卡片按原方式打开；捕获后直接跳目标应用尚未实现。用户归档证明的是0.2.1的系统注入和失败原因，不是0.2.2已在真机修好。当前ADB列表为空，未安装新APK或实测新版确认/真实卡片URL；未运行设备仪器测试、Root/冰箱解冻和目标内容落点检查，未构建长期密钥正式版或远程发布。
