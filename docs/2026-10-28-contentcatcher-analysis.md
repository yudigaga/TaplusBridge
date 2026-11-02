# ContentCatcherOS3_1：卡片与折叠文字取数分析

日期：2026-10-28，Asia/Shanghai。类型：普通 APK 逆向适配，flavor=null。授权与范围见 [scope.md](scope.md)。分析用户给出的系统组件及已有传送门源码，保持模块仅注入传送门，不 Hook 微信，不改系统配置。

## 结论

这份 APK 包含真实的视图采集实现。系统在来源应用进程中读视图，再通过 Binder 向传送门发送结果；传送门没有直接持有微信消息 View 或消息模型。

原生采集会把无障碍文字、描述或 `mText` 转成普通 String。隐藏在 URLSpan 中的目的地址会在这些路径到达传送门前丢失。内置微信反射配置只涉及旧版文字控件字段及图片字段，没有分享卡片消息 XML、原始 URL 或完整消息模型字段。

更关键的是：`fetchTargetViewContent()` 虽先构造字段 Bundle，但当文字模式 bit 1 开启时，返回的是普通无障碍内容，未返回该字段 Bundle。这个控制流已从 APK 原始 DEX 交叉确认。它与此前传送门仅收到13/15字符短文本的日志相符；没有源端运行时逐分支证据，不能断言每次失败都命中同一分支。

未找到能从传送门直接重新请求“原始消息卡片 URL”的有效接口。只增加传送门端 URL 解析规则，无法还原已经丢失或未采集的地址。现有截图卡片问题仍未确认解决。

## 样本与入口

| 属性 | 当前样本 |
|---|---|
| 文件 | 用户提供 `F:/qq/MobileFile/ContentCatcherOS3_1.apk` |
| 大小 | 152829 字节 |
| SHA-256 | `EB05162DEE793A6741D2F8224AE9CA59854C1AAB11AAEFB5AC1D2F369E31EE6F` |
| 包名 | `com.miui.contentcatcher` |
| 版本 | `4.1.4 / 320260119`，不同于传送门自身 `4.1.3 / 40013` |
| 身份声明 | `android.uid.system`；目标 SDK35，最低SDK29 |
| 签名 | 验签通过；证书SHA-256 `c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025`，与已有宿主样本证书一致 |
| 构成 | 单个 classes.dex 288740 字节；无 lib/*.so；7份配置资产 |
| 反编译 | JADX 导出成功，102个处理类型，无报告错误 |

入口为 `ContentCatcherApp.onCreate()` 注册 `miui.contentcatcher.ContentCatcherService` Binder 服务，另有 `StartContentCatcherService`。`Interceptor` 与 `ViewContentFetcher` 是原生视图采集链路。

E-imports 等价依赖锚点：Android `View/AccessibilityNodeInfo/TextView/Bundle/ServiceManager`、系统框架 `miui.contentcatcher.IContentCatcherService`、`IViewContentFetcher`、SDK结果/Token/PageConfig/监听回调。SDK接口定义不在此 APK 内；采集实现本身在 Java/DEX 内，不需要 native 逆向。`l/c.java` 从本机同一内容抓取 APK 的包上下文 ClassLoader 加载 ViewContentFetcher，未发现远程DEX加载链。

## 取数与接口

以下源码路径相对于 `analysis/ContentCatcherOS3_1/jadx/sources/`。

- `com/miui/catcherpatch/ViewContentFetcher.java:82-110`：无障碍 text 或 contentDescription → `toString()` → `putString("viewContent", ...)`。
- 同文件 `:218-253`：微信默认读取 `mText` 或公开 CharSequence 字段，再转 String；图像返回 Bitmap。
- `com/miui/contentcatcher/interceptor/catcher/NativeTextCatcher.java:47-81`：`TextView.getText().toString()`。
- `com/miui/catcherpatch/ViewContentFetcher.java:115-200`：反射字段结果和文字模式回退。DEX证据保存在 `analysis/ContentCatcherOS3_1/dex/fetchTargetViewContent.smali:391-403`；bit1关闭时另一分支才返回原字段包（`:427`）。
- `com/miui/contentcatcher/service/config/a.java:468-482`：把 ViewConfig 规则放入 `reflectioninfo`。
- `com/miui/contentcatcher/Interceptor.java:1393-1397`：将规则传入采集器。内置 `config_view.json` 只有微信版本1300/1320的 MMNeatTextView.mText、朋友圈旧字段链和图片配置；用户运行时的私有/云更新配置尚未取得，不能把内置规则等同于手机当前全部规则。

| 接口 | 实际行为 | 对本问题的意义 |
|---|---|---|
| getPageConfig(Token) | 查询源包、Activity、版本对应的配置 | 能看配置，不能获得跨进程 View/消息对象 |
| registerContentListener | 注册已有结果监听 | 接收已采集内容 |
| updateClientConfig | 切换启用状态 | 没有“全文/原始卡片链接”参数 |
| decorateContent / updateContent | 非空参数在 Interceptor 中直接返回；空参数只标记刷新 | 不是补取字段接口；DEX已复核 |
| updateConfig(String[]) | 先清空全局 feature 规则再加载 | 不能当作局部安全开关调用；也不直接更新独立 ViewConfig |
| reservedFunction | 返回 null | 没有可用扩展采集功能 |

配置管理与注册监听等调用由 `A/a.java` 检查 SET_ACTIVITY_WATCHER；APK声明权限不等于这次已执行或验证所有管理操作。本次未发起上述更新或远程请求。

## 传送门内能做的窄修复

系统 DataHub `z/a.java:55` 实际使用 `ClientCatcherResult.getResults()`。模块此前只扫描 `text_pick`、`image_pick` 和 propertyMap，可能漏掉同一回调内其他 job 已经回传的字符串。

0.1.3 在现有文字/图片回调中补读可选 getResults 返回的 Map，通过原有有界解析器检查；方法缺失或抛错时保留原路径，继续排除缩略图地址、去重并最多8条链接。没有改变路由、UI、手势或作用域。

这只解决“已回传但漏读另一个结果容器”的代码覆盖缺口。没有证据表明用户失败的卡片在其他 job 内实际包含 URL，因此不能把这个修复表述为已解决截图问题。

## Evidence → Finding → Path

| E-id | source_ref | repro_command | content_hash |
|---|---|---|---|
| E-apk | 用户原 APK、分析元数据 | `python tools/verify_contentcatcher.py <apk> analysis/ContentCatcherOS3_1 --output artifacts/contentcatcher-analysis.json` | 上述样本SHA-256 |
| E-loss | ViewContentFetcher、NativeTextCatcher | 读取上述行号 | 同上 |
| E-branch | `analysis/ContentCatcherOS3_1/dex/fetchTargetViewContent.smali:391-427` | apkanalyzer dex code，类/方法见文件头 | 同上 |
| E-ipc | `ContentCatcherService.java:290-328,424-458`；`Interceptor.java:1546-1553` | 同上；updateContent.smali另存 | 同上 |
| E-config | APK assets/config/config_view.json | 从APK ZIP读取JSON | 同上 |
| E-all | `z/a.java:55`；`HostAllResultsTest` | Gradle `testDebugUnitTest --tests ...HostAllResultsTest` | n/a，合成容器回归测试 |
| E-red | `analysis/ContentCatcherOS3_1/regression-red/HostAllResultsTest.xml` | 改动前新增测试，1测试1失败 | n/a |
| E-green | `analysis/ContentCatcherOS3_1/regression-green/HostAllResultsTest.xml` | 改动后同命令，8项通过 | n/a |

| F-id | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F-upstream | n/a_re | E-loss,E-branch | high | 系统采集源端 | 结构可能在Binder前已丢失 |
| F-no-refetch | n/a_re | E-ipc,E-config | high | 现有服务接口 | 未找到可靠的完整卡片URL补取入口 |
| F-map-gap | n/a_re | E-all,E-red,E-green | high | 模块capture() | 已修复getResults已有容器漏读；截图效果未证实 |

P-1，`path_type=callflow`：源应用视图 → 系统自带Interceptor/ViewContentFetcher → 文本String或Bitmap → ContentCatcherService/DataHub → 传送门SDK结果 → 模块链接解析 → 有URL才生成应用流转卡片。

## 验证与边界

系统 APK完成清单/签名/JADX/9个真实DEX方法定义与两个关键方法的汇编检查；结果见 `artifacts/contentcatcher-analysis.json`。未安装/修改此系统 APK，未改全局配置，未修改微信。

模块本地全套119项测试通过，0失败/错误/跳过；其中getResults新增8项。Lint0错误、12警告；传送门静态契约38项通过。候选为0.1.3-dev / 4、177593字节，Debug v2签名与ZIP对齐通过，SHA-256为 `395C95F76DAE0F15100BA56C0B98480EA0F531393258674F1289A77979720F5E`；详情见 `artifacts/validation-0.1.3.json`。未进行此候选真机Hook、冰箱/Root解冻、目标跳转或失败卡片验收。

继续完整解决无链接卡片，需要改变采集源端或找到来源应用公开的可靠链接接口。目前仅传送门进程中的后处理没有经过验证的恢复方案；系统规则是否能在保持既定范围的前提下安全扩展，仍需单独设计，不能直接覆盖全局配置。

Timeline：收到系统APK → 成功反编译 → 对照最新日志查源端字符串化 → DEX复核文字模式分支/装饰回调 → 发现getResults漏读 → 红测确认 → 窄修复与8项绿测 → 全套候选验证。系统采集边界已明确，用户原始卡片问题仍未宣称解决。
