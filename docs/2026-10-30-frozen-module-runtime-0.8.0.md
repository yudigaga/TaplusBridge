# 0.8.0：模块本体冻结后的独立运行

日期：2026-10-30（Asia/Shanghai）。用户已确认冻结对象是“传送门增强”模块本体。范围与授权见[scope.md](scope.md)。本次保留规则列表选择应用＋域名/网址的主路径。

## 使用与前提

1. 保持本体解冻，安装0.8.0并重启，LSPosed模块启用且作用域为传送门＋system。
2. 打开本体“更多”，开启“冻结本体后继续运行”，等待“系统已确认配置，可冻结本体”。需要时点击“系统配置同步”。
3. 确认后再用冰箱、pm disable或隐藏冻结本体。独立处理不启用本体、不启动本体Provider或Activity，只恢复命中的目标包。
4. 修改设置或更新在线规则需先解冻本体，保存后等待新确认再冻结。本体冻结期间使用最后确认的规则，不自动联网更新。

支持范围为Android13+主用户0。冻结后的重启注入取决于LSPosed分支与ROM；卸载、在框架停用模块或取消作用域不属于本功能可弥补的冻结。系统暂停/卸载的目标不处理，多用户/工作资料不继承主用户配置。

## 发现与方案

旧版识别卡片解析、探测布防和解冻都依赖模块进程内的Provider/透明Activity。本体PM-disabled或隐藏后，已有Hook仍在宿主/系统中，也不能访问被禁用组件。`FrozenModuleRecognitionAndroidTest`原始用例在Provider不可用时只得到0张目标卡片，已跑红，记录在`artifacts/frozen-provider-red-0.8.0.txt`。

当前实现分成三部分：

- `RuntimePublisher`在本体可用时将完整有效来源规则、开关、用户及构建版本写入libxposed102框架远程偏好。单个快照有SHA-256身份，规则不包含正文、捕获URL、票据或备注。框架存储提交失败不会显示可冻结。
- `RuntimeServer`在system_server提供Binder通道，使用框架保存的快照规划、生成真实名称/图标、签发探测能力和单次内存流转票据。每笔事务检查实际Binder UID、用户和受信传送门版本；本体UID只有同步状态查询权，其他调用方不能读规则或启动目标。发现Binder句柄不等于获得调用权限。
- `BridgeTransport`在本体可用时保留原Provider和Root/Shizuku路径；确认本体不可用时使用系统通道，失败不回头尝试唤醒本体。冻结目标用只有随机token的内部标记，在已核对的ATMS公开入口消费，由系统工作线程恢复目标状态并启动，不创建模块Activity。

系统包操作只在SYSTEM_UID执行，拒绝模块本体和传送门作为解冻目标；不接受任意Shell、URI命令或跨用户目标。不处理suspended/卸载。启动前重复确认快照身份、期限、前台来源、用户0/默认屏和解锁状态；规则改动、关闭开关、前台切换或票据过期会取消打开。未知来源的剪贴板点击绑定点击时前台包，不能串用到其他页面。

探测通道继续遵守真实UP、来源UID、窗口与几何校验；系统自有捕获票据无需模块Provider。已布防后配置变化会在后续释放/注入检查中撤销资格。原网址只在当前内存事务回传宿主，系统票据容量16、20秒期限且一次消费。

同步确认同时要求：框架已保存当前快照、系统代码版本匹配、系统包管理方法可用、系统返回相同快照身份。时间经过本身不代表确认成功。配置变更或服务断开立即撤销旧确认，等待真实回执；无需把识别诊断界面加回。

## 证据与验证边界

| 证据 | 结果 | 支持范围 |
|---|---|---|
| E-001 `frozen-provider-red-0.8.0.txt` | 旧Provider依赖场景失败 | 本地可重复的0卡片缺陷 |
| E-002 FrozenModuleRecognitionAndroidTest | 本体Provider强制不可用，仍用真实规则生成目标卡片 | Hook共享实现与系统Binder路径；不是手机实测 |
| E-003 RuntimeServerAndroidTest（18项） | 真实PM身份验证、捕获一次消费、规则变更取消、过期、锁屏、资料用户、缺配置/旧版本/卸载和主体保护 | 本地Binder处理、状态与调度逻辑 |
| E-004 SystemPackageOpsAndroidTest（5项） | 真实Android PM状态模拟：启用/取消隐藏目标，本体disabled不变；取消、非system调用拒绝 | 本地包管理行为，最终启动器使用记录seam，未执行手机系统调用 |
| E-005 RuntimeSnapshotAndroidTest（7项） | 完整来源规则/开关、严格schema/用户、写入失败、身份更新、实际XML冷读后不依赖本体私有偏好 | 快照codec/存储事务；不证明实际框架daemon跨重启行为 |
| E-006 Miuix冻结开关/同步入口 | 未收到真实系统确认不展示“可冻结”；实际本地渲染 | 模块UI，不是用户手机截图 |

Finding → Path：上述证据支持旧进程依赖已移除及新运行路径在本地可执行；交付候选后仍需在真实设备完成“确认→冻结本体→长按卡片→打开冻结目标→检查本体仍disabled/hidden”，再验证冻结后重启的框架注入。若没有系统确认，不能把本地构建通过当作设备已支持。

## 最终候选

- `artifacts/TaplusBridge-0.8.0-dev.apk`，versionCode13，29,157,513字节。
- SHA-256：`2749C14A03AFB2FA4E53769764146659447D818C6CD13B9E9CA9ADC343FEA41A`。
- 51套件472项本地测试，0失败/错误/跳过；Python工具19项通过；lint0错误、21警告。
- v2开发证书延续`4e06d06e242802a8b8e77f696079ddd78e3d615d0cf73b781f3a187abb51f980`，ZIP与原生库16KiB对齐通过。API102、传送门/system作用域、58项宿主静态成员契约通过。
- libxposed service102正式依赖入包；libxposed API仍compileOnly，未打包API或旧Xposed类。新AIDL与所有变体继续暂存ASCII目录构建。
- 当前adb无连接设备，未安装、未验证真实LSPosed远程存储同步/冻结/重启/系统解冻/内容落点/零闪页；未远程发布、未长期密钥正式签名、未修改IceBridge。

机器记录：`artifacts/validation-0.8.0.json`。实际Miuix图：`artifacts/ui-0.8.0/`。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --console=plain --max-workers=1 '-Dorg.gradle.jvmargs=-Xms32m -Xmx1024m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
python -m unittest discover -s tools -p 'test_*.py'
```

实现参考本地IceBridge的系统独立运行拆分，仅作只读参考。配置共享采用官方libxposed service102的XposedService.getRemotePreferences与模块API102的getRemotePreferences，不直接读取冻结本体的私有XML，也不改系统全局启用状态策略。
