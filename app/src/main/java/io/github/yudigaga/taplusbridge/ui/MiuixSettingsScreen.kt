package io.github.yudigaga.taplusbridge.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import io.github.yudigaga.taplusbridge.config.ConfigStore
import io.github.yudigaga.taplusbridge.runtime.RuntimePublisher
import io.github.yudigaga.taplusbridge.thaw.ThawEngine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

private const val HOME = 0
private const val RULES = 1
private const val APPS = 2
private const val GESTURES = 3
private const val MORE = 4

object MiuixSettingsScreen {
    @JvmStatic fun install(activity: MainActivity, controller: SettingsController) {
        activity.enableEdgeToEdge()
        activity.setContent {
            SettingsTheme {
                Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)) {
                    SettingsApplication(activity, controller)
                }
            }
        }
    }
}

@Composable internal fun SettingsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    androidx.compose.runtime.SideEffect {
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
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = HOME, pageCount = { 5 })
    var page by rememberSaveable { mutableIntStateOf(HOME) }
    var revision by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf(controller.snapshot()) }
    var sourceScope by remember { mutableStateOf(controller.sourceScope()) }
    var sourceScopeError by remember { mutableStateOf(controller.sourceScopeError()) }
    var apps by remember { mutableStateOf<List<SettingsController.AppEntry>>(emptyList()) }
    var rootMessage by rememberSaveable { mutableStateOf("尚未检查 Root 授权") }
    var checkingRoot by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var showModes by rememberSaveable { mutableStateOf(false) }
    var runtimeStatus by remember { mutableStateOf(RuntimePublisher.status()) }
    var independent by remember { mutableStateOf(ConfigStore(activity).independentEnabled()) }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { page = it }
    }
    LaunchedEffect(page) {
        if (pagerState.currentPage != page) pagerState.animateScrollToPage(page)
    }
    LaunchedEffect(Unit) {
        while (true) { runtimeStatus = RuntimePublisher.status(); kotlinx.coroutines.delay(1000) }
    }
    val refresh: () -> Unit = {
        snapshot = controller.snapshot()
        sourceScope = controller.sourceScope()
        sourceScopeError = controller.sourceScopeError()
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
    val navigationItems = listOf(
        LiquidGlassNavigationItem("首页", SettingsIcons.Home, "nav.home"),
        LiquidGlassNavigationItem("规则", SettingsIcons.Rules, "nav.rules"),
        LiquidGlassNavigationItem("应用", SettingsIcons.Apps, "nav.apps"),
        LiquidGlassNavigationItem("手势", SettingsIcons.Gesture, "nav.gestures"),
        LiquidGlassNavigationItem("更多", SettingsIcons.More, "nav.more"),
    )
    BackHandler(page != HOME) { scope.launch { pagerState.animateScrollToPage(HOME) } }

    Box(Modifier.fillMaxSize().testTag("settings.root")) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().padding(bottom = 112.dp), key = { it }) { index ->
            when (index) {
                HOME -> HomePage(snapshot, sourceScope, sourceScopeError, controller, refresh)
                RULES -> RuleSettingsScreen(controller, apps, revision, bottomBar = {}, onChanged = refresh)
                APPS -> SourceScopeSettingsScreen(controller, apps, onSaved = { refresh() })
                GESTURES -> DragSettingsScreen(controller, onSaved = { refresh() })
                MORE -> MorePage(snapshot, independent, runtimeStatus, checkingRoot, rootMessage, controller,
                    showModes = { showModes = true },
                    setIndependent = { value -> independent = value; ConfigStore(activity).setIndependentEnabled(value); RuntimePublisher.synchronize(activity) },
                    syncRuntime = { RuntimePublisher.synchronize(activity); refresh() },
                    checkRoot = { if (!checkingRoot) { checkingRoot = true; controller.probeRoot { result -> checkingRoot = false; rootMessage = result.message; refresh() } } },
                    openLogs = { dialog = "logs" },
                    requestShizuku = { try { controller.requestShizukuPermission(); refresh() } catch (error: RuntimeException) { toast(error.message ?: "Shizuku 授权暂不可用") } })
            }
        }
        LiquidGlassNavigationBar(selected = page, items = navigationItems,
            onSelected = { target -> scope.launch { pagerState.animateScrollToPage(target) } },
            modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (showModes) OverlayDialog(show = true, title = "解冻授权", summary = "选择执行解冻操作的授权渠道", onDismissRequest = { showModes = false }) {
        Column(Modifier.heightIn(max = LocalWindowInfo.current.containerDpSize.height * .60f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThawEngine.Mode.values().forEach { mode ->
                RadioButtonPreference(title = modeLabel(mode), summary = modeSummary(mode), selected = snapshot.mode == mode,
                    onClick = { controller.setMode(mode); refresh(); showModes = false }, modifier = Modifier.testTag("mode.${mode.name}"))
            }
        }
    }
    if (dialog == "logs") MessageDialog("日志", "模块日志由 LSPosed 统一管理，可在 LSPosed 中查看或导出。\n\n复现问题后导出日志，模块标签为 TaplusBridge。\n\n本模块记录处理阶段和失败原因，不记录聊天正文或完整链接。") { dialog = null }
}

@Composable private fun HomePage(snapshot: SettingsController.Snapshot, sourceScope: io.github.yudigaga.taplusbridge.config.SourceScopePolicy, sourceScopeError: String?, controller: SettingsController, refresh: () -> Unit) {
    val compact = LocalWindowInfo.current.containerDpSize.width < 360.dp || LocalDensity.current.fontScale > 1.2f
    Scaffold(modifier = Modifier.testTag("page.home"), topBar = { TopAppBar(title = "taplusbridge", subtitle = "识别之后，直达应用") }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SettingsCard {
                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (!compact) Box(Modifier.size(48.dp).background(MiuixTheme.colorScheme.tertiaryContainer, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) { Icon(SettingsIcons.Link, null, Modifier.size(27.dp), MiuixTheme.colorScheme.primary) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(if (snapshot.enabled) "应用流转已开启" else "应用流转已关闭", fontSize = 19.sp, fontWeight = FontWeight.SemiBold); QuietText(if (snapshot.enabled) "识别结果会按规则交给目标应用" else "开启后使用链接增强与解冻流转") }
                        Switch(snapshot.enabled, { controller.setEnabled(it); refresh() }, Modifier.testTag("toggle.enabled"))
                    }
                    Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Tag(if (snapshot.hostSupported) "传送门 ${snapshot.hostVersionName}" else if (snapshot.hostInstalled) "版本待适配" else "未检测到传送门", snapshot.hostSupported); Tag("API 102") }
                }
            }
            item { SectionLabel("识别与打开") }
            item { SettingsCard { SwitchPreference(snapshot.lensEnabled, { controller.setLensEnabled(it); refresh() }, title = "Google Lens 识图", summary = "自动上传选中图片到 Google 并在传送门显示结果；需要可访问 Google 的网络", modifier = Modifier.testTag("toggle.lens")) } }
            item {
                SettingsCard {
                    StatusLine("生效应用", sourceScopeError ?: sourceScopeSummary(sourceScope))
                    SwitchPreference(snapshot.recognitionEnabled, { controller.setRecognitionEnabled(it); refresh() }, title = "增强链接识别", summary = "补充文字与卡片中已有的链接", modifier = Modifier.testTag("toggle.recognition"))
                    SwitchPreference(snapshot.nativeEnabled, { controller.setNativeEnabled(it); refresh() }, title = "原生链接接管", summary = "保留原内容，交给目标应用打开", modifier = Modifier.testTag("toggle.native"))
                    SwitchPreference(snapshot.captureEnabled, { controller.setCaptureEnabled(it); refresh() }, title = "自动识别分享卡片", summary = if (snapshot.recognitionEnabled) "长按后静默提取链接" else "需要先开启增强链接识别", enabled = snapshot.enabled && snapshot.recognitionEnabled, modifier = Modifier.testTag("toggle.capture"))
                }
            }
            item { SectionLabel("当前状态") }
            item { SettingsCard { StatusLine("拖拽操作", if (snapshot.dragSettings.enabled) "已开启" else "已关闭") } }
        }
    }
}

@Composable private fun MorePage(snapshot: SettingsController.Snapshot, independent: Boolean, runtimeStatus: String, checkingRoot: Boolean, rootMessage: String, controller: SettingsController, showModes: () -> Unit, setIndependent: (Boolean) -> Unit, syncRuntime: () -> Unit, checkRoot: () -> Unit, openLogs: () -> Unit, requestShizuku: () -> Unit) {
    Scaffold(modifier = Modifier.testTag("page.more"), topBar = { TopAppBar(title = "更多", subtitle = "授权、日志与版本") }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("more.list"), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SectionLabel("冻结后运行") }
            item { SettingsCard { SwitchPreference(independent, setIndependent, title = "冻结本体后继续运行", summary = "Android 13+ 主用户；冻结期间由系统框架处理", modifier = Modifier.testTag("toggle.independent")); ArrowPreference(title = "系统配置同步", summary = runtimeStatus, onClick = syncRuntime, modifier = Modifier.testTag("action.runtime_sync")) } }
            item { SectionLabel("权限与服务") }
            item { SettingsCard { if (snapshot.mode == ThawEngine.Mode.ROOT) ArrowPreference(title = "Root 授权", summary = if (checkingRoot) "正在检查…" else rootMessage, onClick = checkRoot, modifier = Modifier.testTag("action.root")) else ArrowPreference(title = "Shizuku 授权", summary = snapshot.shizukuStatus, onClick = requestShizuku, modifier = Modifier.testTag("action.shizuku"), endActions = { if (snapshot.shizukuGranted) Tag("已授权", true) }) } }
            item { SectionLabel("关于") }
            item { SettingsCard { ArrowPreference(title = "解冻授权", summary = modeSummary(snapshot.mode), onClick = showModes, modifier = Modifier.testTag("action.mode")); ArrowPreference(title = "日志", summary = "在 LSPosed 中查看或导出模块日志", onClick = openLogs, modifier = Modifier.testTag("action.logs")); StatusLine("taplusbridge", snapshot.versionName); StatusLine("传送门", snapshot.hostVersion); StatusLine("界面", "Miuix") } }
        }
    }
}

@Composable private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f), fontSize = 15.sp); Text(value, Modifier.widthIn(max = 205.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
}

internal fun modeLabel(mode: ThawEngine.Mode) = when (mode) { ThawEngine.Mode.ROOT -> "Root"; ThawEngine.Mode.SHIZUKU -> "Shizuku" }
internal fun modeSummary(mode: ThawEngine.Mode) = when (mode) { ThawEngine.Mode.ROOT -> "通过超级用户授权解冻后打开"; ThawEngine.Mode.SHIZUKU -> "通过 Shizuku 服务解冻后打开" }
