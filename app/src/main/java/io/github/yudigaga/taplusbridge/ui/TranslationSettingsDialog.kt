package io.github.yudigaga.taplusbridge.ui

import android.os.CancellationSignal
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.yudigaga.taplusbridge.translation.AiTranslationClient
import io.github.yudigaga.taplusbridge.translation.TranslationApps
import io.github.yudigaga.taplusbridge.translation.TranslationSettings
import io.github.yudigaga.taplusbridge.translation.TranslationStore
import io.github.yudigaga.taplusbridge.runtime.ModuleProcessLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference

/** Translation has its own explicit save; gesture saves never contain these credentials. */
@Composable internal fun TranslationSettingsDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { TranslationStore(context) }
    val initial = remember(store) { store.load() }
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(initial.mode) }
    var appComponent by remember { mutableStateOf(initial.appComponent) }
    var appAction by remember { mutableStateOf(initial.appAction) }
    var endpoint by remember { mutableStateOf(initial.endpoint) }
    var model by remember { mutableStateOf(initial.model) }
    var targetLanguage by remember { mutableStateOf(initial.targetLanguage) }
    var systemPrompt by remember { mutableStateOf(initial.systemPrompt) }
    // Never place the secret in saved instance state or echo an existing key into the field.
    var keyDraft by remember { mutableStateOf("") }
    var clearKey by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var targets by remember { mutableStateOf<List<TranslationApps.Target>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf("") }
    var cancellation by remember { mutableStateOf<CancellationSignal?>(null) }
    var testJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(context) {
        try { targets = withContext(Dispatchers.IO) { TranslationApps.installed(context) } }
        catch (failure: RuntimeException) { error = "无法读取翻译应用，请关闭后重试" }
        finally { loading = false }
    }
    DisposableEffect(Unit) {
        val editing = ModuleProcessLifecycle.acquire()
        onDispose {
            try { cancellation?.cancel(); testJob?.cancel() }
            finally { editing.close() }
        }
    }

    fun stopTest() {
        cancellation?.cancel(); testJob?.cancel()
        cancellation = null; testJob = null; testing = false
    }
    fun close() { stopTest(); onDismiss() }
    fun draft() = TranslationSettings(mode, appComponent, appAction, endpoint.trim(),
        keyDraft.ifEmpty { if (clearKey) "" else initial.apiKey }, model.trim(), targetLanguage.trim(), systemPrompt)
    fun validateDraft(): TranslationSettings {
        val settings = draft()
        settings.validate()
        if (mode == TranslationSettings.Mode.APP && targets.none { it.component() == appComponent && it.action() == appAction })
            throw IllegalArgumentException(if (loading) "正在读取翻译应用，请稍后保存" else "所选翻译应用不可用，请重新选择")
        return settings
    }
    val selectedTarget = targets.firstOrNull { it.component() == appComponent && it.action() == appAction }
    val candidates = remember(targets, query) {
        val search = query.trim()
        targets.filter { it.label().contains(search, true) || it.component().contains(search, true) }
    }

    key(picker) {
        OverlayDialog(show = true, title = if (picker) "选择翻译应用" else "替换翻译",
            onDismissRequest = { if (picker) picker = false else close() }) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalWindowInfo.current.containerDpSize.height * .62f).imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (picker) {
                    TextField(query, { query = it }, label = "搜索翻译应用或包名", singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("translation.app.search"))
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("translation.app.list"),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(candidates, key = { _, target -> target.component() + ":" + target.action() }) { index, target ->
                            RadioButtonPreference(title = target.label(), summary = target.component(), startAction = { AppIcon(target.icon()) },
                                selected = target.component() == appComponent && target.action() == appAction,
                                onClick = { appComponent = target.component(); appAction = target.action(); picker = false; error = "" },
                                modifier = Modifier.testTag("translation.app.target.$index").semantics {
                                    selected = target.component() == appComponent && target.action() == appAction
                                })
                        }
                        if (loading || candidates.isEmpty()) item {
                            QuietText(when {
                                loading -> "正在读取可接收文字的应用…"
                                query.isNotBlank() -> "没有匹配的应用，请调整搜索内容"
                                else -> "未找到可接收翻译、文字处理或文字分享的应用"
                            }, Modifier.padding(12.dp).testTag("translation.app.empty"))
                        }
                    }
                    TextButton("返回翻译设置", { picker = false }, Modifier.fillMaxWidth().testTag("translation.app.back"))
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("translation.dialog.list"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { QuietText("用于传送门翻译和长按拖动翻译。需要开启应用流转并保持模块本体可用，点击“保存”后生效。") }
                        items(TranslationSettings.Mode.values().size) { index ->
                            val option = TranslationSettings.Mode.values()[index]
                            RadioButtonPreference(title = when (option) {
                                TranslationSettings.Mode.NATIVE -> "沿用传送门翻译"
                                TranslationSettings.Mode.APP -> "本机翻译应用"
                                TranslationSettings.Mode.AI -> "AI 翻译"
                            }, summary = when (option) {
                                TranslationSettings.Mode.NATIVE -> "关闭替换，保持原来的翻译流程"
                                TranslationSettings.Mode.APP -> "将原文交给选定的已安装应用"
                                TranslationSettings.Mode.AI -> "通过你配置的服务翻译，在模块内显示译文"
                            }, selected = mode == option, onClick = { stopTest(); mode = option; error = ""; testMessage = "" },
                                modifier = Modifier.testTag("translation.mode.${option.name}").semantics { selected = mode == option })
                        }
                        when (mode) {
                            TranslationSettings.Mode.NATIVE -> Unit
                            TranslationSettings.Mode.APP -> {
                                item { ArrowPreference(title = "翻译应用", summary = selectedTarget?.label()
                                    ?: if (appComponent.isBlank()) "选择翻译或文字处理入口" else "${appComponent} · 当前不可用，请重新选择",
                                    onClick = { query = ""; picker = true }, modifier = Modifier.testTag("translation.app")) }
                                item { QuietText("优先选择“专用翻译”入口。文字处理和分享兼容入口由目标应用决定如何处理，未必直接显示译文。目标语言由该应用设置。") }
                            }
                            TranslationSettings.Mode.AI -> {
                                item { QuietText("兼容 Chat Completions 接口。只在你执行翻译时发送原文，API Key 仅保存在模块私有配置中。") }
                                item { TextField(endpoint, { endpoint = it; error = "" }, label = "完整 HTTPS 接口地址", singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = !testing,
                                    modifier = Modifier.fillMaxWidth().testTag("translation.endpoint")) }
                                item {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        TextField(keyDraft, { keyDraft = it; error = "" },
                                            label = if (!clearKey && initial.apiKey.isNotBlank()) "API Key（留空保留已保存值）" else "API Key",
                                            singleLine = true, visualTransformation = PasswordVisualTransformation(),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !testing,
                                            modifier = Modifier.fillMaxWidth().testTag("translation.api_key"))
                                        if (initial.apiKey.isNotBlank() || keyDraft.isNotEmpty() || clearKey) {
                                            TextButton(if (clearKey && keyDraft.isBlank()) "密钥将于保存时清除" else "清除已保存密钥", {
                                                keyDraft = ""; clearKey = true; error = ""
                                            }, enabled = !testing, modifier = Modifier.fillMaxWidth().testTag("translation.clear_key"))
                                        }
                                    }
                                }
                                item { TextField(model, { model = it; error = "" }, label = "模型名称", singleLine = true, enabled = !testing,
                                    modifier = Modifier.fillMaxWidth().testTag("translation.model")) }
                                item { TextField(targetLanguage, { targetLanguage = it; error = "" }, label = "目标语言", singleLine = true, enabled = !testing,
                                    modifier = Modifier.fillMaxWidth().testTag("translation.target")) }
                                item { ArrowPreference(title = "翻译提示词", summary = if (advanced) "收起自定义系统提示" else "使用默认翻译要求，也可自定义",
                                    onClick = { advanced = !advanced }, modifier = Modifier.testTag("translation.advanced")) }
                                if (advanced) item { TextField(systemPrompt, { systemPrompt = it; error = "" }, label = "系统提示词", minLines = 3, maxLines = 6, enabled = !testing,
                                    modifier = Modifier.fillMaxWidth().testTag("translation.prompt")) }
                                item {
                                    QuietText("测试连接会向所填服务发送固定句子“Hello, world.”，使用当前草稿，不保存配置。")
                                    TextButton(if (testing) "停止测试" else "测试连接", {
                                        if (testing) { stopTest(); testMessage = "测试已取消" }
                                        else try {
                                            val settings = validateDraft()
                                            error = ""; testMessage = ""; testing = true
                                            val signal = CancellationSignal(); cancellation = signal
                                            testJob = scope.launch {
                                                try {
                                                    val translated = withContext(Dispatchers.IO) { AiTranslationClient.translate(settings, "Hello, world.", signal) }
                                                    if (!signal.isCanceled && cancellation === signal) testMessage = "测试成功：${translated.take(160)}"
                                                } catch (cancelled: CancellationException) { throw cancelled }
                                                catch (failure: Exception) {
                                                    if (!signal.isCanceled && cancellation === signal) testMessage = "测试失败，请检查接口、密钥、模型和网络"
                                                } finally {
                                                    if (cancellation === signal) { testing = false; cancellation = null; testJob = null }
                                                }
                                            }
                                        } catch (invalid: IllegalArgumentException) { error = invalid.message ?: "请检查 AI 翻译配置" }
                                    }, Modifier.fillMaxWidth().testTag("translation.test"))
                                }
                                if (testMessage.isNotEmpty()) item { QuietText(testMessage, Modifier.testTag("translation.test.result")) }
                            }
                        }
                    }
                    if (error.isNotEmpty()) QuietText(error, Modifier.testTag("translation.error"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton("取消", ::close, Modifier.weight(1f).testTag("translation.cancel"))
                        TextButton("保存", {
                            try { store.save(validateDraft()); error = ""; onSaved(); close() }
                            catch (invalid: IllegalArgumentException) { error = invalid.message ?: "请检查翻译配置" }
                            catch (failure: RuntimeException) { error = "翻译设置保存失败，请重试" }
                        }, Modifier.weight(1f).testTag("translation.save"), enabled = !testing, colors = ButtonDefaults.textButtonColorsPrimary())
                    }
                }
            }
        }
    }
}
