# 0.15.12：双击识别与拖动取链

日期：2026-11-04（Asia/Shanghai）。版本：0.15.12 / 44。这是本地长期签名 R8 Release，未远程发布，也未安装到手机。

10:28 日志里，0.15.11／43 已经加载。五次双击都记到 `gesture reason=double_tap`，大约 50 毫秒后截图校验连续两次失败，异常类型是 `IllegalStateException`，没有 `submitted` 或 `host_received`。第一下短按会把这次窗口记成已经取过链，第二下因此不再点击。长按拖动三次分别拿到 80、39、39 字，抓取和推荐都是 0 个链接。松手时取链以 `input_or_window_changed` 结束，没有点击，也没有 `card_link`。向上那一次仍按分享打开，内容只有标题。

这一版在双击确认后立刻取数，不再把第一下短按当成已经点过。同一应用的非安全选区或浮层不再让整次截图失败；Android 16 对同一图层返回新的 SurfaceControl 包装时，按原生图层身份继续校验。拖动松手后，传送门自己的遮罩不再挡住补点；卡片上的同源菜单先在菜单外取消，再点原来的位置。分享、浏览器、规则、复制、翻译和传送门在文字里还没有链接时最多再等 1.5 秒。没有截到地址时仍执行预设动作，但不从标题编造链接。

定向测试为 `HostLongPressDragAndroidTest` 14 项、`ScopeCaptureWindowAndroidTest` 30 项、`ScopeLinkProbeAndroidTest` 31 项、`SourceWindowAccessTest` 12 项，合计 87 项、0 失败。Release lint 为 0 error、37 warnings。没有跑全量单元测试，没有连接手机，也没有验证 LSPosed 回调。

APK：[TaplusBridge-0.15.12-release.apk](../artifacts/TaplusBridge-0.15.12-release.apk)。SHA-256 见 [SHA256SUMS-0.15.12.txt](../artifacts/SHA256SUMS-0.15.12.txt)，签名、对齐和 R8 mapping 见 [validation-0.15.12.json](../artifacts/validation-0.15.12.json)。证书与 0.15.11 正式包相同。安装后需要重启，系统侧 Hook 才会换成这一版。
