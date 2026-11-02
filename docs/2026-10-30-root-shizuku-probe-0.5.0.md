# 0.5.0：Root / Shizuku 与卡片探测修复

日期：2026-10-30（Asia/Shanghai）。范围与授权见 [scope.md](scope.md)。

## 交付与升级

本地候选 `artifacts/TaplusBridge-0.5.0-dev.apk`，versionName `0.5.0-dev`、versionCode `10`，28,639,426 字节。

SHA-256：`1D5DB05787714510248D3059B1E964D1A147463A69E39018DC130004495F64AF`。

解冻授权只保留 ROOT / SHIZUKU，旧 NONE / ICEBOX 迁为 ROOT；保留新规则、来源条件、目标开关和功能开关。冰箱 SDK 权限、Receiver、后端和授权入口删除。首页最近状态、更多中的识别诊断/取消/刷新/使用说明入口移除；只保留 LSPosed 日志入口和必要的授权/版本信息。打开设置或切换渠道不会自动请求权限。

## 日志证据与判断

输入归档：`F:/qq/MobileFile/LSPosed_20261030_081407.zip`，SHA-256 `83bd5e04a19bfec09f9181caafa77896f52ace68badc657aef6fb310741b57ae`。只读取相关日志，不整体提取、不执行归档内容，不持久保存正文、完整网址、坐标或 nonce。

| 证据 | 定位 | 观察 |
|---|---|---|
| E-001 | `artifacts/lsp-20261030-081407-summary.json` / metadata | 0.4.0-dev/9 宿主 Hook 已安装；2026-10-29 20:28 系统 Hook 与命令通道已就绪 |
| E-002 | modules 日志第6788行，2026-10-30 07:53:58.853 | `physical_state / touch_unknown`；旧实现随后持续禁止输入 |
| E-003 | full.log 第119271、121647、122736行，08:13:40、51、58 | 三次 `auto_failed / geometry_unavailable`，没有进入 READY/输入阶段 |
| E-004 | 原4.1.3反编译 `TaplusMainFloatView.initWindow` / `artifacts/geometry-red-0.5.0.txt` | 原宿主全屏主窗口 TYPE_PHONE、alpha=1；真实窗口配置回归在旧代码报 `unsupported_covering_window` |
| E-005 | `artifacts/junit-0.5.0/TEST-*.xml` 与 `python-tests-0.5.0.txt` | 当前完整本地回归 403 + 19 项通过，详见验证记录 |

F-001：日志证明失败主要发生在探测前，不能把它归为“只支持哔哩哔哩”。12 次文本长按没有可见 HTTP token，没有 resolve 阶段；本归档不能证明具体短链域名缺失。未凭标题猜目标或随意补一批域名。

F-002：原宿主全屏主窗被旧窗口策略拒绝，是已用原配置复现的适配缺口。日志将所有准备错误合并为 geometry_unavailable，不能断定三次实际失败全部都是该子类型。新版修复该配置，并把细分原因写入日志，以便区分其他准备失败。

F-003：旧触摸状态会把单个可归属设备的异常变成全局永久不可用。日志没有记录触发它的具体原始事件，不能宣称已证明某一个坏时间戳。状态回归先运行15项、3失败，修复后18项通过；未知设备读取失败仍保守停止，不把等待超时当作真实手势抬起。

Path：交付修复候选，安装并重启使 system_server 加载新实现；复现非B站卡片后以新日志验证真实失败子类型及落点。本地通过不等于所有应用、卡片或 ROM 都已在用户手机成功。

## 实现

窗口探测只处理两个已核对的传送门自有窗口。覆盖原点击点时临时设置 alpha=0 与 NOT_TOUCHABLE，结束、取消或异常时恢复原 alpha/flags，不改可信窗口标志，不放行未知覆盖窗口。坐标新鲜度、来源UID/用户/Activity、默认屏、真实UP和目标窗口检查保持。此透明化仅作用于传送门浮窗，不通过打开原网页后再隐藏来实现无感。

触摸异常按实际设备隔离，保留其他活动设备；其他设备UP、旧流UP不能恢复，须该设备新的完整DOWN→UP。提前忽略 mouse、hover、button动作，保留合成0/-1设备与downTime过滤。无法归属设备的读取异常、设备数溢出仍停止输入。日志只有固定原因和异常类别。

Shizuku 使用官方 api/provider 13.1.5 与独立非daemon UserService。服务接口只接受有效目标包、当前Android用户和请求ID，执行固定启用/取消隐藏操作；不接受脚本/网址，不跨用户，不自动回退Root。绑定2.5秒、RPC12秒、命令10秒超时；取消时发oneway cancel、解绑并销毁进程，TransitActivity保留截止/settled保护，拒绝迟到打开。

Shizuku需先启动13或更新版本并显式授权。ADB身份受系统权限限制，ROM拒绝取消隐藏时会报告失败，不能当作完整Root权限。已启用目标直接打开，不需要解冻命令。真实服务授权与包状态恢复尚未在手机验证。

所有AIDL sourceSet暂存至ASCII构建目录，避免Windows中文路径被AIDL写入非UTF-8注释或依赖文件；源码留在用户工作目录。

## 本地验证

- 45个测试套件，403项，0失败、0错误、0跳过；包含Root/Shizuku渠道、有限服务命令、拒绝恶意包/跨用户、取消/超时/协议错误、设置迁移、浮窗恢复、连续触摸和Miuix UI交互。
- Python日志工具19项通过；新阶段、细分窗口原因、跨日启动与脱敏字段均有回归。
- lint 0错误、18警告；保留同步配置、内部系统反射、导出Provider、目标SDK与本地化等已审阅限制。
- 宿主58个静态成员契约通过；API102、传送门/system作用域、不打包libxposed/旧Xposed类已从最终APK复核。
- v2签名沿用Android Debug证书 `4e06d06e242802a8b8e77f696079ddd78e3d615d0cf73b781f3a187abb51f980`，ZIP与四ABI原生库16KiB对齐通过。
- 8张实际Miuix本地渲染图，目录 `artifacts/ui-0.5.0/`，已检查Root/Shizuku授权和无诊断页面。
- `adb devices -l` 无连接设备；未安装手机、未验证新版Hook、真实目标落点、零闪页、Root/Shizuku解冻、实际在线服务响应；未远程发布，未改动IceBridge，非长期密钥正式版。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --console=plain --max-workers=1 '-Dorg.gradle.jvmargs=-Xms32m -Xmx1024m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
python -m unittest discover -s tools -p 'test_*.py'
python .\tools\verify_host.py '..\传送门_4.1.3.apk' --output .\artifacts\host-contract-0.5.0.json
```

完整记录：`artifacts/validation-0.5.0.json`，APK哈希：`artifacts/SHA256SUMS-0.5.0.txt`。
