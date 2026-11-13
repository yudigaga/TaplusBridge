# HyperPortal 拖拽链路静态分析

> 本文中的本地归档路径不随源码仓库分发；公开发布产物请以 Releases 为准。

> 日期：2026-11-03（Asia/Shanghai）
> 目标：HyperPortal.apk，com.github.leaf.hyperportal v1.0（versionCode 1）
> SHA-256：6F340FAE3EF88192A563617D290457435B5BBFD2261CA6811B5F811DE2DD3378

## 摘要

HyperPortal 把拖拽拆成系统拖拽状态、内容传递和动作菜单。system_server 的 Hook 只在 ClipDescription 含私有 MIME application/x-dragshare 时启动其拖拽状态与动画；SystemUI 菜单的文字、图片和 URI 则从独立 com.github.leaf.hyperportal.SHOW Intent 接收。APK 本身没有调用 startDragAndDrop，也没有找到该 MIME 的生产代码。它还修改 com.miui.contentcatcher 的 Zygote/SELinux seInfo，显示它很可能依赖 HyperOS 的 ContentCatcher 配合；但 DEX/smali 中没有找到 SHOW 的发送代码，因此准确的内容传递链不能仅凭此 APK 确认。它另有一个 PortalService 应用悬浮窗，直接监听 DragEvent，并在 DROP 时读取 ClipData 第一个 item 转为文本。

这不是“Hook 一次系统长按就能拿到所有内容”的实现。其配置边缘只支持 right 或 bottom，也不能证明来源应用会把卡片中隐藏的网址提供给它。

## 范围与方法

范围是用户直接授权的本地 APK 静态分析及工作区已有 JADX/APKTool 导出，目标为理解拖拽入口、载荷与目标动作；项目边界另见 [scope.md](scope.md)。未安装 APK、未连接设备、未做运行时 Hook 或系统广播验证。JADX 对 PortalService.lambda$showBottomPortal$3 的反编译结果是占位异常，故该回调以 smali 交叉确认。

APKTool 导出元数据标明 3.0.2；当前命令行 PATH 中没有 jadx 或 apktool，本次没有重新生成导出。样本 SHA-256 可用 Get-FileHash .\HyperPortal.apk -Algorithm SHA256 复核。

## 拖拽数据流

~~~mermaid
flowchart LR
    A[来源应用 / 外部生产者] -->|ClipData: application/x-dragshare| B[system_server DragDropController Hook]
    B -->|拖拽状态与坐标广播、Surface 动画| C[SystemUI 边缘接收层]
    A -. SHOW payload: viewContent / viewBitmap / imageUri, sender absent .-> C
    C --> D[按落点执行内置或用户目标]
    A -->|普通 DragEvent / ClipData| E[PortalService type 2038 悬浮窗]
    E -->|第一个 ClipData.Item.coerceToText| D
~~~

## 关键实现

### system_server Hook

模块作用域包含 system 与 com.android.systemui。y21 安装 DragDropController.performDrag、handleMotionEvent、onDragStateClosedLocked 以及 DragState.updateDragSurfaceLocked Hook。performDrag 拦截逻辑在参数中寻找 ClipData 和 IWindow；只有 application/x-dragshare MIME 命中时才标记本模块拖拽开始、定位源 Task/SurfaceControl，并发送状态更新。该段没有把 ClipData 内容交给 SystemUI 菜单。

### SystemUI 菜单

j31 创建 type 2024 窗口并注册 OnDragListener。SHOW 接收器读取 viewContent、viewBitmap、imageUri、imageMimeType 并缓存；e31 的 DROP 分支根据落点选择目标，动作处理使用这些缓存值，不读取 DragEvent 的 ClipData。

v21/y21 还 Hook Zygote 参数和 SELinuxMMAC.getSeInfo，对 com.miui.contentcatcher 将 seInfo 改为 platform。这是 HyperOS ContentCatcher 集成的强线索，但没有在 APK 内找到它发送 SHOW Intent 的实现，故发送者与具体传递时序仍属未确认项。

内置目标包括复制、图片保存或文本分词、系统分享、文本搜索；还会并入用户配置目标。文本会尝试分类为 URL、邮箱、电话或地址。方向配置读取 trigger_position：仅 right 使用右侧边缘，其余值进入底部边缘逻辑；不是四方向操作配置。

### PortalService 应用悬浮窗

PortalService 另建 type 2038 窗口并注册拖拽监听。JADX 未能还原 lambda$showBottomPortal$3，smali 显示 DROP 时调用 DragEvent.getClipData()，取第一个 item 并调用 coerceToText()，再按落点映射到 target 并调用 performTarget()。因此这个 DragEvent 回调明确读取文本；服务还可从独立启动 Intent 读取图片字段，但不能据此认定该 DROP 回调会从 ClipData 提取图片或 URI。

## 与 TaplusBridge 的关系

当前 TaplusBridge 的 DragActivity 接收已经开始的全局拖拽，在 DROP 时读取 event.getClipData()、申请 DragAndDropPermissions，再解析支持的文本、链接或图片载荷。这个接收路径不依赖 HyperPortal 的私有 MIME application/x-dragshare 或它的 SHOW 广播协议。

工作区的 [Android 16 拖拽修复记录](2026-11-03_drag-android16-0.15.1-report.md) 另记录了原先入口因 NoSuchFieldException 未能安装，并已为 Android 16 的双显示器字段结构添加兼容。本报告是 HyperPortal 静态分析，不是 TaplusBridge 的实机验证；模块安装、重启和实际跨应用拖拽仍需设备确认。

## Evidence → Finding → Path

### Evidence

| E-id | source_ref | repro_command | content_hash |
|---|---|---|---|
| E-APK | ../../HyperPortal.apk，包名/版本来自 analysis/HyperPortal/apktool/apktool.yml | Get-FileHash .\HyperPortal.apk -Algorithm SHA256 | 6F340FAE3EF88192A563617D290457435B5BBFD2261CA6811B5F811DE2DD3378 |
| E-SYS | v21.java（本地归档：`../../analysis/HyperPortal/jadx/sources/defpackage/v21.java#L30`）、y21.java（本地归档：`../../analysis/HyperPortal/jadx/sources/defpackage/y21.java#L400`） | rg -n application/x-dragshare analysis/HyperPortal/jadx/sources/defpackage/v21.java | same APK SHA-256 |
| E-UI | j31.java（本地归档：`../../analysis/HyperPortal/jadx/sources/defpackage/j31.java#L193`）、e31.java（本地归档：`../../analysis/HyperPortal/jadx/sources/defpackage/e31.java#L15`） | rg -n viewContent analysis/HyperPortal/jadx/sources/defpackage/j31.java | same APK SHA-256 |
| E-APP | PortalService.smali（本地归档：`../../analysis/HyperPortal/apktool/smali/com/github/leaf/hyperportal/PortalService.smali#L5159`） | rg -n coerceToText analysis/HyperPortal/apktool/smali/com/github/leaf/hyperportal/PortalService.smali | same APK SHA-256 |
| E-CC | v21.java（本地归档：`../../analysis/HyperPortal/jadx/sources/defpackage/v21.java#L132`）、y21.java（本地归档：`../../analysis/HyperPortal/jadx/sources/defpackage/y21.java#L475`） | rg -n com.miui.contentcatcher analysis/HyperPortal/jadx/sources/defpackage/v21.java | same APK SHA-256 |

### Findings

| F-id | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F-MIME | n/a_re | E-SYS | high | v21.intercept | 确认：系统 Hook 使用私有 MIME 门槛，且不处理菜单载荷 |
| F-SHOW | n/a_re | E-UI | high | j31.d、e31.onDrag | 确认：SystemUI 内容通过独立 SHOW extras 输入；APK 内未找到发送方 |
| F-APP | n/a_re | E-APP | high | PortalService.lambda$showBottomPortal$3 | 确认：该 DROP 回调只把首个 ClipData item 转为文本 |
| F-DIR | n/a_re | E-UI | high | j31 overlay/config path | 确认：边缘配置为 right 或 bottom |
| F-HIDDEN-LINK | n/a_re | E-UI、E-APP | medium | 来源内容桥接 | 未证明隐藏卡片网址会被来源侧提取并传入 |
| F-CC | n/a_re | E-CC | medium | Zygote/SELinuxMMAC hooks | ContentCatcher 集成迹象明确，SHOW 发送链未确认 |

### Path

P-DRAG（path_type=callflow）：来源产生拖拽 → system_server Hook 以私有 MIME 判断是否启动状态/动画 → SystemUI 通过独立 SHOW extras 收到文本/图像/URI并按落点执行目标；并行的 PortalService 路径则接收 DragEvent ClipData 首项并转换为文本。缺少外部 SHOW 发送者或私有 MIME 时，不能假设对应的 HyperPortal 系统 UI 路径会触发。

### Timeline

- 2026-11-03：读取本地 APK 的现有 JADX 与 APKTool 导出。
- 2026-11-03：确认 SystemUI 与 PortalService 两条接收路径及各自载荷来源。
- 2026-11-03：未执行设备安装或动态拖拽验证。
