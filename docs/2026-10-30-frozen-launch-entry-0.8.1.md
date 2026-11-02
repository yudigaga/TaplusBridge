# 0.8.1：本体冻结后解冻启动目标的入口修复

日期：2026-10-30（Asia/Shanghai）。范围与授权见[scope.md](scope.md)。输入为用户提供的`LSPosed_20261030_154137.zip`，仅将内容作为诊断数据，不执行归档中的内容。

## 结论

0.8.0把无结果启动的`requestCode==-1`当成系统独立通道的必需条件，但传送门4.1.3的`AppsUtils.startActivity`实际传入`resultTo=null, requestCode=0, profiler=null`。调用没有结果接收者，仍被0.8.0的外层过滤漏掉。系统内存启动票据没有被消费，标记Intent进入普通Activity解析，返回`-91`，因而根本没有开始目标解冻。

0.8.1根据是否存在结果接收者判断普通启动，不再单凭requestCode数值排除。真实结果接收者或Profiler请求保持原流程；调用包/UID、主用户、单次票据、超时、配置身份、前台与目标权限检查保留。源WebView探测策略没有因此放宽，修复只作用于模块自己的系统启动标记。

## 证据

归档SHA-256：`a2a27c1f3bd8d142c510c8ada9f514e42c39e656c5ee05b42ec45759463d8147`。未整体解压，不保存聊天正文、完整URL、nonce或坐标。

| 证据 | 定位 | 观察 |
|---|---|---|
| E-001 | `artifacts/lsp-20261030-154137-metadata.json` | 0.8.0-dev/13宿主与system模块已加载，15:38:00系统运行通道就绪；6次URL_FOUND、6次resolve成功和目标图标绑定 |
| E-002 | full.log 30437、34989、68616、73574、77306行 | 15:39:45.977、15:40:19.884、15:41:02.479、15:41:12.486、15:41:25.030，内部RUNTIME_OPEN标记进入普通START并返回-91 |
| E-003 | 原4.1.3 APK/JADX `utils/AppsUtils.java` 的startActivity | resultTo为null，requestCode为0；外层0.8.0只允许-1，没有运行到RuntimeServer.intercept |
| E-004 | `artifacts/frozen-launch-entry-red-0.8.1.txt` | 不改变旧判断语义，抽取实际Hook共用seam后，3项测试中宿主requestCode=0用例失败 |
| E-005 | `artifacts/frozen-launch-entry-green-0.8.1.txt` | 修复后24项入口及运行通道定向测试通过；包括真实PM状态模拟与目标Intent记录，目标启用、本体仍disabled |

早期系统初始化有3条NullPointerException，但随后运行通道已正常注册，点击又产生了系统票据标记。因此不能把启动早期的重试日志当成本次点击失败的根因。归档没有系统“目标启动已提交”的事件；0.8.1新增票据认领事件以区分入口与后续包管理步骤。

Finding → Path：日志里的匹配成功和普通START失败，与宿主真实参数及红回归一致，支持入口过滤错误的结论。交付修复候选后，需要在用户手机重新验证冻结状态下的目标解冻和内容落点，不能把本地PM模拟当作真实ROM包管理验收。

## 修改

- `RuntimeLaunchEntry`由实际ATMS Hook和测试共用，兼容没有resultTo的requestCode=0与-1；不吞真实结果或Profiler调用。
- 3项运行集成回归覆盖完整的“规则→系统票据→真实入口判断→PM启用目标→原链接交付”，以及真实结果请求不消费票据、普通或未授权启动不被接管。
- 新增固定的`runtime launch claimed`日志。归档工具记录漏接标记的系统START结果，只保留行号、时间、用户和数值结果码；不保留Intent、URI或extras。
- 不删除规则、不清配置、不改Root/Shizuku选择、不Hook微信/QQ、不修改IceBridge或系统全局启用策略。

## 更新步骤

先解冻模块本体，覆盖安装0.8.1后重启。打开本体“更多”，等待“系统已确认配置，可冻结本体”，再冻结本体重试。系统进程仍加载旧代码时，单纯覆盖APK无法替换当前Hook；无需清除应用数据。

最终APK、测试/lint/签名/对齐记录见`artifacts/validation-0.8.1.json`与`SHA256SUMS-0.8.1.txt`。

- `artifacts/TaplusBridge-0.8.1-dev.apk`，versionCode14，28,737,881字节。
- SHA-256：`BBFC22E74CC4605C9C5B1E027E50A92ABAC3C613F2F7915EBE6E1B17DDCAD21B`。
- 52套件478项本地测试通过，0失败/错误/跳过；Python诊断工具21项通过。
- lint0错误、21警告；原Android Debug证书v2签名和ZIP/原生库16KiB对齐通过。API102、传送门/system作用域与58项宿主静态契约已复核。
- 当前adb无连接设备，没有安装新版、真实ROM解冻启动、目标内容落点或冻结后重启验收；没有远程发布或长期密钥正式签名。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --console=plain --max-workers=1 '-Dorg.gradle.jvmargs=-Xms32m -Xmx1024m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
python -m unittest discover -s tools -p 'test_*.py'
```
