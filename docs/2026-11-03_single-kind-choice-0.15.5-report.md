# 0.15.5：一种识别内容不再弹窗

日期：2026-11-03（Asia/Shanghai）。版本：0.15.5 / 37。这是本地长期签名 R8 Release，未远程发布，也未安装到手机。

拖拽和长按方向手势仍先完成识别。原文、图片识别文字、图片、链接各自算一种；只有一种时直接执行该方向已经设好的操作。多个链接算同一种：分享和传送门使用全部链接，浏览器和按规则打开仍要选择其中一个。多种内容保持原来的选择窗。

定向测试只跑了 `DragChoiceActivityAndroidTest`，7 项、0 失败。Release lint 为 0 error、37 warnings。没有跑全量单元测试，没有连接手机，也没有验证 LSPosed 回调。

APK：[TaplusBridge-0.15.5-release.apk](../artifacts/TaplusBridge-0.15.5-release.apk)。SHA-256 见 [SHA256SUMS-0.15.5.txt](../artifacts/SHA256SUMS-0.15.5.txt)，签名、对齐和 R8 mapping 见 [validation-0.15.5.json](../artifacts/validation-0.15.5.json)。证书与 0.15.4 正式包相同。安装后需要重启，系统侧 Hook 才会换成这一版。
