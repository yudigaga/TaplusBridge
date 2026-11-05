package io.github.yudigaga.taplusbridge.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.yudigaga.taplusbridge.config.DragSettings
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable internal fun DragSettingsScreen(controller: SettingsController, onSaved: () -> Unit) {
    val initial = remember(controller) { controller.getDragSettings() }
    val context = LocalContext.current
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var up by rememberSaveable { mutableStateOf(initial.up) }
    var down by rememberSaveable { mutableStateOf(initial.down) }
    var left by rememberSaveable { mutableStateOf(initial.left) }
    var right by rememberSaveable { mutableStateOf(initial.right) }
    var onlySelected by rememberSaveable { mutableStateOf(initial.shareOnlySelected) }
    var recognitionDelay by rememberSaveable { mutableIntStateOf(initial.recognitionDelayMillis) }
    var dragDelay by rememberSaveable { mutableIntStateOf(initial.dragDelayMillis) }
    var selected by rememberSaveable { mutableStateOf(ArrayList(initial.shareTargets)) }
    var query by rememberSaveable { mutableStateOf("") }
    var choosingDirection by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf("") }
    var targets by remember { mutableStateOf<List<SettingsController.ShareTarget>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(controller) { controller.loadShareTargets { targets = it; loading = false } }
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
        up = persisted.up; down = persisted.down; left = persisted.left; right = persisted.right
        onlySelected = persisted.shareOnlySelected
        recognitionDelay = persisted.recognitionDelayMillis; dragDelay = persisted.dragDelayMillis
        selected = ArrayList(persisted.shareTargets)
        query = ""; error = ""; choosingDirection = null
    }
    Scaffold(modifier = Modifier.testTag("page.drag"), topBar = {
        SettingsPageHeader(
            title = "拖拽操作",
            subtitle = "上下左右与分享菜单",
            discardTag = "drag.back",
            saveTag = "drag.save",
            onDiscard = ::discardDraft,
            onSave = {
                try {
                    controller.setDragSettings(DragSettings(enabled, up, down, left, right, onlySelected, selected,
                        recognitionDelay, dragDelay))
                    Toast.makeText(context, "拖拽设置已保存", Toast.LENGTH_SHORT).show(); onSaved()
                } catch (failure: RuntimeException) { error = failure.message ?: "保存失败，请重试" }
            })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("drag.list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SettingsCard {
                    SwitchPreference(enabled, { enabled = it }, title = "启用拖拽操作", summary = "每个方向先完整识别，一种内容直接执行，多种才选择",
                        modifier = Modifier.testTag("drag.enabled").semantics { toggleableState = if (enabled) ToggleableState.On else ToggleableState.Off })
                }
            }
            if (enabled) {
                item { QuietText("系统长按触发后先等待识别延迟，再等待拖动延迟；阶段从按下时刻累计。提前松手留给系统长按菜单。",
                    Modifier.padding(horizontal = 8.dp).testTag("drag.recognition_hint")) }
                item {
                    SettingsCard {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton("减少", { recognitionDelay = (recognitionDelay - 50).coerceAtLeast(DragSettings.MIN_STAGE_DELAY) }, Modifier.testTag("drag.recognitionDelay.down"))
                            QuietText("识别延迟 $recognitionDelay 毫秒", Modifier.testTag("drag.recognitionDelay.value").align(androidx.compose.ui.Alignment.CenterVertically))
                            TextButton("增加", { recognitionDelay = (recognitionDelay + 50).coerceAtMost(DragSettings.MAX_STAGE_DELAY) }, Modifier.testTag("drag.recognitionDelay.up"))
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton("减少", { dragDelay = (dragDelay - 50).coerceAtLeast(DragSettings.MIN_STAGE_DELAY) }, Modifier.testTag("drag.dragDelay.down"))
                            QuietText("拖动延迟 $dragDelay 毫秒", Modifier.testTag("drag.dragDelay.value").align(androidx.compose.ui.Alignment.CenterVertically))
                            TextButton("增加", { dragDelay = (dragDelay + 50).coerceAtMost(DragSettings.MAX_STAGE_DELAY) }, Modifier.testTag("drag.dragDelay.up"))
                        }
                    }
                }
                item { SectionLabel("四个方向") }
                item {
                    SettingsCard {
                        DragSettings.DIRECTIONS.forEach { direction ->
                            ArrowPreference(title = directionLabel(direction), summary = dragActionLabel(actions.getValue(direction)),
                                onClick = { choosingDirection = direction }, modifier = Modifier.testTag("drag.direction.$direction"))
                        }
                    }
                }
                item { QuietText("模块能力菜单保留本轮可用的传送门操作；按规则打开遵循现有链接和来源规则。识图使用 Google Lens，需要图片与可访问 Google 的网络。",
                    Modifier.padding(horizontal = 8.dp)) }
                item { SectionLabel("分享菜单") }
                item {
                    SettingsCard {
                        RadioButtonPreference(title = "全部可用目标", summary = "按当前内容显示可处理文字或图片的分享入口", selected = !onlySelected,
                            onClick = { onlySelected = false }, modifier = Modifier.testTag("drag.share.all").semantics { this.selected = !onlySelected })
                        RadioButtonPreference(title = "只显示所选目标", summary = "按下方顺序显示；不支持当前内容的目标自动隐藏", selected = onlySelected,
                            onClick = { onlySelected = true }, modifier = Modifier.testTag("drag.share.selected").semantics { this.selected = onlySelected })
                    }
                }
                if (onlySelected) {
                    if (selected.isEmpty()) item {
                        QuietText("尚未选择分享目标；分享时将提示配置，不会自动显示全部目标。", Modifier.padding(horizontal = 8.dp).testTag("drag.share.empty"))
                    }
                    if (error.isNotEmpty()) item { QuietText(error, Modifier.padding(horizontal = 8.dp).testTag("drag.error")) }
                    item { QuietText("已选 ${selected.size} 个目标，可上下调整顺序。", Modifier.padding(horizontal = 8.dp).testTag("drag.share.count")) }
                    items(selected, key = { "selected:$it" }) { component ->
                        val target = targetMap[component]
                        val index = selected.indexOf(component)
                        SettingsCard {
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
                        SettingsCard {
                            CheckboxPreference(title = target.label, summary = target.component, checked = false,
                                onCheckedChange = { select(target.component, it) }, startAction = { AppIcon(target.icon) },
                                modifier = Modifier.testTag("drag.share.target.${target.component}").semantics { toggleableState = ToggleableState.Off })
                        }
                    }
                    if (loading || available.isEmpty()) item { QuietText(if (loading) "正在读取分享入口…" else "没有其他匹配的分享入口。", Modifier.padding(horizontal = 8.dp)) }
                }
            }
        }
    }
    choosingDirection?.let { direction ->
        OverlayDialog(show = true, title = "${directionLabel(direction)}操作", onDismissRequest = { choosingDirection = null }) {
            Column(Modifier.heightIn(max = LocalWindowInfo.current.containerDpSize.height * .62f).verticalScroll(rememberScrollState())) {
                DragSettings.ACTIONS.forEach { action ->
                    RadioButtonPreference(title = dragActionLabel(action), selected = actions[direction] == action,
                        onClick = {
                            when (direction) { "up" -> up = action; "down" -> down = action; "left" -> left = action; "right" -> right = action }
                            choosingDirection = null
                        }, modifier = Modifier.testTag("drag.action.$action").semantics { this.selected = actions[direction] == action })
                }
            }
        }
    }
}

private fun directionLabel(direction: String) = when (direction) { "up" -> "向上"; "down" -> "向下"; "left" -> "向左"; else -> "向右" }
internal fun dragActionLabel(action: String) = when (action) {
    "portal" -> "模块能力菜单"; "share" -> "分享"; "browser" -> "浏览器打开"; "rules" -> "按模块规则用对应应用打开"
    "lens" -> "识图"; "translate" -> "翻译"; "copy" -> "复制"; else -> "不执行操作"
}
