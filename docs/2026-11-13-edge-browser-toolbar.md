# 0.15.46：默认浏览器、翻译图标与原生工具条

日期：2026-11-13（Asia/Shanghai）。本地测试候选 **0.15.46 / 78**，包含此前0.15.44、0.15.45的通用取链修复。未安装到手机，未提交或发布远端。

## 安装与使用

1. 覆盖安装 `artifacts/edge-browser-toolbar-20261113/TaplusBridge-0.15.46-release.apk`，保持传送门、系统框架作用域并重启手机。
2. 模块「识别」页打开「默认浏览器」，选择应用并保存。未指定时沿用原来的系统默认/候选选择逻辑；指定目标失效时提示重新选择。
3. 同页「替换翻译」选择本机翻译应用并保存。长按识别的翻译卡会显示该 App 图标；AI模式显示模块图标，原生模式保留原图。
4. **接管来源 App 的文字工具条，还需在 LSPosed 中额外勾选该来源 App**，例如从酷安选词就勾选酷安，而不是只勾选目标浏览器。保持模块来源名单允许该 App，重启来源 App。前两项宿主图标与打开目标的替换不需要给来源 App 加作用域。

工具条按用户选择保留系统外观、全选、复制、问小爱等原操作。可识别的浏览器/翻译项使用指定 App；不存在对应项时，可按有效选区增加指定浏览器/APP翻译入口。浏览器仅处理完整HTTP(S)地址、唯一有效URLSpan或现有网页动作中的真实URL；不猜普通词，也不执行未知ClickableSpan。来源工具条的翻译接管本轮针对 APP 模式，NATIVE/AI来源菜单保持原样。

来源接管仅覆盖**原生、非编辑、非密码、非secure TextView的浮动ActionMode**。WebView、Compose、自绘菜单或自定义选区不宣称已覆盖。实现依赖平台 `MenuItemImpl.invoke`；安装该拦截失败时保留原条，不能仅换图标却继续执行旧动作。原生钩子是否安装可在来源进程模块日志查找 `Native source selection hook installed/unavailable`。

## 边缘拖动：已修问题与证据边界

测试归档：`F:/qq/MobileFile/LSPosed_20261113_115541.zip`，SHA-256 `531c240a29e595c8ec3690619c27635221b92ca7acb73dc72d9025058f66dc02`。系统与宿主均加载0.15.45 / 77；宿主为5.1.5 / 50015。用户明确指屏幕四边。

| 证据 | 发现 | 修改/限制 |
| --- | --- | --- |
| `EdgeDragTarget`旧版固定48dp门槛；新增四边红测失败 | 起点离目标边不足48dp时，朝近边拖到底仍可能不能进入接触状态 | 按可用行程缩短门槛，保留8dp常规防抖；空间不足8dp时需真正到边；零位移不触发。短行程触边也记为dragging |
| 新modules日志899/939/968/1397及902–906、942–946、971–975、1400–1404 | 四轮失败均已向左释放，随后补点超时，再出现import_failed/IOException | 不是这四轮没判出方向；起点距左边48–96dp，不能把它们认定为“48dp向外不可达” |
| 同日志1162–1171、1297–1306 | 两轮成功补点后迅速HTTP captured并导入 | 原流程存在成功链路；失败原因须进一步区分 |
| `full.log`四失败均有三个源PopupWindow，且模块有assist window_changed；成功两轮无此变化 | 存在相关性，但当前窗口代码已允许同源安全弹窗 | 不据此放宽安全窗口校验；没有证据唯一判定是空内容、桥接拒绝还是窗口复验拒绝 |

新增诊断不输出正文、URL、token或原始坐标：`import_payload`只记录text/image/link存在性；`import_declined`保留固定桥接拒绝原因；`import_failure_detail`只保留IOException白名单；`consume_rejected`区分无效手势与固定窗口拒绝码。旧import_failed事件保留，详情使用独立stage以免双计失败数。

**本地修复了短行程数学缺陷，没有把原归档四次取链超时宣称为已解决。** 若新包仍出现同类情况，应使用新增原因码继续实机定位。

## 浏览器、图标与工具条实现

- 新增独立默认浏览器存储与Miuix选择对话框。保存失败恢复旧值；所有实例共享锁，Provider不能在失败回滚前读到未保存草稿。设置可清除、取消，目标卸载时仍保留选择以便明确提示。
- 拖动浏览器动作优先采用指定浏览器；普通网页识别未匹配规则时，预览与真实启动共用指定目标。已匹配域名/协议规则及非浏览器原生入口保持原有优先级。指定目标失效不静默启动另一浏览器。
- 新网页兜底按URL保留独立卡片，不将多个入口合成一个无法打开的无规则分组；已有规则分组保留。浏览器兜底与未匹配空结果不缓存，配置更改后立即重绑。
- 认证宿主查询 `action_target` 仅接受kind及可选合法source；只返回configured/available、target/title/icon。翻译图标与点击应用相同来源策略；APP/AI/NATIVE分别处理，并复用SourceIcon防异步回写。
- `selection_actions` 是独立只读来源查询，按UID、用户、来源包与名单认证，只返回非秘密目标信息。翻译component/action/title/icon从同一次设置快照生成，不导出API Key、接口或提示词。
- 来源View的Callback2继续执行原生命周期。已绑定菜单项在原listener之前接管；配置过期在后台刷新，存活模式才重绘或完成点击。延迟完成前核对选区边界、文字、URL与绑定身份，防止同名选词换位置后执行旧操作。

## 验证和产物

全量命令返回 `BUILD SUCCESSFUL in 5m 9s`：**144套件、1,527项Android测试，0失败/错误/跳过；Python 39+32=71项通过；Release lint 0错误、31警告**。新增警告包括同步保存偏好及平台私有菜单API，未将warning说成实机兼容性证明。

红绿测试覆盖四边/密度/防抖/回原点、导入原因白名单、默认浏览器优先与失效、真实内存变化后的保存失败及并发读取、规则优先、多URL单卡、图标防回写与来源策略、来源认证、原菜单listener、配置过期/prepare、同名选区移动及目标设置快照一致性。浅色与小屏深色大字体的真实Miuix渲染已检查，按钮可见。

| 产物 | 校验 |
| --- | --- |
| `artifacts/edge-browser-toolbar-20261113/TaplusBridge-0.15.46-release.apk` | 3,611,649字节；versionName=0.15.46，versionCode=78 |
| APK SHA-256 | `44828F53595404843186E0B2CC34E65EE8395BAD74754905B764DE890F3FE404` |
| 签名 | APK v2通过，证书与原长期证书一致：`2f3c681d676d8f763b191dea0714c399e21bbaa0e948f25289ebfbcf41d1aabd` |
| R8与对齐 | 保存mapping ZIP；4字节ZIP、16KiB原生库ZIP及ELF PT_LOAD对齐通过 |
| 打包代码 | 收集器确认默认浏览器、目标图标和来源工具条代码在最终DEX或有效R8内联方法中保留 |
| 记录 | 同目录`validation-0.15.46.json`、`SHA256SUMS-0.15.46.txt`、JUnit、lint、签名与构建日志 |

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --offline --console=plain --max-workers=1 --no-daemon '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8'
py -m unittest discover -s tools -p 'test_*.py' -v
py -m unittest discover -s tools/tests -p 'test_*.py' -v
```

ADB未连接设备；未证明0.15.46在用户手机上的实际点击、工具条注入、图标显示或边缘取链结果。图标和动作的本地测试、R8打包检查均不能代替实机验收。
