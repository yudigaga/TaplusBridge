# 酷安“查看链接”取链与流转排查

分析日期：2026-11-06，Asia/Shanghai。范围为用户提供的两份LSPosed日志、本地传送门4.1.3/5.1.5反编译结果和TaplusBridge项目代码。仅静态/本地调试，未连接手机、未访问网盘分享内容；不新增来源应用Hook。

## 观察与结论

| 证据 | 原始位置与脱敏观察 | 结论 |
|---|---|---|
| E-01 | `LSPosed_20261106_165843.zip` SHA256 `0a8803afdbe8bd29bf69b7fb72ddc7bc6faf237756befe03bf00c81555561343`；旧日志system_server=66、新配置/宿主=67 | 旧归档确有升级未重启造成的系统采集门控停用，不能据此证明重启后仍失败 |
| E-02 | `LSPosed_20261106_171714.zip` SHA256 `b6d44b6025c0d6f52b5adb63f35e86ea65c0a2835a122fa5a8193976b714e4f6`；系统和宿主均67，17:16:09配置confirmed | 新日志已排除旧版本混用，继续失败需另找根因 |
| E-03 | 新归档`full.log` 41653/41699/41713–41715：17:15:08 HTTP captured→submitted→host_received→宿主`sIsTaskFinished=false`→host_submitted；17:15:14–15重复 | 系统已抓到链接；宿主忙时callback直接返回，模块只记录“已调用”，未证链接真正进入识别任务 |
| E-04 | 本地5.1.5 `TextContentExtensionService.java`102–123、4.1.3 103–124：先设置mCatcherResult，非observe事件且`sIsTaskFinished=false`直接return | F-01：已有原生“查看链接”文字任务时，晚到补点链接不能作为新pick直接重送。应安全补入原会话 |
| E-05 | 新归档`full.log`29245/29247、66563/66566：酷安第一跳为`www.coolapk.com/link`，`url`解码后目标host与用户提供的数字子域一致，path一致 | F-02：即使补点内容成功送达，还需解释显式跳转包装中的真实目标；不用联网跟随或猜按钮 |
| E-06 | 新归档17:14:21/17:16:36均clipboard来源1条，用户明确来自手动进入后复制；17:14:23.711 `unable to resolve Intent`目标`com.mfcloudcalculate.networkdisk`、结果-91 | 手动复制成功不能算长按识别成功。目标已匹配，但当前应用无法接收该HTTP启动Intent，是后续独立边界 |
| E-07 | `ScopedRuleResolver.matchingRules()`比较精确host或以`.`+rule.host结尾；精确回归验证`share.123pan.cn`匹配`1817601681.share.123pan.cn`并拒绝伪后缀 | 规则域名填写正确，无需改成带数字的具体子域名；不能把匹配成功当启动成功 |

BakaSU的GitHub、Telegram补点记录属于其他操作，不用来证明酷安成功。原始日志对补点URL不输出正文，不能直接证明两次captured里具体是哪一个网址；包装结构由同一页面手动点击日志佐证。

证据复现：在项目根目录运行`python tools/analyze_lsp_archive.py F:/qq/MobileFile/LSPosed_20261106_171714.zip --date 2026-11-06 --after 17:14:00`；完整日志行号需从归档内`full.log`读取，避免在报告或控制台输出分享完整URL及token。脱敏索引为`.local/evidence/20261106_171714_focused_timeline.json`和`artifacts/diagnostics-20261106-171714/summary.json`。

## 调用路径与修复要求

正常路径：实际长按→原生标签任务→系统补点→真实启动URL→同会话补链接→域名匹配→目标应用接收。E-03/E-04对应断点在“同会话补链接”，E-05对应中转目标解析，E-06对应最终应用接收。

F-01修复限定相同来源、配置身份、物理手势、服务实例、当前result对象和epoch；不强行修改宿主busy标记，不取消原任务。原文字和已识别结果继续保留，链接由现有probe来源及推荐发布链补充并供拖动复用。失效/新会话结果不得串入。

F-02只在明确跳转路径（link、redirect、go、jump、out）和单一目标字段时进行有界离线解包；严格UTF8百分号解码，最多两层，普通页面参数不改，多目标拒绝。规范化后再判唯一性，防止包装URL与同一inner被误认为两个目标；保持最终URL长度、Intent选择器/ClipData/授权限制。

F-03最终应用接收不属于域名匹配问题。新日志仅证明本次`ACTION_VIEW + 目标包 + 该网址`未解析到入口，不能泛化成目标应用完全不支持链接，也不能仅凭该行区分Intent过滤器与包/组件状态。模块继续原样传递真实目标URL，不伪造123盘私有协议、不静默启动桌面来冒充链接已打开。有界检查项目目录与用户MobileFile共94个APK的Manifest，未找到`com.mfcloudcalculate.networkdisk`，因此继续核对接收方式需要用户当前安装的目标APK或设备只读PM解析证据；此段尚未修复。

最终接纳路径只向仍忙碌的同一原生任务补链。已完成、取消、替换或配置变更的原任务会直接丢弃晚到结果，不再重新调用宿主callback来重开页面；没有匹配原生任务的新采集保留原worker回调。这同时避免了跨线程旧回调覆盖新任务，并保持宿主隐私检查要求的工作线程边界。

本地实现与验证结果见对应版本发布记录。报告不将本地测试或静态签名核对称作设备验收。
