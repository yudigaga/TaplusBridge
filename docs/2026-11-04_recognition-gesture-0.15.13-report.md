# 0.15.13：双击接管原来的长按识别

日期：2026-11-04（Asia/Shanghai）。版本：0.15.13 / 45。这是本地长期签名 R8 Release，未远程发布，也未安装到手机。

11:11 日志里，0.15.12 的长按仍然直接走出传送门识别。双击只进入截图兜底，并且因为 `capture_focus` 失败。卡片长按拿到 80 或 16 个字、0 个链接。手指停住松开时，原来的取链探针能记到 `URL_FOUND`；一旦拖到边缘，松手时的另一次点击会把这次拖动取消，链接进不了拖动内容。

这一版不改模块里的开关。手指按住超过长按时间时，传送门识别面板不再打开，系统长按菜单仍由系统处理。双击同一位置会补一次长按采集，识别面板和原来的取链、规则逻辑都走这一次。长按拖动松手后继续用那条已经能取出卡片链接的探针，地址并进拖动内容；这次点击改了前台窗口时，两秒内不再因此取消拖动。没有截到地址时仍执行预设动作，不从标题编造链接。

定向测试为 `RecognitionGestureTest` 3 项和 `HostLongPressDragAndroidTest` 14 项，合计 17 项、0 失败。Release lint 为 0 error、37 warnings。没有跑全量单元测试，没有连接手机，也没有验证 LSPosed 回调。

APK：[TaplusBridge-0.15.13-release.apk](../artifacts/TaplusBridge-0.15.13-release.apk)。SHA-256 见 [SHA256SUMS-0.15.13.txt](../artifacts/SHA256SUMS-0.15.13.txt)，签名、对齐和 R8 mapping 见 [validation-0.15.13.json](../artifacts/validation-0.15.13.json)。证书与 0.15.12 正式包相同。安装后需要重启，系统侧 Hook 才会换成这一版。
