# 0.2.1-dev 系统框架作用域修复

> 本文中的本地归档路径不随源码仓库分发；公开发布产物请以 Releases 为准。

日期：2026-10-29，Asia/Shanghai。

> 后续10:33用户日志已确认0.2.1正确进入系统框架，原先的勾选和重启操作有效；捕获失败来自ACK匿名Parcelable解包异常，详见[0.2.2捕获确认修复](2026-10-29-capture-ack-fix-0.2.2.md)。以下验证限制按0.2.1交付时的事实保留。

用户指出的作用域问题成立。0.2.0把现代API的框架作用域写成`android`，安装说明也错误地要求勾选“安卓系统”。代码中存在`onSystemServerStarting()`并不代表该配置已经正确请求框架注入。0.2.0的182项测试及签名验证不能证明此部署配置正确；原校验脚本甚至把错误的`android`写成了预期值。

## 修复依据和改动

直接核对本地IceBridge：`app/src/main/resources/META-INF/xposed/scope.list`第1行是`system`；`IceBridgeModule.java`的`onSystemServerStarting()`安装系统Hook，`onModuleLoaded()`用`param.isSystemServer()`判断加载位置。

0.2.1将最终APK内的作用域更正为：

```text
com.miui.contentextension
system
```

设置页、握手超时提示、捕获状态和README统一改为“系统框架”。模块加载日志增加进程名及`system_server`标志，系统启动回调开始时单独记录到达日志。保留原有ATMS观察方法、来源UID/用户校验、15秒窗口和观察模式，不借此增加其他Hook。

`system`是框架作用域，运行时系统Context的实际包名仍是`android`。因此用于系统动态接收器的广播`.setPackage("android")`保留；这里不能机械替换成`system`。本机SDK35源码的`LoadedApk(ActivityThread)`系统构造器将包名设为`android`，动态接收器也使用该Context包名注册。

## 安装与确认

1. 覆盖安装TaplusBridge-0.2.1-dev.apk（本地归档：`../artifacts/TaplusBridge-0.2.1-dev.apk`），versionCode 6，沿用原开发证书。
2. 在LSPosed中勾选“传送门”和“系统框架”，取消旧版误选的“安卓系统”，然后重启手机。不勾选微信或QQ。
3. 确认模块日志出现`build=0.2.1-dev/6; system_server=true`，随后出现`System framework callback reached`、`System one-shot observation hook installed`和`System observation command channel ready`。这些是期望日志，目前未在手机上确认。
4. 长按原卡片触发传送门，选择“捕获接下来打开的卡片”。等待已准备提示后，15秒内点击原卡片，再到模块查看捕获结果。

当前仍是第一阶段验证：只检查已知网页启动字段中是否含网址，原卡片按原方式打开；未实现捕获后的拦截、解冻与直接跳转。Android13及以上、传送门4.1.3 / 40013、已知微信/QQ网页组件等限制保持不变。数据处理细节见[第一阶段历史说明](2026-10-29-system-capture-observer.md)，其中旧作用域已明确标注错误。

## 本地验证与不足

- 修复前，更新后的APK校验脚本对0.2.0明确失败：`actual scope: ['com.miui.contentextension', 'android']`。
- 修复后，同一校验从0.2.1最终APK中读出传送门与`system`并通过。校验还核对版本、API102元数据、入口类、签名权限，以及未打包libxposed API类或旧Xposed桥接类。
- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline`通过；182项测试，0失败、0错误、0跳过；Lint 0错误、16警告。Android相关单元测试使用Robolectric SDK34，不代表真实框架加载成功。
- 宿主39项DEX定义契约通过，APK v2签名与4字节对齐通过，无原生库。Android Debug证书SHA256：`4e06d06e242802a8b8e77f696079ddd78e3d615d0cf73b781f3a187abb51f980`。
- APK大小233591字节，SHA256：`4DF3ECB5C1754DC331EC7F4F26FB9852040F583DC0C80059C8F023A0CE56752F`。

完整证据见validation-0.2.1.json（本地归档：`../artifacts/validation-0.2.1.json`），测试、Lint、清单、签名和对齐输出在`artifacts/`对应版本文件中。0.2.0 APK和验证文件保留为历史事实，不能作为框架作用域正确的证据。

ADB列表为空，未安装手机、未验证本次system_server回调及命令通道，也未确认原卡片的真实URL字段。未运行设备仪器测试，未验证Root/冰箱及目标内容落点，未使用长期密钥构建正式版或远程发布；本地IceBridge保持只读。
