package io.github.yudigaga.taplusbridge.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.github.yudigaga.taplusbridge.runtime.RuntimePublisher
import io.github.yudigaga.taplusbridge.thaw.ThawEngine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

private const val HOME = 0
private const val RECOGNITION = 1
private const val APPS = 2
private const val RULES = 3

object MiuixSettingsScreen {
    @JvmStatic fun install(activity: MainActivity, controller: SettingsController) {
        activity.enableEdgeToEdge()
        activity.setContent {
            SettingsTheme {
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
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = HOME, pageCount = { 4 })
    var page by rememberSaveable { mutableIntStateOf(HOME) }
    var revision by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf(controller.snapshot()) }
    var sourceScope by remember { mutableStateOf(controller.sourceScope()) }
    var sourceScopeError by remember { mutableStateOf(controller.sourceScopeError()) }
    var apps by remember { mutableStateOf<List<SettingsController.AppEntry>>(emptyList()) }
    val refresh: () -> Unit = {
        snapshot = controller.snapshot()
        sourceScope = controller.sourceScope()
        sourceScopeError = controller.sourceScopeError()
        revision++
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { page = it }
    }
    DisposableEffect(activity, controller) {
        val preferences = activity.getSharedPreferences("config", 0)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        activity.setRefreshListener { refresh(); controller.loadInstalledApps { apps = it } }
        controller.setAuthorizationRefresh(refresh)
        controller.loadInstalledApps { apps = it }
        onDispose {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
            activity.setRefreshListener(null)
            controller.setAuthorizationRefresh(null)
        }
    }
    val navigationItems = listOf(
        LiquidGlassNavigationItem("概览", SettingsIcons.Home, "nav.home"),
        LiquidGlassNavigationItem("识别", SettingsIcons.Gesture, "nav.recognition"),
        LiquidGlassNavigationItem("应用", SettingsIcons.Apps, "nav.apps"),
        LiquidGlassNavigationItem("规则", SettingsIcons.Rules, "nav.rules"),
    )
    BackHandler(page != HOME) { scope.launch { pagerState.animateScrollToPage(HOME) } }
    Box(Modifier.fillMaxSize().testTag("settings.root")) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().padding(bottom = 112.dp), key = { it }) { index ->
            when (index) {
                HOME -> OverviewSettingsScreen(snapshot, sourceScope, sourceScopeError, controller, refresh)
                RECOGNITION -> DragSettingsScreen(controller, onSaved = refresh)
                APPS -> SourceScopeSettingsScreen(controller, apps, onSaved = refresh)
                RULES -> RuleSettingsScreen(controller, apps, revision, snapshot.nativeEnabled, snapshot.cloudLinkHandoffEnabled, bottomBar = {}, onChanged = refresh)
            }
        }
        LiquidGlassNavigationBar(selected = page, items = navigationItems,
            onSelected = { target -> scope.launch { pagerState.animateScrollToPage(target) } },
            modifier = Modifier.align(Alignment.BottomCenter))
    }
}

internal fun modeLabel(mode: ThawEngine.Mode) = when (mode) { ThawEngine.Mode.ROOT -> "Root"; ThawEngine.Mode.SHIZUKU -> "Shizuku" }
internal fun modeSummary(mode: ThawEngine.Mode) = when (mode) { ThawEngine.Mode.ROOT -> "通过超级用户授权解冻后打开"; ThawEngine.Mode.SHIZUKU -> "通过 Shizuku 服务解冻后打开" }
