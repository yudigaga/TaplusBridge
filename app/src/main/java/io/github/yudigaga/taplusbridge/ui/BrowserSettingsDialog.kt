package io.github.yudigaga.taplusbridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yudigaga.taplusbridge.browser.BrowserApps
import io.github.yudigaga.taplusbridge.browser.BrowserStore
import io.github.yudigaga.taplusbridge.runtime.ModuleProcessLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.RadioButtonPreference

@Composable internal fun BrowserSettingsDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { BrowserStore(context) }
    var selectedPackage by remember { mutableStateOf(store.selectedPackage()) }
    var targets by remember { mutableStateOf<List<BrowserApps.Target>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(context) {
        try { targets = withContext(Dispatchers.IO) { BrowserApps.installed(context) } }
        catch (failure: RuntimeException) { error = "无法读取浏览器，请关闭后重试" }
        finally { loading = false }
    }
    DisposableEffect(Unit) {
        val editing = ModuleProcessLifecycle.acquire()
        onDispose { editing.close() }
    }
    val candidates = remember(targets, query) {
        val search = query.trim()
        targets.filter { it.label().contains(search, true) || it.packageName().contains(search, true) }
    }
    OverlayDialog(show = true, title = "默认浏览器", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalWindowInfo.current.containerDpSize.height * .62f).imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(query, { query = it }, label = "搜索浏览器或包名", singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("browser.search"))
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("browser.dialog.list"),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { QuietText("用于浏览器打开和普通网页识别。域名与协议规则继续使用对应应用；点击“保存”后生效。", Modifier.padding(horizontal = 12.dp)) }
                item {
                    RadioButtonPreference(title = "沿用原选择", summary = "使用系统默认浏览器或原来的浏览器候选列表",
                        selected = selectedPackage.isEmpty(), onClick = { selectedPackage = ""; error = "" },
                        modifier = Modifier.testTag("browser.default").semantics { selected = selectedPackage.isEmpty() })
                }
                if (!loading && selectedPackage.isNotEmpty() && targets.none { it.packageName() == selectedPackage }) item {
                    QuietText("$selectedPackage · 当前不可用，请重新选择或沿用原选择",
                        Modifier.padding(12.dp).testTag("browser.unavailable"))
                }
                itemsIndexed(candidates, key = { _, target -> target.packageName() }) { index, target ->
                    RadioButtonPreference(title = target.label(), summary = target.packageName(), startAction = { AppIcon(target.icon()) },
                        selected = selectedPackage == target.packageName(),
                        onClick = { selectedPackage = target.packageName(); error = "" },
                        modifier = Modifier.testTag("browser.target.$index").semantics { selected = selectedPackage == target.packageName() })
                }
                if (loading || candidates.isEmpty()) item {
                    QuietText(when {
                        loading -> "正在读取浏览器…"
                        query.isNotBlank() -> "没有匹配的浏览器，请调整搜索内容"
                        else -> "未找到可用浏览器，可沿用原选择"
                    }, Modifier.padding(12.dp).testTag("browser.empty"))
                }
            }
            if (error.isNotEmpty()) QuietText(error, Modifier.testTag("browser.error"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton("取消", onDismiss, Modifier.weight(1f).testTag("browser.cancel"))
                TextButton("保存", {
                    if (selectedPackage.isNotEmpty() && BrowserApps.installed(context).none { it.packageName() == selectedPackage })
                        error = "所选浏览器当前不可用，请重新选择"
                    else if (!store.save(selectedPackage)) error = "浏览器设置保存失败，请重试"
                    else { error = ""; onSaved(); onDismiss() }
                }, Modifier.weight(1f).testTag("browser.save"), enabled = !loading,
                    colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}
