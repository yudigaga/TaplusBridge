package io.github.yudigaga.taplusbridge.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yudigaga.taplusbridge.rules.LinkRuleEntry
import io.github.yudigaga.taplusbridge.hook.HostPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

@Composable internal fun RuleSettingsScreen(
    controller: SettingsController,
    apps: List<SettingsController.AppEntry>,
    revision: Int,
    nativeEnabled: Boolean,
    cloudLinkHandoffEnabled: Boolean,
    bottomBar: @Composable () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries = remember(revision) { controller.ruleEntries() }
    val manual = remember(revision) { controller.manualRuleEntries() }
    val disabled = remember(revision) { controller.disabledTargets() }
    val online = remember(revision) { controller.onlineRulesState() }
    val appMap = remember(apps) { apps.associateBy { it.packageName } }
    var query by rememberSaveable { mutableStateOf("") }
    var editor by rememberSaveable { mutableStateOf(false) }
    var manualIndex by rememberSaveable { mutableIntStateOf(-1) }
    var draftHost by remember { mutableStateOf("") }
    var draftTarget by rememberSaveable { mutableStateOf("") }
    var draftSource by rememberSaveable { mutableStateOf("*") }
    var draftNote by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var formError by remember { mutableStateOf("") }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyInstalled by rememberSaveable { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf("") }
    val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    fun changeRules(action: () -> Unit) {
        try { action(); onChanged() }
        catch (error: RuntimeException) {
            onChanged(); message = error.message ?: "规则保存失败，请重试"; dialog = "message"
        }
    }
    fun openEditor(entry: LinkRuleEntry? = null, target: String = "") {
        manualIndex = if (entry?.source == LinkRuleEntry.SOURCE_MANUAL) manual.indexOf(entry) else -1
        draftHost = entry?.host.orEmpty(); draftTarget = entry?.targetPackage ?: target
        draftSource = entry?.sourcePackage ?: "*"
        draftNote = entry?.note.orEmpty(); advanced = false; formError = ""; editor = true
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { controller.importRules(readRuleFile(context, uri)) }
                onChanged(); toast("已导入 $result 条手动规则")
            } catch (error: Exception) { message = error.message ?: "无法读取规则文件"; dialog = "message" }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法写入所选文件" }
                        .use { it.write(pendingExport.toByteArray(Charsets.UTF_8)) }
                }
                toast("手动规则已导出")
            } catch (error: Exception) { message = error.message ?: "导出失败"; dialog = "message" }
        }
    }
    BackHandler(editor) { editor = false; formError = "" }
    Scaffold(
        modifier = Modifier.testTag(if (editor) "page.rule_editor" else "page.rules"),
        topBar = {
            TopAppBar(title = if (editor) if (manualIndex < 0) "添加规则" else "编辑规则" else "流转规则",
                subtitle = if (editor) "选择目标应用，填写域名或协议" else "按域名或协议匹配应用",
                navigationIcon = {
                    if (editor) IconButton({ editor = false }) { Icon(SettingsIcons.Back, "返回") }
                }, actions = {
                    if (!editor) IconButton({ openEditor() }, Modifier.testTag("rules.add")) { Icon(SettingsIcons.Plus, "添加规则") }
                    else TextButton("保存", {
                        try {
                            controller.saveIceRule(manualIndex, draftSource, draftHost, draftTarget, LinkRuleEntry.CONVERSION_NONE, draftNote)
                            onChanged(); editor = false; toast("规则已保存")
                        } catch (error: RuntimeException) { formError = error.message ?: "规则格式有误" }
                    }, Modifier.testTag("editor.save"))
                })
        },
        bottomBar = { if (!editor) bottomBar() },
    ) { padding ->
        if (editor) {
            LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { SectionLabel("应用与网站") }
                item {
                    SettingsCard {
                        ArrowPreference(title = "目标应用", summary = appMap[draftTarget]?.label ?: draftTarget.ifBlank { "选择已安装或冻结的应用" },
                            onClick = { picker = "target" }, modifier = Modifier.testTag("editor.pick_target"),
                            startAction = { AppIcon(appMap[draftTarget]?.icon) })
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextField(draftHost, { draftHost = it }, label = "域名、网址或协议", singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("editor.host"))
                            QuietText("网页填写域名或完整网址；磁力填写 magnet:，电驴填写 ed2k:。也可粘贴完整链接，保存时提取匹配条件，按目标应用支持的入口打开。")
                        }
                    }
                }
                item {
                    SettingsCard {
                        ArrowPreference(title = "高级选项", summary = if (advanced) "来源应用与备注" else "默认所有来源，备注可选",
                            onClick = { advanced = !advanced }, modifier = Modifier.testTag("editor.advanced"))
                    }
                }
                if (advanced) item {
                    SettingsCard {
                        ArrowPreference(title = "来源应用", summary = if (draftSource == "*" || draftSource.isBlank()) "所有应用" else appMap[draftSource]?.label ?: draftSource,
                            onClick = { picker = "source" }, modifier = Modifier.testTag("editor.pick_source"))
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            TextField(draftSource, { draftSource = it }, label = "来源包名，* 表示所有应用", singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("editor.source"))
                        }
                    }
                }
                if (advanced) item {
                    SettingsCard {
                        Column(Modifier.padding(16.dp)) {
                            TextField(draftNote, { draftNote = it }, label = "备注（选填）", maxLines = 3,
                                modifier = Modifier.fillMaxWidth().testTag("editor.note"))
                        }
                    }
                }
                if (formError.isNotEmpty()) item { Text(formError, Modifier.padding(8.dp).testTag("editor.error"), color = MiuixTheme.colorScheme.error, fontSize = 14.sp) }
                if (manualIndex >= 0) item { TextButton("删除这条规则", { dialog = "delete" }, Modifier.fillMaxWidth().testTag("editor.delete")) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        } else {
            val groups = entries.groupBy { it.targetPackage }.filter { (pkg, rules) ->
                (!onlyInstalled || pkg in appMap) && (query.isBlank() || pkg.contains(query, true)
                    || appMap[pkg]?.label?.contains(query, true) == true || rules.any { it.host.contains(query, true) || it.sourcePackage.contains(query, true) })
            }.toList().sortedWith(compareBy({ it.first !in appMap }, { appMap[it.first]?.label ?: it.first }))
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    SettingsCard {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text("${entries.size} 条规则", fontSize = 21.sp, fontWeight = FontWeight.SemiBold); QuietText("${entries.map { it.targetPackage }.distinct().size} 个目标应用 · 手动优先") }
                                Tag("IceBridge", true)
                            }
                            TextField(query, { query = it }, label = "搜索应用、域名、协议或来源", singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("rules.search"))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(if (onlyInstalled) "仅已安装 ✓" else "全部应用", { onlyInstalled = !onlyInstalled }, Modifier.testTag("rules.installed"))
                                TextButton("匹配预览", { dialog = "preview" }, Modifier.testTag("rules.preview"))
                            }
                        }
                    }
                }
                item {
                    SettingsCard {
                        ArrowPreference(title = "打开行为", summary = "网盘适配${if (cloudLinkHandoffEnabled) "已开启" else "已关闭"} · 原生兜底${if (nativeEnabled) "已开启" else "已关闭"}",
                            onClick = { dialog = "behavior" }, modifier = Modifier.testTag("rules.behavior"))
                        ArrowPreference(title = "规则管理", summary = "在线规则、手动规则导入导出与清空",
                            onClick = { dialog = "manage" }, modifier = Modifier.testTag("rules.manage"))
                    }
                }
                if (groups.isEmpty()) item {
                    SettingsCard {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (entries.isEmpty()) "尚无规则" else if (query.isNotBlank()) "没有搜索结果" else "没有符合已安装筛选的应用", fontWeight = FontWeight.SemiBold)
                            QuietText(if (entries.isEmpty()) "可以同步在线规则，或选择应用添加网站规则。"
                                else "本地仍保留 ${entries.size} 条规则，可清除搜索或切换全部应用查看。")
                        }
                    }
                }
                items(groups, key = { it.first }) { (pkg, rules) ->
                    val open = expanded == pkg || query.isNotBlank()
                    val app = appMap[pkg]
                    SettingsCard {
                        Row(Modifier.fillMaxWidth().clickable { expanded = if (open) null else pkg }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppIcon(app?.icon)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(app?.label ?: pkg, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                QuietText("${rules.size} 条规则" + if (app == null) " · 未安装" else "")
                            }
                            Switch(pkg !in disabled, { value -> changeRules { controller.setTargetEnabled(pkg, value) } }, Modifier.testTag("rule.target.$pkg"))
                        }
                        if (open) {
                            rules.forEach { rule ->
                                ArrowPreference(title = rule.host,
                                    summary = (if (rule.source == LinkRuleEntry.SOURCE_MANUAL) "手动" else "在线") + " · " +
                                        (if (rule.sourcePackage == "*") "所有来源" else appMap[rule.sourcePackage]?.label ?: rule.sourcePackage),
                                    onClick = { openEditor(rule) },
                                    modifier = Modifier.testTag("rule.row.${rule.host}.$pkg"))
                            }
                            TextButton("为此应用添加手动规则", { openEditor(target = pkg) }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
                        } else QuietText(rules.map { it.host }.distinct().take(3).joinToString(" · ") + if (rules.size > 3) " …" else "",
                            Modifier.padding(start = 68.dp, end = 16.dp, bottom = 16.dp))
                    }
                }
                item { QuietText("同一域名或协议有多个可用目标时不会猜测。停用不使用的应用，或添加更明确的来源条件。", Modifier.padding(8.dp)) }
            }
        }
    }
    if (picker != null) {
        ApplicationPicker(apps, picker == "source", {
            if (picker == "source") draftSource = it else draftTarget = it
            picker = null
        }) { picker = null }
    } else key(dialog) { when (dialog) {
        "message" -> MessageDialog("规则操作", message) { dialog = null }
        "delete" -> ConfirmDialog("删除规则", "只删除这条手动规则，在线规则仍保留。", { dialog = null }) {
            changeRules { controller.deleteIceRule(manualIndex); editor = false; dialog = null }
        }
        "clear" -> ConfirmDialog("清空手动规则", "已保存的手动规则将被删除，在线缓存不会改变。", { dialog = "manage" }) {
            changeRules { controller.clearManualRules(); dialog = "manage" }
        }
        "preview" -> RulePreview(controller) { dialog = null }
        "behavior" -> OverlayDialog(show = true, title = "打开行为", onDismissRequest = { dialog = null }) {
            Column(Modifier.heightIn(max = LocalWindowInfo.current.containerDpSize.height * .60f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    SwitchPreference(cloudLinkHandoffEnabled, { value -> changeRules { controller.setCloudLinkHandoffEnabled(value) } },
                        title = "网盘链接适配", summary = "使用已适配网盘的应用入口打开分享链接；关闭后按原网址流转。切换立即保存。",
                        modifier = Modifier.testTag("toggle.cloud_handoff")
                            .semantics { toggleableState = if (cloudLinkHandoffEnabled) ToggleableState.On else ToggleableState.Off })
                    if (cloudLinkHandoffEnabled) QuietText("已适配：123、百度、阿里、夸克、115、迅雷的指定分享地址。115、迅雷使用应用内网页；阿里暂仅无提取码链接。其他网盘保留原网址。磁力与 ed2k 始终按协议规则流转。", Modifier.padding(horizontal = 16.dp))
                    SwitchPreference(nativeEnabled, { value -> changeRules { controller.setNativeEnabled(value) } },
                        title = "原生目标兜底", summary = "无匹配规则时，接管传送门提供的原生目标；规则命中不受影响。切换立即保存。",
                        modifier = Modifier.testTag("toggle.native")
                            .semantics { toggleableState = if (nativeEnabled) ToggleableState.On else ToggleableState.Off })
                }
                TextButton("完成", { dialog = null }, Modifier.fillMaxWidth().testTag("rules.behavior.close"),
                    colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
        "manage" -> OverlayDialog(show = true, title = "规则管理", onDismissRequest = { dialog = null }) {
            Column(Modifier.heightIn(max = LocalWindowInfo.current.containerDpSize.height * .60f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    ArrowPreference(title = "在线规则", summary = "本地缓存 r${online.revision} · ${online.ruleCount} 条" + if (online.sourceName.isNullOrBlank()) "" else " · ${online.sourceName}",
                        onClick = { dialog = "online" }, modifier = Modifier.testTag("rules.online"), endActions = { Tag(if (updating) "同步中" else "管理") })
                    ArrowPreference(title = "导入手动规则", summary = "兼容 IceBridge JSON 规则文件",
                        onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.testTag("rules.import"))
                    ArrowPreference(title = "导出手动规则", summary = "保存来源、网站、目标与备注", onClick = {
                        try { pendingExport = controller.exportRules(); exporter.launch("TaplusBridge-rules.json") }
                        catch (error: RuntimeException) { message = error.message ?: "导出失败"; dialog = "message" }
                    }, modifier = Modifier.testTag("rules.export"))
                    ArrowPreference(title = "清空手动规则", summary = "保留已校验的在线规则", onClick = { dialog = "clear" }, modifier = Modifier.testTag("rules.clear"))
                }
                TextButton("完成", { dialog = null }, Modifier.fillMaxWidth().testTag("rules.manage.close"),
                    colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
        "online" -> OverlayDialog(show = true, title = "在线规则", onDismissRequest = { dialog = "manage" }) {
            Column(Modifier.heightIn(max = LocalWindowInfo.current.containerDpSize.height * .60f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    QuietText("使用 IceBridge 自有规则源，完整快照校验后替换，手动规则不受刷新影响。")
                    SwitchPreference(online.enabled, { value -> changeRules { controller.setOnlineUpdatesEnabled(value) } }, title = "允许在线更新", summary = "关闭后仍使用已校验的缓存", modifier = Modifier.testTag("online.enabled"))
                    QuietText("缓存修订：r${online.revision}\n缓存规则：${online.ruleCount} 条\n来源：${online.sourceName}\n状态：${online.status}", Modifier.testTag("online.cache"))
                    QuietText("关闭更新仍使用已有缓存，重新打开页面不会清空规则。")
                    TextButton(if (updating) "正在同步…" else "立即同步", {
                        if (!updating) {
                            updating = true
                            controller.refreshOnlineRules { result -> updating = false; onChanged(); toast(result.message) }
                        }
                    }, Modifier.fillMaxWidth().testTag("online.refresh"), enabled = online.enabled && !updating, colors = ButtonDefaults.textButtonColorsPrimary())
                }
                TextButton("返回规则管理", { dialog = "manage" }, Modifier.fillMaxWidth().testTag("online.close"))
            }
        }
    } }
}

@Composable private fun ApplicationPicker(apps: List<SettingsController.AppEntry>, source: Boolean,
                                          selected: (String) -> Unit, dismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val maxHeight = LocalWindowInfo.current.containerDpSize.height * .60f
    val found = remember(query, apps, source) { apps.filter { (source || HostPolicy.validTarget(it.packageName)) && (it.label.contains(query, true) || it.packageName.contains(query, true)) } }
    OverlayDialog(show = true, title = if (source) "选择来源应用" else "选择目标应用", onDismissRequest = dismiss) {
        Column(Modifier.heightIn(max = maxHeight), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(query, { query = it }, label = "搜索应用名或包名", singleLine = true, modifier = Modifier.fillMaxWidth().testTag("picker.search"))
            LazyColumn(Modifier.weight(1f, fill = false).testTag("picker.apps")) {
                if (source) item { ArrowPreference(title = "所有应用", summary = "不限来源", onClick = { selected("*") }) }
                items(found, key = { it.packageName }) { app ->
                    ArrowPreference(title = app.label, summary = app.packageName, startAction = { AppIcon(app.icon) },
                        onClick = { selected(app.packageName) }, modifier = Modifier.testTag("picker.${app.packageName}"))
                }
                if (found.isEmpty()) item { QuietText("没有找到应用，请确认已安装在当前用户中。", Modifier.padding(16.dp)) }
            }
            TextButton("取消", dismiss, Modifier.fillMaxWidth())
        }
    }
}

@Composable private fun RulePreview(controller: SettingsController, dismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<SettingsController.Preview?>(null) }
    OverlayDialog(show = true, title = "匹配预览", summary = "只检查规则，不打开或解冻应用", onDismissRequest = dismiss) {
        Column(Modifier.heightIn(max = LocalWindowInfo.current.containerDpSize.height * .60f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(url, { url = it }, label = "粘贴网页、磁力或 ed2k 链接", singleLine = true, modifier = Modifier.fillMaxWidth().testTag("preview.url"))
            TextField(source, { source = it }, label = "来源包名（可留空）", singleLine = true, modifier = Modifier.fillMaxWidth().testTag("preview.source"))
            result?.let { Text(it.title, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("preview.result")); QuietText(it.detail) }
            TextButton("检查匹配", { result = controller.matchPreview(url, source) }, Modifier.fillMaxWidth().testTag("preview.check"), colors = ButtonDefaults.textButtonColorsPrimary())
            TextButton("关闭", dismiss, Modifier.fillMaxWidth())
        }
    }
}

private fun readRuleFile(context: Context, uri: Uri): String {
    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开所选文件" }.use { stream ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 1_048_576) { "规则文件超过 1 MiB" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}
