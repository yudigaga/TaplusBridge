# 0.15.40 / 72

- 修复保存设置后可能一直等待系统确认的问题。
- 修复同一磁力链接仅协议大小写不同时，批量添加误报规则失效的问题；批量分组复用规则快照。
- 截图 PNG 编码移到后台，交付前重新检查手势、配置、锁屏与窗口状态。
- Lens 缩略图独立排队，关闭或换图时取消旧任务，减少对下一轮识图准备的影响。
- 将测试源码和脱敏样例纳入版本管理，补齐 CI，并修正测试中的链接上限和异步列表等待。

覆盖安装后重启，再打开模块确认“系统已确认配置”。优化实现及前一轮完整测试见 [优化验证记录](2026-11-07-reliability-performance.md)。

## 本地构建验证

版本递增后执行配置快照、运行时服务、保存确认与作用域发布四组定向测试，共 102 项全部通过；Release lint 为 0 错误、37 项警告，签名 Release 构建通过。APK Manifest 已核对为 `0.15.40 / 72`，无开发版后缀。

- APK：`artifacts/TaplusBridge-0.15.40-release.apk`，3,578,753 bytes。
- SHA256：`74A18DCBB3B77B486C1BC5CB6ECC4B9C70B20093EA4771D00B9C51110CBC7B73`。
- 长期 v2 签名与既有证书一致；ZIP 4 字节、原生库 ZIP 16 KiB 及 4 个 ELF 原生库 16 KiB 对齐均通过。
- 同次 R8 映射：`artifacts/TaplusBridge-0.15.40-r8-mapping.zip`。
- 校验清单：`artifacts/validation-0.15.40.json`、`artifacts/SHA256SUMS-0.15.40.txt`；日志与 JUnit XML 位于 `artifacts/release-0.15.40/`。

上一轮 `0.15.39 / 71` 优化源码的 1384 项完整单测、24 项 Python 测试已通过；本轮仅递增版本，未重跑该全量与 Python 测试。未连接手机验证真实 LSPosed、ROM、Google 或目标服务，未执行远端 CI、推送或发布。

## 发布前追加验证

2026-11-07，按用户的最新构建发布要求，在 `0.15.40 / 72` 上重新执行完整 Android 单元测试、Python 诊断测试、Release lint 与 assembleRelease。最终 134 组 Android 测试共 1384 项通过，失败、错误、跳过均为 0；Python 24 项通过；lint 0 错误、37 条警告，Release 构建通过。归档 APK 与构建输出 SHA256 一致，R8 mapping、长期签名及 ZIP/native 16 KiB 对齐复核通过。原始 `validation-0.15.40.json` 保留上述本地构建阶段的记录；本节为其后追加的发布验证，不表示设备验收或远端 CI 已通过。
