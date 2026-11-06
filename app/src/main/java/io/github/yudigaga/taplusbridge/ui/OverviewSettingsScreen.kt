package io.github.yudigaga.taplusbridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yudigaga.taplusbridge.config.ConfigStore
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy
import io.github.yudigaga.taplusbridge.runtime.RuntimePublisher
import io.github.yudigaga.taplusbridge.thaw.ThawEngine
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable internal fun OverviewSettingsScreen(
    snapshot: SettingsController.Snapshot,
    sourceScope: SourceScopePolicy,
    sourceScopeError: String?,
    independent: Boolean,
    controller: SettingsController,
    refresh: () -> Unit,
) {
    val context = LocalContext.current
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var runtimeStatus by remember { mutableStateOf(RuntimePublisher.status()) }
    var rootMessage by rememberSaveable { mutableStateOf("尚未检查 Root 授权") }
    var checkingRoot by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) { runtimeStatus = RuntimePublisher.status(); kotlinx.coroutines.delay(500) }
    }
    fun change(action: () -> Unit) {
        try { action(); error = "" } catch (failure: RuntimeException) { error = failure.message ?: "设置失败，请重试" }
        refresh()
    }
    Scaffold(modifier = Modifier.testTag("page.home"), topBar = { TopAppBar(title = "taplusbridge", subtitle = "识别、选择应用、按规则打开") }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("overview.list"), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SettingsCard {
                    SwitchPreference(snapshot.enabled, { change { controller.setEnabled(it) } }, title = "应用流转",
                        summary = "切换立即保存；控制模块打开、采集、拖动与替换翻译，应用原有识别及 Lens 单独运行",
                        modifier = Modifier.testTag("toggle.enabled").semantics { toggleableState = if (snapshot.enabled) ToggleableState.On else ToggleableState.Off })
                    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tag(if (snapshot.hostSupported) "传送门 ${snapshot.hostVersionName}" else if (snapshot.hostInstalled) "版本待适配" else "未检测到传送门", snapshot.hostSupported)
                        Tag("API 102")
                    }
                }
            }
            item { SectionLabel("已保存的设置") }
            item {
                SettingsCard {
                    OverviewStatus("识别增强", if (snapshot.recognitionEnabled) "已开启" else "已关闭 · 宿主识别照常", "overview.recognition")
                    OverviewStatus("生效应用", sourceScopeError ?: sourceScopeSummary(sourceScope), "overview.source_scope")
                    OverviewStatus("长按拖动", if (snapshot.dragSettings.enabled) "已开启" else "已关闭", "overview.drag")
                    OverviewStatus("Google Lens", if (snapshot.lensEnabled) "已开启" else "已关闭", "overview.lens")
                }
            }
            item {
                SettingsCard {
                    ArrowPreference(title = "运行与授权", summary = "${modeLabel(snapshot.mode)} · " + if (independent) "允许本体冻结后独立打开" else "独立打开已关闭",
                        onClick = { dialog = "runtime" }, modifier = Modifier.testTag("action.runtime"))
                    ArrowPreference(title = "关于与日志", summary = "${snapshot.versionName} · 传送门版本与诊断说明",
                        onClick = { dialog = "about" }, modifier = Modifier.testTag("action.about"))
                }
            }
            if (error.isNotEmpty()) item { QuietText(error, Modifier.padding(horizontal = 8.dp).testTag("overview.error")) }
            item { QuietText("识别能力在识别页设置，来源名单在应用页设置，打开目标在规则页设置。此处只展示已保存状态。", Modifier.padding(horizontal = 8.dp)) }
        }
    }
    dialog?.let { section ->
        val close = { dialog = null }
        key(section) {
            OverlayDialog(show = true, title = when (section) { "runtime" -> "运行与授权"; "logs" -> "模块日志"; else -> "关于与日志" },
                onDismissRequest = { if (section == "logs") dialog = "about" else close() }) {
                Column(Modifier.heightIn(max = LocalWindowInfo.current.containerDpSize.height * .60f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyColumn(Modifier.weight(1f, fill = false).testTag("overview.dialog.list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (error.isNotEmpty()) item { QuietText(error, Modifier.testTag("overview.dialog.error")) }
                        when (section) {
                            "runtime" -> {
                                item { QuietText("本组切换立即保存。独立打开只覆盖支持的路由能力，Google Lens 仍需要本体与可访问 Google 的网络。") }
                                item {
                                    SwitchPreference(independent, { value -> change { ConfigStore(context).setIndependentEnabled(value); RuntimePublisher.synchronize(context) } },
                                        title = "本体冻结后独立打开", summary = "Android 13+ 主用户；冻结前需等待系统确认配置",
                                        modifier = Modifier.testTag("toggle.independent").semantics { toggleableState = if (independent) ToggleableState.On else ToggleableState.Off })
                                }
                                item { ArrowPreference(title = "系统配置同步", summary = runtimeStatus,
                                    onClick = { RuntimePublisher.synchronize(context); runtimeStatus = RuntimePublisher.status() }, modifier = Modifier.testTag("action.runtime_sync")) }
                                item { SectionLabel("解冻方式") }
                                items(ThawEngine.Mode.values().size) { index ->
                                    val mode = ThawEngine.Mode.values()[index]
                                    RadioButtonPreference(title = modeLabel(mode), summary = modeSummary(mode), selected = snapshot.mode == mode,
                                        onClick = { change { controller.setMode(mode) } }, modifier = Modifier.testTag("mode.${mode.name}").semantics { selected = snapshot.mode == mode })
                                }
                                if (snapshot.mode == ThawEngine.Mode.ROOT) item {
                                    ArrowPreference(title = "检查 Root 授权", summary = if (checkingRoot) "正在检查…" else rootMessage,
                                        onClick = { if (!checkingRoot) { checkingRoot = true; controller.probeRoot { result -> checkingRoot = false; rootMessage = result.message; refresh() } } },
                                        modifier = Modifier.testTag("action.root"))
                                } else item {
                                    ArrowPreference(title = "请求 Shizuku 授权", summary = snapshot.shizukuStatus,
                                        onClick = { change { controller.requestShizukuPermission() } }, modifier = Modifier.testTag("action.shizuku"),
                                        endActions = { if (snapshot.shizukuGranted) Tag("已授权", true) })
                                }
                            }
                            "logs" -> item {
                                QuietText("模块日志由 LSPosed 统一管理，可在 LSPosed 中查看或导出。\n\n复现问题后导出日志，模块标签为 TaplusBridge。\n\n模块记录处理阶段与识别来源，不记录聊天正文或完整链接。",
                                    Modifier.testTag("message.body"))
                            }
                            else -> {
                                item { OverviewStatus("taplusbridge", snapshot.versionName, "about.version") }
                                item { OverviewStatus("传送门", snapshot.hostVersion, "about.host") }
                                item { OverviewStatus("界面", "Miuix", "about.ui") }
                                item { ArrowPreference(title = "日志与识别诊断", summary = "在 LSPosed 中查看阶段、识别来源及失败原因",
                                    onClick = { dialog = "logs" }, modifier = Modifier.testTag("action.logs")) }
                            }
                        }
                    }
                    if (section == "logs") TextButton("返回关于", { dialog = "about" }, Modifier.fillMaxWidth().testTag("message.close"))
                    else TextButton("完成", close, Modifier.fillMaxWidth().testTag("overview.dialog.close"), colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
    }
}

@Composable private fun OverviewStatus(label: String, value: String, tag: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag(tag).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        QuietText(value)
    }
}
