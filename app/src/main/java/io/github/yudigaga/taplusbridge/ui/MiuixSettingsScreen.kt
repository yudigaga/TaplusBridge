package io.github.yudigaga.taplusbridge.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import io.github.yudigaga.taplusbridge.thaw.ThawEngine
import io.github.yudigaga.taplusbridge.runtime.RuntimePublisher
import io.github.yudigaga.taplusbridge.config.ConfigStore
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

object MiuixSettingsScreen {
    @JvmStatic fun install(activity: MainActivity, controller: SettingsController) {
        activity.enableEdgeToEdge()
        activity.setContent {
            SettingsTheme {
                // OverlayDialog must be inside the root Miuix popup host, including dialogs
                // declared alongside a page's nested Scaffold.
                Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
                    SettingsApplication(activity, controller)
                }
            }
        }
    }
}

@Composable internal fun SettingsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    SideEffect {
        (context as? MainActivity)?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme(), content = content)
}

@Composable internal fun SettingsApplication(activity: MainActivity, controller: SettingsController) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf(controller.snapshot()) }
    var apps by remember { mutableStateOf<List<SettingsController.AppEntry>>(emptyList()) }
    var rootMessage by rememberSaveable { mutableStateOf("尚未检查 Root 授权") }
    var checkingRoot by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var showModes by rememberSaveable { mutableStateOf(false) }
    var runtimeStatus by remember { mutableStateOf(RuntimePublisher.status()) }
    var independent by remember { mutableStateOf(ConfigStore(activity).independentEnabled()) }
    LaunchedEffect(Unit) {
        while (true) { runtimeStatus = RuntimePublisher.status(); kotlinx.coroutines.delay(1000) }
    }
    val compact = LocalWindowInfo.current.containerDpSize.width < 360.dp || LocalDensity.current.fontScale > 1.2f
    val refresh: () -> Unit = {
        snapshot = controller.snapshot()
        independent = ConfigStore(activity).independentEnabled()
        runtimeStatus = RuntimePublisher.status()
        revision++
    }
    DisposableEffect(activity, controller) {
        activity.setRefreshListener { refresh(); controller.loadInstalledApps { apps = it } }
        controller.setAuthorizationRefresh(refresh)
        controller.loadInstalledApps { apps = it }
        onDispose { activity.setRefreshListener(null); controller.setAuthorizationRefresh(null) }
    }
    val toast: (String) -> Unit = { Toast.makeText(activity, it, Toast.LENGTH_SHORT).show() }
    val nav: @Composable () -> Unit = {
        NavigationBar(showDivider = false, modifier = Modifier.testTag("settings.navigation")) {
            NavigationBarItem(page == 0, { page = 0 }, SettingsIcons.Home, "首页", Modifier.testTag("nav.home"))
            NavigationBarItem(page == 1, { page = 1 }, SettingsIcons.Rules, "规则", Modifier.testTag("nav.rules"))
            NavigationBarItem(page == 2, { page = 2 }, SettingsIcons.More, "更多", Modifier.testTag("nav.more"))
        }
    }
    BackHandler(page != 0) { page = 0 }
    if (page == 1) {
        RuleSettingsScreen(controller, apps, revision, nav, onChanged = refresh)
    } else {
        Scaffold(
            modifier = Modifier.testTag("settings.root"),
            topBar = { TopAppBar(title = if (page == 0) "taplusbridge" else "更多",
                subtitle = if (page == 0) "识别之后，直达应用" else "授权、日志与版本") },
            bottomBar = nav
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).testTag(if (page == 0) "page.home" else "page.more"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (page == 0) {
                    item {
                        SettingsCard {
                            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (!compact) Box(Modifier.size(48.dp).background(MiuixTheme.colorScheme.tertiaryContainer,
                                    androidx.compose.foundation.shape.RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                                    Icon(SettingsIcons.Link, null, Modifier.size(27.dp), MiuixTheme.colorScheme.primary)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(if (snapshot.enabled) "应用流转已开启" else "应用流转已关闭", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                                    QuietText(if (snapshot.enabled) "将识别结果交给你选择的应用" else "开启后使用链接增强与解冻流转")
                                }
                                Switch(snapshot.enabled, { controller.setEnabled(it); refresh() }, Modifier.testTag("toggle.enabled"))
                            }
                            Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Tag(if (snapshot.hostSupported) "传送门 ${snapshot.hostVersionName}" else if (snapshot.hostInstalled) "版本待适配" else "未检测到传送门", snapshot.hostSupported)
                                Tag("API 102")
                            }
                        }
                    }
                    item { SectionLabel("识别与打开") }
                    item {
                        SettingsCard {
                            SwitchPreference(snapshot.recognitionEnabled, { controller.setRecognitionEnabled(it); refresh() },
                                title = "增强链接识别", summary = "补充文字与卡片中已有的链接", modifier = Modifier.testTag("toggle.recognition"))
                            SwitchPreference(snapshot.nativeEnabled, { controller.setNativeEnabled(it); refresh() },
                                title = "原生链接接管", summary = "保留原内容，交给目标应用打开", modifier = Modifier.testTag("toggle.native"))
                        }
                    }
                    item {
                        SettingsCard {
                            SwitchPreference(snapshot.captureEnabled, { controller.setCaptureEnabled(it); refresh() },
                                title = "自动识别分享卡片", summary = if (snapshot.recognitionEnabled) "长按后静默提取链接，直接显示目标应用" else "需要先开启增强链接识别",
                                enabled = snapshot.enabled && snapshot.recognitionEnabled, modifier = Modifier.testTag("toggle.capture"))
                            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 15.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Tag("实验功能", true)
                                QuietText("仅对可安全定位的卡片进行探测", Modifier.weight(1f))
                            }
                        }
                    }
                    item { SectionLabel("解冻与流转") }
                    item {
                        SettingsCard {
                            ArrowPreference(title = "解冻授权", summary = modeSummary(snapshot.mode), onClick = { showModes = true },
                                modifier = Modifier.testTag("action.mode"), endActions = { Tag(modeLabel(snapshot.mode), true) })
                            ArrowPreference(title = "流转规则", summary = "选择应用、填写网站，匹配后原样打开链接",
                                onClick = { page = 1 }, modifier = Modifier.testTag("home.rules"))
                        }
                    }
                    item { QuietText("LSPosed 勾选「传送门」与「系统框架」，更新模块后重启。无需勾选微信。", Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
                } else {
                    item { SectionLabel("冻结后运行") }
                    item {
                        SettingsCard {
                            SwitchPreference(independent, { value ->
                                independent = value; ConfigStore(activity).setIndependentEnabled(value)
                                RuntimePublisher.synchronize(activity)
                            }, title = "冻结本体后继续运行", summary = "Android 13+ 主用户；冻结期间由系统框架处理", modifier = Modifier.testTag("toggle.independent"))
                            ArrowPreference(title = "系统配置同步", summary = runtimeStatus,
                                onClick = { RuntimePublisher.synchronize(activity) }, modifier = Modifier.testTag("action.runtime_sync"))
                        }
                    }
                    item { SectionLabel("权限与服务") }
                    item {
                        SettingsCard {
                            if (snapshot.mode == ThawEngine.Mode.ROOT) {
                                ArrowPreference(title = "Root 授权", summary = if (checkingRoot) "正在检查…" else rootMessage,
                                    onClick = {
                                        if (!checkingRoot) {
                                            checkingRoot = true
                                            controller.probeRoot { result -> checkingRoot = false; rootMessage = result.message; refresh() }
                                        }
                                    }, modifier = Modifier.testTag("action.root"))
                            } else {
                                ArrowPreference(title = "Shizuku 授权", summary = snapshot.shizukuStatus,
                                    onClick = {
                                        try { controller.requestShizukuPermission(); refresh() }
                                        catch (error: RuntimeException) { toast(error.message ?: "Shizuku 授权暂不可用") }
                                    }, modifier = Modifier.testTag("action.shizuku"),
                                    endActions = { if (snapshot.shizukuGranted) Tag("已授权", true) })
                            }
                        }
                    }
                    item { SectionLabel("关于") }
                    item {
                        SettingsCard {
                            ArrowPreference(title = "日志", summary = "在 LSPosed 中查看或导出模块日志", onClick = { dialog = "logs" }, modifier = Modifier.testTag("action.logs"))
                            StatusLine("taplusbridge", snapshot.versionName)
                            StatusLine("传送门", snapshot.hostVersion)
                            StatusLine("界面", "Miuix")
                        }
                    }
                    item { QuietText("识别能力取决于来源数据、规则匹配与目标应用支持。开发版本名不限制链接数量。", Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
                }
            }
        }
    }
    if (showModes) OverlayDialog(show = true, title = "解冻授权", summary = "选择执行解冻操作的授权渠道", onDismissRequest = { showModes = false }) {
        Column(Modifier.heightIn(max = LocalWindowInfo.current.containerDpSize.height * .60f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThawEngine.Mode.values().forEach { mode ->
                RadioButtonPreference(title = modeLabel(mode), summary = modeSummary(mode), selected = snapshot.mode == mode,
                    onClick = { controller.setMode(mode); refresh(); showModes = false }, modifier = Modifier.testTag("mode.${mode.name}"))
            }
        }
    }
    when (dialog) {
        "logs" -> MessageDialog("日志", "模块日志由 LSPosed 统一管理，可在 LSPosed 中查看并导出。\n\n复现问题后导出日志，模块标签为 TaplusBridge。\n\n本模块记录处理阶段和失败原因，不记录聊天正文或完整链接。") { dialog = null }
    }
}

@Composable private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp)
        Text(value, Modifier.widthIn(max = 205.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
internal fun modeLabel(mode: ThawEngine.Mode) = when (mode) { ThawEngine.Mode.ROOT -> "Root"; ThawEngine.Mode.SHIZUKU -> "Shizuku" }
internal fun modeSummary(mode: ThawEngine.Mode) = when (mode) {
    ThawEngine.Mode.ROOT -> "通过超级用户授权解冻后打开"
    ThawEngine.Mode.SHIZUKU -> "通过 Shizuku 服务解冻后打开"
}
