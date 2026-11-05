# 0.15.10：拖动松手后补取卡片链接

日期：2026-11-04（Asia/Shanghai）。版本：0.15.10 / 42。这是本地长期签名 R8 Release，未远程发布，也未安装到手机。

00:09 日志里，0.15.9 的三次长按拖动分别拿到 80、80、16 字。抓取和推荐都是 0 个链接，松手在左边后仍按规则打开，识别文本里没有地址。长按识别能打开卡片，是因为手指停住后会在原点补一次点击，从微信的打开意图里截到真实地址；手指一移动，这条探针就以 touch_changed 结束。

这一版在拖动松手且方向成立后，用同一次长按的原点再做这下点击，最多等 1.5 秒，把截到的地址并进拖动内容。四边遮罩不接收这次点击。没有截到地址时仍执行预设动作，但不从标题编造链接。

定向测试 `HostLongPressDragAndroidTest` 14 项、0 失败。Release lint 为 0 error、37 warnings。没有跑全量单元测试，没有连接手机，也没有验证 LSPosed 回调。微信若没有发出可截取的打开意图，这一版同样没有链接。

APK：[TaplusBridge-0.15.10-release.apk](../artifacts/TaplusBridge-0.15.10-release.apk)。SHA-256 见 [SHA256SUMS-0.15.10.txt](../artifacts/SHA256SUMS-0.15.10.txt)，签名、对齐和 R8 mapping 见 [validation-0.15.10.json](../artifacts/validation-0.15.10.json)。证书与 0.15.9 正式包相同。安装后需要重启，系统侧 Hook 才会换成这一版。
