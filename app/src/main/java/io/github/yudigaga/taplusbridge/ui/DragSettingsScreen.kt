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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.yudigaga.taplusbridge.config.DragSettings
import io.github.yudigaga.taplusbridge.config.RecognitionChannels
import io.github.yudigaga.taplusbridge.runtime.RuntimePublisher
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable internal fun DragSettingsScreen(controller: SettingsController, onSaved: () -> Unit) {
    val initial = remember(controller) { controller.getDragSettings() }
    val context = LocalContext.current
    val initialState = remember(controller) { controller.snapshot() }
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var linkProbe by remember { mutableStateOf(controller.scopeLinkProbeEnabled()) }
    var channels by remember { mutableStateOf(controller.recognitionChannels()) }
    var screenshot by remember { mutableStateOf(controller.scopeCaptureEnabled()) }
    var lens by remember { mutableStateOf(initialState.lensEnabled) }
    var moduleEnabled by remember { mutableStateOf(initialState.enabled) }
    var enhancement by remember { mutableStateOf(initialState.recognitionEnabled) }
    var chatCards by remember { mutableStateOf(initialState.captureEnabled) }
    var up by rememberSaveable { mutableStateOf(initial.up) }
    var down by rememberSaveable { mutableStateOf(initial.down) }
    var left by rememberSaveable { mutableStateOf(initial.left) }
    var right by rememberSaveable { mutableStateOf(initial.right) }
    var onlySelected by rememberSaveable { mutableStateOf(initial.shareOnlySelected) }
    var recognitionDelay by rememberSaveable { mutableIntStateOf(initial.recognitionDelayMillis) }
    var dragDelay by rememberSaveable { mutableIntStateOf(initial.dragDelayMillis) }
    var selected by rememberSaveable { mutableStateOf(ArrayList(initial.shareTargets)) }
    var query by rememberSaveable { mutableStateOf("") }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingDirection by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf("") }
    var runtimeStatus by remember { mutableStateOf(RuntimePublisher.status()) }
    var targets by remember { mutableStateOf<List<SettingsController.ShareTarget>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(controller) { controller.loadShareTargets { targets = it; loading = false } }
    LaunchedEffect(controller) {
        while (true) { runtimeStatus = RuntimePublisher.status(); kotlinx.coroutines.delay(250) }
    }
    DisposableEffect(controller, context) {
        val preferences = context.getSharedPreferences("config", 0)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            linkProbe = controller.scopeLinkProbeEnabled()
            channels = controller.recognitionChannels()
            screenshot = controller.scopeCaptureEnabled()
            val current = controller.snapshot()
            lens = current.lensEnabled
            moduleEnabled = current.enabled
            enhancement = current.recognitionEnabled
            chatCards = current.captureEnabled
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun changeRecognition(change: () -> Unit) {
        try {
            change()
            error = ""
        } catch (failure: RuntimeException) { error = failure.message ?: "识别设置保存失败，请重试" }
        linkProbe = controller.scopeLinkProbeEnabled()
        channels = controller.recognitionChannels()
        screenshot = controller.scopeCaptureEnabled()
        val current = controller.snapshot()
        lens = current.lensEnabled
        moduleEnabled = current.enabled
        enhancement = current.recognitionEnabled
        chatCards = current.captureEnabled
        runtimeStatus = RuntimePublisher.status()
    }
    val targetMap = remember(targets) { targets.associateBy { it.component } }
    val available = remember(targets, selected, query) {
        targets.filter { it.component !in selected && (it.label.contains(query.trim(), true) || it.component.contains(query.trim(), true)) }
    }
    val actions = mapOf("up" to up, "down" to down, "left" to left, "right" to right)
    fun select(component: String, checked: Boolean) {
        if (checked && component !in selected && selected.size >= DragSettings.MAX_TARGETS) {
            error = "最多选择 ${DragSettings.MAX_TARGETS} 个分享目标"; return
        }
        selected = ArrayList(if (checked) (selected + component).distinct() else selected - component)
        error = ""
    }
    fun move(index: Int, offset: Int) {
        if (index + offset !in selected.indices) return
        selected = ArrayList(selected).also { val item = it.removeAt(index); it.add(index + offset, item) }
    }
    fun discardDraft() {
        val persisted = controller.getDragSettings()
        enabled = persisted.enabled
        linkProbe = controller.scopeLinkProbeEnabled()
        up = persisted.up; down = persisted.down; left = persisted.left; right = persisted.right
        onlySelected = persisted.shareOnlySelected
        recognitionDelay = persisted.recognitionDelayMillis; dragDelay = persisted.dragDelayMillis
        selected = ArrayList(persisted.shareTargets)
        query = ""; error = ""; choosingDirection = null; dialog = null
    }
    Scaffold(modifier = Modifier.testTag("page.drag"), topBar = {
        SettingsPageHeader(
            title = "识别与手势",
            subtitle = "识别开关即时保存，手势参数单独保存",
            discardTag = "drag.back",
            saveTag = "drag.save",
            discardText = "放弃手势",
            saveText = "保存手势",
            onDiscard = ::discardDraft,
            onSave = {
                try {
                    controller.setDragSettings(DragSettings(enabled, up, down, left, right, onlySelected, selected,
                        recognitionDelay, dragDelay))
                    RuntimePublisher.publishSavedSettings(context)
                    runtimeStatus = RuntimePublisher.status()
                    error = ""
                    Toast.makeText(context, "本体已保存，正在确认系统生效", Toast.LENGTH_SHORT).show(); onSaved()
                } catch (failure: RuntimeException) { error = failure.message ?: "保存失败，请重试" }
            })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("drag.list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!moduleEnabled) item { QuietText("应用流转已关闭，模块系统采集、补点与拖动暂停；可预先设置，宿主原有识别与 Lens 独立运行。", Modifier.padding(horizontal = 8.dp).testTag("recognition.module_hint")) }
            if (error.isNotEmpty()) item {
                QuietText(error, Modifier.padding(horizontal = 8.dp).testTag("drag.error"))
            }
            item {
                SettingsCard {
                    ArrowPreference(title = "识别增强与来源", summary = "增强${if (enhancement) "开启" else "关闭"} · ${RecognitionChannels.Channel.values().count { channels.enabled(it) }}/6 项来源与处理",
                        onClick = { dialog = "recognition" }, modifier = Modifier.testTag("drag.group.recognition"))
                    ArrowPreference(title = "图片与识图", summary = "截图${if (screenshot) "开启" else "关闭"} · Lens ${if (lens) "开启" else "关闭"}",
                        onClick = { dialog = "images" }, modifier = Modifier.testTag("drag.group.images"))
                    ArrowPreference(title = "补点取链", summary = if (linkProbe) "已允许不可见点击 · 会操作来源内容" else "已禁止不可见点击",
                        onClick = { dialog = "probe" }, modifier = Modifier.testTag("drag.group.probe"))
                    ArrowPreference(title = "长按与拖动", summary = if (enabled) "草稿已开启 · 计时、方向与分享" else "草稿已关闭 · 点击配置",
                        onClick = { dialog = "gesture" }, modifier = Modifier.testTag("drag.group.gesture"))
                }
            }
            item { QuietText("系统配置状态（不含 Lens）：$runtimeStatus", Modifier.padding(horizontal = 8.dp).testTag("drag.runtime_status")) }
            item { QuietText("识别、图片和补点开关切换即保存。长按与拖动中的修改先保留为草稿，点击“保存手势”后生效。", Modifier.padding(horizontal = 8.dp)) }
        }
    }
    dialog?.let { section ->
        val direction = choosingDirection
        val title = if (direction != null) "${directionLabel(direction)}操作" else when (section) {
            "recognition" -> "识别增强与来源"; "images" -> "图片与识图"; "probe" -> "补点取链"; "gesture" -> "长按与拖动"
            "timing" -> "阶段计时"; "directions" -> "方向动作"; else -> "分享菜单"
        }
        val draftSection = section in listOf("gesture", "timing", "directions", "share")
        val close = {
            choosingDirection = null
            dialog = if (section in listOf("timing", "directions", "share")) "gesture" else null
        }
        key(section, direction) {
            OverlayDialog(show = true, title = title, onDismissRequest = {
                if (choosingDirection != null) choosingDirection = null else close()
            }) {
                Column(Modifier.heightIn(max = LocalWindowInfo.current.containerDpSize.height * .60f).imePadding(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyColumn(Modifier.weight(1f, fill = false).testTag("drag.dialog.list"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (error.isNotEmpty()) item { QuietText(error, Modifier.testTag("drag.dialog.error")) }
                        if (direction != null) {
                            items(DragSettings.ACTIONS.toList(), key = { it }) { action ->
                                RadioButtonPreference(title = dragActionLabel(action), selected = actions[direction] == action,
                                    onClick = {
                                        when (direction) { "up" -> up = action; "down" -> down = action; "left" -> left = action; "right" -> right = action }
                                        choosingDirection = null
                                    }, modifier = Modifier.testTag("drag.action.$action").semantics { this.selected = actions[direction] == action })
                            }
                        } else when (section) {
                            "gesture" -> {
                                item {
                                    SwitchPreference(enabled, { enabled = it }, title = "启用长按拖动", summary = "每个方向先完整识别，一种内容直接执行，多种才选择",
                                        modifier = Modifier.testTag("drag.enabled").semantics { toggleableState = if (enabled) ToggleableState.On else ToggleableState.Off })
                                }
                                item { ArrowPreference(title = "阶段计时", summary = "普通长按识别 $recognitionDelay 毫秒 · 拖动追加 $dragDelay 毫秒",
                                    onClick = { dialog = "timing" }, modifier = Modifier.testTag("drag.group.timing")) }
                                if (enabled) {
                                    item { ArrowPreference(title = "方向动作", summary = "上：${dragActionLabel(up)} · 下：${dragActionLabel(down)}\n左：${dragActionLabel(left)} · 右：${dragActionLabel(right)}",
                                        onClick = { dialog = "directions" }, modifier = Modifier.testTag("drag.group.directions")) }
                                    item { ArrowPreference(title = "分享菜单", summary = if (onlySelected) "只显示所选的 ${selected.size} 个目标" else "显示全部可用目标",
                                        onClick = { dialog = "share" }, modifier = Modifier.testTag("drag.group.share")) }
                                }
                            }
                            "timing" -> {
                                item { QuietText("系统长按触发后先等待识别延迟，再等待拖动延迟；阶段从按下时刻累计。提前松手留给系统长按菜单。识别延迟也作用于普通长按取链，关闭拖动后仍生效；拖动延迟只在拖动开启时使用。",
                                    Modifier.testTag("drag.recognition_hint")) }
                                item { StageDelayPreference("识别延迟", "recognitionDelay", recognitionDelay) { recognitionDelay = it } }
                                item { StageDelayPreference("拖动延迟", "dragDelay", dragDelay) { dragDelay = it } }
                            }
                            "directions" -> {
                                items(DragSettings.DIRECTIONS.toList(), key = { it }) { itemDirection ->
                                    ArrowPreference(title = directionLabel(itemDirection), summary = dragActionLabel(actions.getValue(itemDirection)),
                                        onClick = { choosingDirection = itemDirection }, modifier = Modifier.testTag("drag.direction.$itemDirection"))
                                }
                                item { QuietText("模块能力菜单保留本轮可用的传送门操作；按规则打开遵循现有链接和来源规则。识图使用 Google Lens，需要图片与可访问 Google 的网络。") }
                            }
                            "share" -> {
                                item {
                                    RadioButtonPreference(title = "全部可用目标", summary = "按当前内容显示可处理文字或图片的分享入口", selected = !onlySelected,
                                        onClick = { onlySelected = false }, modifier = Modifier.testTag("drag.share.all").semantics { this.selected = !onlySelected })
                                    RadioButtonPreference(title = "只显示所选目标", summary = "按下方顺序显示；不支持当前内容的目标自动隐藏", selected = onlySelected,
                                        onClick = { onlySelected = true }, modifier = Modifier.testTag("drag.share.selected").semantics { this.selected = onlySelected })
                                }
                                if (onlySelected) {
                                    if (selected.isEmpty()) item {
                                        QuietText("尚未选择分享目标；分享时将提示配置，不会自动显示全部目标。", Modifier.testTag("drag.share.empty"))
                                    }
                                    item { QuietText("已选 ${selected.size} 个目标，可上下调整顺序。", Modifier.testTag("drag.share.count")) }
                                    items(selected, key = { "selected:$it" }) { component ->
                                        val target = targetMap[component]
                                        val index = selected.indexOf(component)
                                        Column {
                                            CheckboxPreference(title = target?.label ?: component, summary = if (target == null) "当前不可用，保留已选记录" else component,
                                                checked = true, onCheckedChange = { select(component, it) }, startAction = { AppIcon(target?.icon) },
                                                modifier = Modifier.testTag("drag.share.target.$component").semantics { toggleableState = ToggleableState.On })
                                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                                                TextButton("上移", { move(index, -1) }, Modifier.testTag("drag.share.up.$component").semantics { contentDescription = "上移${target?.label ?: component}" }, enabled = index > 0)
                                                TextButton("下移", { move(index, 1) }, Modifier.testTag("drag.share.down.$component").semantics { contentDescription = "下移${target?.label ?: component}" }, enabled = index < selected.lastIndex)
                                            }
                                        }
                                    }
                                    item { TextField(query, { query = it }, label = "搜索分享入口或包名", singleLine = true, modifier = Modifier.fillMaxWidth().testTag("drag.share.search")) }
                                    item { SectionLabel("可选分享入口") }
                                    items(available, key = { it.component }) { target ->
                                        CheckboxPreference(title = target.label, summary = target.component, checked = false,
                                            onCheckedChange = { select(target.component, it) }, startAction = { AppIcon(target.icon) },
                                            modifier = Modifier.testTag("drag.share.target.${target.component}").semantics { toggleableState = ToggleableState.Off })
                                    }
                                    if (loading || available.isEmpty()) item { QuietText(if (loading) "正在读取分享入口…" else "没有其他匹配的分享入口。") }
                                }
                            }
                            "recognition" -> {
                                item { SwitchPreference(enhancement, { value -> changeRecognition { controller.setRecognitionEnabled(value) } },
                                    title = "模块识别增强", summary = "补充链接并介入宿主来源过滤；关闭后宿主原有识别仍可运行",
                                    modifier = Modifier.testTag("toggle.recognition").semantics { toggleableState = if (enhancement) ToggleableState.On else ToggleableState.Off }) }
                                item { QuietText(if (enhancement) "切换立即保存。可只开启一路进行观察；日志区分链接来源和新增数量。分词只是处理已有文字，图片 OCR 使用已有图片；页面的保存手势或放弃手势不会回改这些开关。"
                                    else "宿主原有文字、OCR 和推荐照常运行；下方来源选择仍用于模块拖入等链路，可以独立调整。",
                                    Modifier.testTag("recognition.immediate_hint")) }
                                items(RecognitionChannels.Channel.values().toList(), key = { it.wire }) { channel ->
                                    SwitchPreference(channels.enabled(channel), { value ->
                                        changeRecognition { controller.setRecognitionChannel(channel, value) }
                                    }, title = recognitionChannelLabel(channel), summary = recognitionChannelSummary(channel),
                                        modifier = Modifier.testTag("recognition.channel.${channel.wire}")
                                            .semantics { toggleableState = if (channels.enabled(channel)) ToggleableState.On else ToggleableState.Off })
                                }
                            }
                            "images" -> {
                                item { QuietText("切换立即保存。截图提供图片，OCR提取图片文字，Lens处理选中图片。增强开启时模块按 OCR 来源选择过滤；增强关闭后宿主 OCR 仍可能运行。") }
                                item {
                                    SwitchPreference(screenshot, { value -> changeRecognition { controller.setScopeCaptureEnabled(value) } },
                                        title = "截图采集兜底", summary = "允许模块在原生取数不足时采集当前来源图片，与原生图片输入及 Lens 独立",
                                        modifier = Modifier.testTag("recognition.screenshot")
                                            .semantics { toggleableState = if (screenshot) ToggleableState.On else ToggleableState.Off })
                                    if (screenshot) QuietText("仅主用户 Android 13+；锁屏、安全窗口及显示输入法时不截图。模块过滤图片文字后仍保留图片用于分享或识图。",
                                        Modifier.padding(horizontal = 16.dp))
                                }
                                item {
                                    SwitchPreference(lens, { value -> changeRecognition { controller.setLensEnabled(value) } },
                                        title = "Google Lens 识图", summary = "点击识图后上传选中图片到 Google 并显示结果；需要本体及可访问 Google 的网络，与图片文字 OCR 独立",
                                        modifier = Modifier.testTag("recognition.lens")
                                            .semantics { toggleableState = if (lens) ToggleableState.On else ToggleableState.Off })
                                }
                            }
                            "probe" -> {
                                item { QuietText("切换立即保存。补点会操作来源页面；只观察无点击识别能力时请关闭。") }
                                item {
                                    SwitchPreference(linkProbe, { value -> changeRecognition { controller.setScopeLinkProbeEnabled(value) } }, title = "不可见点击取链",
                                        summary = if (linkProbe) "缺少链接时，松手后补点最初按下位置" else "关闭补点，保留已有链接与图片识别",
                                        modifier = Modifier.testTag("drag.link_probe").semantics { toggleableState = if (linkProbe) ToggleableState.On else ToggleableState.Off })
                                }
                                if (linkProbe) {
                                    item { QuietText("同时作用于长按识别与长按拖动；仅对会打开链接的内容使用，原位置的按钮也可能响应补点。系统长按补点使用应用名单，不依赖下方聊天卡片适配。", Modifier.testTag("drag.link_probe_hint")) }
                                    item { SwitchPreference(chatCards, { value -> changeRecognition { controller.setCaptureEnabled(value) } },
                                        title = "微信 / QQ 原生卡片补点适配", summary = if (moduleEnabled && enhancement) "允许传送门原生卡片识别链路请求补点；不控制系统长按截图"
                                            else "需开启概览的应用流转与模块识别增强；已保存选择保留",
                                        enabled = moduleEnabled && enhancement, modifier = Modifier.testTag("toggle.capture").semantics {
                                            toggleableState = if (chatCards) ToggleableState.On else ToggleableState.Off
                                            if (!moduleEnabled || !enhancement) disabled()
                                        }) }
                                }
                            }
                        }
                    }
                    if (draftSection && direction == null) {
                        QuietText("完成仅关闭弹窗，点击页面“保存手势”后生效。", Modifier.testTag("drag.dialog.draft_hint"))
                    }
                    if (direction != null) TextButton("返回方向", { choosingDirection = null },
                        Modifier.fillMaxWidth().testTag("drag.direction.back"))
                    else TextButton("完成", close, Modifier.fillMaxWidth().testTag("drag.dialog.close"),
                        colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
    }
}

@Composable private fun StageDelayPreference(title: String, tag: String, value: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        QuietText(title, Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton("减少", { onChange((value - 50).coerceAtLeast(DragSettings.MIN_STAGE_DELAY)) }, Modifier.testTag("drag.$tag.down"),
                enabled = value > DragSettings.MIN_STAGE_DELAY)
            Text("$value 毫秒", Modifier.weight(1f).testTag("drag.$tag.value"), textAlign = TextAlign.Center)
            TextButton("增加", { onChange((value + 50).coerceAtMost(DragSettings.MAX_STAGE_DELAY)) }, Modifier.testTag("drag.$tag.up"),
                enabled = value < DragSettings.MAX_STAGE_DELAY)
        }
    }
}

private fun recognitionChannelLabel(channel: RecognitionChannels.Channel) = when (channel) {
    RecognitionChannels.Channel.NATIVE -> "原生文字与卡片"
    RecognitionChannels.Channel.ASSIST -> "页面结构采集"
    RecognitionChannels.Channel.OCR -> "图片文字 OCR"
    RecognitionChannels.Channel.SERVER -> "服务端推荐识别"
    RecognitionChannels.Channel.SEGMENT -> "文字分词处理"
    RecognitionChannels.Channel.CLIPBOARD -> "剪贴板识别"
}

private fun recognitionChannelSummary(channel: RecognitionChannels.Channel) = when (channel) {
    RecognitionChannels.Channel.NATIVE -> "读取原生长按回调中的实际文字和卡片链接"
    RecognitionChannels.Channel.ASSIST -> "向当前页面请求触点附近的文字和链接，不点击来源内容"
    RecognitionChannels.Channel.OCR -> "从已有图片中识别文字及网址，不包含不可见点击"
    RecognitionChannels.Channel.SERVER -> "请求并接收传送门服务端返回的识别推荐"
    RecognitionChannels.Channel.SEGMENT -> "对已取得文字分词；本身不算新的取链来源"
    RecognitionChannels.Channel.CLIPBOARD -> "处理传送门收到的剪贴板内容，不读取其他历史剪贴板"
}

private fun directionLabel(direction: String) = when (direction) { "up" -> "向上"; "down" -> "向下"; "left" -> "向左"; else -> "向右" }
internal fun dragActionLabel(action: String) = when (action) {
    "portal" -> "模块能力菜单"; "share" -> "分享"; "browser" -> "浏览器打开"; "rules" -> "按模块规则用对应应用打开"
    "lens" -> "识图"; "translate" -> "翻译"; "copy" -> "复制"; else -> "不执行操作"
}
