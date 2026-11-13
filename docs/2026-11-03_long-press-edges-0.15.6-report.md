# 0.15.6：长按识别不显示四边分享

> 本文中的本地归档路径不随源码仓库分发；公开发布产物请以 Releases 为准。

日期：2026-11-03（Asia/Shanghai）。版本：0.15.6 / 38。这是本地长期签名 R8 Release，未远程发布，也未安装到手机。

原地长按识别保持原来的界面，不出现四边分享，也不接管触摸。手指移过拖动阈值后才显示四边彩虹；拖到某一边松手才执行该方向已经设好的操作。停在原地松手不执行方向操作。

定向测试为 `HostLongPressDragAndroidTest` 13 项和 `LongPressDirectionTest` 10 项，合计 23 项、0 失败。Release lint 为 0 error、37 warnings。没有跑全量单元测试，没有连接手机，也没有验证 LSPosed 回调。

APK：TaplusBridge-0.15.6-release.apk（本地归档：`../artifacts/TaplusBridge-0.15.6-release.apk`）。SHA-256 见 SHA256SUMS-0.15.6.txt（本地归档：`../artifacts/SHA256SUMS-0.15.6.txt`），签名、对齐和 R8 mapping 见 validation-0.15.6.json（本地归档：`../artifacts/validation-0.15.6.json`）。证书与 0.15.5 正式包相同。安装后需要重启，系统侧 Hook 才会换成这一版。
