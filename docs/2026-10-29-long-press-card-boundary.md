# 仅长按卡片的取数边界

> 本文中的本地归档路径不随源码仓库分发；公开发布产物请以 Releases 为准。

日期：2026-10-29，Asia/Shanghai。

用户在11:05归档后明确要求：在微信中只长按触发传送门，不再点原卡片，也不Hook微信。此要求与目前“主动布防后再点击原卡片”的观察流程不同。0.2.2没有实现长按直接识别隐藏卡片URL，不能把系统观察成功视为这一需求完成。

## 这次日志实际证明了什么

输入：`F:/qq/MobileFile/LSPosed_20261029_110514.zip`，SHA256：`d1bb183eca3dbef4ab9c1c55d653c3daa6e682f084d77f7cfc002fe1c716297e`。

当前模块日志为`log/modules_2026-10-29T11:00:23.86132.log`。

| 阶段 | 位置与时间 | 结果 |
| --- | --- | --- |
| 系统加载 | 模块日志15、52–53、166行 | 0.2.2进入系统框架，观察Hook及命令通道就绪 |
| 纯长按输入 | 模块日志542、597行 | 两次capture_text均input=18、提取URL=0；不是证明完整消息只有18字符 |
| 原生输入日志 | full.log 25048、61850行 | 可计数的记录正文10字符，HTTP token为0；与模块输入来自不同阶段 |
| 布防和确认 | 模块日志605行；full.log 64904行，11:04:32.843–32.844 | armed后host_ack=accepted，前次确认回调故障已在这次运行中消除 |
| 点击后观察 | 模块日志606行，11:04:38.631 | MMWebViewUI携带一个有效HTTP(S)，origin=RAW_URL、fields=2、for_result=false |

结构化结果见无正文日志摘要（本地归档：`../artifacts/diagnostics/capture-20261029-110514.json`）。模块没有保存原网址和域名，这份归档不能证明rawUrl属于B站直链还是其他包装链接。

`CaptureBroker.status()`把MMWebViewUI写成“目标组件”容易误导。它是微信承载外链的页面，不是已解析出的最终目标应用。当前`IntentObservation`只返回组件、计数和枚举，`SystemCaptureObserver`继续执行原启动；没有把网址交给路由规则，也未选择B站或微信作为内容目标。

## 本地IceBridge的真实方式

核对`E:/vs/claude/IceBridge/IceBridge-master`当前源码：

- `android16/NativeActivityLinkCapture.java:25`：输入是已生成的Activity启动Intent，识别微信MMWebViewUI及QQBrowserActivity。
- `android16/IntentUrlExtractor.java:41`：从Intent.data、rawUrl、url、EXTRA_TEXT和已知嵌套字段中提取候选链接。
- `modules/IceBridgeModule.java:374`：ActivityStarter执行路径接管启动，避免原网页与目标重复出现。
- 同文件`:978`：按捕获URL、来源包和域名匹配规则，取得实际targetPackage。
- `docs/NATIVE_LINK_CAPTURE.md:9`：明确其独立运行模式处理普通微信/QQ点击，在来源WebView启动前接管。

因此它能实现“点卡片后不显示微信网页，直接流转到目标应用”，但没有“卡片未点击时，仅凭长按就读取隐藏链接”的实现。完整移植其启动接管，也不会给无URL的长按结果增加原卡片地址。系统剪贴板路径同样需要已复制的链接，不能替代未取得的消息URL。

## 为什么不能继续用当前路线承诺长按直达

已经分析的ContentCatcher源端在微信进程内读取实际View，再向传送门回传String/Bitmap。`ViewContentFetcher.fetchViewContent(View,...)`需要源进程View，不是回传token可跨进程请求消息对象的接口。`getPageConfig`只返回配置，`decorateContent/updateContent`不是可靠补取接口，`reservedFunction`返回null。FavViewCatcher的rawUrl模板面向已打开的WebView，不能据此获得聊天列表卡片未点击时的隐藏地址。详细DEX与接口证据见[系统采集分析](2026-10-28-contentcatcher-analysis.md)。

本次结论是：**在已验证的本地IceBridge、传送门和该ContentCatcher链路内，没有找到符合“只长按、不额外点卡片、不Hook微信”的完整卡片URL来源。** 这不是断言所有ROM私有能力都不可能，而是当前不能提供可靠实现。

只有来源确实提供完整URL时，才能选择目标应用并打开对应内容。根据卡片标题、图标猜应用，或改为标题搜索，都不能等同于打开原B站视频。自动点原卡片再隐藏页面也仍然依赖点击流程，不符合用户这次确认的交互。

后续研究应先证明原生采集能在长按阶段可靠回传卡片结构，而非继续扩大Activity启动观察。本次没有修改系统配置、来源应用或路由生产代码，没有构建新的功能APK；0.2.2仍为观察验证版。
