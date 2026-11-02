# 不可见点击取链接：可行性和实现约束

日期：2026-10-29，Asia/Shanghai。状态：源码与日志可行性核对完成，尚未实现或构建此功能。

> 后续已按用户确认实现0.3.0候选，采用已真机观察过的公共ATMS入口在原方法体之前消费请求，未进入ActivityStarter，因此无需伪造其内层结果或跳过清理。本文为设计时记录，最终实现和验证边界见[0.3.0说明](2026-10-29-invisible-card-probe-0.3.0.md)。

用户补充允许模块内部模拟一次原卡片点击，前提是微信卡片内容不显示，也不再要求用户手动点原卡片。仍然不Hook微信。这使“启动前截获”成为可用取数方向，与之前完全不产生点击事件的约束不同。

## 已确认的基础

- `LSPosed_20261029_110514.zip/full.log`61852、61853、61857、61859行，11:04:30.686附近，存在原始触点和view_rect。仅内存核对发现第一个触点在卡片范围内，第二个不在；不能无条件使用两点中点。报告不保留原坐标。
- 同归档模块日志606行，11:04:38.631，微信MMWebViewUI的rawUrl含一个有效HTTP(S)，没有结果回调。前次ACK修复也已在该次运行得到accepted确认。
- 来源Activity是`com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI`，不能只硬编码LauncherUI。
- 传送门服务有getInjectorPoint/getContentRect/getFirstTouchPoint/getSecondTouchPoint；浮窗BaseFloatView持有WindowManager、窗口参数和根View。
- 本机SDK35的InputManager支持定向UID的三参数injectInputEvent。它防止事件投给其他UID，不能绕过覆盖窗，也不证明目标控件就是卡片。
- 本地IceBridge有ActivityStarter外层执行/内层取消的真实实现，可在来源网页启动前截断请求，保留框架自身清理。

## 目标流程

1. 用户长按卡片，传送门记录当前来源、窗口、触点和识别会话。
2. 模块建立一次探测事务，确认系统侧拦截已安装、身份及窗口快照仍有效。
3. 在手指已经抬起、点击可准确投递的前提下，内部模拟一次原卡片点击；用户无需再点击原卡片。
4. 系统框架取得该次启动Intent中的网址，同时取消原微信网页请求。网页本身不创建，不采用打开后再关闭、移到后台或用新页面遮住的办法。
5. 网址只在内存经Binder返回当前传送门会话；调用已有规则预览，生成“在哔哩哔哩打开”等真实目标名称、图标卡片，传送门浮窗保留。
6. 用户点击目标应用卡片时，才解冻并打开目标内容。

这是设计流程，不是当前0.2.2行为。0.2.2仍然只观察并放行原启动。

## 必须先满足的条件

**拦截必须先就绪。** 新握手应明确protocol、probe模式、block_ready、同一nonce/user。不能收到旧版code=1/空Bundle就模拟点击，否则旧观察器会放行微信网页。新版宿主遇到仍运行旧系统代码时应拒绝点击。

**点击必须准确且单次。** 绑定来源包/实际UID、当前Android用户、Activity、display、旋转、原卡片范围和会话时效。长按回调可能发生在原指针仍按下时，不能简单延迟几百毫秒后盲点。原指针状态无法可靠确认时，首版应从传送门内明确的“无感识别此卡片”操作触发；这仍不要求用户点原卡片。不能对所有无URL微信文字自动点击。

**保持浮窗与输入状态一致。** 需要检查两个attached浮窗，而非只处理一个；必要时暂时调整NOT_TOUCHABLE及WATCH_OUTSIDE_TOUCH后完整恢复。Splash是2038覆盖窗，Android触摸遮挡限制可能使透传失败；定向UID注入不能解决该限制。不可用时应在点击前退出。显示、旋转、多窗口等未覆盖场景不能猜测坐标。

**按IceBridge方式取消内层，保留外层清理。** 公共ATMS入口先缓存原调用UID/PID/用户；ActivityStarter.execute绑定具体starter/request对象并proceed一次，内层executeRequest才取消，保留last-result字段、SafeActivityOptions.abort和getExternalResult映射验证。不能直接在execute返回成功，否则可能触发不存在ActivityRecord的访问。安装检查失败时不允许点击。

**失败不显示源页面。** 已拥有的探测请求即使无URL或解析失败也要取消。不能复用IceBridge executeTakeover/abandonTakeover中的原请求replay回退。只有确认内层已阻断并退出外层清理后，才能回传结果。已拥有的请求即使后来超时也不能再次放行。

**结果不污染其他会话。** 采用READY/CAPTURED/FAILED多阶段框架ResultReceiver，按epoch/source/nonce/user检查结果；不得使用当前cancelTask清除浮窗后强行发布。新长按、取消、剪贴板或销毁时，旧结果失效并恢复窗口参数。URL不进日志或SharedPreferences；后续冻结目标的launch ticket需要内存分支，旧TicketStore会持久化URL和Intent，不能直接复用后仍声称不落盘。

## 仍需验证的边界

原卡片若在现有Activity内直接换内容、启动其他组件，或在探测超时后才异步启动，单纯拦截MMWebViewUI不保证不可见。还需要覆盖不支持和迟到请求的结束策略，并核对与同时启用的IceBridge等模块的Hook顺序。不能只凭Hook已安装就承诺所有卡片绝对零闪现。

验证需包含真实外层execute→内层取消→返回值转换→finally顺序、错误身份、旧ACK禁止点击、无URL仍阻断、重复/迟到结果、窗口标志恢复和内存URL路径。之后在用户这台ROM上检查微信网页是否曾被创建/显示，以及传送门是否显示真实目标应用图标。当前没有手机连接，本次没有注入实际输入、修改生产代码、构建新APK或验证无闪现。

## 主要本地证据

- IceBridge `modules/IceBridgeModule.java:448–545`、`android16/ActivityStarterTakeoverPolicy.java`：内部START_ABORTED及外部结果映射、框架清理。
- IceBridge `android16/SystemAutonomousRuntime.java:285–336`：需要排除的原网页重放回退。
- 传送门 `services/TextContentExtensionService.java:500–524,628–632,882–907`：触点与范围。
- 传送门 `text/floatview/BaseFloatView.java:15–16,49–53`：窗口参数及更新。
- SDK35 `android/hardware/input/InputManager.java:917–943`：定向UID注入；`android/app/ActivityManager.java:529–534`：START_ABORTED是内部结果。
