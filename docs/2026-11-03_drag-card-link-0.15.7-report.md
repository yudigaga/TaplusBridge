# 0.15.7：拖动等待卡片链接

> 本文中的本地归档路径不随源码仓库分发；公开发布产物请以 Releases 为准。

日期：2026-11-03（Asia/Shanghai）。版本：0.15.7 / 39。这是本地长期签名 R8 Release，未远程发布，也未安装到手机。

21:31 日志里，长按拖动按规则打开时只拿到标题 `YouTube`（7 字，0 个链接），约 1 秒后就接管触摸。原地长按没有被打断，回传 28 字并带 1 个链接，所以能解析到 YouTube。这一版在还没有链接时不接管触摸；同一轮若随后传来带链接的结果就改用它。按规则或浏览器打开时，松手后再等 1 秒。分享和复制仍立即执行。

定向测试 `HostLongPressDragAndroidTest` 14 项、0 失败。Release lint 为 0 error、37 warnings。没有跑全量单元测试，没有连接手机，也没有验证 LSPosed 回调。传送门若始终不回传卡片链接，这一版也不能凭标题猜出地址。

APK：TaplusBridge-0.15.7-release.apk（本地归档：`../artifacts/TaplusBridge-0.15.7-release.apk`）。SHA-256 见 SHA256SUMS-0.15.7.txt（本地归档：`../artifacts/SHA256SUMS-0.15.7.txt`），签名、对齐和 R8 mapping 见 validation-0.15.7.json（本地归档：`../artifacts/validation-0.15.7.json`）。证书与 0.15.6 正式包相同。安装后需要重启，系统侧 Hook 才会换成这一版。
