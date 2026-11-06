# 123 云盘唤起参数 `_ukid` 的省略边界

分析日期：2026-11-06。普通链接兼容性逆向，flavor=null。范围见工作区 `analysis/cloud123-web-open/umeng-link-sdk/scope.md`。

官方 Android ULink SDK 1.2.0 和 2.0.0 都没有把 `_ukid` 作为唤起解析或业务回调的必要条件。两个版本都以 `_sdk_=umeng` 识别 ULink URI，再把路径和查询参数交给业务监听器。原始 URI 另用于异步统计报告；报告返回不会阻塞或决定业务回调。因此可构造不含 `_ukid` 的稳定离线候选，保留真实分享业务字段和已观察到的固定 SDK 字段。123 云盘样本有壳，未能确认其业务监听器是否另外读取该参数；这部分仍需手机验收，不能将 SDK 结论写成目标应用已打开成功。

## 证据

| E-id | source_ref | 复现方式 | content_hash |
| --- | --- | --- | --- |
| E-01 | `analysis/cloud123-web-open/ulink-index.min.js` | 搜索 `deeplink/init`、`wakeupUrl`、`_ukid`。该 SDK 直接采用服务端返回的唤起 URL，本地没有 `_ukid` 字面量或参数校验 | SHA256 `f4b935922d875119ed78b0b8ea59452528b5e15598566d772ecaade856d083c8` |
| E-02 | 官方 Maven Central `com.umeng.umsdk:link:2.0.0`，下载源 `https://repo.maven.apache.org/maven2/com/umeng/umsdk/link/2.0.0/` | 读取 POM 的 umeng 开发者与官方仓库来源；JADX `-r` 反编译 AAR | AAR SHA256 `522b7780859d2f655a511fefb2dd9c4dc61724ae5b72482c2135bf00e22e999b`；POM SHA256 `a4b5a4f2b7fabbd5b02ae31e8d9fc99df9404654787538a08323873de726bb15` |
| E-03 | `analysis/cloud123-web-open/umeng-link-sdk/jadx/sources/com/umeng/umlink/internal/m.java:230-274` | 查看查询参数循环及 onLink 回调条件；全目录搜索 `_ukid` 无结果 | 同 E-02 |
| E-04 | 同目录 `internal/j.java:22-48` | 查看异步 Runnable 的 deviceFingerprint 报告，无业务回调结果依赖 | 同 E-02 |
| E-05 | 官方 Maven Central `com.umeng.umsdk:link:1.2.0`，`jadx-1.2.0/sources/com/umeng/umlink/MobclickLink.java:200-275` | 同样以 `_sdk_` 判别，最后直接 onLink；后台报告独立运行 | AAR SHA256 `0055c2b0751645447c9f2ce0725b63c4ea10ed77e2021183d9cb3d02ef7cb8db` |
| E-06 | 用户的 `123云盘_3.3.5.apk`、既有 `analysis/cloud123-3.3.5/intent-contract.json` | 全部 DEX/SO 字节查找 `_ukid` / `_linkid_` 均无可见命中；Manifest 已证 `pan` scheme | APK SHA256 `877615BE104B6C79857FF69A7BF89057A55AB06707C8C291A395AEFC8AD3E497` |

所有 SDK 文件保存在工作区 `analysis/cloud123-web-open/umeng-link-sdk/`。公共 SDK 是分析参照物，未被加入模块依赖或打包产物。此子任务没有访问真实分享页面或再次调用 init 接口。

## 发现

| F-id | severity | evidence_ids | confidence | location | status |
| --- | --- | --- | --- | --- | --- |
| F-01 | n/a_re | E-02,E-03,E-05 | 高 | 官方 ULink handleUMLinkURI | 已验证：SDK 不要求 `_ukid`；无该参数也能进入 onLink 条件路径 |
| F-02 | n/a_re | E-03,E-04,E-05 | 高 | SDK 业务回调与统计 Runnable | 已验证：网络统计结果不门控业务回调；URI 本身会被目标 SDK 用于统计，不能承诺目标应用启动后绝不联网 |
| F-03 | n/a_re | E-01,E-06 | 中 | 123 云盘业务监听 | 候选：省 `_ukid` 的离线 URI 是否被当前 123 业务处理器完整接受，尚未设备验证 |

`_ukid` 每次 init 可变化这一观察来自根代理的 dummy 请求证据，本子任务没有复发请求。结合独立统计路径，可以推断它可能承担统计关联作用；SDK 中没有这个名字，不能据此宣称已还原其服务器端精确定义。

## 建议构造策略

保留官方观测模板中的 `pan://umeng.com/share/list`、`_sdk_=umeng`、`_linkid_=usr1ke01qbgngpn9`、`_bizType_=ushare` 与业务 `action=share_list`、`shareKey`、`sharePwd`，仅省略 `_ukid`。空密码按原模板保留空值。对真实分享字段使用 URI builder 编码并保持大小写，不加入随机占位 `_ukid`，不使用任何 dummy 响应的 `_ukid`。

该建议在相同输入下生成相同 URI，避免每次重新规划时 filterEquals 因随机参数失败。模块无需请求 init 或上报真实用户分享链接。固定 SDK 字段本身不等于永久公开协议；未来网页模板或目标应用升级可能需要重新核对。

## 调用路径与验收限制

P-01，path_type=callflow：模块离线构造稳定 pan URI → 系统根据导出的 pan 过滤器启动目标 → 目标 ULink SDK 解析路径与参数、确认 `_sdk_=umeng` → onLink(path,map) → 123 云盘自己的业务监听器处理 share_list。E-03/E-05 证明 SDK 一段；最后的 123 业务处理仍是 F-03。

本子任务只做静态分析，没有运行原生 SDK、Gradle、目标应用、手机测试，也没有修改生产代码。尚需验证“省 `_ukid` 时能打开当前分享”“密码分享字段不丢失”“冷启动和已运行状态均可处理”；不得用 Manifest 可解析或通用 SDK 静态路径替代这些验收。

时间线：2026-11-06 读取本地 JS 和 APK 证据 → 下载并核验官方 2.0.0 AAR/POM → 定位 SDK 参数循环及统计 Runnable → 用官方 1.2.0 交叉确认相同门控行为 → 整理本报告。附加依赖只用于分析，无模块依赖变化。
