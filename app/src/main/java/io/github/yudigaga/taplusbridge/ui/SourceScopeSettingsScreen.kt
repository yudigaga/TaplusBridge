package io.github.yudigaga.taplusbridge.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** Both lists stay in the draft until one explicit save commits the selected mode and lists. */
@Composable internal fun SourceScopeSettingsScreen(
    controller: SettingsController,
    apps: List<SettingsController.AppEntry>,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val initial = remember(controller) { controller.sourceScope() }
    val configurationError = remember(controller) { controller.sourceScopeError() }
    val context = LocalContext.current
    var modeName by rememberSaveable { mutableStateOf(initial.mode.name) }
    var blacklist by rememberSaveable { mutableStateOf(ArrayList(initial.blacklist.sorted())) }
    var whitelist by rememberSaveable { mutableStateOf(ArrayList(initial.whitelist.sorted())) }
    var captureFallback by rememberSaveable { mutableStateOf(controller.scopeCaptureEnabled()) }
    var linkProbe by rememberSaveable { mutableStateOf(controller.scopeLinkProbeEnabled()) }
    var query by rememberSaveable { mutableStateOf("") }
    var saveError by rememberSaveable { mutableStateOf("") }
    val mode = SourceScopePolicy.Mode.valueOf(modeName)
    val selected = if (mode == SourceScopePolicy.Mode.BLACKLIST) blacklist else whitelist
    val appMap = remember(apps) { apps.associateBy { it.packageName } }
    val found = remember(apps, query) {
        val filter = query.trim()
        apps.filter { it.label.contains(filter, true) || it.packageName.contains(filter, true) }
    }
    val missing = remember(selected, appMap, query) {
        selected.filter { it !in appMap && it.contains(query.trim(), true) }.sorted()
    }
    fun select(packageName: String, checked: Boolean) {
        val next = selected.toMutableSet()
        if (checked) next.add(packageName) else next.remove(packageName)
        if (mode == SourceScopePolicy.Mode.BLACKLIST) blacklist = ArrayList(next.sorted())
        else whitelist = ArrayList(next.sorted())
        saveError = ""
    }
    BackHandler(onBack = onDismiss)
    Scaffold(
        modifier = Modifier.testTag("page.source_scope"),
        topBar = {
            TopAppBar(title = "生效应用", subtitle = "选择传送门可使用的来源应用",
                navigationIcon = {
                    IconButton(onDismiss, Modifier.testTag("source_scope.back")) { Icon(SettingsIcons.Back, "返回") }
                }, actions = {
                    TextButton("保存", {
                        try {
                            controller.saveSourceScope(mode, blacklist, whitelist, captureFallback, linkProbe)
                            Toast.makeText(context, "已保存；来源应用可能需要退出后重新进入", Toast.LENGTH_LONG).show()
                            onSaved()
                        } catch (error: RuntimeException) {
                            saveError = error.message ?: "保存失败，请重试"
                        }
                    }, Modifier.testTag("source_scope.save"))
                })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("source_scope.list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                QuietText(if (initial.managed) "设置来源应用是否允许触发传送门。返回可放弃本次修改。"
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
                SettingsCard {
                    SwitchPreference(captureFallback, { captureFallback = it; saveError = "" },
                        title = "截图识别兜底", summary = "原生无法取数时，在允许的应用内松开长按后截图识别",
                        modifier = Modifier.testTag("source_scope.capture_fallback")
                            .semantics { toggleableState = if (captureFallback) ToggleableState.On else ToggleableState.Off })
                }
            }
            item {
                SettingsCard {
                    SwitchPreference(linkProbe, { linkProbe = it; saveError = "" },
                        title = "长按取链接", summary = "长按松开后自动点一次原位置，拦截网页启动并提取链接",
                        modifier = Modifier.testTag("source_scope.link_probe")
                            .semantics { toggleableState = if (linkProbe) ToggleableState.On else ToggleableState.Off })
                    QuietText("链接补充到原生识别结果，保留图片和文字操作；截图仍由兜底开关控制。仅对确认会打开链接的入口使用，普通按钮可能执行原操作",
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).testTag("source_scope.link_probe_hint"))
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
                TextField(query, { query = it }, label = "搜索应用名或包名", singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("source_scope.search"))
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
            if (found.isNotEmpty()) item { SectionLabel("已安装应用") }
            items(found, key = { it.packageName }) { app ->
                SettingsCard {
                    CheckboxPreference(title = app.label, summary = app.packageName + if (app.enabled) "" else " · 已停用",
                        checked = app.packageName in selected, onCheckedChange = { select(app.packageName, it) },
                        startAction = { AppIcon(app.icon) }, modifier = Modifier.testTag("source_scope.app.${app.packageName}")
                            .semantics { toggleableState = if (app.packageName in selected) ToggleableState.On else ToggleableState.Off })
                }
            }
            if (found.isEmpty() && missing.isEmpty()) item {
                QuietText("没有找到应用，请调整搜索内容或确认应用已安装在当前用户中。", Modifier.padding(16.dp))
            }
            item { QuietText("切换模式会保留两份名单，只有当前模式生效。保存后，来源应用可能需要退出后重新进入。",
                Modifier.padding(horizontal = 8.dp)) }
            item { QuietText("截图识别兜底默认关闭，开启并保存后才生效。仅主用户、Android 13+；锁屏、安全窗口和显示输入法时不截图。",
                Modifier.padding(horizontal = 8.dp).testTag("source_scope.fallback_hint")) }
        }
    }
}

internal fun sourceScopeSummary(scope: SourceScopePolicy): String = if (!scope.managed) "尚未接管 · 保存后按黑名单或白名单生效"
else if (scope.mode == SourceScopePolicy.Mode.BLACKLIST) "黑名单 · ${scope.blacklist.size} 个应用不生效"
else "白名单 · 仅 ${scope.whitelist.size} 个应用生效"
