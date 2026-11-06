# 115 37.2.6 链接接收契约

分析日期：2026-11-06，Asia/Shanghai。普通 APK 静态逆向，`flavor = null`。工具为 JADX 1.5.6、Android SDK build-tools 36.0.0 aapt。范围见 [scope](../analysis/cloud115-37.2.6/scope.md)。

该版本已有标准磁力和 ed2k 接收入口。模块按用户规则选定 115 后，可以原样传递 `ACTION_VIEW` 的 data，无需私有协议转换。该版本没有 HTTP/HTTPS 的 Intent filter；通用文本分享入口会进入发帖页面，不能作为“在网盘打开链接”的等价替代。以上是静态入口契约，未做手机登录状态、Activity 启动或离线任务验收。

## 样本

| 属性 | 值 |
|---|---|
| 原文件 | `F:/qq/MobileFile/115生活_37.2.6.apk` |
| 大小 | 152,107,847 bytes |
| SHA256 | `c340b921b7b430997ea29465075b9b60d2333638e4609a0d46d2eb907f7a9d19` |
| 包名 | `com.ylmf.androidclient` |
| 版本 | 37.2.6 / 1395 |
| min / target SDK | 23 / 30 |

## Evidence

| E-id | source_ref | 观察 | content_hash |
|---|---|---|---|
| E-115-01 | `analysis/cloud115-37.2.6/entry-filters.json`，YunCloudSchemeMainActivity | filter 为 VIEW + DEFAULT + BROWSABLE，scheme 为 `magnet`、`ed2k`；组件未显式设置 exported，target SDK30；未设置组件接收权限 | `830614ed1f7bea2c327cac474f5b5b55d2b4502d805f2e4de4fdebf43e2110c2` |
| E-115-02 | `YunCloudSchemeMainActivity.java:4`；`SchemeMainActivity.java:535`、724–726 | 子类继承 SchemeMainActivity；从 Intent data 读取 scheme，遇 magnet/ed2k 时将完整 `data.toString()` 传给 `DiskOfflineTaskAddActivity.launchWithContentAndLock` | Scheme SHA256 `609a56853191c823ac0b343af8f638d723bc524bf5a7b1fdab08a01f1fc9d455` |
| E-115-03 | `DiskOfflineTaskAddActivity.java:256`、383–386、542起 | launch 方法将字符串放入 `content`，并打开离线添加页面；仍有应用自己的状态与锁检查 | n/a；内容来自已哈希样本的定向反编译 |
| E-115-04 | `entry-filters.json`，MainBossActivity；`MainBossActivity.java:1246`、1395；`y2.java:309`起 | MainBoss 注册 SEND/SEND_MULTIPLE + text/*；`EXTRA_TEXT` 最后进入 HomePostActivity 发帖流程 | y2 SHA256 `901945e1c591a4ed5dfd371b36febb1a8ba040853757a50eb7276c7865e63998` |
| E-115-05 | 全样本 `aapt dump xmltree`；`metadata.json` | HTTP(S) filter0，PROCESS_TEXT action0。SchemeMainActivity 自有 `oof.disk`/`oof.disk_life` 接收入口 | n/a；aapt 由已哈希样本生成 |
| E-115-06 | `SchemeMainActivity.java:615`–636 | `oof.disk://url/` 后缀做 URLDecoder，再交 WebBrowserActivity.setData；`oof.disk://openurl/` 后缀交 i6.E。前者是应用内浏览器入口，不等于网盘分享导入 | 同 E-115-02 |

复现命令在 PowerShell 中运行。aapt 使用 ASCII staging 文件是为避免其中文路径编码错误。

```powershell
Get-FileHash -LiteralPath 'F:\qq\MobileFile\115生活_37.2.6.apk' -Algorithm SHA256
& 'C:\Users\95300\AppData\Local\Android\Sdk\build-tools\36.0.0\aapt.exe' dump badging 'E:\vs\claude\.cloud115-contract\input.apk'
& 'C:\Users\95300\AppData\Local\Android\Sdk\build-tools\36.0.0\aapt.exe' dump xmltree 'E:\vs\claude\.cloud115-contract\input.apk' AndroidManifest.xml
$env:JAVA_OPTS='-Xmx1g'
& 'C:\Users\95300\Tools\jadx\bin\jadx.bat' --no-res --single-class 'com.main.disk.file.uidisk.SchemeMainActivity' --single-class-output 'E:\vs\claude\.cloud115-contract\SchemeMainActivity.java' --log-level error 'E:\vs\claude\.cloud115-contract\input.apk'
```

## Findings

| F-id | severity | evidence_ids | confidence | location | status | 结论 |
|---|---|---|---|---|---|---|
| F-115-01 | n/a_re | E-115-01、02、03 | high | YunCloudSchemeMainActivity → SchemeMainActivity.x | validated_static | 原样 VIEW 磁力/ed2k 是已注册并实现的接收契约；不需要写死 115，规则目标可用现有包限定 |
| F-115-02 | n/a_re | E-115-04 | high | MainBossActivity → y2.b | validated_static | SEND text/plain 进入发帖，不能自动作为链接打开的成功回退 |
| F-115-03 | n/a_re | E-115-05、06 | high | Manifest / SchemeMainActivity.x | validated_static | 无标准 HTTP(S) 接收；已证实的私有 url 入口用于内置浏览器，是否满足用户希望的分享导入需单独界定 |

`validated_static` 仅表示 Manifest 与反编译分支相互印证。省略 exported 属性的组件在 target30 下的系统默认导出行为、实际启用/挂起状态，仍需设备 PM 查询确认。

## Path

P-115-01，`path_type=callflow`：模块识别磁力/ed2k → 用户协议规则选中目标 `com.ylmf.androidclient` → `ACTION_VIEW`、data 保留原串、setPackage 约束目标 → Manifest 命中 YunCloudSchemeMainActivity（E-115-01）→ 继承 SchemeMainActivity.x，检查应用自身状态（E-115-02）→ 打开 DiskOfflineTaskAddActivity，content 保留原串（E-115-03）。

P-115-02，`path_type=callflow`：`ACTION_SEND text/plain` → MainBossActivity → y2.b → HomePostActivity（E-115-04）；该路径应避免被自动等同于网盘接收链接。

## Timeline 与验证边界

- 19:05，确认原样本哈希、包名版本与 Manifest；aapt 对中文路径失败，转 ASCII 只读副本后成功。
- 19:06–19:10，定向反编译 SchemeMainActivity、MainBossActivity、y2、DiskOfflineTaskAddActivity。一次未限堆并行 JVM 发生 Windows1455 内存错误，改为顺序 `-Xmx1g` 后目标类均成功输出。
- 完成：静态确认协议 filter、保留字符串调用链、SEND 行为与 HTTP(S) 缺失。
- 未执行：手机安装/启动、登录/锁状态、真实磁力或 ed2k 任务、网络请求、模块 Gradle 测试；未改生产代码。
