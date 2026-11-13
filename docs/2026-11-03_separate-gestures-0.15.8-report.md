# 0.15.8：原生菜单、长按识别、长按拖动分开

> 本文中的本地归档路径不随源码仓库分发；公开发布产物请以 Releases 为准。

日期：2026-11-03（Asia/Shanghai）。版本：0.15.8 / 40。这是本地长期签名 R8 Release，未远程发布，也未安装到手机。

22:06 日志里，0.15.7 把已经带链接的长按回调标成 `native_pick` 后不再交给传送门；另一次拖动只有 52 个字、没有链接，仍然重放识别并弹出没有链接。这一版让原生菜单和长按识别继续收到回调。没有链接的拖动不显示遮罩、不重放、不弹窗。同一轮随后出现链接才按规则或浏览器打开。

定向测试 `HostLongPressDragAndroidTest` 14 项、0 失败。Release lint 为 0 error、37 warnings。没有跑全量单元测试，没有连接手机，也没有验证 LSPosed 回调。传送门若这次回调里本来就没有链接，拖动不会编造地址。

APK：TaplusBridge-0.15.8-release.apk（本地归档：`../artifacts/TaplusBridge-0.15.8-release.apk`）。SHA-256 见 SHA256SUMS-0.15.8.txt（本地归档：`../artifacts/SHA256SUMS-0.15.8.txt`），签名、对齐和 R8 mapping 见 validation-0.15.8.json（本地归档：`../artifacts/validation-0.15.8.json`）。证书与 0.15.7 正式包相同。安装后需要重启，系统侧 Hook 才会换成这一版。
