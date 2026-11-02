# 0.14.1 / 26

2026-11-02，Asia/Shanghai。按用户要求启用 R8，并递增版本；界面和识图功能沿用 0.14.0。

Release 使用 `minifyEnabled true`、`proguard-android-optimize.txt` 和精确自定义保留规则。`TaplusModule` 由 `META-INF/xposed/java_init.list` 加载，保留类名、公共构造与 API 102 回调；`PrivilegedThawService` 是非 Manifest 的 Shizuku UserService，保留类名及两个公共构造器。其他 Manifest 组件由 AGP 自动生成规则保留；内部代码允许改名、合并和裁剪，未保留整个模块包。

| 验证 | 结果 |
| --- | --- |
| Release 构建 | `minifyReleaseWithR8` 与 `assembleRelease` 成功；R8 9.1.31，生成 mapping、seeds、usage 和最终配置 |
| JVM／Robolectric 定向回归 | 195 项，0 失败／错误／跳过；包含识图、配置、运行时与 Shizuku 相关源代码测试 |
| 混淆后 APK 静态检查 | `java_init.list` 与实际 DEX 入口一致，三个 API 102 回调和构造器签名保留；Shizuku 两个公共构造器与 Binder `onTransact` 保留；12 个 Manifest 组件构造入口存在 |
| 裁剪与混淆证据 | 内部类实际改名；非空 usage 输出；最终 DEX 共 2,838 个类，其中 2,695 个含成员定义。完整记录见验证 JSON |
| Lint | 0 错误、30 警告 |
| 签名 | APK v2 通过，长期证书 SHA-256 `2f3c681d676d8f763b191dea0714c399e21bbaa0e948f25289ebfbcf41d1aabd` |
| 对齐与内容 | ZIP 16 KiB、四个原生库 ELF PT_LOAD 对齐通过；API 102、作用域、解析脚本和规则资产核对通过；未打包 API 或测试框架类 |
| 体积 | 3,135,765 字节；0.14.0 为 22,485,638 字节，减少 86.05% |
| APK SHA-256 | `41efe8e53c942149f5f4003d7e0302fb0c46497b7595fd4fee379d10eefe8423` |

安装包：`artifacts/TaplusBridge-0.14.1-release.apk`。本次配套映射与 R8 配置已归档到 `artifacts/r8-0.14.1/`，产物与检查清单为 `artifacts/r8-validation-0.14.1.json`，构建与测试日志分别为 `artifacts/r8-release-0.14.1.txt`、`artifacts/r8-targeted-0.14.1.txt`。

定向 JVM 测试验证的是未混淆源代码行为；最终混淆产物另做 DEX／入口静态检查。这两者不证明 LSPosed、Shizuku 或 Google 服务在手机上已运行成功。没有连接手机，未运行全量测试或真机混淆运行验收。

覆盖安装后打开模块同步配置并重启，确保宿主 Hook、系统运行时与模块工作进程使用相同版本。历史验证脚本若要求内部原名存在，不应用整包 keep 迁就；本轮混淆校验使用 mapping 与外部入口契约。
