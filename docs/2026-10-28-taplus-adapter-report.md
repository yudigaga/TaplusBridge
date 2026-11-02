# 传送门 4.1.3 应用流转增强适配报告

分析日期：2026-10-28。类型：普通 APK 适配分析（flavor=null）。范围见 [scope.md](scope.md)。

## 结论

本地传送门可以在用户点击识别结果的 Intent 生成处增强应用流转，无需把 IceBridge 的 system_server 全局启动 Hook 移植进来。首版已经实现独立 API 102 模块：域名规则、来源条件、固定深链转换、原生链接保留、Root/冰箱解冻和前台 Activity 打开。真实 LSPosed 注入、HyperOS 后台启动策略和目标应用是否接受深链仍需手机验证。模块不修改识别服务、OCR、触发手势或宿主隐私策略。

## 输入与依赖

宿主包名 `com.miui.contentextension`，版本 `4.1.3 / 40013`。

宿主 SHA-256：`20FCE3E54932711177EB449F60586113D07F287371FD47D629CA5B59A984A93E`。

DEX 依赖等价锚点：识别与展示使用宿主 Java 主包；内容获取依赖系统 `miui.contentcatcher.sdk`；界面使用 miuix；跨设备使用 Xiaomi Continuity。此次增强链路位于 Java 层，不依赖替换原生库。JADX 原始导出存在 4 处反编译错误，关键 Hook 的实际 DEX 定义另外校验。

IceBridge 当前参考版本为 `0.0.90 / 90`，HEAD `3ac3baa6273cb084c2563bd34d10d1f54e5da8ee`。参考规则优先级、受控深链和冰箱 SDK 协议；新工程没有复制其无许可证声明的业务源码和完整在线规则。SDK 与库版本固定于 `app/build.gradle`；现代 Xposed 入口使用 `META-INF/xposed/`，最小与目标 API 均为 102。

## Evidence → Finding → Path

下列宿主路径相对于父目录的 `传送门_4.1.3_反编译结果/sources/com/miui/contentextension/`。

| Evidence | source_ref | repro_command | content_hash |
|---|---|---|---|
| E-host | 原始宿主 APK / `artifacts/host-contract.json` | `python tools/verify_host.py ../传送门_4.1.3.apk` | 上述宿主 SHA-256 |
| E-intent | `utils/AppsUtils.java:37` | 搜索 `generateOpenIntent`、`getIntentWithBrowser`、`queryIntentActivities` | n/a（原 APK 已固定） |
| E-click | `text/adapter/TaplusRecommendAdapter.java:125` 与 `text/floatview/TaplusSplashFloatView.java:570` | 搜索 `AppsUtils.generateOpenIntent`、`AppsUtils.startActivity` | n/a |
| E-origin | `services/TextContentExtensionService.java:453`、`:598`、`:757` | 搜索剪贴板任务、`parseInjectorToken` 和 `cancelTask` | n/a |
| E-icebox | 本地 IceBridge `sdk_client/SdkImplement.java` | 读取 `setAppEnabledSettings` 的 Provider extras | n/a（参考提交已固定） |
| E-imports | 宿主清单、DEX 定义、上述依赖类 | `tools/verify_host.py` 校验定义和 static 标记 | 同 E-host |

| Finding | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F-builder | n/a_re | E-intent,E-click | high | `AppsUtils.generateOpenIntent(Context,TextRecommendationInfo)` | 已实现窄 Hook；仅静态验证 |
| F-frozen | n/a_re | E-intent | high | enabled-only `queryIntentActivities` 后退到 mibrowser | 保存模型原生 Intent，待解冻后打开 |
| F-source | n/a_re | E-origin | high | 剪贴板任务不刷新旧 injector 来源 | 追踪新 ContentCatcher 会话；剪贴板来源置未知 |
| F-thaw | n/a_re | E-icebox | high | 当前用户的 Root / IceBox 协议 | 后端实现、状态确认、超时；设备待验证 |

P-1，`path_type=callflow`：长按/复制触发 → 宿主文字或 OCR 识别 → 点击卡片 → E-click 的 Intent builder → 模块 Provider 验证宿主 UID/当前用户/版本 → 30 秒一次性票据 → TransitActivity 重新匹配本机规则 → 工作线程解冻当前用户目标 → 确认启用且可见 → 前台 Activity 打开目标页面。

P-2，`path_type=callflow`：未匹配、冲突、模块关闭、宿主版本不符、Provider 不可用 → 原样返回宿主 Intent。已接管后解冻失败、超时或目标无法处理链接 → 显示回退按钮。不会在确认失败后自动打开其他应用。

## 实现要点

- 两个业务入口：`AppsUtils.generateOpenIntent(Context,TextRecommendationInfo)` 和 `ClipboardUtils.generateNerIntent(Context,String,String)` 的 URL 分支；原方法先执行，异常时保留返回值。
- 来源辅助 Hook 只读取会话/剪贴板标记，不改变宿主字段；任一辅助契约不满足，来源按未知处理。
- 规则区分未匹配与冲突。来源专用规则优先于 `*`，再比较域名长度；主机名验证防止后缀混淆。
- 默认只有精确域名，固定转换只接受已核对的 URL 形态；不运行远程脚本、不联网展开短链。
- Provider 仅给相同 Android 用户中的宿主 UID 签发票据；Activity 只接收票据，不接受外部直接传入的解冻包名。
- 票据随机、最多 32 个、30 秒过期且只能消费一次，进程重建后仍可校验。配置变化会在启动前再检查。
- Root 命令包名严格校验，显式 `--user`；12 秒命令超时、4 KiB 输出上限并持续排空管道。
- 冰箱 SDK Provider 通过不可变 PendingIntent 验证模块身份；回执本身不等于解冻成功，需要再次检查 PackageManager 状态。
- TransitActivity 的 20 秒截止只终止自动打开，不保证能够撤销已进入第三方 Binder 的解冻操作；迟到结果不再启动目标。旋转保留任务，退出则停止打开；自动启动和回退分别有单次保护。
- 规则选择任意安装/冻结应用需要 QUERY_ALL_PACKAGES；该 lint 项在声明处单独说明并抑制。Provider 的调用方校验由代码实现。其他 lint 警告见验证记录。

## 验证与剩余工作

完整本地结果与 APK 哈希见 `artifacts/validation.json`；JUnit XML、lint 文本和签名/对齐输出保存在同目录。`host-contract.json` 校验 14 个真实 DEX 方法/字段定义及 static 标记。

手机验证步骤：

1. LSPosed 启用 API 102 模块并只勾选传送门，重启宿主进程，检查 `TaplusBridge` 安装日志。
2. B 站/知乎普通链接分别验证已启用、被冻结和目标未安装三种状态。
3. Root 和冰箱各自验证授权拒绝、解冻成功、超时回退；检查当前用户未串到其他资料。
4. 先长按来自 A 应用的内容，再复制其他应用 URL，确认来源规则不会继承 A。
5. 验证旋转、返回、Home、重复点击、第三方 Binder 迟到回复，不产生重复打开。
6. 确认拨号、邮件、翻译、地图和原有内容识别行为仍按宿主处理。

未实施任意 OCR 文字的多应用搜索菜单、支付请求重放、系统级后台启动放行、自动重冻、远程规则同步和其他宿主版本适配。开发 APK 使用 Debug 签名；未生成长期签名正式版，未远程发布。

## Timeline

- 2026-10-28：确认用户要求 API 102 和「解冻后打开」。
- 2026-10-28：只读核查 IceBridge 当前源码与宿主识别卡片、Intent、来源链路。
- 2026-10-28：创建独立工程与模块入口；实现规则、票据、界面和解冻后端。
- 2026-10-28：修复冲突规则原生兜底、回退重复点击、Binder 等待截止和 Windows 中文测试路径问题。
- 2026-10-28：执行 JVM/Robolectric、Lint、APK 构建及宿主 DEX 契约检查；设备未连接。
