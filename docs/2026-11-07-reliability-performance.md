# 可靠性与响应速度优化验证

日期：2026-11-07。分支：`optimize/reliability-20261107`，起点：`dcb53f0`。用户授权执行审阅提出的六项优化，本次交付本地源码与构建验证。

## 改动

1. **可复现测试**：解除 `app/src/test/` 的 Git 忽略，将 136 个测试源码/辅助文件和 3 个脱敏 HTML 样例纳入版本管理；截图目录由 Gradle 注入，移除开发机绝对路径。新增 `.github/workflows/android-ci.yml`，使用 JDK 21、Android SDK 37，运行 Android 单元测试、Release lint 和 Python 诊断测试。
2. **配置确认**：`RuntimePublisher` 发布后直接进入已有的有限次数状态确认，连接中的回调丢失不会再阻断确认。保留请求前后 generation、配置身份与各阶段就绪检查。
3. **批量归并**：`LinkChoiceActivity` 接受协议大小写归一后得到的合法单条计划，以重新规划结果和目标包校验交付，不再用“批量规则”展示文案拒绝它。
4. **规则快照复用**：链接选择与拖拽分组各读取一次有效规则与三个开关，循环使用现有 `planWithRules`。最终点击仍重新规划，配置变化后不能沿用旧目标。
5. **截图编码**：窗口检查与截图留在手势 worker，PNG 压缩和输出复制交给有界后台编码器。每个实例最多一个编码任务和一个不持有像素的最新等待请求；交付前重新检查输入代次、配置、锁屏、宿主 UID 和来源窗口。取消、失败及过期结果释放 Bitmap。
6. **Lens 缩略图**：下载使用独立的两线程池，等待上限 40；关闭或换图会取消任务、移除队列，并在后台断开连接。图片准备继续使用原有 IO，保留 URL、大小、epoch 和图片代次检查；缺失缩略图不丢弃结果链接。

测试基线同时修正了旧的 8 条链接断言，改为验证当前 64 条上限、顺序和去重；应用名单界面测试等待异步候选列表就绪后再滚动，保留原有取消草稿断言。后者曾在首轮全量运行中失败，单独重跑通过，已定位到测试未等待真实后台扫描。

## 本地验证

| 检查 | 最终结果 |
| --- | --- |
| Android 完整单元测试 | 134 组，1384 项；失败、错误、跳过均为 0 |
| Python 诊断测试 | 24 项通过 |
| Release lint | 0 错误、37 项警告 |
| assembleRelease | 通过，启用 R8 |
| APK v2 签名 | 通过，证书与既有正式版一致 |
| ZIP 4 字节、原生库 ZIP 16 KiB 对齐 | 通过 |
| 4 个原生库 ELF PT_LOAD 16 KiB 对齐 | 通过 |
| 工作流 YAML | 本地解析、触发器及命令检查通过 |
| 独立代码审阅 | 无阻塞项 |

新增回归先在旧实现观察到 16 个预期失败断言，再实施修复；后续补充截图代次交接、编码异常、链接回退、secure 窗口与锁屏等验证。最终全量测试通过后，单独运行 Release lint 与构建，返回成功。

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain
python -m unittest discover -s tools/tests -p 'test_*.py'
.\gradlew.bat :app:lintRelease :app:assembleRelease --offline --console=plain
```

首次检出使用 README 的在线构建命令下载依赖。本次离线执行复用本机已缓存依赖。

## 本地验证产物

归档目录：`artifacts/optimization-20261107/`。

- APK：`TaplusBridge-optimization-20261107-release.apk`，3,578,753 bytes。
- 验证包沿用版本 `0.15.39 / 71`，正式发布归档仍单独保留。
- SHA256：`FCF76F1C97676D2785E4C6194F5753AFF06D5BB475DE49F0F86A71FE79CE26EB`。
- 长期签名证书 SHA256：`2f3c681d676d8f763b191dea0714c399e21bbaa0e948f25289ebfbcf41d1aabd`。
- 同次 R8 映射：`r8-mapping.zip`；检查清单：`validation.json`、`SHA256SUMS`。
- 归档包含完整 JUnit XML、单测/构建/Python 日志、lint、签名、对齐结果，以及旧实现的失败回归日志。
- APK 中 LSPosed 作用域仍为 `com.miui.contentextension` 与 `system`。

## 验证边界

未连接或安装手机，未验证真实 LSPosed 回调、ROM 截图及窗口移交、手势耗时、Root／Shizuku、Google Lens 上传、翻译或网盘服务。Lens 取消回归使用本机受控 HTTP 服务，截图回归使用受控队列和本地 Bitmap，不代表设备侧性能实测。GitHub Actions 尚未在远端运行，未推送、打标签或发布。

## 后续版本递增：0.15.40 / 72

用户追加要求递增版本号，已将 `app/build.gradle` 及 README 同步为 `0.15.40 / 72`，重新构建长期签名 Release。版本变更后四组运行时定向测试共 102 项通过，Release lint 0 错误/37 警告，签名、ZIP 与原生库 16 KiB 对齐通过。本轮未重跑前述完整单测和 Python 测试。

新版 APK：`artifacts/TaplusBridge-0.15.40-release.apk`，3,578,753 bytes；SHA256：`74A18DCBB3B77B486C1BC5CB6ECC4B9C70B20093EA4771D00B9C51110CBC7B73`。同次映射及验证清单见 [0.15.40 更新说明](release-notes-0.15.40.md)。前述 `optimization-20261107/` 归档保留原验证版本。

2026-11-07 发布前，在最终 `0.15.40 / 72` 再次执行完整 Android 单测（134 组、1384 项全部通过）、Python 24 项测试、Release lint（0 错误、37 警告）及 assembleRelease，均通过，APK 哈希与归档一致。该次结果补足正式版本上的全量复核；设备与真实服务仍未验收，远端 CI 另行核实。
