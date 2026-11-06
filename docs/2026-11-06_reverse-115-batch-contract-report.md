# 115 多行磁力与 ed2k 接收合同

更新：0.15.39再次核对发现magnet/ed2k的单条协议接收分支会完整传递data到同一多行编辑页，可用经过逐条验证的专用多行载荷直接接收批次。本报告下方的主页准备限制只适用于旧lx/EventBus入口；新实现不再使用该入口。详见[0.15.39直达说明](2026-11-06-release-0.15.39-direct-batch.md)。

分析日期：2026-11-06，Asia/Shanghai。普通授权兼容性逆向，flavor=null。范围仅为用户已提供 115生活 37.2.6 / 1395 APK 的静态入口和多行数据链；未请求下载、登录或分享接口，未安装/启动应用。样本 SHA256 `c340b921b7b430997ea29465075b9b60d2333638e4609a0d46d2eb907f7a9d19`。工具为本机 JADX 1.5.6，定向反编译时 `JAVA_OPTS=-Xmx1g`。

该版本存在一次接收完整多行离线文本的 `oof.disk://lx/` 入口，进入应用确认弹窗后预填离线添加页，点击提交才由客户端解析成多条任务。该入口依赖已登录的 MainBossActivity 实例订阅普通 EventBus 事件；直接冷启动 SchemeMainActivity 不会主动创建 MainBossActivity，因此不能称作冷启动可靠。模块应提供明确准备步骤，不能定时启动主页并宣称批量内容已接收。

## 证据

| E-id | source_ref | 观察 |
| --- | --- | --- |
| E-01 | `analysis/cloud115-37.2.6/entry-filters.json` 与原 aapt Manifest1853/1870节点 | DiskOfflineTaskAddActivity 无 intent-filter 或 exported 声明；target SDK30 下非外部入口。SchemeMainActivity 有 VIEW、DEFAULT/BROWSABLE、oof.disk/oof.disk_life，无组件接收权限 |
| E-02 | `analysis/cloud115-37.2.6/SchemeMainActivity.java:599`–614 | 读取 `oof.disk://lx/` 后整个后缀，URLDecoder UTF8 一次解码，调用 event.k.c(content,null)。仅末尾全匹配`(.*)/[0-9]+`时拆 union code；包含换行的批量内容不匹配该启发式 |
| E-03 | `analysis/cloud115-batch-37.2.6/event-k.java:18` | c(content,code)调用 EventBus.i(new k)，两个字段原样保存，无分割和截断 |
| E-04 | `analysis/cloud115-37.2.6/MainBossActivity.java:2048`–2059、1197–1198 | 订阅 event.k，显示 LxMagnetDialog，确认回调把两个字符串原样传给 DiskOfflineTaskAddActivity.launchWithContent |
| E-05 | `analysis/cloud115-batch-37.2.6/LxMagnetDialog.java:28`–62 | 构造器保存完整文本，TextView显示原文，确认执行原callback，不做URL单选或截断 |
| E-06 | `analysis/cloud115-37.2.6/DiskOfflineTaskAddActivity.java:254`–272、377–380、556–570 | launchWithContent走客户端自己的check/agreement链，Intent content保存完整文本，onCreate预填EditText，不自动提交 |
| E-07 | 同类209–221；`analysis/cloud115-batch-37.2.6/offline-presenter-a.java:27`–41 | 提交时Matcher逐行收集List；size>1走批量请求构造，非循环启动单条Activity。网络仍由用户客户端控制 |
| E-08 | `analysis/cloud115-batch-37.2.6/EventBus-c.java:367`–405；MainBossActivity1490 | i是普通即时post，l才是sticky；MainBoss注册n为非sticky。未创建MainBoss时事件无后续补发 |

## 发现与路径

F-01，evidence_ids=E-01…E-07，confidence=high，status=validated_static：外部可向规则选定的 `com.ylmf.androidclient` 一次发送 VIEW `oof.disk://lx/` 加 UTF8 百分号编码的多行文本。由实际安装版 PackageManager 解析入口；不直接启动未导出的 DiskOfflineTaskAddActivity，不使用 SEND。

P-01，path_type=callflow：规则逐条决定同一目标 → 用户确认完整批次 → 单个 lx Intent → SchemeMainActivity UTF8解码 → event.k → 已存在 MainBossActivity → LxMagnetDialog确认 → 离线添加页全文预填 → 用户提交 → 多行List处理。单条链接继续采用原 magnet/ed2k VIEW 路由。

F-02，evidence_ids=E-02,E-04,E-08，confidence=high，status=validated_static：冷进程仅启动 SchemeMainActivity 的情况下，普通事件可能无人接收。需要用户先打开115主界面并完成登录，再回模块继续批量添加。仅看进程存在不能证明 MainBoss订阅者存在。

P-02，path_type=callflow：冷进程 → SchemeMainActivity → event.k普通post → 无MainBoss订阅 → 无离线添加页。不能把 startActivity 没抛错当作已接收全部内容；没有真机证据证明后台订阅在全部启动状态可靠。

F-03，evidence_ids=E-01,E-06，confidence=high：未导出的离线Activity没有可从外部直接传 content 的合法入口。通用 SEND text 路由在前序报告中已确认进入发帖，不等价于离线添加。

## 复现方式与限制

以下命令仅输出源码，不执行客户端或网络任务：

```powershell
$env:JAVA_OPTS='-Xmx1g'
& 'C:\Users\95300\Tools\jadx\bin\jadx.bat' --no-res --single-class 'com.main.disk.file.uidisk.event.k' --single-class-output 'E:\vs\claude\.cloud115-contract\event-k.java' --log-level error 'E:\vs\claude\.cloud115-contract\input.apk'
& 'C:\Users\95300\Tools\jadx\bin\jadx.bat' --no-res --single-class 'com.main.disk.file.file.view.LxMagnetDialog' --single-class-output 'E:\vs\claude\.cloud115-contract\LxMagnetDialog.java' --log-level error 'E:\vs\claude\.cloud115-contract\input.apk'
```

同样方式可复现 EventBus 与 presenter。原始APK哈希及Manifest已记录于前序 [115接收合同](2026-11-06_reverse-115-link-contract-report.md)。本报告不声称实际批量任务已创建，也不提供自动确认下载或绕过登录/客户端协议检查的行为。
