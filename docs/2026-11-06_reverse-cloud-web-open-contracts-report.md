# 115、迅雷、天翼与微云链接打开契约

日期：2026-11-06，Asia/Shanghai。普通静态逆向，`flavor = null`。范围和授权见 [scope](../analysis/cloud-web-open-20261106/scope.md)，官网静态源码索引见 [contracts-index.json](../analysis/cloud-web-open-20261106/contracts-index.json)。索引记录原 URL、当前本地文件 SHA256、大小及字符位置；本文未使用真实分享内容、登录或下载接口。

本轮可确认两项保留完整分享 URL 的离线转交方式：115 APK 的内置浏览器入口，以及迅雷官方分享页为 Android 构造的 webview 入口。天翼与微云能唤起应用的证据存在，但当前证据不足以仅凭任意分享 URL 构造“在应用中打开当前分享”的入口，因此不应拿应用主页或需要线上文件信息的动作替代。

## 可构造项与边界

| 服务 | 目标包 | 已证实构造 | 实际含义 |
|---|---|---|---|
| 115 | `com.ylmf.androidclient` | `oof.disk://url/` + `URLEncoder.encode(fullUrl, UTF-8)` | 将完整 URL 交给应用内 WebBrowserActivity；不宣称原生分享导入或离线页 |
| 迅雷云盘 | `com.xunlei.downloadprovider` | `xunleiapp://xunlei.com/webview?url=` + `encodeURIComponent(fullUrl)` + `&title=` + `encodeURIComponent("迅雷云盘")` + `&from=pan.xunlei.com` | 官方 Android 分享页“打开 APP”的 webview 路由；无须取得分享文件元数据 |
| 天翼云盘 | `com.cn21.ecloud` | unsupported：当前分享动作的必需字段不能只从分享短码恢复 | 已证实 `cloud189://app/<encoded-action>`，但具体分享动作依赖 account/shareId/fileId 等数据 |
| 腾讯微云 | `com.qq.qcloud` | unsupported：未证实只携带当前分享 URL/key 的通用 Android 入口 | 官方打开 APP 进入 `weiyun://action/recentfeeds`；该地址不携带当前分享 |

115 生产分享域名由 APK 自己的分享路由确认：`share.115.com` 的非空路径，以及 `115.com`、`115cdn.com`、`anxia.com` 的 `/s/` 非空路径。测试 `rc` 域不应纳入生产白名单。迅雷以官网实际 `/s/:id` 路由对应的 `pan.xunlei.com/s/` 分享为本轮已证实范围；不要据此扩展为任意迅雷网站地址或任意目标包。

URL 中的 query、fragment、编码、字面加号及大小写都应保留。115 用 Java form 编码与它的 URLDecoder 配对；迅雷的官方 CallApp helper 不会二次编码参数，因此 url 字段只编码一次。下发仍须由用户规则选择目标，并核实目标安装及接收入口，不能把私有 scheme 解析成功当作设备内容验收。

## Evidence

| E-id | source_ref | 观察 | content_hash |
|---|---|---|---|
| E-CLOUD-01 | `analysis/cloud115-37.2.6/entry-filters.json`；`SchemeMainActivity.java:615`–626 | Manifest 注册 oof.disk/oof.disk_life 的 VIEW + DEFAULT/BROWSABLE；url 分支对整个后缀 URLDecoder，再 setData 到 WebBrowserActivity | filter SHA256 `830614ed1f7bea2c327cac474f5b5b55d2b4502d805f2e4de4fdebf43e2110c2`；Java `609a56853191c823ac0b343af8f638d723bc524bf5a7b1fdab08a01f1fc9d455` |
| E-CLOUD-02 | `analysis/cloud115-37.2.6/i6.java:521`–525；`h6.java:135`–141 | 应用自身匹配 share.115.com、115.com/s/、115cdn.com/s/、anxia.com/s/，并读取 password 后交给分享处理；h6 也列有相应生产/测试域转换 | i6 `d9cf135af87bf9146bcf566d7819799bfabc6f76f8f58ad202fa3fcc6fa27e79`；h6 `28e4f3c00a3d7946f53a717796022936581db8190be612645190e3c7b642837e` |
| E-CLOUD-03 | 官网 `https://static-pan.xunlei.com/_nuxt/dist/client/106.cba7d159ab9802ab51f8.js`，`onAppDown` 字符226425起 | android path 为 `xunlei.com/webview`，url 参数为 encodeURIComponent(当前分享 URL)，title/from 为固定业务值 | `3167213afa6a234b6a83fd4a67c95f0eacb212f879b96ef32026a5c2e321cae7` |
| E-CLOUD-04 | 官网 `utils-async.c0fda5f4dea4348db26a.js`，module627；上述106 JS的module734 | CallApp Android protocol=xunleiapp、intent.package=com.xunlei.downloadprovider；generateScheme 直接拼 path 和预先编码 param，未二次编码 | async `2789d5f1c881dfa32c151f2046a51022d6bb9ca786ff737a93a4c09f02ed7e5d`；106同 E-CLOUD-03 |
| E-CLOUD-05 | 官网 `https://h5.cloud.189.cn/static/js/13.bb3800d4deafa050b21b.js`，saveByApp/downLoadByApp | `m.html?go=openShareFile` 需要已登录 account、服务端 shareId、fileId/name，下载还使用 size/md5；前往客户端还有复制短链再进下载引导页的流程 | `92f008f81ff8e14917422c6807bc94bf42243b815db976b179b6d5557f29888b` |
| E-CLOUD-06 | 官网 `https://h5.cloud.189.cn/static/js/launch.3b0355bd4f233ce5726b.js`，handlePath/launch | `cloud189://app/<path>` 中 path 由 go 动作与 query 参数经过 encryptData 构造；这只能证明外壳协议，不能证明未知的纯 URL 动作 | `1bd5d2cb1e58a4384a4afd6b2e3bfb5b1968d91a56867f6bd9b719b8646d6c9a` |
| E-CLOUD-07 | 官网 `https://docs.gtimg.com/weiyun/wy/share/h5/main.1afa7099.prod.js`，launchWeiyun/source 与 getPlayVideoSchema | 普通打开 APP 进入 recentfeeds，不含 shareKey；视频等业务入口需 fileID/dirKey/sign 等分享数据；packageInfo 为 com.qq.qcloud | `447f997e5c4be8cbffecb982aa9615e5b99395b4f79083bf8ce6ab3cd0cb3093` |

官网所有 JS 都从空静态壳与 webpack runtime 对照提取，不猜资源文件名。XHR 函数仅阅读源码，没有执行；远端 JS 没有在浏览器或 Node 中运行。

## Findings

| F-id | severity | evidence_ids | confidence | location | status | 结论 |
|---|---|---|---|---|---|---|
| F-CLOUD-01 | n/a_re | E-CLOUD-01、02 | high | 115 SchemeMainActivity / i6 | validated_static | 可将认可的分享原 URL 通过已导出的内置浏览器协议转交；进一步原生分享处理不在本轮验收范围 |
| F-CLOUD-02 | n/a_re | E-CLOUD-03、04 | high | 迅雷 onAppDown / CallApp | validated_static | 可离线复现官方 Android 分享打开入口，并在 url 参数中保留完整原链接 |
| F-CLOUD-03 | n/a_re | E-CLOUD-05、06 | high | 天翼 share / launch | unsupported_from_current_evidence | 协议外壳已证实，但不能凭空构造文件元数据或未知 go 动作 |
| F-CLOUD-04 | n/a_re | E-CLOUD-07 | high | 微云 launchWeiyun | unsupported_from_current_evidence | 唤起主页不等于打开当前分享，不能当成功适配 |

## Path

P-CLOUD-01，`path_type=callflow`：规则选中115 → 检查生产分享域和路径（E-CLOUD-02）→ 完整原 URL form 编码 → oof.disk://url/ → exported SchemeMainActivity → URLDecoder → WebBrowserActivity.setData（E-CLOUD-01）。

P-CLOUD-02，`path_type=callflow`：规则选中迅雷 → 检查 pan.xunlei.com/s/ → 官方 onAppDown 的 Android webview 参数（E-CLOUD-03）→ 使用已证实 xunleiapp protocol/目标包且 url 仅编码一次（E-CLOUD-04）→ 当前安装版 PM 解析及实际页面需设备验证。

P-CLOUD-03，`path_type=callflow`：天翼分享页保存/下载按钮 → 读取登录/分享/文件数据（E-CLOUD-05）→ launch 页编码动作到 cloud189 scheme（E-CLOUD-06）；仅输入短链时必需数据缺失，流程在此不能离线完成。微云普通按钮则进入 recentfeeds（E-CLOUD-07），缺少当前分享参数。

## 复现与验证边界

```powershell
curl.exe --http1.1 -sS -L --max-time 25 'https://static-pan.xunlei.com/_nuxt/dist/client/106.cba7d159ab9802ab51f8.js' -o 'analysis\cloud-web-open-20261106\xunlei-106.cba7d159ab9802ab51f8.js'
Get-FileHash -LiteralPath 'analysis\cloud-web-open-20261106\xunlei-106.cba7d159ab9802ab51f8.js' -Algorithm SHA256
rg -n 'onAppDown:function|protocol:"xunleiapp"' analysis/cloud-web-open-20261106
```

2026-11-06 研究顺序：115 Manifest/Java合同复核 → 官方空壳页面追踪静态资源 → 迅雷 mobile-share 与 CallApp闭环 → 天翼 share/launch必需字段确认 → 微云 current-share入口不足确认。均为本地或静态源码证据。

本任务未改生产代码、未跑 Gradle、未安装或启动手机应用、未访问实际分享数据、未测试真实目标应用版本。天翼和微云标 unsupported 是本轮缺少可离线构造的当前分享入口证据，并非断言其应用永远不支持。
