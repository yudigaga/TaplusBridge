# 0.15.9：恢复边缘松手执行

日期：2026-11-03（Asia/Shanghai）。版本：0.15.9 / 41。这是本地长期签名 R8 Release，未远程发布，也未安装到手机。

22:47 日志里，0.15.8 有两次 `released direction=left`，随后 `skipped_no_card_link` 再取消，预设动作没有执行。另一次先进入左边，松手时方向变成 null，同样取消。这一版拖动后重新显示四边并接管手指；松手仍在刚进入的那一侧时保留该边缘并执行预设动作，不再因为文本没有链接而跳过。长按识别回调继续交给传送门。回调里本来没有链接时，不会从标题编造地址。

定向测试为 `HostLongPressDragAndroidTest`。Release lint 与签名、对齐结果见 [validation-0.15.9.json](../artifacts/validation-0.15.9.json)。没有跑全量单元测试，没有连接手机，也没有验证 LSPosed 回调。

APK：[TaplusBridge-0.15.9-release.apk](../artifacts/TaplusBridge-0.15.9-release.apk)。SHA-256 见 [SHA256SUMS-0.15.9.txt](../artifacts/SHA256SUMS-0.15.9.txt)。证书应与 0.15.8 正式包相同。安装后需要重启，系统侧 Hook 才会换成这一版。
