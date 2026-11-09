package io.github.yudigaga.taplusbridge.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference

/** Both lists stay in the draft until one explicit save commits the selected mode and lists. */
@Composable internal fun SourceScopeSettingsScreen(
    controller: SettingsController,
    apps: List<SettingsController.AppEntry>,
    onSaved: () -> Unit,
) {
    val initial = remember(controller) { controller.sourceScope() }
    var managed by remember(controller) { mutableStateOf(initial.managed) }
    var configurationError by remember(controller) { mutableStateOf(controller.sourceScopeError()) }
    val context = LocalContext.current
    var modeName by rememberSaveable { mutableStateOf(initial.mode.name) }
    var blacklist by rememberSaveable { mutableStateOf(ArrayList(initial.blacklist.sorted())) }
    var whitelist by rememberSaveable { mutableStateOf(ArrayList(initial.whitelist.sorted())) }
    var query by rememberSaveable { mutableStateOf("") }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var saveError by rememberSaveable { mutableStateOf("") }
    val mode = SourceScopePolicy.Mode.valueOf(modeName)
    val selected = if (mode == SourceScopePolicy.Mode.BLACKLIST) blacklist else whitelist
    val appMap = remember(apps) { apps.associateBy { it.packageName } }
    val candidates = remember(apps, query, selected) {
        val filter = query.trim()
        apps.filter { it.packageName !in selected && (it.label.contains(filter, true) || it.packageName.contains(filter, true)) }
    }
    val selectedApps = remember(apps, selected) { apps.filter { it.packageName in selected } }
    val missing = remember(selected, appMap) {
        selected.filter { it !in appMap }.sorted()
    }
    fun select(packageName: String, checked: Boolean) {
        val next = selected.toMutableSet()
        if (checked) next.add(packageName) else next.remove(packageName)
        if (mode == SourceScopePolicy.Mode.BLACKLIST) blacklist = ArrayList(next.sorted())
        else whitelist = ArrayList(next.sorted())
        saveError = ""
    }
    fun discardDraft() {
        val persisted = controller.sourceScope()
        managed = persisted.managed
        configurationError = controller.sourceScopeError()
        modeName = persisted.mode.name
        blacklist = ArrayList(persisted.blacklist.sorted())
        whitelist = ArrayList(persisted.whitelist.sorted())
        query = ""
        showPicker = false
        saveError = ""
    }
    Scaffold(
        modifier = Modifier.testTag("page.source_scope"),
        topBar = {
            SettingsPageHeader(
                title = "生效应用",
                subtitle = "选择传送门可使用的来源应用",
                discardTag = "source_scope.back",
                saveTag = "source_scope.save",
                discardText = "放弃名单",
                saveText = "保存名单",
                onDiscard = ::discardDraft,
                onSave = {
                    try {
                        controller.saveSourceScope(mode, blacklist, whitelist)
                        managed = controller.sourceScope().managed
                        configurationError = controller.sourceScopeError()
                        saveError = ""
                        Toast.makeText(context, "已保存；来源应用可能需要退出后重新进入", Toast.LENGTH_LONG).show()
                        onSaved()
                    } catch (error: RuntimeException) {
                        saveError = error.message ?: "保存失败，请重试"
                    }
                })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("source_scope.list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                QuietText(if (managed) "修改暂存于本页，点击保存名单后生效；点击放弃名单可恢复已保存名单。"
                    else "保存后接管传送门的应用范围。首次保存前沿用传送门原设置。",
                    Modifier.padding(horizontal = 8.dp).testTag("source_scope.hint"))
            }
            if (configurationError != null) item {
                QuietText(configurationError,
                    Modifier.padding(horizontal = 8.dp).testTag("source_scope.configuration_error"))
            }
            item {
                SettingsCard {
                    RadioButtonPreference(title = "黑名单", summary = "列表中的应用不生效，其他应用允许使用",
                        selected = mode == SourceScopePolicy.Mode.BLACKLIST,
                        onClick = { modeName = SourceScopePolicy.Mode.BLACKLIST.name; saveError = "" },
                        modifier = Modifier.testTag("source_scope.mode.BLACKLIST")
                            .semantics { this.selected = mode == SourceScopePolicy.Mode.BLACKLIST })
                    RadioButtonPreference(title = "白名单", summary = "仅列表中的应用生效，空列表表示全部不生效",
                        selected = mode == SourceScopePolicy.Mode.WHITELIST,
                        onClick = { modeName = SourceScopePolicy.Mode.WHITELIST.name; saveError = "" },
                        modifier = Modifier.testTag("source_scope.mode.WHITELIST")
                            .semantics { this.selected = mode == SourceScopePolicy.Mode.WHITELIST })
                }
            }
            item {
                QuietText(if (mode == SourceScopePolicy.Mode.BLACKLIST)
                    "已选 ${selected.size} 个应用不生效；空黑名单允许所有应用。"
                else "已选 ${selected.size} 个应用生效；空白名单禁止所有应用。",
                    Modifier.padding(horizontal = 8.dp).testTag("source_scope.selection"))
            }
            if (saveError.isNotEmpty()) item {
                QuietText(saveError, Modifier.padding(horizontal = 8.dp).testTag("source_scope.error"))
            }
            item {
                SettingsCard {
                    ArrowPreference(title = "添加应用", summary = "从已安装应用中选择，添加后点击页面顶部保存名单",
                        onClick = { query = ""; showPicker = true }, modifier = Modifier.testTag("source_scope.add"))
                }
            }
            if (missing.isNotEmpty()) item { SectionLabel("已选应用 · 当前未安装或不可见") }
            items(missing, key = { "missing:$it" }) { packageName ->
                SettingsCard {
                    CheckboxPreference(title = packageName, summary = "保留已选记录；取消勾选可移除", checked = true,
                        onCheckedChange = { select(packageName, it) },
                        modifier = Modifier.testTag("source_scope.app.$packageName")
                            .semantics { toggleableState = ToggleableState.On })
                }
            }
            if (selectedApps.isNotEmpty()) item { SectionLabel("已选应用") }
            items(selectedApps, key = { it.packageName }) { app ->
                SettingsCard {
                    CheckboxPreference(title = app.label, summary = app.packageName + if (app.enabled) "" else " · 已停用",
                        checked = true, onCheckedChange = { select(app.packageName, it) },
                        startAction = { AppIcon(controller, app) }, modifier = Modifier.testTag("source_scope.app.${app.packageName}")
                            .semantics { toggleableState = ToggleableState.On })
                }
            }
            if (selected.isEmpty()) item {
                QuietText("尚未选择应用，点击添加应用编辑当前名单。", Modifier.padding(horizontal = 8.dp).testTag("source_scope.empty"))
            }
            item { QuietText("切换模式会保留两份名单，只有当前模式生效。保存后，来源应用可能需要退出后重新进入。",
                Modifier.padding(horizontal = 8.dp)) }
            item { QuietText("截图、识别通道与不可见点击可在识别页独立切换，开关立即保存；此页的保存和放弃只影响应用名单。",
                Modifier.padding(horizontal = 8.dp).testTag("source_scope.fallback_hint")) }
        }
    }
    if (showPicker) {
        OverlayDialog(show = true, title = "添加应用", summary = "选择后暂存于名单，返回页面点击保存名单后生效。",
            onDismissRequest = { showPicker = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalWindowInfo.current.containerDpSize.height * .62f).imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(query, { query = it }, label = "搜索应用名或包名", singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("source_scope.search"))
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("source_scope.picker.list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(candidates, key = { it.packageName }) { app ->
                        CheckboxPreference(title = app.label, summary = app.packageName + if (app.enabled) "" else " · 已停用",
                            checked = false, onCheckedChange = { select(app.packageName, it) },
                            startAction = { AppIcon(controller, app) }, modifier = Modifier.testTag("source_scope.candidate.${app.packageName}")
                                .semantics { toggleableState = ToggleableState.Off })
                    }
                    if (candidates.isEmpty()) item {
                        QuietText(when {
                            apps.isEmpty() -> "当前用户没有可添加的应用。"
                            query.isNotBlank() -> "没有匹配的未选应用，请调整搜索内容。"
                            else -> "已安装应用均已加入当前名单。"
                        }, Modifier.padding(16.dp).testTag("source_scope.picker.empty"))
                    }
                }
                TextButton("完成", { showPicker = false }, Modifier.fillMaxWidth().testTag("source_scope.picker.close"),
                    colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}

internal fun sourceScopeSummary(scope: SourceScopePolicy): String = if (!scope.managed) "尚未接管 · 保存后按黑名单或白名单生效"
else if (scope.mode == SourceScopePolicy.Mode.BLACKLIST) "黑名单 · ${scope.blacklist.size} 个应用不生效"
else "白名单 · 仅 ${scope.whitelist.size} 个应用生效"
