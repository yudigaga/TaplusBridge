# 常见网盘网页唤起合同

分析日期：2026-11-06，Asia/Shanghai。普通兼容性逆向，flavor=null。范围与授权：`../analysis/cloud-open-multi/scope.md`（相对工作区父目录）。证据目录为 `E:/vs/claude/传送门/analysis/cloud-open-multi/`。

百度、阿里、夸克的官方网页均包含离线可构造的应用 URI 业务格式。规则仍须先决定目标包，兼容开关只允许修改已核对的该包链接入口。官方 JavaScript 证明的是网页当前生成方式；没有取得这三家 APK 或进行手机运行验证，因此不能据此宣称所有分享和所有版本均可打开。

## Evidence

| E-id | source_ref | 方法 | content_hash |
|---|---|---|---|
| E-01 | `pan.baidu.com/s/1___taplusbridge_probe___`，Android UA，HTTP200 | 保存 HTML，提取实际 script src，不请求分享 API | `7360e7ffc491ca0b40946503e8187c2096e9966132264fc4223963c2e62bf278` |
| E-02 | `https://nd-static.bdstatic.com/m-static/m-share/js/error-new_98b762b.js` | GET官方静态脚本，保存为 `baidu-error.js` | `f9874600039a0bbc7ec29fa53f7eac6bfccefec5c8a36602f8ca057f539bc5b9` |
| E-03 | `www.alipan.com/s/___taplusbridge_probe___`，Android UA，HTTP200 | 保存 HTML及脚本地址 | `10830348bed3e248a73ea335b4bd9535810830c131d14aaf45f97f50824dd24f` |
| E-04 | `https://g.alicdn.com/aliyun-drive-fe/aliyun-drive/6.2.3-share/share/bundle.js` | GET官方实际引用包，保存为 `alipan-bundle.js` | `ceba193fa671fce37ef099f63d7c034153248306c8878abbe15fceb0e23ccdd7` |
| E-05 | 上述官方目录 `51.bundle.js`、`49.bundle.js` | 由 webpack u/p 和 lazy route 列表定位，无猜测动态接口 | `b03ad339d7e6ca1bc1c710d1e20a2cc0f87a362879f4fb2483e4a06a1c634e09`、`652a266e86c8f461b7dabb4baa4fdff19229e4ef4519d4a077a54451a3323aa4` |
| E-06 | `pan.quark.cn/s/___taplusbridge_probe___`，Android UA，HTTP200 | 保存 HTML及官方脚本地址 | `28a6197917ac8acb6f48e4b835e8fe5d72edaaa607071dd98480da1a0d75151e` |
| E-07 | `https://g.alicdn.com/quark-cloud-drive/quark-cloud-drive-share-h5/0.1.55/js/index.js` | GET实际引用脚本，保存为 `quark-index.js` | `f545a24c6785c3b452a0fcca82fc31284fd5d51f6f19752f4f2575b0b99ec4c0` |

`fetch-metadata.json`、`static-metadata.json`、`alipan-lazy-metadata.json`、`alipan-alias-metadata.json`记录地址、哈希及实际抓取结果。`fixed-code-evidence.json`记录16个源码片段和字符偏移；`receiver-contracts.json`是给实现使用的字段清单。这里只对公开 JS 做文本分析，没有执行远程脚本。

## Findings

| F-id | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F-01 | n/a_re | E-01,E-02 | 高（网页合同） | `getSchemaUrl`、业务参数函数 I、Surl函数 Bn | 百度的标准短分享可由路径离线转为 SHARE_LINK |
| F-02 | n/a_re | E-03,E-04,E-05 | 高（网页合同） | 模块69442/24252、移动51.chunk打开按钮 | 阿里的普通分享可转 smartdrive；密码源为已输入状态，URL密码字段尚未证实 |
| F-03 | n/a_re | E-06,E-07 | 高（网页合同） | generateUCLink、模块20507、默认actlinkConfig、预加载初始化 | 夸克含完整固定业务链；外层应用封装和内层 Flutter JSON 必须分别编码 |
| F-04 | n/a_re | E-01至E-07 | 高（验证边界） | 本次全部证据 | 无对应APK/设备验证，未证明各版本内部导航与参数必需性；不将网页JS分析等同设备验收 |

## 百度网盘

目标包为 `com.baidu.netdisk`。本次可严格限定 host=`pan.baidu.com`、path=`/s/1<ASCII-key>`；不将旧长分享链接、其他业务路径或来源不明短域名套入同一模板。

```text
bdnetdisk://n/action.SHARE_LINK?m_n_v=8.3.0&surl=<去掉路径末段首字符1后的key>&pwd=<可选URL提取码>
```

官方业务函数对普通 `/s/1KEY` 的 rawSurl 执行 `.slice(1)`，而 `/wap/init` 是另一分支，不能混用。URL密码来自 `$pwd=get('pwd')||''`，密码非空才附加。BDCLND cookie 存在时才附 `sekey`，没有 cookie 时仍生成 SHARE_LINK，不需要虚构该值。

站点未登录时还稳定附加 `userType=`、`slDisFrom=wap_unlog_page`、`channelType=wap`、`switchiOSNewSchema=true`、`surlType=1`、`hasPanCode=no`。hasPanCode 是独立口令标记，源码默认值不会因普通 pwd 非空变成 yes。不要生成 loginUK、cookie、时间戳或日志指纹。

## 阿里云盘

模块24252明确 `protocol=smartdrive`，普通 share 路由为 `share/browse`；模块69442明确 intent package=`com.alicloud.databox`。普通打开函数将 `{shareId,sharePwd}` 放到 URI 顶层 query，同时添加 `source_type=scheme`。

```text
smartdrive://share/browse?shareId=<share-id>&sharePwd=&source_type=scheme
```

已证实本次 `www.alipan.com` 的普通 `/s/:shareId` 页面。HEAD核对还确认 `alipan.com`、`aliyundrive.com` 会重定向到 `www.alipan.com`并保留同路径。`www.aliyundrive.com` 本次返回204，没有取得同站JS，不单凭该结果宣称旧域当前页面等同新站。

保守路径只处理 `/s/<share-id>`，排除 `bottle`、`iqiyi`、`album` 等专用路由，不将 folder/file/album 路由降成根分享。

51.chunk 的实际按钮传 `route.params.shareId` 和 `store.shareInfo.sharePassword`。该密码来自用户输入后 `loadShareListWithGetToken({pwd:e})` 与 `setSharePassword(e)`；49.chunk 的 getShareToken 默认空密码。本次未发现网址 `pwd` 或 `share_pwd` 被读取为这个值。不能猜测两者任一为受支持密码 query，也不能对带未知密码语义的 URL 静默丢弃密码后转换。无可独立确认的口令时，允许客户端自行提示输入。

官方拼接器默认还放入 `sid=window.adrive_sid`，并可附 adtag、click_position/source_address；这些来自页面会话或来源状态，本地适配不应随机生成或硬编码一次性值。客户端是否要求它们仍需设备观察。

## 夸克与独立夸克网盘

官方SDK的客户端列表、scheme映射与包名对应关系：

| 包名 | scheme | host / path | 默认 src_ch |
|---|---|---|---|
| `com.quark.browser` | `qklink` | `www.uc.cn/b20b84fd735a8dd3f7541129bacc4e9a` | `kk@product_wangpan_share` |
| `com.quark.clouddrive` | `qkcloudlink` | `www.uc.cn/d28241335bda4522a538cc12052f89dd` | `kkcloud@product_wangpan_share` |

外层 query 的 action 为 `open_url`，url 为 encodeURIComponent(landingPage)。网页默认的普通分享 landingPage 是：

```text
https://www.myquark.cn/?qk_tech=flutter&qk_biz=cloud_disk&qk_module=/clouddrive/main&qk_params=<encodeURIComponent(JSON)>
```

内层业务 JSON 来自官方模块20507：

```json
{
  "statParams": { "entry": "share" },
  "flutter_view_mode": { "immerse": true },
  "params": {
    "action": "share",
    "query": { "pwd_id": "EXAMPLE", "passcode": "A1b2" }
  }
}
```

source 中另有平台、页面版本、来源与实验组统计字段；这里仅列固定业务所需值，不编造 user_token、stoken、fid 或 stash_id。用户未提供密码时，源 JSON.stringify 会省略 undefined passcode。预加载器明确读取 URL query `pwd` 作为 passcode；pwd_id 由 pathname split 后取最后一段。适配可严格限制 `pan.quark.cn/s/<ASCII-id>`。

这不是把原 pan.quark.cn 网页直接套进 qklink：站点实际在目标应用中打开 myquark.cn 的 Flutter 分享路由。应先 JSON 编码并对 qk_params 编码，再构造 landingPage，最后对完整 landingPage 编码到外层 url。解码检查应能恢复原 key/password，任何不明业务 query/路径不能被无声丢弃。

## Path

P-01（callflow）：用户规则确定目标包 → 统一兼容开关判定 → 对照该包已核的 host/path/字段 → 离线构造对应应用 URI → 由实际系统包解析器确认接收入口 → 原有前台/解冻链路启动。开关关闭、其他包、未核格式、密码含糊或路径不支持时保留原 URL。magnet/ed2k 仍按其独立协议规则流转。

## 时间线与限制

- 2026-11-06：用三个虚拟分享路径读取官方 HTML和实际静态脚本。
- 2026-11-06：定位百度 SHARE_LINK 与阿里 smartdrive 参数，进一步读取阿里两个已声明 lazy chunks确认密码来源。
- 2026-11-06：定位夸克固定应用封装、默认Flutter路由、业务JSON和URL提取码来源，形成可复核字段清单。
- 未请求分享API、下载/登录API，未进行APK解析或手机运行验收；参数是否在每个历史版本成立尚未确认。
