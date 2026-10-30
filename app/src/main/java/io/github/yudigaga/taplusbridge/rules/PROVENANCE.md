# IceBridge 规则核心来源

此目录的 LinkRuleEntry、ScopedRuleResolver、RuleEditorValidation 与手动规则传输模型改编自本地 `E:/vs/claude/IceBridge/IceBridge-master` 的 `automation/`，HEAD 为 `3ac3baa6273cb084c2563bd34d10d1f54e5da8ee`。

保留 IceBridge 的来源条件、manual 优先、来源精确优先、域名更具体优先、同优先级不同目标不猜和完整手动数据字段。0.7.0 按用户要求统一原始 HTTP(S) VIEW 交付，删除单应用URI模板，旧conversion字段仅格式兼容、不执行转换。配置仓库/在线传输另行接入，规则不包含可执行 JavaScript、Shell 或任意 Intent 动作。

手动JSON传输增加完整词法预检与重复键/未知字段拒绝，保留有效IceBridge v1格式的兼容性。0.7.0 在线快照使用带版本Base64保存原始UTF-8，避免XML冷载换行归一破坏校验，主备及高水位仍同一偏好事务。

当前 IceBridge 的 FluidCloudRuleCatalog 没有内置运行时 host 候选，因此本模块也不重新定义旧传送门7应用表。`assets/icebridge-rules/manifest.json`、`rules.json`、`revisions/rules-000001.json` 是 IceBridge `online-rules/` 的原始字节复制，作为首次初始化的已校验在线离线基线；32条候选、revision 1、SHA256 `e840f50919b022b085621c34394c7d09fde1373e22467e490bbb8c8633a05203`。网络成功快照全量替代它，接受空撤回后不能重新恢复基线。

原基线保留上游 `nyehueh/shortx-Fluid_Cloud` commit `c343298036121b3841e7d16024e68317ba63c541` 的数据来源声明；没有移植或执行上游动作脚本。B站官方版和PiliPlus是独立同优先级候选，同时可用时须通过用户禁用目标组或手动覆盖消除冲突。
